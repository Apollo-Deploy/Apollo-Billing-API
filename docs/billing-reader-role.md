# Billing Reader Role

A single cluster-wide, read-only PostgreSQL login role used by the billing
service's read paths. Despite the "super root" framing, it is intentionally
**least-privilege**: it can connect to both billing databases but is granted
`SELECT` on only the specific columns the billing reads touch.

## Why one role across two databases

PostgreSQL roles are cluster-global, so a single login role can be granted
scoped privileges in multiple databases. The billing read paths span two:

| Database      | Tables                                              | Source in code |
|---------------|-----------------------------------------------------|----------------|
| platform DB   | `platform_apps`, `billing_customers`, `billing_subscriptions`, `apikey` | `SubscriptionRepo` read queries + `SignalBillingConfig.SQL_USAGE` |
| signal DB     | `projects`, `domains`, `webhook_endpoints`, `organization_usage_daily` | `SignalBillingConfig.SQL_SIGNAL_USAGE` |

## What it can and cannot do

- **Can:** `LOGIN`, `CONNECT` to both databases, `SELECT` only the named
  columns each read path uses.
- **Cannot:** `INSERT` / `UPDATE` / `DELETE`, read non-granted columns
  (e.g. `billing_customers.email`), create databases/roles, replicate, or act
  as superuser.

The write path (Polar webhooks via `SubscriptionRepo`) keeps using the existing
`DB_USER` (`billing_app`). This role is for read/usage resolution only.

## Granted columns

Platform DB:

- `platform_apps (id, slug)`
- `billing_customers (app_id, customer_id, external_ref)`
- `billing_subscriptions (app_id, customer_id, polar_product_id, status, quantity, created_at)`
- `apikey ("referenceId", "configId", enabled)`

Signal DB:

- `projects (organization_id, status)`
- `domains (organization_id, status)`
- `webhook_endpoints (organization_id, deleted_at)`
- `organization_usage_daily (organization_id, usage_date, email_count)`

If a billing read path starts touching a new column, add it to the matching
`GRANT SELECT (...)` in `scripts/provision/billing-reader-role.sql`.

## Usage

Via the helper script (reads `.env` for DB host/port/name defaults):

```bash
# Local Docker Postgres (infra profile)
USE_DOCKER=1 ADMIN_USER=postgres PGPASSWORD=<admin-pw> \
  PLATFORM_DB=apollo_deploy_platform SIGNAL_DB=apollo_deploy_signal \
  make provision-reader

# Remote Postgres
PGHOST=db.internal PGPORT=5432 ADMIN_USER=postgres PGPASSWORD=<admin-pw> \
  PLATFORM_DB=apollo_deploy_platform SIGNAL_DB=apollo_deploy_signal \
  BILLING_READER_PASSWORD=<strong-pw> \
  scripts/provision/provision-billing-reader.sh
```

If `BILLING_READER_PASSWORD` is omitted, a strong one is generated and printed
once at the end. Store it securely.

Or run the SQL directly:

```bash
psql -h <host> -U <admin> -d postgres \
     -v billing_role=apollo_billing_reader \
     -v billing_password='<strong-pw>' \
     -v signal_db=apollo_deploy_signal \
     -v platform_db=apollo_deploy_platform \
     -f scripts/provision/billing-reader-role.sql
```

The admin connection must be a superuser (or owner of the listed tables) and be
able to `\connect` to both databases.

## Notes

- The script is **idempotent**: re-running creates the role only if missing,
  re-asserts its attributes, refreshes the password, and re-applies grants.
- Assumes both databases keep these tables in the `public` schema. Adjust the
  schema-qualified names in the SQL if your deployment differs.
- The role only gains access to tables that exist at provisioning time. Run it
  again after schema migrations introduce new billing-read tables.
