#!/usr/bin/env bash
set -euo pipefail

# Sets up the Signal product catalog in Polar (sandbox or production).
#
# Polar API shape:
#   POST /v1/meters/
#   POST /v1/benefits/
#   POST /v1/products/
#   POST /v1/products/{id}/benefits

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# shellcheck source=scripts/polar/signal-catalog.sh
source "$SCRIPT_DIR/signal-catalog.sh"

ENV_FILE="${ENV_FILE:-$REPO_ROOT/.env}"
if [[ -f "$ENV_FILE" ]]; then
    set -a
    # shellcheck source=/dev/null
    source "$ENV_FILE"
    set +a
fi

# ── Defaults (overridden by flags) ────────────────────────────────────────────

ENV_MODE="sandbox"       # sandbox | production
SETUP_MODE="both"        # email | sms | both
DRY_RUN=0
API_KEY="${POLAR_API_KEY:-}"
ORGANIZATION_ID="${POLAR_ORGANIZATION_ID:-}"
OUTPUT_FILE=""           # resolved after ENV_MODE is known

# ── Argument parsing ─────────────────────────────────────────────────────────

while [[ $# -gt 0 ]]; do
    case "$1" in
        --dry-run)
            DRY_RUN=1
            shift
            ;;
        --env)
            ENV_MODE="${2:?Missing value for --env (sandbox|production)}"
            case "$ENV_MODE" in
                sandbox|production) ;;
                *) echo "Invalid --env value: $ENV_MODE. Must be sandbox or production." >&2; exit 1 ;;
            esac
            shift 2
            ;;
        --setup)
            SETUP_MODE="${2:?Missing value for --setup (email|sms|both)}"
            case "$SETUP_MODE" in
                email|sms|both) ;;
                *) echo "Invalid --setup value: $SETUP_MODE. Must be email, sms, or both." >&2; exit 1 ;;
            esac
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
  POLAR_API_KEY=... scripts/polar/setup-signal.sh [options]

Options:
  --env ENV          Target environment: sandbox (default) or production.
  --setup MODE       What to set up: email, sms, or both (default: both).
  --dry-run          Print intended work without calling Polar.
  --organization-id  UUID required when using a user token instead of an org token.
  --output PATH      Write product and meter IDs to PATH.

Environment variables:
  ENV_FILE                  Path to an env file (default: repo .env).
  POLAR_API_KEY             Polar API key for the target environment.
  POLAR_ORGANIZATION_ID     Optional organization UUID.
  POLAR_SETUP_NAMESPACE     Override the idempotency namespace.
USAGE
            exit 0
            ;;
        *)
            echo "Unknown argument: $1" >&2
            exit 1
            ;;
    esac
done

# ── Resolve environment-specific values ──────────────────────────────────────

if [[ "$ENV_MODE" == "production" ]]; then
    BASE_URL="$POLAR_URL_PRODUCTION"
    NAMESPACE="${POLAR_SETUP_NAMESPACE:-$NAMESPACE_PRODUCTION}"
    OUTPUT_FILE="${OUTPUT_FILE:-$REPO_ROOT/build/polar/signal-production-products.json}"
else
    BASE_URL="$POLAR_URL_SANDBOX"
    NAMESPACE="${POLAR_SETUP_NAMESPACE:-$NAMESPACE_SANDBOX}"
    OUTPUT_FILE="${OUTPUT_FILE:-$REPO_ROOT/build/polar/signal-sandbox-products.json}"
fi

# ── Pre-flight checks ─────────────────────────────────────────────────────────

if [[ "$DRY_RUN" -eq 0 && -z "$API_KEY" ]]; then
    echo "Set POLAR_API_KEY before running this script." >&2
    echo "The script also loads $ENV_FILE when it exists." >&2
    exit 1
fi

if [[ "$DRY_RUN" -eq 0 && "$ENV_MODE" == "production" ]]; then
    echo ""
    echo "  ⚠️  You are about to write to the PRODUCTION Polar API."
    echo "     Base URL : $BASE_URL"
    echo "     Namespace: $NAMESPACE"
    echo "     Setup    : $SETUP_MODE"
    echo ""
    read -r -p "  Type 'yes' to continue: " CONFIRM
    if [[ "$CONFIRM" != "yes" ]]; then
        echo "Aborted." >&2
        exit 1
    fi
fi

for cmd in curl jq mktemp; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
        echo "Missing required command: $cmd" >&2
        exit 1
    fi
done

BASE_URL="${BASE_URL%/}"
mkdir -p "$(dirname "$OUTPUT_FILE")"

# ── Working state ─────────────────────────────────────────────────────────────

WORK_DIR="$(mktemp -d)"
METERS_TMP="$WORK_DIR/meters.ndjson"
BENEFITS_TMP="$WORK_DIR/benefits.ndjson"
PRODUCTS_TMP="$WORK_DIR/products.ndjson"
LAST_ID=""
touch "$METERS_TMP" "$BENEFITS_TMP" "$PRODUCTS_TMP"
trap 'rm -rf "$WORK_DIR"' EXIT

# ── Logging ───────────────────────────────────────────────────────────────────

log() { printf '%s\n' "$*" >&2; }

# ── Polar API helpers ─────────────────────────────────────────────────────────

api_request() {
    local method="$1"
    local path="$2"
    local body="${3:-}"
    local tmp status
    tmp="$(mktemp)"

    if [[ -n "$body" ]]; then
        status="$(curl -sS -o "$tmp" -w '%{http_code}' \
            -X "$method" "$BASE_URL$path" \
            -H "Authorization: Bearer $API_KEY" \
            -H "Content-Type: application/json" \
            --data "$body")"
    else
        status="$(curl -sS -o "$tmp" -w '%{http_code}' \
            -X "$method" "$BASE_URL$path" \
            -H "Authorization: Bearer $API_KEY")"
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
    status="$(curl -sS -G -o "$tmp" -w '%{http_code}' "$BASE_URL$resource_path" \
        -H "Authorization: Bearer $API_KEY" \
        --data-urlencode "limit=100" \
        --data-urlencode "metadata[apollo_setup_key]=$setup_key" \
        --data-urlencode "metadata[apollo_namespace]=$NAMESPACE")"

    if [[ "$status" -lt 200 || "$status" -ge 300 ]]; then
        echo "Polar API lookup failed: GET $resource_path -> HTTP $status" >&2
        cat "$tmp" >&2
        rm -f "$tmp"
        exit 1
    fi
    cat "$tmp"
    rm -f "$tmp"
}

resource_id_from_lookup() { jq -r '.items[0].id // empty'; }

# ── Record helpers ────────────────────────────────────────────────────────────

record_meter()   { jq -nc --arg key "$1" --arg id "$2" '{key:$key,id:$id}' >> "$METERS_TMP"; }
record_benefit() { jq -nc --arg key "$1" --arg id "$2" '{key:$key,id:$id}' >> "$BENEFITS_TMP"; }
record_product() { jq -nc --arg slug "$1" --arg id "$2" '{slug:$slug,id:$id}' >> "$PRODUCTS_TMP"; }

# ── Metadata builders ─────────────────────────────────────────────────────────

metadata_json() {
    local setup_key="$1" category="$2"
    jq -n --arg ns "$NAMESPACE" --arg k "$setup_key" --arg cat "$category" \
        '{apollo_namespace:$ns, apollo_setup_key:$k, apollo_app:"signal", apollo_category:$cat}'
}

sms_metadata_json() {
    local setup_key="$1" category="$2"
    jq -n --arg ns "$NAMESPACE" --arg k "$setup_key" --arg cat "$category" \
        '{apollo_namespace:$ns, apollo_setup_key:$k, apollo_app:"signal", apollo_category:$cat, apollo_addon_type:"sms"}'
}

maybe_org_json() {
    if [[ -n "$ORGANIZATION_ID" ]]; then
        jq -n --arg id "$ORGANIZATION_ID" '{organization_id:$id}'
    else
        jq -n '{}'
    fi
}

# ── ensure_meter ─────────────────────────────────────────────────────────────
# Args: key output_key name event_name label multiplier

ensure_meter() {
    local key="$1" output_key="$2" name="$3" event_name="$4" label="$5" multiplier="$6"
    local existing_id body response id
    if [[ "$DRY_RUN" -eq 1 ]]; then
        id="dry-${key}"; log "[dry-run] meter $name -> $id"
        LAST_ID="$id"; record_meter "$output_key" "$id"; return
    fi

    existing_id="$(api_get_by_setup_key "/v1/meters/" "$key" | resource_id_from_lookup)"
    if [[ -n "$existing_id" ]]; then
        log "Using existing meter: $name ($existing_id)"
        LAST_ID="$existing_id"; record_meter "$output_key" "$existing_id"; return
    fi

    body="$(jq -n \
        --arg name "$name" --arg eventName "$event_name" \
        --arg label "$label" --argjson multiplier "$multiplier" \
        --argjson metadata "$(metadata_json "$key" "meter")" \
        --argjson org "$(maybe_org_json)" \
        '$org + {
            metadata: $metadata, name: $name, unit: "custom",
            custom_label: $label, custom_multiplier: $multiplier,
            filter: { conjunction: "and", clauses: [{ property: "name", operator: "eq", value: $eventName }] },
            aggregation: { func: "sum", property: "units" }
        }')"

    response="$(api_request POST "/v1/meters/" "$body")"
    id="$(jq -r '.id' <<<"$response")"
    log "Created meter: $name ($id)"
    LAST_ID="$id"; record_meter "$output_key" "$id"
}

# ── ensure_meter_credit_benefit ───────────────────────────────────────────────
# Args: key description meter_id units rollover

ensure_meter_credit_benefit() {
    local key="$1" description="$2" meter_id="$3" units="$4" rollover="$5"
    local existing_id body response id

    if [[ "$DRY_RUN" -eq 1 ]]; then
        id="dry-${key}"; log "[dry-run] benefit $description -> $id"
        LAST_ID="$id"; record_benefit "$key" "$id"; return
    fi

    existing_id="$(api_get_by_setup_key "/v1/benefits/" "$key" | resource_id_from_lookup)"
    if [[ -n "$existing_id" ]]; then
        body="$(jq -n \
            --arg desc "$description" --arg mid "$meter_id" \
            --argjson units "$units" --argjson rollover "$rollover" \
            --argjson metadata "$(metadata_json "$key" "benefit")" \
            '{metadata:$metadata, type:"meter_credit", description:$desc,
              properties:{units:$units, rollover:$rollover, meter_id:$mid}}')"
        api_request PATCH "/v1/benefits/$existing_id" "$body" >/dev/null
        log "Updated existing benefit: $description ($existing_id)"
        LAST_ID="$existing_id"; record_benefit "$key" "$existing_id"; return
    fi

    body="$(jq -n \
        --arg desc "$description" --arg mid "$meter_id" \
        --argjson units "$units" --argjson rollover "$rollover" \
        --argjson metadata "$(metadata_json "$key" "benefit")" \
        --argjson org "$(maybe_org_json)" \
        '$org + {metadata:$metadata, type:"meter_credit", description:$desc,
          properties:{units:$units, rollover:$rollover, meter_id:$mid}}')"

    response="$(api_request POST "/v1/benefits/" "$body")"
    id="$(jq -r '.id' <<<"$response")"
    log "Created benefit: $description ($id)"
    LAST_ID="$id"; record_benefit "$key" "$id"
}

# ── Price JSON builders ───────────────────────────────────────────────────────

static_price_json() {
    local price_cents="$1"
    case "$price_cents" in
        custom) jq -n '[{amount_type:"custom", price_currency:"usd", minimum_amount:0, preset_amount:0}]' ;;
        0)      jq -n '[{amount_type:"free",   price_currency:"usd"}]' ;;
        *)      jq -n --argjson p "$price_cents" '[{amount_type:"fixed", price_currency:"usd", price_amount:$p}]' ;;
    esac
}

metered_price_json() {
    local meter_id="$1" unit_amount="$2"
    if [[ -z "$unit_amount" ]]; then
        jq -n '[]'
    else
        jq -n --arg mid "$meter_id" --arg ua "$unit_amount" \
            '[{amount_type:"metered_unit", price_currency:"usd", meter_id:$mid, unit_amount:$ua}]'
    fi
}

# ── _upsert_product (internal) ────────────────────────────────────────────────
# Args: metadata_fn key slug name description product_type price_cents
#       metered_meter_id metered_unit_amount visibility

_upsert_product() {
    local metadata_fn="$1" key="$2" slug="$3" name="$4" description="$5"
    local product_type="$6" price_cents="$7"
    local metered_meter_id="${8:-}" metered_unit_amount="${9:-}" visibility="${10:-public}"
    local existing_id prices body response id

    if [[ "$DRY_RUN" -eq 1 ]]; then
        id="dry-${key}"; log "[dry-run] product $name -> $id"
        LAST_ID="$id"; record_product "$slug" "$id"; return
    fi

    existing_id="$(api_get_by_setup_key "/v1/products/" "$key" | resource_id_from_lookup)"
    if [[ -n "$existing_id" ]]; then
        log "Using existing product: $name ($existing_id)"
        LAST_ID="$existing_id"; record_product "$slug" "$existing_id"; return
    fi

    prices="$(jq -s 'add' \
        <(static_price_json "$price_cents") \
        <(metered_price_json "$metered_meter_id" "$metered_unit_amount"))"

    if [[ "$product_type" == "recurring" ]]; then
        body="$(jq -n \
            --arg name "$name" --arg desc "$description" --arg vis "$visibility" \
            --argjson prices "$prices" \
            --argjson metadata "$("$metadata_fn" "$key" "product")" \
            --argjson org "$(maybe_org_json)" \
            '$org + {metadata:$metadata, name:$name, description:$desc,
              visibility:$vis, recurring_interval:"month", prices:$prices}')"
    else
        body="$(jq -n \
            --arg name "$name" --arg desc "$description" --arg vis "$visibility" \
            --argjson prices "$prices" \
            --argjson metadata "$("$metadata_fn" "$key" "product")" \
            --argjson org "$(maybe_org_json)" \
            '$org + {metadata:$metadata, name:$name, description:$desc, visibility:$vis,
              recurring_interval:null, recurring_interval_count:null, prices:$prices}')"
    fi

    response="$(api_request POST "/v1/products/" "$body")"
    id="$(jq -r '.id' <<<"$response")"
    log "Created product: $name ($id)"
    LAST_ID="$id"; record_product "$slug" "$id"
}

ensure_product()     { _upsert_product metadata_json     "$@"; }
ensure_sms_product() { _upsert_product sms_metadata_json "$@"; }

# ── Benefit attachment helpers ────────────────────────────────────────────────

attach_benefit_to_product() {
    local product_id="$1" benefit_id="$2" current body
    if [[ "$DRY_RUN" -eq 1 ]]; then
        log "[dry-run] attach benefit $benefit_id to product $product_id"; return
    fi
    current="$(api_request GET "/v1/products/$product_id")"
    body="$(jq -n --argjson cur "$current" --arg bid "$benefit_id" \
        '{benefits: (([$bid] + ($cur.benefits // [] | map(.id))) | unique)}')"
    api_request POST "/v1/products/$product_id/benefits" "$body" >/dev/null
    log "Attached benefit $benefit_id to product $product_id"
}

set_product_benefits() {
    local product_id="$1"; shift
    local benefits_json body
    if [[ "$DRY_RUN" -eq 1 ]]; then
        log "[dry-run] set product $product_id benefits: $*"; return
    fi
    benefits_json="$(printf '%s\n' "$@" | jq -R . | jq -s 'unique')"
    body="$(jq -n --argjson b "$benefits_json" '{benefits:$b}')"
    api_request POST "/v1/products/$product_id/benefits" "$body" >/dev/null
    log "Set benefits on product $product_id"
}

# ── High-level email setup helpers ───────────────────────────────────────────

# Args: plan slug included_emails price_cents overage_rate included_ai_credits visibility description
setup_plan() {
    local plan="$1" slug="$2" included_emails="$3" price_cents="$4"
    local overage_rate="$5" included_ai_credits="$6" visibility="$7" description="$8"
    local product_key product_id benefit_key
    local benefit_ids
    benefit_ids=()

    product_key="product-${slug}"
    ensure_product "$product_key" "$slug" "Signal $plan" "$description" \
        "recurring" "$price_cents" "$EMAIL_METER_ID" "$overage_rate" "$visibility"
    product_id="$LAST_ID"

    if [[ "$included_emails" != "custom" && "$included_emails" != "0" ]]; then
        benefit_key="benefit-${slug}-emails"
        ensure_meter_credit_benefit \
            "$benefit_key" "Signal $plan monthly emails" "$EMAIL_METER_ID" "$included_emails" "false"
        benefit_ids+=("$LAST_ID")
    fi

    if [[ "$included_ai_credits" != "custom" && "$included_ai_credits" != "0" ]]; then
        benefit_key="benefit-${slug}-ai-credits"
        ensure_meter_credit_benefit \
            "$benefit_key" "Signal $plan monthly AI credits" "$AI_CREDIT_METER_ID" "$included_ai_credits" "false"
        benefit_ids+=("$LAST_ID")
    fi

    [[ "${#benefit_ids[@]}" -gt 0 ]] && set_product_benefits "$product_id" "${benefit_ids[@]}" || true
}

# Args: name slug runs price_cents description
setup_pack() {
    local name="$1" slug="$2" runs="$3" price_cents="$4" description="$5"
    local product_key="product-${slug}" benefit_key="benefit-${slug}-runs"

    ensure_meter_credit_benefit "$benefit_key" "Signal $name runs" "$AUTOMATION_METER_ID" "$runs" "true"
    local benefit_id="$LAST_ID"
    ensure_product "$product_key" "$slug" "Signal $name" "$description" "one_time" "$price_cents" "" "" "public"
    attach_benefit_to_product "$LAST_ID" "$benefit_id"
}

# Args: name slug credits price_cents description
setup_ai_credit_pack() {
    local name="$1" slug="$2" credits="$3" price_cents="$4" description="$5"
    local product_key="product-${slug}" benefit_key="benefit-${slug}-monthly-credits"

    ensure_meter_credit_benefit "$benefit_key" "Signal $name credits" "$AI_CREDIT_METER_ID" "$credits" "false"
    local benefit_id="$LAST_ID"
    ensure_product "$product_key" "$slug" "Signal $name" "$description" "one_time" "$price_cents" "" "" "public"
    set_product_benefits "$LAST_ID" "$benefit_id"
}

# ── High-level SMS setup helpers ─────────────────────────────────────────────

# Args: plan slug included_segments price_cents overage_rate visibility description
setup_sms_plan() {
    local plan="$1" slug="$2" included_segments="$3" price_cents="$4"
    local overage_rate="$5" visibility="$6" description="$7"
    local product_key product_id benefit_key
    local benefit_ids
    benefit_ids=()

    product_key="product-${slug}"
    ensure_sms_product "$product_key" "$slug" "Signal $plan" "$description" \
        "recurring" "$price_cents" "$SMS_SEGMENT_METER_ID" "$overage_rate" "$visibility"
    product_id="$LAST_ID"

    if [[ "$included_segments" != "custom" && "$included_segments" != "0" ]]; then
        benefit_key="benefit-${slug}-segments"
        ensure_meter_credit_benefit \
            "$benefit_key" "Signal $plan monthly SMS segments" "$SMS_SEGMENT_METER_ID" \
            "$included_segments" "false"
        benefit_ids+=("$LAST_ID")
    fi

    [[ "${#benefit_ids[@]}" -gt 0 ]] && set_product_benefits "$product_id" "${benefit_ids[@]}" || true
}

# Args: name slug segments price_cents description
setup_sms_segment_pack() {
    local name="$1" slug="$2" segments="$3" price_cents="$4" description="$5"
    local product_key="product-${slug}" benefit_key="benefit-${slug}-segments"

    ensure_meter_credit_benefit "$benefit_key" "Signal $name segments" "$SMS_SEGMENT_METER_ID" "$segments" "true"
    local benefit_id="$LAST_ID"
    ensure_sms_product "$product_key" "$slug" "Signal $name" "$description" "one_time" "$price_cents" "" "" "public"
    attach_benefit_to_product "$LAST_ID" "$benefit_id"
}

# ── Output helpers ────────────────────────────────────────────────────────────

write_output() {
    local products_json benefits_json meters_json
    products_json="$(jq -s 'unique_by(.slug) | sort_by(.slug)' "$PRODUCTS_TMP")"
    benefits_json="$(jq -s 'unique_by(.key)  | sort_by(.key)'  "$BENEFITS_TMP")"
    meters_json="$(  jq -s 'unique_by(.key)  | sort_by(.key)'  "$METERS_TMP")"

    jq -n \
        --arg namespace "$NAMESPACE" \
        --arg baseUrl "$BASE_URL" \
        --arg env "$ENV_MODE" \
        --argjson dryRun "$DRY_RUN" \
        --argjson meters "$meters_json" \
        --argjson benefits "$benefits_json" \
        --argjson products "$products_json" \
        '{namespace:$namespace, baseUrl:$baseUrl, env:$env,
          dryRun:($dryRun==1), meters:$meters, benefits:$benefits, products:$products}' \
        > "$OUTPUT_FILE"
    log "Wrote $OUTPUT_FILE"
}

print_catalog_hint() {
    local output
    output="$(cat "$OUTPUT_FILE")"

    echo ""
    echo "Catalog values to copy into SignalPlanCatalog.kt:"

    if [[ "$SETUP_MODE" == "email" || "$SETUP_MODE" == "both" ]]; then
        echo ""
        echo "// Email / core meters"
        echo "const val SIGNAL_EMAIL_METER_ID = \"$(jq -r '.meters[] | select(.key=="email") | .id' <<<"$output")\""
        echo "const val SIGNAL_AUTOMATION_RUN_METER_ID = \"$(jq -r '.meters[] | select(.key=="automation") | .id' <<<"$output")\""
        echo "const val SIGNAL_AI_CREDIT_METER_ID = \"$(jq -r '.meters[] | select(.key=="aiCredit") | .id' <<<"$output")\""
        echo ""
        echo "// Email product IDs:"
        jq -r '.products[] | select(.slug | test("^signal-(spark|ignite|growth|pulse|scale|enterprise|dedicated|email-payg|automation)"))
            | "const val " + (.slug | ascii_upcase | gsub("-";"_")) + "_PRODUCT_ID = \"" + .id + "\""' <<<"$output"
    fi

    if [[ "$SETUP_MODE" == "sms" || "$SETUP_MODE" == "both" ]]; then
        echo ""
        echo "// SMS meters"
        echo "const val SIGNAL_SMS_SEGMENT_METER_ID = \"$(jq -r '.meters[] | select(.key=="smsSegment") | .id' <<<"$output")\""
        echo "const val SIGNAL_MMS_MESSAGE_METER_ID = \"$(jq -r '.meters[] | select(.key=="mmsMessage") | .id' <<<"$output")\""
        echo ""
        echo "// SMS product IDs:"
        jq -r '.products[] | select(.slug | test("^signal-sms|^signal-mms"))
            | "const val " + (.slug | ascii_upcase | gsub("-";"_")) + "_PRODUCT_ID = \"" + .id + "\""' <<<"$output"
    fi

    echo ""
    echo "All products:"
    jq -r '.products[] | "  " + .slug + " = " + .id' <<<"$output"
}

# ══════════════════════════════════════════════════════════════════════════════
# Main execution
# ══════════════════════════════════════════════════════════════════════════════

log "Setting up Signal Polar catalog | env=$ENV_MODE setup=$SETUP_MODE url=$BASE_URL"

# ── Email ─────────────────────────────────────────────────────────────────────

if [[ "$SETUP_MODE" == "email" || "$SETUP_MODE" == "both" ]]; then
    log "--- Email meters and products ---"

    ensure_meter "$EMAIL_METER_KEY"      "$EMAIL_METER_OUTPUT_KEY"      "$EMAIL_METER_NAME"      "$EMAIL_METER_EVENT"      "$EMAIL_METER_LABEL"      "$EMAIL_METER_MULTIPLIER"
    EMAIL_METER_ID="$LAST_ID"

    ensure_meter "$AUTOMATION_METER_KEY" "$AUTOMATION_METER_OUTPUT_KEY" "$AUTOMATION_METER_NAME" "$AUTOMATION_METER_EVENT" "$AUTOMATION_METER_LABEL" "$AUTOMATION_METER_MULTIPLIER"
    AUTOMATION_METER_ID="$LAST_ID"

    ensure_meter "$AI_CREDIT_METER_KEY"  "$AI_CREDIT_METER_OUTPUT_KEY"  "$AI_CREDIT_METER_NAME"  "$AI_CREDIT_METER_EVENT"  "$AI_CREDIT_METER_LABEL"  "$AI_CREDIT_METER_MULTIPLIER"
    AI_CREDIT_METER_ID="$LAST_ID"

    setup_plan "$PLAN_SPARK_NAME"      "$PLAN_SPARK_SLUG"      "$PLAN_SPARK_EMAILS"      "$PLAN_SPARK_PRICE"      "$PLAN_SPARK_OVERAGE_RATE"      "$PLAN_SPARK_AI_CREDITS"      "$PLAN_SPARK_VISIBILITY"      "$PLAN_SPARK_DESC"
    setup_plan "$PLAN_IGNITE_NAME"     "$PLAN_IGNITE_SLUG"     "$PLAN_IGNITE_EMAILS"     "$PLAN_IGNITE_PRICE"     "$PLAN_IGNITE_OVERAGE_RATE"     "$PLAN_IGNITE_AI_CREDITS"     "$PLAN_IGNITE_VISIBILITY"     "$PLAN_IGNITE_DESC"
    setup_plan "$PLAN_GROWTH_NAME"     "$PLAN_GROWTH_SLUG"     "$PLAN_GROWTH_EMAILS"     "$PLAN_GROWTH_PRICE"     "$PLAN_GROWTH_OVERAGE_RATE"     "$PLAN_GROWTH_AI_CREDITS"     "$PLAN_GROWTH_VISIBILITY"     "$PLAN_GROWTH_DESC"
    setup_plan "$PLAN_PULSE_NAME"      "$PLAN_PULSE_SLUG"      "$PLAN_PULSE_EMAILS"      "$PLAN_PULSE_PRICE"      "$PLAN_PULSE_OVERAGE_RATE"      "$PLAN_PULSE_AI_CREDITS"      "$PLAN_PULSE_VISIBILITY"      "$PLAN_PULSE_DESC"
    setup_plan "$PLAN_SCALE_NAME"      "$PLAN_SCALE_SLUG"      "$PLAN_SCALE_EMAILS"      "$PLAN_SCALE_PRICE"      "$PLAN_SCALE_OVERAGE_RATE"      "$PLAN_SCALE_AI_CREDITS"      "$PLAN_SCALE_VISIBILITY"      "$PLAN_SCALE_DESC"
    setup_plan "$PLAN_ENTERPRISE_NAME" "$PLAN_ENTERPRISE_SLUG" "$PLAN_ENTERPRISE_EMAILS" "$PLAN_ENTERPRISE_PRICE" "$PLAN_ENTERPRISE_OVERAGE_RATE" "$PLAN_ENTERPRISE_AI_CREDITS" "$PLAN_ENTERPRISE_VISIBILITY" "$PLAN_ENTERPRISE_DESC"

    ensure_product "product-$DEDICATED_IP_SLUG"     "$DEDICATED_IP_SLUG"     "$DEDICATED_IP_NAME"     "$DEDICATED_IP_DESC"     "recurring" "$DEDICATED_IP_PRICE"   ""               ""                       "public"
    ensure_product "product-$EMAIL_PAYG_SLUG"       "$EMAIL_PAYG_SLUG"       "$EMAIL_PAYG_NAME"       "$EMAIL_PAYG_DESC"       "recurring" "$EMAIL_PAYG_PRICE"      "$EMAIL_METER_ID"      "$EMAIL_PAYG_OVERAGE_RATE"      "public"
    ensure_product "product-$AUTOMATION_PAYG_SLUG"  "$AUTOMATION_PAYG_SLUG"  "$AUTOMATION_PAYG_NAME"  "$AUTOMATION_PAYG_DESC"  "recurring" "$AUTOMATION_PAYG_PRICE" "$AUTOMATION_METER_ID" "$AUTOMATION_PAYG_OVERAGE_RATE" "public"

    setup_pack "$PACK_AUTO_SMALL_NAME"  "$PACK_AUTO_SMALL_SLUG"  "$PACK_AUTO_SMALL_RUNS"  "$PACK_AUTO_SMALL_PRICE"  "$PACK_AUTO_SMALL_DESC"
    setup_pack "$PACK_AUTO_MEDIUM_NAME" "$PACK_AUTO_MEDIUM_SLUG" "$PACK_AUTO_MEDIUM_RUNS" "$PACK_AUTO_MEDIUM_PRICE" "$PACK_AUTO_MEDIUM_DESC"
    setup_pack "$PACK_AUTO_GROWTH_NAME" "$PACK_AUTO_GROWTH_SLUG" "$PACK_AUTO_GROWTH_RUNS" "$PACK_AUTO_GROWTH_PRICE" "$PACK_AUTO_GROWTH_DESC"
    setup_pack "$PACK_AUTO_SCALE_NAME"  "$PACK_AUTO_SCALE_SLUG"  "$PACK_AUTO_SCALE_RUNS"  "$PACK_AUTO_SCALE_PRICE"  "$PACK_AUTO_SCALE_DESC"

    setup_ai_credit_pack "$PACK_AI_100_NAME"  "$PACK_AI_100_SLUG"  "$PACK_AI_100_CREDITS"  "$PACK_AI_100_PRICE"  "$PACK_AI_100_DESC"
    setup_ai_credit_pack "$PACK_AI_500_NAME"  "$PACK_AI_500_SLUG"  "$PACK_AI_500_CREDITS"  "$PACK_AI_500_PRICE"  "$PACK_AI_500_DESC"
    setup_ai_credit_pack "$PACK_AI_1000_NAME" "$PACK_AI_1000_SLUG" "$PACK_AI_1000_CREDITS" "$PACK_AI_1000_PRICE" "$PACK_AI_1000_DESC"
fi

# ── SMS ───────────────────────────────────────────────────────────────────────

if [[ "$SETUP_MODE" == "sms" || "$SETUP_MODE" == "both" ]]; then
    log "--- SMS meters and products ---"

    ensure_meter "$SMS_SEGMENT_METER_KEY" "$SMS_SEGMENT_METER_OUTPUT_KEY" "$SMS_SEGMENT_METER_NAME" "$SMS_SEGMENT_METER_EVENT" "$SMS_SEGMENT_METER_LABEL" "$SMS_SEGMENT_METER_MULTIPLIER"
    SMS_SEGMENT_METER_ID="$LAST_ID"

    ensure_meter "$MMS_MESSAGE_METER_KEY" "$MMS_MESSAGE_METER_OUTPUT_KEY" "$MMS_MESSAGE_METER_NAME" "$MMS_MESSAGE_METER_EVENT" "$MMS_MESSAGE_METER_LABEL" "$MMS_MESSAGE_METER_MULTIPLIER"
    MMS_MESSAGE_METER_ID="$LAST_ID"

    # SMS subscription plans
    setup_sms_plan "$SMS_PLAN_LITE_NAME"       "$SMS_PLAN_LITE_SLUG"       "$SMS_PLAN_LITE_SEGMENTS"       "$SMS_PLAN_LITE_PRICE"       "$SMS_PLAN_LITE_OVERAGE_RATE"       "$SMS_PLAN_LITE_VISIBILITY"       "$SMS_PLAN_LITE_DESC"
    setup_sms_plan "$SMS_PLAN_STARTER_NAME"    "$SMS_PLAN_STARTER_SLUG"    "$SMS_PLAN_STARTER_SEGMENTS"    "$SMS_PLAN_STARTER_PRICE"    "$SMS_PLAN_STARTER_OVERAGE_RATE"    "$SMS_PLAN_STARTER_VISIBILITY"    "$SMS_PLAN_STARTER_DESC"
    setup_sms_plan "$SMS_PLAN_GROWTH_NAME"     "$SMS_PLAN_GROWTH_SLUG"     "$SMS_PLAN_GROWTH_SEGMENTS"     "$SMS_PLAN_GROWTH_PRICE"     "$SMS_PLAN_GROWTH_OVERAGE_RATE"     "$SMS_PLAN_GROWTH_VISIBILITY"     "$SMS_PLAN_GROWTH_DESC"
    setup_sms_plan "$SMS_PLAN_BUSINESS_NAME"   "$SMS_PLAN_BUSINESS_SLUG"   "$SMS_PLAN_BUSINESS_SEGMENTS"   "$SMS_PLAN_BUSINESS_PRICE"   "$SMS_PLAN_BUSINESS_OVERAGE_RATE"   "$SMS_PLAN_BUSINESS_VISIBILITY"   "$SMS_PLAN_BUSINESS_DESC"
    setup_sms_plan "$SMS_PLAN_SCALE_NAME"      "$SMS_PLAN_SCALE_SLUG"      "$SMS_PLAN_SCALE_SEGMENTS"      "$SMS_PLAN_SCALE_PRICE"      "$SMS_PLAN_SCALE_OVERAGE_RATE"      "$SMS_PLAN_SCALE_VISIBILITY"      "$SMS_PLAN_SCALE_DESC"
    setup_sms_plan "$SMS_PLAN_ENTERPRISE_NAME" "$SMS_PLAN_ENTERPRISE_SLUG" "$SMS_PLAN_ENTERPRISE_SEGMENTS" "$SMS_PLAN_ENTERPRISE_PRICE" "$SMS_PLAN_ENTERPRISE_OVERAGE_RATE" "$SMS_PLAN_ENTERPRISE_VISIBILITY" "$SMS_PLAN_ENTERPRISE_DESC"

    # MMS add-on
    ensure_sms_product "product-$MMS_ADDON_SLUG" "$MMS_ADDON_SLUG" "$MMS_ADDON_NAME" "$MMS_ADDON_DESC" \
        "recurring" "$MMS_ADDON_PRICE" "$MMS_MESSAGE_METER_ID" "$MMS_ADDON_OVERAGE_RATE" "public"
    MMS_ADDON_PRODUCT_ID="$LAST_ID"
    ensure_meter_credit_benefit \
        "benefit-${MMS_ADDON_SLUG}-messages" "Signal MMS monthly messages" \
        "$MMS_MESSAGE_METER_ID" "$MMS_ADDON_INCLUDED_MESSAGES" "false"
    set_product_benefits "$MMS_ADDON_PRODUCT_ID" "$LAST_ID"

    # Premium recurring add-ons
    ensure_sms_product "product-$SMS_NUMBER_POOLING_SLUG"    "$SMS_NUMBER_POOLING_SLUG"    "$SMS_NUMBER_POOLING_NAME"    "$SMS_NUMBER_POOLING_DESC"    "recurring" "$SMS_NUMBER_POOLING_PRICE"    "" "" "public"
    ensure_sms_product "product-$SMS_SHORT_CODE_RANDOM_SLUG" "$SMS_SHORT_CODE_RANDOM_SLUG" "$SMS_SHORT_CODE_RANDOM_NAME" "$SMS_SHORT_CODE_RANDOM_DESC" "recurring" "$SMS_SHORT_CODE_RANDOM_PRICE" "" "" "public"
    ensure_sms_product "product-$SMS_SHORT_CODE_VANITY_SLUG" "$SMS_SHORT_CODE_VANITY_SLUG" "$SMS_SHORT_CODE_VANITY_NAME" "$SMS_SHORT_CODE_VANITY_DESC" "recurring" "$SMS_SHORT_CODE_VANITY_PRICE" "" "" "public"

    # One-time setup fees
    ensure_sms_product "product-$SMS_SHORT_CODE_SETUP_SLUG"     "$SMS_SHORT_CODE_SETUP_SLUG"     "$SMS_SHORT_CODE_SETUP_NAME"     "$SMS_SHORT_CODE_SETUP_DESC"     "one_time" "$SMS_SHORT_CODE_SETUP_PRICE"     "" "" "public"
    ensure_sms_product "product-$SMS_SHORT_CODE_MMS_SETUP_SLUG" "$SMS_SHORT_CODE_MMS_SETUP_SLUG" "$SMS_SHORT_CODE_MMS_SETUP_NAME" "$SMS_SHORT_CODE_MMS_SETUP_DESC" "one_time" "$SMS_SHORT_CODE_MMS_SETUP_PRICE" "" "" "public"

    # Segment top-up packs
    setup_sms_segment_pack "$SMS_PACK_1K_NAME"   "$SMS_PACK_1K_SLUG"   "$SMS_PACK_1K_SEGMENTS"   "$SMS_PACK_1K_PRICE"   "$SMS_PACK_1K_DESC"
    setup_sms_segment_pack "$SMS_PACK_5K_NAME"   "$SMS_PACK_5K_SLUG"   "$SMS_PACK_5K_SEGMENTS"   "$SMS_PACK_5K_PRICE"   "$SMS_PACK_5K_DESC"
    setup_sms_segment_pack "$SMS_PACK_25K_NAME"  "$SMS_PACK_25K_SLUG"  "$SMS_PACK_25K_SEGMENTS"  "$SMS_PACK_25K_PRICE"  "$SMS_PACK_25K_DESC"
    setup_sms_segment_pack "$SMS_PACK_100K_NAME" "$SMS_PACK_100K_SLUG" "$SMS_PACK_100K_SEGMENTS" "$SMS_PACK_100K_PRICE" "$SMS_PACK_100K_DESC"
fi

write_output
print_catalog_hint