#!/usr/bin/env bash
set -euo pipefail

# Creates the Signal sandbox catalog in Polar.
#
# Polar API shape was checked against Context7 plus Polar OpenAPI:
# - POST /v1/meters/
# - POST /v1/benefits/
# - POST /v1/products/
# - POST /v1/products/{id}/benefits

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
ENV_FILE="${ENV_FILE:-$REPO_ROOT/.env}"

if [[ -f "$ENV_FILE" ]]; then
    set -a
    # shellcheck source=/dev/null
    source "$ENV_FILE"
    set +a
fi

BASE_URL="https://sandbox-api.polar.sh"
API_KEY="${POLAR_API_KEY:-}"
ORGANIZATION_ID="${POLAR_ORGANIZATION_ID:-}"
OUTPUT_FILE="${OUTPUT_FILE:-$REPO_ROOT/build/polar/signal-sandbox-products.json}"
NAMESPACE="${POLAR_SETUP_NAMESPACE:-apollo-signal-sandbox-v1}"
DRY_RUN=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --dry-run)
            DRY_RUN=1
            shift
            ;;
        --base-url)
            BASE_URL="${2:?Missing value for --base-url}"
            shift 2
            ;;
        --output)
            OUTPUT_FILE="${2:?Missing value for --output}"
            shift 2
            ;;
        --organization-id)
            ORGANIZATION_ID="${2:?Missing value for --organization-id}"
            shift 2
            ;;
        -h|--help)
            cat <<'USAGE'
Usage:
  POLAR_API_KEY=... scripts/polar/setup-signal-sandbox.sh

Options:
  --dry-run                  Print intended work without calling Polar.
  --base-url URL             Override Polar API base URL. Defaults to sandbox.
  --organization-id UUID     Required only when using a user token instead of an organization token.
  --output PATH              Write product and meter IDs to PATH.

Environment:
  ENV_FILE                   Optional path to an env file. Defaults to repo .env.
  POLAR_API_KEY              Polar API key. Use a sandbox key for this script.
  POLAR_ORGANIZATION_ID      Optional organization ID.
  POLAR_SETUP_NAMESPACE      Metadata namespace for idempotency.
USAGE
            exit 0
            ;;
        *)
            echo "Unknown argument: $1" >&2
            exit 1
            ;;
    esac
done

if [[ "$DRY_RUN" -eq 0 && -z "$API_KEY" ]]; then
    echo "Set POLAR_API_KEY before running this script." >&2
    echo "The script also loads $ENV_FILE when it exists." >&2
    exit 1
fi

if [[ "$DRY_RUN" -eq 0 && "$BASE_URL" != *"sandbox"* && "${ALLOW_NON_SANDBOX_POLAR_SETUP:-0}" != "1" ]]; then
    echo "Refusing to write to non-sandbox Polar API: $BASE_URL" >&2
    echo "Set ALLOW_NON_SANDBOX_POLAR_SETUP=1 to override intentionally." >&2
    exit 1
fi

for cmd in curl jq mktemp; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
        echo "Missing required command: $cmd" >&2
        exit 1
    fi
done

BASE_URL="${BASE_URL%/}"
mkdir -p "$(dirname "$OUTPUT_FILE")"

WORK_DIR="$(mktemp -d)"
METERS_TMP="$WORK_DIR/meters.ndjson"
BENEFITS_TMP="$WORK_DIR/benefits.ndjson"
PRODUCTS_TMP="$WORK_DIR/products.ndjson"
LAST_ID=""
touch "$METERS_TMP" "$BENEFITS_TMP" "$PRODUCTS_TMP"
trap 'rm -rf "$WORK_DIR"' EXIT

log() {
    printf '%s\n' "$*" >&2
}

api_request() {
    local method="$1"
    local path="$2"
    local body="${3:-}"
    local tmp status
    tmp="$(mktemp)"

    if [[ -n "$body" ]]; then
        status="$(
            curl -sS -o "$tmp" -w '%{http_code}' \
                -X "$method" "$BASE_URL$path" \
                -H "Authorization: Bearer $API_KEY" \
                -H "Content-Type: application/json" \
                --data "$body"
        )"
    else
        status="$(
            curl -sS -o "$tmp" -w '%{http_code}' \
                -X "$method" "$BASE_URL$path" \
                -H "Authorization: Bearer $API_KEY"
        )"
    fi

    if [[ "$status" -lt 200 || "$status" -ge 300 ]]; then
        echo "Polar API request failed: $method $path -> HTTP $status" >&2
        cat "$tmp" >&2
        rm -f "$tmp"
        exit 1
    fi

    cat "$tmp"
    rm -f "$tmp"
}

api_get_by_setup_key() {
    local resource_path="$1"
    local setup_key="$2"
    local tmp status
    tmp="$(mktemp)"
    status="$(
        curl -sS -G -o "$tmp" -w '%{http_code}' "$BASE_URL$resource_path" \
            -H "Authorization: Bearer $API_KEY" \
            --data-urlencode "limit=100" \
            --data-urlencode "metadata[apollo_setup_key]=$setup_key" \
            --data-urlencode "metadata[apollo_namespace]=$NAMESPACE"
    )"

    if [[ "$status" -lt 200 || "$status" -ge 300 ]]; then
        echo "Polar API lookup failed: GET $resource_path metadata[$setup_key] -> HTTP $status" >&2
        cat "$tmp" >&2
        rm -f "$tmp"
        exit 1
    fi

    cat "$tmp"
    rm -f "$tmp"
}

resource_id_from_lookup() {
    jq -r '.items[0].id // empty'
}

record_meter() {
    jq -nc --arg key "$1" --arg id "$2" '{key: $key, id: $id}' >> "$METERS_TMP"
}

record_benefit() {
    jq -nc --arg key "$1" --arg id "$2" '{key: $key, id: $id}' >> "$BENEFITS_TMP"
}

record_product() {
    jq -nc --arg slug "$1" --arg id "$2" '{slug: $slug, id: $id}' >> "$PRODUCTS_TMP"
}

metadata_json() {
    local setup_key="$1"
    local category="$2"
    jq -n \
        --arg namespace "$NAMESPACE" \
        --arg setupKey "$setup_key" \
        --arg app "signal" \
        --arg category "$category" \
        '{
            apollo_namespace: $namespace,
            apollo_setup_key: $setupKey,
            apollo_app: $app,
            apollo_category: $category
        }'
}

maybe_org_json() {
    if [[ -n "$ORGANIZATION_ID" ]]; then
        jq -n --arg organizationId "$ORGANIZATION_ID" '{organization_id: $organizationId}'
    else
        jq -n '{}'
    fi
}

ensure_meter() {
    local key="$1"
    local output_key="$2"
    local name="$3"
    local event_name="$4"
    local label="$5"
    local multiplier="$6"
    local existing_id body response id

    if [[ "$DRY_RUN" -eq 1 ]]; then
        id="dry-${key}"
        log "[dry-run] meter $name -> $id"
        LAST_ID="$id"
        record_meter "$output_key" "$id"
        return
    fi

    existing_id="$(api_get_by_setup_key "/v1/meters/" "$key" | resource_id_from_lookup)"
    if [[ -n "$existing_id" ]]; then
        log "Using existing meter: $name ($existing_id)"
        LAST_ID="$existing_id"
        record_meter "$output_key" "$existing_id"
        return
    fi

    body="$(
        jq -n \
            --arg name "$name" \
            --arg eventName "$event_name" \
            --arg label "$label" \
            --argjson multiplier "$multiplier" \
            --argjson metadata "$(metadata_json "$key" "meter")" \
            --argjson org "$(maybe_org_json)" \
            '$org + {
                metadata: $metadata,
                name: $name,
                unit: "custom",
                custom_label: $label,
                custom_multiplier: $multiplier,
                filter: {
                    conjunction: "and",
                    clauses: [
                        { property: "name", operator: "eq", value: $eventName }
                    ]
                },
                aggregation: { func: "sum", property: "units" }
            }'
    )"

    response="$(api_request POST "/v1/meters/" "$body")"
    id="$(jq -r '.id' <<<"$response")"
    log "Created meter: $name ($id)"
    LAST_ID="$id"
    record_meter "$output_key" "$id"
}

ensure_meter_credit_benefit() {
    local key="$1"
    local description="$2"
    local meter_id="$3"
    local units="$4"
    local rollover="$5"
    local existing_id body response id

    if [[ "$DRY_RUN" -eq 1 ]]; then
        id="dry-${key}"
        log "[dry-run] benefit $description -> $id"
        LAST_ID="$id"
        record_benefit "$key" "$id"
        return
    fi

    existing_id="$(api_get_by_setup_key "/v1/benefits/" "$key" | resource_id_from_lookup)"
    if [[ -n "$existing_id" ]]; then
        body="$(
            jq -n \
                --arg description "$description" \
                --arg meterId "$meter_id" \
                --argjson units "$units" \
                --argjson rollover "$rollover" \
                --argjson metadata "$(metadata_json "$key" "benefit")" \
                '{
                    metadata: $metadata,
                    type: "meter_credit",
                    description: $description,
                    properties: {
                        units: $units,
                        rollover: $rollover,
                        meter_id: $meterId
                    }
                }'
        )"
        api_request PATCH "/v1/benefits/$existing_id" "$body" >/dev/null
        log "Updated existing benefit: $description ($existing_id)"
        LAST_ID="$existing_id"
        record_benefit "$key" "$existing_id"
        return
    fi

    body="$(
        jq -n \
            --arg description "$description" \
            --arg meterId "$meter_id" \
            --argjson units "$units" \
            --argjson rollover "$rollover" \
            --argjson metadata "$(metadata_json "$key" "benefit")" \
            --argjson org "$(maybe_org_json)" \
            '$org + {
                metadata: $metadata,
                type: "meter_credit",
                description: $description,
                properties: {
                    units: $units,
                    rollover: $rollover,
                    meter_id: $meterId
                }
            }'
    )"

    response="$(api_request POST "/v1/benefits/" "$body")"
    id="$(jq -r '.id' <<<"$response")"
    log "Created benefit: $description ($id)"
    LAST_ID="$id"
    record_benefit "$key" "$id"
}

static_price_json() {
    local price_cents="$1"
    if [[ "$price_cents" == "custom" ]]; then
        jq -n '[{
            amount_type: "custom",
            price_currency: "usd",
            minimum_amount: 0,
            preset_amount: 0
        }]'
    elif [[ "$price_cents" == "0" ]]; then
        jq -n '[{ amount_type: "free", price_currency: "usd" }]'
    else
        jq -n --argjson priceAmount "$price_cents" '[{
            amount_type: "fixed",
            price_currency: "usd",
            price_amount: $priceAmount
        }]'
    fi
}

metered_price_json() {
    local meter_id="$1"
    local unit_amount_cents="$2"
    if [[ -z "$unit_amount_cents" ]]; then
        jq -n '[]'
    else
        jq -n --arg meterId "$meter_id" --arg unitAmount "$unit_amount_cents" '[{
            amount_type: "metered_unit",
            price_currency: "usd",
            meter_id: $meterId,
            unit_amount: $unitAmount
        }]'
    fi
}

ensure_product() {
    local key="$1"
    local slug="$2"
    local name="$3"
    local description="$4"
    local product_type="$5"
    local price_cents="$6"
    local metered_meter_id="${7:-}"
    local metered_unit_amount_cents="${8:-}"
    local visibility="${9:-public}"
    local existing_id prices body response id

    if [[ "$DRY_RUN" -eq 1 ]]; then
        id="dry-${key}"
        log "[dry-run] product $name -> $id"
        LAST_ID="$id"
        record_product "$slug" "$id"
        return
    fi

    existing_id="$(api_get_by_setup_key "/v1/products/" "$key" | resource_id_from_lookup)"
    if [[ -n "$existing_id" ]]; then
        log "Using existing product: $name ($existing_id)"
        LAST_ID="$existing_id"
        record_product "$slug" "$existing_id"
        return
    fi

    prices="$(
        jq -s 'add' \
            <(static_price_json "$price_cents") \
            <(metered_price_json "$metered_meter_id" "$metered_unit_amount_cents")
    )"

    if [[ "$product_type" == "recurring" ]]; then
        body="$(
            jq -n \
                --arg name "$name" \
                --arg description "$description" \
                --arg visibility "$visibility" \
                --argjson prices "$prices" \
                --argjson metadata "$(metadata_json "$key" "product")" \
                --argjson org "$(maybe_org_json)" \
                '$org + {
                    metadata: $metadata,
                    name: $name,
                    description: $description,
                    visibility: $visibility,
                    recurring_interval: "month",
                    prices: $prices
                }'
        )"
    else
        body="$(
            jq -n \
                --arg name "$name" \
                --arg description "$description" \
                --arg visibility "$visibility" \
                --argjson prices "$prices" \
                --argjson metadata "$(metadata_json "$key" "product")" \
                --argjson org "$(maybe_org_json)" \
                '$org + {
                    metadata: $metadata,
                    name: $name,
                    description: $description,
                    visibility: $visibility,
                    recurring_interval: null,
                    recurring_interval_count: null,
                    prices: $prices
                }'
        )"
    fi

    response="$(api_request POST "/v1/products/" "$body")"
    id="$(jq -r '.id' <<<"$response")"
    log "Created product: $name ($id)"
    LAST_ID="$id"
    record_product "$slug" "$id"
}

attach_benefit_to_product() {
    local product_id="$1"
    local benefit_id="$2"
    local current body

    if [[ "$DRY_RUN" -eq 1 ]]; then
        log "[dry-run] attach benefit $benefit_id to product $product_id"
        return
    fi

    current="$(api_request GET "/v1/products/$product_id")"
    body="$(
        jq -n \
            --argjson current "$current" \
            --arg benefitId "$benefit_id" \
            '{
                benefits: (([$benefitId] + ($current.benefits // [] | map(.id))) | unique)
            }'
    )"
    api_request POST "/v1/products/$product_id/benefits" "$body" >/dev/null
    log "Attached benefit $benefit_id to product $product_id"
}

set_product_benefits() {
    local product_id="$1"
    shift
    local benefits_json body

    if [[ "$DRY_RUN" -eq 1 ]]; then
        log "[dry-run] set product $product_id benefits: $*"
        return
    fi

    benefits_json="$(
        printf '%s\n' "$@" | jq -R . | jq -s 'unique'
    )"
    body="$(
        jq -n \
            --argjson benefits "$benefits_json" \
            '{ benefits: $benefits }'
    )"
    api_request POST "/v1/products/$product_id/benefits" "$body" >/dev/null
    log "Set benefits on product $product_id"
}

setup_plan() {
    local plan="$1"
    local slug="$2"
    local included_emails="$3"
    local price_cents="$4"
    local overage_unit_cents="$5"
    local included_ai_credits="$6"
    local visibility="$7"
    local description="$8"
    local benefit_key product_key benefit_ids product_id

    product_key="product-${slug}"
    ensure_product \
        "$product_key" "$slug" "Signal $plan" "$description" "recurring" "$price_cents" \
        "$EMAIL_METER_ID" "$overage_unit_cents" "$visibility"
    product_id="$LAST_ID"
    benefit_ids=()

    if [[ "$included_emails" != "custom" && "$included_emails" -gt 0 ]]; then
        benefit_key="benefit-${slug}-emails"
        ensure_meter_credit_benefit \
            "$benefit_key" "Signal $plan monthly emails" "$EMAIL_METER_ID" "$included_emails" "false"
        benefit_ids+=("$LAST_ID")
    fi

    if [[ "$included_ai_credits" != "custom" && "$included_ai_credits" -gt 0 ]]; then
        benefit_key="benefit-${slug}-ai-credits"
        ensure_meter_credit_benefit \
            "$benefit_key" "Signal $plan monthly AI credits" "$AI_CREDIT_METER_ID" "$included_ai_credits" "false"
        benefit_ids+=("$LAST_ID")
    fi

    if [[ "${#benefit_ids[@]}" -gt 0 ]]; then
        set_product_benefits "$product_id" "${benefit_ids[@]}"
    fi
}

setup_pack() {
    local name="$1"
    local slug="$2"
    local runs="$3"
    local price_cents="$4"
    local description="$5"
    local product_key benefit_key product_id benefit_id

    product_key="product-${slug}"
    benefit_key="benefit-${slug}-runs"

    ensure_meter_credit_benefit \
        "$benefit_key" "Signal $name runs" "$AUTOMATION_METER_ID" "$runs" "true"
    benefit_id="$LAST_ID"
    ensure_product \
        "$product_key" "$slug" "Signal $name" "$description" "one_time" "$price_cents" "" "" "public"

    product_id="$LAST_ID"
    attach_benefit_to_product "$product_id" "$benefit_id"
}

setup_ai_credit_pack() {
    local name="$1"
    local slug="$2"
    local credits="$3"
    local price_cents="$4"
    local description="$5"
    local product_key benefit_key product_id benefit_id

    product_key="product-${slug}"
    benefit_key="benefit-${slug}-monthly-credits"

    ensure_meter_credit_benefit \
        "$benefit_key" "Signal $name credits" "$AI_CREDIT_METER_ID" "$credits" "false"
    benefit_id="$LAST_ID"
    ensure_product \
        "$product_key" "$slug" "Signal $name" "$description" "one_time" "$price_cents" "" "" "public"

    product_id="$LAST_ID"
    set_product_benefits "$product_id" "$benefit_id"
}

write_output() {
    local products_json benefits_json meters_json

    products_json="$(jq -s 'unique_by(.slug) | sort_by(.slug)' "$PRODUCTS_TMP")"
    benefits_json="$(jq -s 'unique_by(.key) | sort_by(.key)' "$BENEFITS_TMP")"
    meters_json="$(jq -s 'unique_by(.key) | sort_by(.key)' "$METERS_TMP")"

    jq -n \
        --arg namespace "$NAMESPACE" \
        --arg baseUrl "$BASE_URL" \
        --argjson dryRun "$DRY_RUN" \
        --argjson meters "$meters_json" \
        --argjson benefits "$benefits_json" \
        --argjson products "$products_json" \
        '{
            namespace: $namespace,
            baseUrl: $baseUrl,
            dryRun: ($dryRun == 1),
            meters: $meters,
            benefits: $benefits,
            products: $products
        }' > "$OUTPUT_FILE"

    log "Wrote $OUTPUT_FILE"
}

print_catalog_hint() {
    local output
    output="$(cat "$OUTPUT_FILE")"
    cat <<EOF

Catalog values to copy into SignalPlanCatalog.kt:

const val SIGNAL_EMAIL_METER_ID = "$(jq -r '.meters[] | select(.key == "email") | .id' <<<"$output")"
const val SIGNAL_AUTOMATION_RUN_METER_ID = "$(jq -r '.meters[] | select(.key == "automation") | .id' <<<"$output")"
const val SIGNAL_AI_CREDIT_METER_ID = "$(jq -r '.meters[] | select(.key == "aiCredit") | .id' <<<"$output")"

Product IDs:
$(jq -r '.products[] | select(.slug | startswith("signal-")) | "  " + .slug + " = " + .id' <<<"$output")
EOF
}

log "Setting up Signal Polar sandbox catalog at $BASE_URL"

ensure_meter "meter-signal-emails" "email" "Signal Emails" "signal.email.sent" "email" "1000"
EMAIL_METER_ID="$LAST_ID"

ensure_meter "meter-signal-automation-runs" "automation" "Signal Automation Runs" "signal.automation.run" "run" "1000"
AUTOMATION_METER_ID="$LAST_ID"

ensure_meter "meter-signal-ai-credits" "aiCredit" "Signal AI Credits" "signal.ai.credit.used" "credit" "1"
AI_CREDIT_METER_ID="$LAST_ID"

setup_plan "Spark" "signal-spark" "3000" "0" "" "5" "public" "3,000 emails and 5 AI credits included. Free plan."
setup_plan "Ignite" "signal-ignite" "50000" "1500" "0.05" "20" "public" "50,000 emails and 20 AI credits included. \$0.50 per 1k email overage."
setup_plan "Growth" "signal-growth" "150000" "3500" "0.042" "50" "public" "150,000 emails and 50 AI credits included. \$0.42 per 1k email overage."
setup_plan "Pulse" "signal-pulse" "300000" "6500" "0.036" "100" "public" "300,000 emails and 100 AI credits included. \$0.36 per 1k email overage."
setup_plan "Scale" "signal-scale" "1000000" "18000" "0.03" "250" "public" "1,000,000 emails and 250 AI credits included. \$0.30 per 1k email overage."
setup_plan "Enterprise" "signal-enterprise" "custom" "custom" "" "custom" "private" "Custom Signal contract. Configure manually after sales."

ensure_product \
    "product-signal-dedicated-ip-addon" "signal-dedicated-ip-addon" "Signal Dedicated IP" \
    "Monthly dedicated IP add-on for an active Signal plan." "recurring" "3000" "" "" "public"

ensure_product \
    "product-signal-email-payg" "signal-email-payg" "Signal Email PAYG" \
    "Pay as you go emails at \$0.50 per 1k emails." "recurring" "0" \
    "$EMAIL_METER_ID" "0.05" "public"

ensure_product \
    "product-signal-automation-payg" "signal-automation-payg" "Signal Automation PAYG" \
    "Pay as you go automation runs at \$1.00 per 1k runs." "recurring" "0" \
    "$AUTOMATION_METER_ID" "0.1" "public"

setup_pack "Automation Small Pack" "signal-automation-small-pack" "10000" "1000" "10,000 automation runs. \$1.00 per 1k."
setup_pack "Automation Medium Pack" "signal-automation-medium-pack" "50000" "3000" "50,000 automation runs. \$0.60 per 1k."
setup_pack "Automation Growth Pack" "signal-automation-growth-pack" "100000" "5500" "100,000 automation runs. \$0.55 per 1k."
setup_pack "Automation Scale Pack" "signal-automation-scale-pack" "500000" "20000" "500,000 automation runs. \$0.40 per 1k."

setup_ai_credit_pack "AI Credits 100" "signal-ai-credits-100" "100" "300" "100 AI credits. Credits expire at the end of the monthly period."
setup_ai_credit_pack "AI Credits 500" "signal-ai-credits-500" "500" "1500" "500 AI credits. Credits expire at the end of the monthly period."
setup_ai_credit_pack "AI Credits 1000" "signal-ai-credits-1000" "1000" "3000" "1,000 AI credits. Credits expire at the end of the monthly period."

write_output
print_catalog_hint
