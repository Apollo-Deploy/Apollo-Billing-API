#!/usr/bin/env bash
set -euo pipefail

# Attaches an enterprise plan (email and/or SMS) to a client in Polar.
#
# Enterprise products use a custom price, so Polar requires the checkout flow —
# this script creates a checkout session for the client and prints the payment
# URL for them to complete. Once paid, their subscription activates normally.
#
# Polar API endpoints used:
#   GET  /v1/customers/             (lookup by email)
#   GET  /v1/customers/external/{}  (lookup by external ID)
#   POST /v1/customers/             (create customer if not found)
#   GET  /v1/products/              (resolve enterprise product IDs by metadata)
#   POST /v1/checkouts/             (create a checkout session with custom price)

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

# ── Defaults ─────────────────────────────────────────────────────────────────

ENV_MODE="sandbox"          # sandbox | production
DRY_RUN=0
API_KEY="${POLAR_API_KEY:-}"
ORGANIZATION_ID="${POLAR_ORGANIZATION_ID:-}"

# Client identity — one of --customer-id, --external-id, or --email required
CUSTOMER_ID=""
EXTERNAL_ID=""
CUSTOMER_EMAIL=""
CUSTOMER_NAME=""            # used when creating a new customer

# Plan selection
ATTACH_EMAIL=0              # attach Signal Enterprise (email)
ATTACH_SMS=0                # attach Signal SMS Enterprise

# Pricing — cents; "custom" products require an explicit amount to charge
EMAIL_PRICE_CENTS=""        # e.g. 50000  → $500/mo
SMS_PRICE_CENTS=""          # e.g. 20000  → $200/mo

# Checkout options
SUCCESS_URL="${POLAR_SUCCESS_URL:-https://app.apollosignal.com/billing/success}"
RETURN_URL="${POLAR_RETURN_URL:-https://app.apollosignal.com/billing}"
DISCOUNT_ID=""              # optional Polar discount ID to apply
NOTE=""                     # internal note stored in checkout metadata

# ── Argument parsing ─────────────────────────────────────────────────────────

while [[ $# -gt 0 ]]; do
    case "$1" in
        --dry-run)
            DRY_RUN=1; shift ;;
        --env)
            ENV_MODE="${2:?Missing value for --env (sandbox|production)}"
            case "$ENV_MODE" in
                sandbox|production) ;;
                *) echo "Invalid --env value: $ENV_MODE" >&2; exit 1 ;;
            esac
            shift 2 ;;
        --customer-id)
            CUSTOMER_ID="${2:?Missing value for --customer-id}"; shift 2 ;;
        --external-id)
            EXTERNAL_ID="${2:?Missing value for --external-id}"; shift 2 ;;
        --email)
            CUSTOMER_EMAIL="${2:?Missing value for --email}"; shift 2 ;;
        --name)
            CUSTOMER_NAME="${2:?Missing value for --name}"; shift 2 ;;
        --attach)
            ATTACH_VALUE="${2:?Missing value for --attach (email|sms|both)}"
            case "$ATTACH_VALUE" in
                email) ATTACH_EMAIL=1 ;;
                sms)   ATTACH_SMS=1 ;;
                both)  ATTACH_EMAIL=1; ATTACH_SMS=1 ;;
                *) echo "Invalid --attach value: $ATTACH_VALUE. Must be email, sms, or both." >&2; exit 1 ;;
            esac
            shift 2 ;;
        --email-price)
            EMAIL_PRICE_CENTS="${2:?Missing value for --email-price (cents, e.g. 50000)}"; shift 2 ;;
        --sms-price)
            SMS_PRICE_CENTS="${2:?Missing value for --sms-price (cents, e.g. 20000)}"; shift 2 ;;
        --discount-id)
            DISCOUNT_ID="${2:?Missing value for --discount-id}"; shift 2 ;;
        --success-url)
            SUCCESS_URL="${2:?Missing value for --success-url}"; shift 2 ;;
        --return-url)
            RETURN_URL="${2:?Missing value for --return-url}"; shift 2 ;;
        --note)
            NOTE="${2:?Missing value for --note}"; shift 2 ;;
        --organization-id)
            ORGANIZATION_ID="${2:?Missing value for --organization-id}"; shift 2 ;;
        -h|--help)
            cat <<'USAGE'
Usage:
  POLAR_API_KEY=... scripts/polar/attach-enterprise-plan.sh [options]

Client identification (one required):
  --customer-id UUID        Polar customer ID.
  --external-id ID          Your internal org/customer ID mapped via Polar external_id.
  --email EMAIL             Customer email. A new Polar customer is created if not found.
  --name NAME               Customer display name (used when creating a new customer).

Plan selection (one required):
  --attach email|sms|both   Which enterprise plan(s) to attach.

Pricing (required when attaching; cents):
  --email-price CENTS       Monthly price for email enterprise plan (e.g. 50000 = $500/mo).
  --sms-price   CENTS       Monthly price for SMS enterprise plan   (e.g. 20000 = $200/mo).

Optional:
  --env sandbox|production  Target environment (default: sandbox).
  --dry-run                 Print what would happen without calling Polar.
  --discount-id UUID        Apply an existing Polar discount to the checkout.
  --success-url URL         Redirect after successful payment.
  --return-url  URL         Redirect if customer cancels.
  --note TEXT               Internal note stored in checkout metadata.
  --organization-id UUID    Required when using a user token instead of an org token.

Environment variables:
  POLAR_API_KEY             Polar API key.
  POLAR_ORGANIZATION_ID     Optional Polar org UUID.
  POLAR_SUCCESS_URL         Default post-payment redirect.
  POLAR_RETURN_URL          Default cancel redirect.
  ENV_FILE                  Path to .env file (default: repo .env).

Examples:
  # Attach email enterprise at $500/mo to an existing customer by email
  POLAR_API_KEY=sk_... ./attach-enterprise-plan.sh \
    --env production \
    --email sales@acme.com \
    --attach email \
    --email-price 50000

  # Attach both plans to a known customer by Polar ID
  POLAR_API_KEY=sk_... ./attach-enterprise-plan.sh \
    --env production \
    --customer-id 992fae2a-2a17-4b7a-8d9e-e287cf90131b \
    --attach both \
    --email-price 75000 \
    --sms-price 25000 \
    --note "ACME Corp Q3 deal"
USAGE
            exit 0 ;;
        *)
            echo "Unknown argument: $1" >&2; exit 1 ;;
    esac
done

# ── Validation ───────────────────────────────────────────────────────────────

errors=0

if [[ -z "$CUSTOMER_ID" && -z "$EXTERNAL_ID" && -z "$CUSTOMER_EMAIL" ]]; then
    echo "Error: one of --customer-id, --external-id, or --email is required." >&2
    errors=1
fi

if [[ "$ATTACH_EMAIL" -eq 0 && "$ATTACH_SMS" -eq 0 ]]; then
    echo "Error: --attach email|sms|both is required." >&2
    errors=1
fi

if [[ "$ATTACH_EMAIL" -eq 1 && -z "$EMAIL_PRICE_CENTS" ]]; then
    echo "Error: --email-price CENTS is required when attaching the email enterprise plan." >&2
    errors=1
fi

if [[ "$ATTACH_SMS" -eq 1 && -z "$SMS_PRICE_CENTS" ]]; then
    echo "Error: --sms-price CENTS is required when attaching the SMS enterprise plan." >&2
    errors=1
fi

if [[ "$DRY_RUN" -eq 0 && -z "$API_KEY" ]]; then
    echo "Error: POLAR_API_KEY is not set." >&2
    errors=1
fi

[[ "$errors" -eq 1 ]] && exit 1

# ── Resolve environment ───────────────────────────────────────────────────────

if [[ "$ENV_MODE" == "production" ]]; then
    BASE_URL="$POLAR_URL_PRODUCTION"
    NAMESPACE="$NAMESPACE_PRODUCTION"
else
    BASE_URL="$POLAR_URL_SANDBOX"
    NAMESPACE="$NAMESPACE_SANDBOX"
fi
BASE_URL="${BASE_URL%/}"

# ── Production guard ──────────────────────────────────────────────────────────

if [[ "$DRY_RUN" -eq 0 && "$ENV_MODE" == "production" ]]; then
    echo ""
    echo "  ⚠️  You are about to write to the PRODUCTION Polar API."
    echo "     Base URL : $BASE_URL"
    echo "     Plans    : $(
        [[ "$ATTACH_EMAIL" -eq 1 ]] && printf "email-enterprise "
        [[ "$ATTACH_SMS"   -eq 1 ]] && printf "sms-enterprise"
    )"
    [[ -n "$CUSTOMER_EMAIL" ]]  && echo "     Customer : $CUSTOMER_EMAIL"
    [[ -n "$EXTERNAL_ID" ]]     && echo "     Ext ID   : $EXTERNAL_ID"
    [[ -n "$CUSTOMER_ID" ]]     && echo "     Polar ID : $CUSTOMER_ID"
    echo ""
    read -r -p "  Type 'yes' to continue: " CONFIRM
    if [[ "$CONFIRM" != "yes" ]]; then
        echo "Aborted." >&2
        exit 1
    fi
fi

for cmd in curl jq; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
        echo "Missing required command: $cmd" >&2; exit 1
    fi
done

# ── Logging & API helpers ─────────────────────────────────────────────────────

log()  { printf '%s\n' "$*" >&2; }
info() { printf '  %s\n' "$*"; }

api_request() {
    local method="$1" path="$2" body="${3:-}"
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
        echo "Polar API error: $method $path -> HTTP $status" >&2
        cat "$tmp" >&2
        rm -f "$tmp"
        exit 1
    fi
    cat "$tmp"
    rm -f "$tmp"
}

api_get() {
    local path="$1" query="${2:-}"
    local tmp status url
    url="$BASE_URL$path"
    tmp="$(mktemp)"

    if [[ -n "$query" ]]; then
        status="$(curl -sS -G -o "$tmp" -w '%{http_code}' "$url" \
            -H "Authorization: Bearer $API_KEY" \
            --data-urlencode "$query")"
    else
        status="$(curl -sS -o "$tmp" -w '%{http_code}' "$url" \
            -H "Authorization: Bearer $API_KEY")"
    fi

    if [[ "$status" -lt 200 || "$status" -ge 300 ]]; then
        echo "Polar API error: GET $path -> HTTP $status" >&2
        cat "$tmp" >&2
        rm -f "$tmp"
        exit 1
    fi
    cat "$tmp"
    rm -f "$tmp"
}

maybe_org_field() {
    if [[ -n "$ORGANIZATION_ID" ]]; then
        jq -n --arg id "$ORGANIZATION_ID" '"organization_id": $id'
    else
        echo ""
    fi
}

# ── Customer resolution ───────────────────────────────────────────────────────

resolve_customer() {
    # Returns customer ID in CUSTOMER_ID; sets CUSTOMER_EMAIL/NAME from Polar if found.

    if [[ -n "$CUSTOMER_ID" ]]; then
        if [[ "$DRY_RUN" -eq 1 ]]; then
            log "[dry-run] Using provided Polar customer ID: $CUSTOMER_ID"
            return
        fi
        log "Using provided Polar customer ID: $CUSTOMER_ID"
        # Fetch to validate and retrieve email for display
        local data
        data="$(api_get "/v1/customers/$CUSTOMER_ID")"
        CUSTOMER_EMAIL="$(jq -r '.email // ""' <<<"$data")"
        CUSTOMER_NAME="$(jq -r '.name // ""' <<<"$data")"
        log "Customer: ${CUSTOMER_NAME:-<no name>} <$CUSTOMER_EMAIL>"
        return
    fi

    if [[ -n "$EXTERNAL_ID" ]]; then
        if [[ "$DRY_RUN" -eq 1 ]]; then
            log "[dry-run] Would look up customer by external ID: $EXTERNAL_ID"
            CUSTOMER_ID="dry-customer-id"
            return
        fi
        log "Looking up customer by external ID: $EXTERNAL_ID"
        local data
        data="$(api_get "/v1/customers/external/$EXTERNAL_ID")"
        CUSTOMER_ID="$(jq -r '.id' <<<"$data")"
        CUSTOMER_EMAIL="$(jq -r '.email // ""' <<<"$data")"
        CUSTOMER_NAME="$(jq -r '.name // ""' <<<"$data")"
        log "Found customer: ${CUSTOMER_NAME:-<no name>} <$CUSTOMER_EMAIL> ($CUSTOMER_ID)"
        return
    fi

    # Lookup by email — dry-run already handled before resolve_customer is called
    log "Looking up customer by email: $CUSTOMER_EMAIL"
    local list
    list="$(api_get "/v1/customers/" "email=$CUSTOMER_EMAIL")"
    local found_id
    found_id="$(jq -r '.items[0].id // empty' <<<"$list")"

    if [[ -n "$found_id" ]]; then
        CUSTOMER_ID="$found_id"
        CUSTOMER_NAME="$(jq -r '.items[0].name // ""' <<<"$list")"
        log "Found existing customer: ${CUSTOMER_NAME:-<no name>} <$CUSTOMER_EMAIL> ($CUSTOMER_ID)"
        return
    fi

    # Create new customer
    log "No existing customer found for $CUSTOMER_EMAIL — creating."
    local body
    body="$(jq -n \
        --arg email "$CUSTOMER_EMAIL" \
        --arg name  "${CUSTOMER_NAME:-}" \
        --argjson org "$(if [[ -n "$ORGANIZATION_ID" ]]; then
                            jq -n --arg id "$ORGANIZATION_ID" '{organization_id:$id}'
                         else
                            jq -n '{}'
                         fi)" \
        '$org + {email: $email, name: ($name | if . == "" then null else . end)}')"
    local created
    created="$(api_request POST "/v1/customers/" "$body")"
    CUSTOMER_ID="$(jq -r '.id' <<<"$created")"
    log "Created customer: ${CUSTOMER_NAME:-<no name>} <$CUSTOMER_EMAIL> ($CUSTOMER_ID)"
}

# ── Product resolution ────────────────────────────────────────────────────────
# Looks up a product by its setup_key in metadata, matching the current namespace.

resolve_product_id() {
    local setup_key="$1" label="$2"
    local list product_id
    list="$(
        curl -sS -G "$BASE_URL/v1/products/" \
            -H "Authorization: Bearer $API_KEY" \
            --data-urlencode "limit=100" \
            --data-urlencode "metadata[apollo_setup_key]=$setup_key" \
            --data-urlencode "metadata[apollo_namespace]=$NAMESPACE"
    )"
    product_id="$(jq -r '.items[0].id // empty' <<<"$list")"

    if [[ -z "$product_id" ]]; then
        echo "Error: Could not find $label product in Polar (key=$setup_key, ns=$NAMESPACE)." >&2
        echo "Run setup-signal.sh first to create the catalog." >&2
        exit 1
    fi
    echo "$product_id"
}

# ── Checkout creation ─────────────────────────────────────────────────────────

create_checkout() {
    local product_id="$1"
    local price_cents="$2"
    local plan_label="$3"

    if [[ "$DRY_RUN" -eq 1 ]]; then
        log "[dry-run] Would create checkout for customer $CUSTOMER_ID"
        log "[dry-run]   product : $product_id ($plan_label)"
        log "[dry-run]   price   : \$$((price_cents / 100))/mo (${price_cents}c)"
        [[ -n "$DISCOUNT_ID" ]] && log "[dry-run]   discount: $DISCOUNT_ID"
        log "[dry-run]   -> checkout URL: https://example.com/checkout/dry-run-session"
        echo "https://example.com/checkout/dry-run-session"
        return
    fi

    # Build ad-hoc fixed price override for the custom-priced enterprise product
    local prices_json
    prices_json="$(jq -n \
        --arg productId "$product_id" \
        --argjson amount "$price_cents" \
        '{($productId): [{amount_type: "fixed", price_currency: "usd", price_amount: $amount}]}')"

    # Build metadata
    local metadata
    metadata="$(jq -n \
        --arg ns    "$NAMESPACE" \
        --arg app   "signal" \
        --arg label "$plan_label" \
        --arg note  "${NOTE:-}" \
        '{
            apollo_namespace: $ns,
            apollo_app: $app,
            apollo_plan_label: $label,
            apollo_note: (if $note == "" then null else $note end)
        }')"

    # Build discount fragment
    local discount_fragment=""
    if [[ -n "$DISCOUNT_ID" ]]; then
        discount_fragment="$(jq -n --arg did "$DISCOUNT_ID" '"discount_id": $did')"
    fi

    # Build org fragment
    local org_fragment=""
    if [[ -n "$ORGANIZATION_ID" ]]; then
        org_fragment="$(jq -n --arg oid "$ORGANIZATION_ID" '"organization_id": $oid')"
    fi

    local body
    body="$(jq -n \
        --arg customerId  "$CUSTOMER_ID" \
        --arg productId   "$product_id" \
        --arg successUrl  "$SUCCESS_URL" \
        --arg returnUrl   "$RETURN_URL" \
        --argjson prices    "$prices_json" \
        --argjson metadata  "$metadata" \
        --argjson discountFragment "$(
            if [[ -n "$DISCOUNT_ID" ]]; then
                jq -n --arg did "$DISCOUNT_ID" '{"discount_id": $did}'
            else
                jq -n '{}'
            fi)" \
        --argjson orgFragment "$(
            if [[ -n "$ORGANIZATION_ID" ]]; then
                jq -n --arg oid "$ORGANIZATION_ID" '{"organization_id": $oid}'
            else
                jq -n '{}'
            fi)" \
        '$discountFragment + $orgFragment + {
            customer_id:  $customerId,
            products:     [$productId],
            prices:       $prices,
            metadata:     $metadata,
            success_url:  $successUrl,
            return_url:   $returnUrl
        }')"

    local response
    response="$(api_request POST "/v1/checkouts/" "$body")"
    jq -r '.url' <<<"$response"
}

# ══════════════════════════════════════════════════════════════════════════════
# Main execution
# ══════════════════════════════════════════════════════════════════════════════

log "Attaching enterprise plan(s) | env=$ENV_MODE dry_run=$DRY_RUN"

# ── Resolve customer ──────────────────────────────────────────────────────────

resolve_customer

# ── Resolve enterprise product IDs from Polar catalog ────────────────────────

EMAIL_ENTERPRISE_PRODUCT_ID=""
SMS_ENTERPRISE_PRODUCT_ID=""

if [[ "$ATTACH_EMAIL" -eq 1 ]]; then
    if [[ "$DRY_RUN" -eq 1 ]]; then
        EMAIL_ENTERPRISE_PRODUCT_ID="dry-product-signal-enterprise"
        log "[dry-run] Would resolve email enterprise product (key=product-${PLAN_ENTERPRISE_SLUG})"
    else
        log "Resolving email enterprise product..."
        EMAIL_ENTERPRISE_PRODUCT_ID="$(resolve_product_id "product-${PLAN_ENTERPRISE_SLUG}" "Signal Enterprise")"
        log "Email enterprise product ID: $EMAIL_ENTERPRISE_PRODUCT_ID"
    fi
fi

if [[ "$ATTACH_SMS" -eq 1 ]]; then
    if [[ "$DRY_RUN" -eq 1 ]]; then
        SMS_ENTERPRISE_PRODUCT_ID="dry-product-signal-sms-enterprise"
        log "[dry-run] Would resolve SMS enterprise product (key=product-${SMS_PLAN_ENTERPRISE_SLUG})"
    else
        log "Resolving SMS enterprise product..."
        SMS_ENTERPRISE_PRODUCT_ID="$(resolve_product_id "product-${SMS_PLAN_ENTERPRISE_SLUG}" "Signal SMS Enterprise")"
        log "SMS enterprise product ID: $SMS_ENTERPRISE_PRODUCT_ID"
    fi
fi

# ── Create checkout session(s) ────────────────────────────────────────────────

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo " Enterprise plan checkout(s) for: ${CUSTOMER_NAME:-${CUSTOMER_EMAIL:-$CUSTOMER_ID}}"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""

if [[ "$ATTACH_EMAIL" -eq 1 ]]; then
    price_display="$((EMAIL_PRICE_CENTS / 100))"
    echo "  Signal Enterprise (email) — \$${price_display}/mo"
    checkout_url="$(create_checkout "$EMAIL_ENTERPRISE_PRODUCT_ID" "$EMAIL_PRICE_CENTS" "signal-enterprise")"
    echo "  Checkout URL:"
    echo "  $checkout_url"
    echo ""
fi

if [[ "$ATTACH_SMS" -eq 1 ]]; then
    price_display="$((SMS_PRICE_CENTS / 100))"
    echo "  Signal SMS Enterprise — \$${price_display}/mo"
    checkout_url="$(create_checkout "$SMS_ENTERPRISE_PRODUCT_ID" "$SMS_PRICE_CENTS" "signal-sms-enterprise")"
    echo "  Checkout URL:"
    echo "  $checkout_url"
    echo ""
fi

if [[ -n "$NOTE" ]]; then
    echo "  Note: $NOTE"
    echo ""
fi

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo " Send the URL(s) above to the client to complete payment."
echo " Subscriptions activate automatically once payment clears."
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo ""
