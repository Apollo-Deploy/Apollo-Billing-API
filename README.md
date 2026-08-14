# Apollo Billing API

Central billing service for the Apollo Deploy platform. Handles subscriptions, entitlements, enforcement, metered usage, checkout, invoices, and customer billing profiles — all backed by [Polar](https://polar.sh).

Built with **Kotlin**, **Ktor**, **Netty**, and **PostgreSQL** (via HikariCP). Runs as a single container that joins the platform Docker network.

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│  Internal Apps (Signal, Deploy, …)                                  │
│  Enforce · Entitlements · Checkout · Usage · Customer · Subs/Invoices│
└───────────────────────────────┬─────────────────────────────────────┘
                                │  OAuth 2.1 M2M (EdDSA JWT)
                                ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Apollo Billing API  (this service)                                 │
│  /internal/billing/enforce                                          │
│  /internal/billing/entitlements/{app}/{org}                         │
│  /internal/billing/checkout                                         │
│  /internal/billing/usage/ingest                                     │
│  /internal/billing/customer/*                                       │
│  /internal/billing/subscriptions                                    │
│  /internal/billing/invoices                                         │
│  /billing/catalog/{app}  (public)                                   │
│  /webhooks/polar                                                    │
│  /health · /docs                                                    │
└───────────────┬───────────────────────────────┬─────────────────────┘
                │                               │
                ▼                               ▼
       ┌────────────────┐              ┌──────────────┐
       │ Platform DB +  │              │  Polar API   │
       │ Redis (shared) │              │              │
       └────────────────┘              └──────────────┘
```

**Billing does NOT own** Postgres, Redis, or nginx. Infrastructure comes from the platform stack. This service joins the shared Docker network to reach those services.

Configuration is loaded through nested typed objects in `AppConfig` (`polar`, `platform`, `iam`, `redis`, `platformDatabase`, `signalDatabase`, …) from `application.conf` and environment overrides.

---

## Quick Start

### Prerequisites

- Docker 24+ (BuildKit enabled)
- Terraform >= 1.10
- Java 21 (local development outside Docker)

### Start the full local stack

Billing is part of the shared Terraform-managed local environment under `APIs/infra`.

From this repo (`apollo-billing-api`):

```bash
export NPM_TOKEN=npm_...             # required for platform build
export CODEARTIFACT_AUTH_TOKEN=...   # required if enable_signal=true

cd ../infra/terraform/local
terraform init        # first time only
terraform apply -auto-approve
```

Or from this repo with Make:

```bash
make tf-up
```

This starts infra (Postgres, PgBouncer, Redis), runs migrations, registers OAuth clients, and starts Platform, Billing, and Signal.

Local Terraform defaults to **dev mode**: source is bind-mounted and the container builds `shadowJar` on start (no full image rebuild). After code changes:

```bash
docker restart apollo-billing
# or force a clean jar rebuild inside the container:
docker exec apollo-billing ./gradlew shadowJar --no-daemon -x test --rerun-tasks
docker restart apollo-billing
```

Verify billing:

```bash
docker exec apollo-billing wget -qO- http://127.0.0.1:3040/health
# or via the local HTTPS edge (mkcert):
# curl https://api.billing.apollodeploy.local/health
```

### Configuration

Optional credentials (Polar, AWS, etc.) go in `terraform.tfvars`:

```bash
cd ../infra/terraform/local
cp terraform.tfvars.example terraform.tfvars
# edit terraform.tfvars, then re-apply
terraform apply -auto-approve
```

See `terraform.tfvars.example` for available options. For a standalone JVM process, copy [`.env.example`](.env.example) to `.env`.

---

## Development

### Run locally (outside Docker)

```bash
make run          # development mode (hot errors, stack traces)
make dev          # hot-reload with Gradle continuous build
make run-prod     # production mode
```

Requires Java 21. Set `JAVA_HOME` if it is not at the default Homebrew path.

### Build

```bash
make build        # compile
make shadow       # fat JAR
make clean        # remove build artifacts
```

### Test

```bash
make test         # full test suite
make test-unit TEST=com.apollodeploy.billing.feature.enforce.*   # specific tests
make test-watch   # re-run on changes
```

### Code quality

```bash
make lint         # ktlint check
make lint-fix     # auto-fix
make check        # lint + test
```

### Terraform / container commands

```bash
make tf-up      # apply (build + start) local stack
make tf-down    # destroy local stack
make tf-logs    # tail billing logs
```

Or directly:

```bash
docker logs -f apollo-billing
docker restart apollo-billing
```

### Database

Migrations live in `scripts/migrations/`:

| File | Purpose |
|------|---------|
| `01_billing_core.psql` | Core billing tables |
| `02_billing_subscription_renewal.psql` | Renewal / cancel-at-period-end columns |
| `03_billing_subscription_display.psql` | Display fields for client dashboards |

```bash
make migrate      # apply pending migrations
make db-connect   # open psql shell
```

### Polar catalog helpers

Scripts under `scripts/polar/` set up Signal products in Polar (sandbox or production):

```bash
# See scripts/polar/setup-signal.sh and signal-catalog.sh
# Enterprise attach helper:
# scripts/polar/attach-enterprise-plan.sh
```

Also see [Enterprise Provisioning](docs/enterprise-plan-provisioning.md).

---

## Environment Variables

See [`.env.example`](.env.example) for the full list with descriptions.

Key groups (mapped into nested `AppConfig`):

- **Runtime** — `APP_ENV`, `BILLING_PORT`, `METRICS_ENABLED`, `CORS_ORIGINS`
- **Platform DB** — `PLATFORM_DB_HOST`, `PLATFORM_DB_PORT`, `PLATFORM_DB_NAME`, `PLATFORM_DB_USER`, `PLATFORM_DB_PASSWORD`, `PLATFORM_DB_SSLMODE`
- **Reader / Signal DB** — `BILLING_SUPERUSER_PASSWORD`, `SIGNAL_DB_HOST`, `SIGNAL_DB_PORT`, `SIGNAL_DB_NAME`, `SIGNAL_DB_SSLMODE`
- **Redis** — `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`, `REDIS_DB`
- **Polar** — `POLAR_API_KEY`, `POLAR_WEBHOOK_SECRET`, `POLAR_API_BASE_URL`
- **OAuth/IAM** — `PLATFORM_URL`, `PLATFORM_AUDIENCE_URL`, `PLATFORM_CLIENT_ID`, `PLATFORM_CLIENT_SECRET`, `AUTH_JWKS_URL`, `AUTH_OAUTH_ISSUER_URL`, `AUTH_OAUTH_VALID_AUDIENCES`, `OAUTH_SERVICE_CLIENT_IDS`
- **SSL** — `DB_PROVIDER` (`postgres` / `planetscale`) influences effective SSL mode

---

## Adding Billing to a New Project

This section covers integrating billing into a new internal Apollo app (e.g. "Deploy", "Launchpad").

### Overview

Each internal app integrates with billing through:

1. **A plan catalog** registered inside this billing service
2. **The generated SDK** consumed from the app's backend
3. **Service-to-service auth** via OAuth 2.1 `client_credentials`

The app never calls Polar directly and never exposes billing secrets to the frontend.

### Step 1: Register the app in Apollo Billing

Create two files inside this repo:

```
src/main/kotlin/com/apollodeploy/billing/feature/<app>/domain/<App>PlanCatalog.kt
src/main/kotlin/com/apollodeploy/billing/feature/<app>/application/<App>BillingConfig.kt
```

The **plan catalog** defines plans, entitlements, products, and meter IDs.

The **billing config** wires a single `resolvePlanAndUsage` callback (one round-trip for plan + usage) and product registration:

```kotlin
class DeployBillingConfig(
    private val db: DatabasePool,
    private val subscriptionRepo: SubscriptionRepo,
    private val polarClient: PolarClient,
) {
    companion object { const val APP_SLUG = "deploy" }

    fun buildRegistration(): BillingAppRegistration =
        BillingAppRegistration(
            slug = APP_SLUG,
            enforcer = BillingEnforcer(
                BillingConfig(
                    appSlug = APP_SLUG,
                    resolvePlanAndUsage = { orgId -> resolvePlanAndUsage(orgId) },
                ),
            ),
            products = billingProducts(),
            catalog = billingCatalog(),
        )

    private suspend fun resolvePlanAndUsage(orgId: String): PlanAndUsageResolution {
        // Resolve plan + usage for orgId, then return PlanAndUsageResolution(...)
        TODO()
    }
}
```

Register it in `AppAssembly.kt` next to Signal:

```kotlin
val deployApp = DeployBillingConfig(...).buildRegistration()
val appRegistry = AppRegistry(listOf(signalApp, deployApp))
```

Seed the database:

```sql
INSERT INTO platform_apps (slug, name) VALUES ('deploy', 'Apollo Deploy')
ON CONFLICT (slug) DO NOTHING;
```

### Step 2: Set up Polar products

Use the [Billing-Plan-Setup](https://github.com/Apollo-Deploy/Billing-Plan-Setup) repo (and/or `scripts/polar/` for Signal) to configure Polar products, meters, and webhooks.

For each new app you will typically need:

- Subscription products for each plan tier
- Subscription products for recurring add-ons
- One-time products for credit packs / permanent purchases
- Meters for metered resources
- Webhooks pointing to `https://billing.apollodeploy.com/webhooks/polar`

### Step 3: Register the calling service for OAuth

1. Register an OAuth client on the platform
2. Add the returned `client_id` to billing's `OAUTH_SERVICE_CLIENT_IDS`
3. Configure the calling app:

```bash
PLATFORM_CLIENT_ID=<client_id>
PLATFORM_CLIENT_SECRET=<client_secret>
PLATFORM_URL=http://platform:3000
PLATFORM_AUDIENCE_URL=https://api.platform.apollodeploy.com
BILLING_BASE_URL=https://billing.apollodeploy.com
```

### Step 4: Install the SDK in your app

**Kotlin/JVM:**

```kotlin
// build.gradle.kts
repositories {
    mavenCentral()
}

dependencies {
    implementation("com.apollodeploy:billing-sdk:<version>")
}
```

**TypeScript:**

```bash
# From this repo:
make sdk
cd sdk/typescript && npm pack
# In your app:
npm install /path/to/apollo-deploy-billing-sdk-1.6.0.tgz
```

### Step 5: Use the SDK in your backend

Create a billing client with automatic token refresh:

```kotlin
val platformUrl = System.getenv("PLATFORM_URL")
val m2mClient = MachineOAuthClient {
    httpClient(httpClient)
    tokenEndpoint("${platformUrl.trimEnd('/')}/auth/oauth2/token")
    clientId(System.getenv("PLATFORM_CLIENT_ID"))
    clientSecret(System.getenv("PLATFORM_CLIENT_SECRET"))
    audience(System.getenv("PLATFORM_AUDIENCE_URL").ifBlank { platformUrl })
    clientSecretPost()
    if (platformUrl.startsWith("http://")) allowInsecureHttp()
}

val billingProvider = ApolloBillingClientProvider(
    baseUrl = System.getenv("BILLING_BASE_URL"),
    m2mClient = m2mClient,
)
```

#### Enforce before work

```kotlin
val decision = billingProvider.get().billingEnforcement.enforceBillingCheck(
    EnforceRequest(
        orgId = orgId,
        appSlug = "deploy",
        check = buildJsonObject {
            put("type", "quota")
            put("resource", "project")
            put("limitKey", "maxProjects")
        },
    ),
)

if (!decision.allowed) throw BillingBlockedError("Quota exceeded")
```

#### Report usage after work

```kotlin
billingProvider.get().billingUsage.ingestBillingUsage(
    UsageIngestRequest(
        orgId = orgId,
        eventKey = "deploy.build_minutes",
        quantity = actualMinutes,
        metadata = buildJsonObject { put("buildId", buildId) },
    ),
)
```

#### Get entitlements for dashboards

```kotlin
val entitlements = billingProvider.get().billingEntitlements.getBillingEntitlements(
    appSlug = "deploy",
    orgId = orgId,
)
```

### Step 6: Follow the integration rules

- **Enforce** billing in the backend path that does the work — not just in the UI
- **Report usage** only after the operation commits successfully
- **Never** call Polar directly from app code
- **Never** expose service JWTs or billing secrets to the browser
- **Never** hardcode Polar product IDs — use the catalog and SDK
- Use `fail-closed` for paid/metered work, `fail-open` for reads and dashboards

### Checklist for new apps

- [ ] `<App>PlanCatalog.kt` created
- [ ] `<App>BillingConfig.kt` created with `resolvePlanAndUsage`
- [ ] App-specific config added under nested `AppConfig` / `application.conf` if needed
- [ ] App registered in `AppAssembly.kt`
- [ ] `platform_apps` row seeded
- [ ] OAuth client registered; `client_id` added to `OAUTH_SERVICE_CLIENT_IDS`
- [ ] Polar products, meters, and webhooks configured
- [ ] SDK installed in app backend
- [ ] Enforcement calls added to write paths
- [ ] Usage reporting added after successful operations
- [ ] Tests passing (`./gradlew test`)

---

## SDK Generation & Publishing

```bash
make sdk                              # export OpenAPI 3.1, then generate all SDKs locally
make sdk-publish-kotlin SDK_VERSION=2.2.0  # publish the Kotlin SDK to Maven Central
make sdk-publish SDK_VERSION=2.3.0         # publish npm + Maven Central when both versions are new
```

See [docs/sdk-generation.md](docs/sdk-generation.md) and [docs/sdk-readme.md](docs/sdk-readme.md) for publishing details.

---

## API Endpoints

| Endpoint | Auth | Description |
|----------|------|-------------|
| `GET /health` | None | Health check |
| `GET /docs` · `GET /docs/openapi.json` | None | Scalar docs / OpenAPI spec |
| `GET /billing/catalog/{app}` | None (public) | Product catalog for plan selectors |
| `POST /internal/billing/enforce` | Service JWT | Enforce quota / feature / meter check |
| `GET /internal/billing/entitlements/{app}/{org}` | Service JWT | Entitlement snapshot |
| `POST /internal/billing/checkout` | Service JWT | Create checkout session |
| `POST /internal/billing/usage/ingest` | Service JWT | Report metered usage |
| `POST /internal/billing/customer/provision` | Service JWT | Provision Polar customer for an org |
| `PATCH /internal/billing/customer/billing-info` | Service JWT | Update billing profile |
| `GET /internal/billing/customer/payment-methods` | Service JWT | List payment methods |
| `DELETE /internal/billing/customer/payment-methods/{id}` | Service JWT | Delete payment method |
| `PATCH /internal/billing/customer/payment-methods/{id}/default` | Service JWT | Set default payment method |
| `POST /internal/billing/customer/portal` | Service JWT | Open customer portal |
| `POST /internal/billing/customer/session` | Service JWT | Create customer session |
| `GET /internal/billing/subscriptions` | Service JWT | Active subscriptions grouped by app |
| `POST /internal/billing/subscriptions/{id}/cancel` | Service JWT | Cancel at period end |
| `GET /internal/billing/invoices` | Service JWT | Paginated invoices |
| `GET /internal/billing/invoices/{id}` | Service JWT | Invoice detail |
| `GET /internal/billing/invoices/{id}/meter-usage` | Service JWT | Meter usage for invoice period |
| `POST /internal/billing/invoices/{id}/invoice` | Service JWT | Generate Polar PDF invoice URL |
| `POST /webhooks/polar` | Polar signature | Polar webhook receiver |

---

## Project Structure

```
src/main/kotlin/com/apollodeploy/billing/
├── BillingApplication.kt          # Entry point, Ktor plugins, routes
├── bootstrap/AppAssembly.kt       # Composition root
├── core/                          # Enforcer, helpers, registry, domain types
├── feature/
│   ├── catalog/                   # Public product catalog
│   ├── checkout/                  # Checkout session creation
│   ├── customer/                  # Customer billing profile / portal
│   ├── docs/                      # OpenAPI + Scalar
│   ├── enforce/                   # Billing enforcement
│   ├── entitlements/              # Entitlement resolution
│   ├── health/                    # Health endpoint
│   ├── invoices/                  # Invoice list, detail, PDF generation
│   ├── signal/                    # Signal app (reference implementation)
│   ├── subscriptions/             # Active subscriptions + cancel
│   ├── usage/                     # Usage ingestion
│   └── webhook/                   # Polar webhook handling
└── infrastructure/
    ├── config/AppConfig.kt        # Nested typed configuration
    ├── iam/                       # OAuth M2M verification
    ├── persistence/               # Hikari pools + subscription repo
    ├── polar/                     # PolarClient + webhook handler
    │   └── model/                 # Polar DTOs / call results
    ├── redis/                     # RedisPool + Polar state cache
    ├── validation/                # Redirect URL validation
    └── webhook/                   # Webhook deduplication
```

---

## Further Documentation

- [Internal Backend Integration](docs/internal-backend-integration.md) — full integration guide for consuming apps
- [Adding Billing Apps](docs/adding-billing-apps.md) — step-by-step for registering a new app
- [SDK README](docs/sdk-readme.md) — SDK install, usage, and error handling
- [SDK Generation](docs/sdk-generation.md) — how SDKs are generated and published
- [Enterprise Provisioning](docs/enterprise-plan-provisioning.md) — attaching custom-priced enterprise plans
- [Billing Reader Role](docs/billing-reader-role.md) — read-only PostgreSQL role setup
