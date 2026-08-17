# Enterprise Plan Provisioning

Use `scripts/polar/attach-enterprise-plan.sh` to create a custom-priced Signal
Enterprise checkout for a client. Polar requires paid custom plans to use a
checkout session; the script returns the hosted payment URL and the existing
webhook flow activates the subscription after payment.

## Prerequisites

Create the Signal catalog in the target environment first:

```bash
POLAR_API_KEY="sk_sandbox_..." bash scripts/polar/setup-signal.sh --env sandbox
POLAR_API_KEY="sk_live_..." bash scripts/polar/setup-signal.sh --env production
```

The API key needs `customers:read`, `customers:write`, `products:read`, and
`checkouts:write`. Keep sandbox and production keys separate.

## Usage

Identify the client with one of `--customer-id`, `--external-id`, or `--email`.
Pass the negotiated monthly price in cents:

```bash
POLAR_API_KEY="sk_live_..." \
  bash scripts/polar/attach-enterprise-plan.sh \
    --env production \
    --email billing@acme.com \
    --name "ACME Corp" \
    --attach email \
    --email-price 50000 \
    --note "ACME enterprise agreement"
```

`--attach email` remains as an optional compatibility flag. Signal Enterprise
email is the only supported enterprise product.

Run the command with `--dry-run` before production use. A non-dry-run production
command prints its target and requires an explicit `yes` confirmation.

## What happens next

1. Send the generated URL to the client.
2. The client completes Polar's hosted checkout.
3. Polar emits `subscription.active` to `/webhooks/polar`.
4. Billing records the subscription and invalidates the entitlement cache.
5. Signal's entitlement endpoint returns the enterprise plan on its next read.

The ad-hoc checkout price applies only to that customer's subscription and does
not change the shared Polar product price.

## Troubleshooting

- If the enterprise product cannot be found, run `setup-signal.sh` for the same
  Polar environment and namespace.
- For an HTTP 422 response, verify the customer and product belong to the same
  environment and that `--email-price` is a positive integer.
- If payment succeeds but entitlements remain stale, verify the Polar webhook
  configuration and delivery of `subscription.active`.
