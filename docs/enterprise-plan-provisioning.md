# Enterprise Plan Provisioning

This guide covers how to provision a Signal enterprise plan for a client using
`scripts/polar/attach-enterprise-plan.sh`. It explains the pricing model,
prerequisites, common invocation patterns, and what happens after the checkout
is created.

## Background

Signal Enterprise and Signal SMS Enterprise are **private, custom-priced**
Polar products. Because Polar only allows `POST /v1/subscriptions/` for free
products, paid plans must go through the checkout flow. The script creates a
Polar checkout session with a per-deal ad-hoc price and returns a payment URL
to send to the client. Once the client completes payment, their subscription
activates automatically and the platform webhook handler picks it up.

The negotiated price is stored on the resulting subscription object in Polar —
it is not written back to the product definition, so each client can have a
completely different monthly rate.

## Prerequisites

### 1. Catalog created in Polar

The target environment must already have the Signal product catalog created.
Run `setup-signal.sh` first if you have not done so:

```bash
# Sandbox
POLAR_API_KEY="sk_sandbox_..." \
  scripts/polar/setup-signal.sh --env sandbox --setup both

# Production
POLAR_API_KEY="sk_live_..." \
  scripts/polar/setup-signal.sh --env production --setup both
```

The provisioning script resolves product IDs at runtime by querying Polar
metadata — it will fail with a clear error if the enterprise product is missing.

### 2. API key with the right scopes

Use a Polar API key that has:

- `customers:read` and `customers:write` — to look up or create the customer
- `products:read` — to resolve product IDs by metadata
- `checkouts:write` — to create the checkout session

Keep sandbox and production keys separate. The script defaults to sandbox;
passing `--env production` switches the target URL and namespace.

Set the key in your environment or `.env` file:

```bash
export POLAR_API_KEY="sk_sandbox_..."
```

Or prefix inline:

```bash
POLAR_API_KEY="sk_sandbox_..." scripts/polar/attach-enterprise-plan.sh ...
```

### 3. Agreed price (in cents)

Enterprise pricing is negotiated per deal. You need the agreed monthly amount
before running the script. Prices are passed in **cents**:

| Deal price | Flag value |
|------------|------------|
| $500/mo    | `50000`    |
| $750/mo    | `75000`    |
| $1,200/mo  | `120000`   |

There is no default — the script requires an explicit price when attaching each
plan.

## Quick Reference

```
scripts/polar/attach-enterprise-plan.sh [options]

Client identification (one required):
  --customer-id UUID        Polar customer UUID (fastest; no lookup needed).
  --external-id ID          Apollo org ID registered as Polar external_id.
  --email EMAIL             Customer email. Creates a new Polar customer if not found.
  --name NAME               Display name used only when creating a new customer.

Plan selection (required):
  --attach email|sms|both

Pricing (required per plan selected):
  --email-price CENTS       Monthly price for Signal Enterprise.
  --sms-price   CENTS       Monthly price for Signal SMS Enterprise.

Environment:
  --env sandbox|production  Default: sandbox.
  --dry-run                 Print what would happen without calling Polar.

Optional:
  --discount-id UUID        Apply an existing Polar discount to the checkout.
  --success-url URL         Redirect after successful payment.
  --return-url  URL         Redirect if customer cancels.
  --note TEXT               Internal note stored in checkout metadata (e.g. deal name).
  --organization-id UUID    Required when using a user token instead of an org token.
```

## Usage Examples

### Attach email enterprise by client email

The most common case. Useful when you have just closed a deal and do not have
the client's Polar customer ID yet.

```bash
POLAR_API_KEY="sk_live_..." \
  scripts/polar/attach-enterprise-plan.sh \
    --env production \
    --email billing@acme.com \
    --name "ACME Corp" \
    --attach email \
    --email-price 50000 \
    --note "ACME Q3 2026 — signed by Alice"
```

If a Polar customer already exists for that email it is reused. If not, a new
customer record is created before the checkout session.

### Attach both email and SMS enterprise

```bash
POLAR_API_KEY="sk_live_..." \
  scripts/polar/attach-enterprise-plan.sh \
    --env production \
    --email billing@globex.com \
    --attach both \
    --email-price 75000 \
    --sms-price 25000 \
    --note "Globex Corp — combined deal"
```

Two separate checkout sessions are created, one per product. The client
receives both URLs and completes each payment independently. SMS enterprise
is an add-on that stacks on top of any Ignite+ base plan, so the client needs
an active base plan subscription as well.

### Look up an existing client by Polar customer ID

Use this when you already know the Polar customer UUID (e.g. from a previous
deal or from the Polar dashboard).

```bash
POLAR_API_KEY="sk_live_..." \
  scripts/polar/attach-enterprise-plan.sh \
    --env production \
    --customer-id 992fae2a-2a17-4b7a-8d9e-e287cf90131b \
    --attach email \
    --email-price 60000
```

When `--customer-id` is provided the script skips the email lookup entirely and
uses the ID directly.

### Look up by Apollo org ID (external ID)

If the customer is already registered in Polar with their Apollo org ID as
`external_id` you can use that instead:

```bash
POLAR_API_KEY="sk_live_..." \
  scripts/polar/attach-enterprise-plan.sh \
    --env production \
    --external-id org_1a2b3c4d \
    --attach sms \
    --sms-price 19500
```

### Apply a Polar discount

Pass an existing Polar discount ID to apply a pre-configured discount to the
checkout:

```bash
  --discount-id disc_abc123
```

The discount is applied to the checkout session. It reduces the amount the
client pays at checkout but does not change the base subscription amount stored
in Polar.

### Dry run before going live

Always run with `--dry-run` first to verify the arguments and see what would
happen:

```bash
POLAR_API_KEY="sk_live_..." \
  scripts/polar/attach-enterprise-plan.sh \
    --dry-run \
    --env production \
    --email billing@acme.com \
    --attach both \
    --email-price 50000 \
    --sms-price 20000 \
    --note "ACME test run"
```

Dry run does not call any Polar endpoint. It logs the resolved customer,
product keys, prices, and a placeholder checkout URL.

### Sandbox testing

Use the sandbox environment and a sandbox API key to test the full flow before
running against production:

```bash
POLAR_API_KEY="sk_sandbox_..." \
  scripts/polar/attach-enterprise-plan.sh \
    --env sandbox \
    --email test@example.com \
    --attach email \
    --email-price 50000
```

The sandbox Polar API is at `https://sandbox-api.polar.sh`. The script uses
the `apollo-signal-sandbox-v1` namespace to resolve products.

## How Pricing Works

The price passed via `--email-price` / `--sms-price` is set as an **ad-hoc
price override on the checkout session** and does not modify the product
definition in Polar.

Internally, the script sends:

```json
POST /v1/checkouts/
{
  "customer_id": "<polar customer id>",
  "products": ["<enterprise product id>"],
  "prices": {
    "<enterprise product id>": [
      { "amount_type": "fixed", "price_currency": "usd", "price_amount": 50000 }
    ]
  }
}
```

Polar stores the negotiated price on the resulting subscription record. Future
renewal invoices for that customer use the amount from their subscription, not
the product's default price. Each client's price is fully independent.

## What Happens After the Checkout URL Is Sent

1. Client opens the URL and completes payment via Polar's hosted checkout.
2. Polar creates an order and activates the subscription.
3. The platform webhook (`/webhooks/polar`) receives `subscription.active`.
4. `PolarWebhookHandler` resolves the product through `AppRegistry`, upserts
   the customer and subscription, and invalidates the entitlement cache.
5. The next call to `GET /internal/billing/entitlements/signal/{orgId}` returns
   the updated plan.

Checkout sessions expire if the client does not complete payment. Generate a
new one by running the script again — product resolution is idempotent and a
new session is cheap to create.

## Production Guard

When `--env production` is specified and `--dry-run` is not set, the script
prints a summary and requires you to type `yes` before making any API calls:

```
  ⚠️  You are about to write to the PRODUCTION Polar API.
     Base URL : https://api.polar.sh
     Plans    : email-enterprise sms-enterprise
     Customer : billing@acme.com

  Type 'yes' to continue:
```

## Troubleshooting

**`Could not find Signal Enterprise product in Polar`**
The enterprise product has not been created yet in the target environment.
Run `setup-signal.sh --env <env> --setup email` (or `--setup sms` / `--setup both`).

**`POLAR_API_KEY is not set`**
Export the key or prefix the command: `POLAR_API_KEY="..." scripts/polar/attach-enterprise-plan.sh`.

**`Polar API error: POST /v1/checkouts/ -> HTTP 422`**
The request body was rejected. Common causes:
- `product_id` does not exist in the target environment (sandbox vs. production mismatch).
- `customer_id` does not belong to the organization associated with your API key.
- Price amount is zero or negative — pass a positive integer in cents.

**`Polar API error: GET /v1/customers/external/{id} -> HTTP 404`**
No Polar customer exists with that external ID. Use `--email` instead to create
one, then record the returned Polar customer ID for future use.

**Checkout URL expires before the client pays**
Polar checkout sessions have a TTL. Run the script again to generate a fresh
URL. The existing customer and subscription records are unaffected.

**Client paid but entitlements have not updated**
Check that the Polar webhook is configured and reachable. Verify that
`subscription.active` is in the enabled webhook events for the target
environment. The entitlement cache TTL is 5 seconds by default, so a forced
re-fetch should return the updated state within seconds of webhook delivery.
