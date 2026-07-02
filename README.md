# Apollo Billing API

Central billing service for the Apollo Deploy platform. Handles subscriptions, entitlements, enforcement, metered usage, checkout, and customer billing profiles — all backed by [Polar](https://polar.sh).

Built with **Kotlin**, **Ktor**, **Netty**, and **PostgreSQL** (via HikariCP). Runs as a single container that joins the platform Docker network.

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│  Internal Apps (Signal, Deploy, …)                                  │
│  ┌────────────┐  ┌────────────┐  ┌────────────┐  ┌──────────────┐  │
│  │ Enforce    │  │ Entitle    │  │ Checkout   │  │ Usage Ingest │  │
│  └─────┬──────┘  └─────┬──────┘  └──────┬─────┘  └──────┬───────┘  │
└────────┼────────────────┼────────────────┼───────────────┼──────────┘
         │  OAuth 2.1 M2M (EdDSA JWT)     │               │
         ▼                ▼                ▼               ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Apollo Billing API  (this service)                                 │
│  /internal/billing/enforce                                          │
│  /internal/billing/entitlements/{app}/{org}                         │
│  /internal/billing/checkout                                         │
│  /internal/billing/usage/ingest                                     │
│  /internal/billing/customer/*                                       │
│  /billing/catalog/{app}  (public)                                   │
│  /webhooks/polar                                                    │
│  /health                                                            │
└─────────────────┬───────────────────────────────────────────────────┘
                  │
                  ▼
         ┌────────────────┐       ┌──────────────┐
         │  Platform DB   │       │  Polar API   │
         │  (PostgreSQL)  │       │              │
         └────────────────┘       └──────────────┘
```

**Billing does NOT own** Postgres, Redis, or nginx. All infrastructure is provided by the platform stack. This service only joins the platform Docker network to reach those services.

---

## Quick Start

### Prerequisites

- Docker 24+ (BuildKit enabled)
- Platform stack running (`postgres`, `redis` healthy)
- Java 21 (local development only)

### 1. Clone and configure

```bash
git clone git@github.com:Apollo-Deploy/apollo-billing-api.git
cd apollo-billing-api
cp .env.example .env
```

Fill in the required values in `.env`:

| Variable | Source |
|----------|--------|
| `PLATFORM_DB_PASSWORD` | Platform installer → `BILLING_APP_DB_PASS` |
| `BILLING_SUPERUSER_PASSWORD` | Platform installer → `BILLING_SUPERUSER_DB_PASS` |
| `REDIS_PASSWORD` | Platform installer → `REDIS_PASSWORD` |
| `POLAR_API_KEY` | Polar dashboard |
| `POLAR_WEBHOOK_SECRET` | Polar webhook settings |
| `PLATFORM_CLIENT_ID` | `bun run oauth:register-clients` on platform |
| `PLATFORM_CLIENT_SECRET` | Same as above |
| `IAM_SERVICE_CLIENT_IDS` | OAuth client IDs of services calling billing |

### 2. First-time setup

```bash
./init.sh
```

This creates `.env` from the example (if missing) and runs database migrations.

### 3. Start the service

```bash
docker compose up -d
```

Verify it's running:

```bash
curl http://localhost:3040/health
```

---

## Development

### Run locally (outside Docker)

```bash
make run          # development mode (hot errors, stack traces)
make dev          # hot-reload with Gradle continuous build
make run-prod     # production mode
```

Requires Java 21. Set `JAVA_HOME` if it's not at the default Homebrew path.

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

### Docker commands

```bash
make up           # build & start
make down         # stop
make ps           # container status
make logs         # tail logs
```

### Database

```bash
make migrate      # apply pending migrations from scripts/migrations/
make db-connect   # open psql shell
```

---

## Environment Variables

See [`.env.example`](.env.example) for the full list with descriptions.

Key groups:

- **Platform DB** — `PLATFORM_DB_HOST`, `PLATFORM_DB_PORT`, `PLATFORM_DB_NAME`, `PLATFORM_DB_USER`, `PLATFORM_DB_PASSWORD`, `PLATFORM_DB_SSLMODE`
- **Signal DB** — `SIGNAL_DB_HOST`, `SIGNAL_DB_PORT`, `SIGNAL_DB_NAME`, `SIGNAL_DB_SSLMODE`
- **Redis** — `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`
- **Polar** — `POLAR_API_KEY`, `POLAR_WEBHOOK_SECRET`, `POLAR_API_BASE_URL`
- **OAuth/IAM** — `PLATFORM_URL`, `PLATFORM_CLIENT_ID`, `PLATFORM_CLIENT_SECRET`, `IAM_JWKS_URL`, `IAM_SERVICE_CLIENT_IDS`
- **SSL** — `PLATFORM_DB_SSLMODE`, `SIGNAL_DB_SSLMODE`, `DB_PROVIDER` (supports `postgres`, `planetscale`)

---

## Adding Billing to a New Project

This section covers how to integrate billing into a new internal Apollo app (e.g. "Deploy", "Launchpad").

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

The **plan catalog** defines plans, entitlements, products, and meter IDs:

```kotlin
data class DeployPlan(
    val slug: String,
    val polarProductId: String,
    val name: String,
    val entitlements: DeployPlanEntitlements,
)

val deployPlans = listOf(
    DeployPlan(
        slug = "deploy-pro",
        polarProductId = "<polar-product-id>",
        name = "Pro",
        entitlements = DeployPlanEntitlements(maxProjects = 20, advancedRollouts = true),
    ),
)
```

The **billing config** wires plan resolution, usage resolution, and product registration:

```kotlin
class DeployBillingConfig(
    private val db: DatabasePool,
    private val subscriptionRepo: SubscriptionRepo,
    private val polarClient: PolarClient,
) {
    companion object { const val APP_SLUG = "deploy" }

    fun buildRegistration(): BillingAppRegistration = BillingAppRegistration(
        slug = APP_SLUG,
        enforcer = BillingEnforcer(BillingConfig(
            appSlug = APP_SLUG,
            resolvePlan = { orgId -> resolvePlan(orgId) },
            resolveUsage = { orgId -> resolveUsage(orgId) },
        )),
        products = billingProducts(),
    )
    // ... plan resolution, usage resolution, product list
}
```

Then register it in `AppAssembly.kt`:

```kotlin
val deployApp = DeployBillingConfig(db, subscriptionRepo, polarClient).buildRegistration()
val appRegistry = AppRegistry(listOf(signalApp, deployApp))
```

Seed the database:

```sql
INSERT INTO platform_apps (slug, name) VALUES ('deploy', 'Apollo Deploy') ON CONFLICT (slug) DO NOTHING;
```

### Step 2: Set up Polar products

Use the [Billing-Plan-Setup](https://github.com/Apollo-Deploy/Billing-Plan-Setup) repo to configure Polar products, meters, and webhooks for the new app.

For each new app you'll need to define:

- Subscription products for each plan tier
- Subscription products for recurring add-ons
- One-time products for credit packs / permanent purchases
- Meters for metered resources
- Webhooks pointing to `https://billing.apollodeploy.com/webhooks/polar`

See the Billing-Plan-Setup README for detailed instructions on catalog creation and environment management (sandbox vs production).

### Step 3: Register the calling service for OAuth

1. Register an OAuth client on the platform:
   ```bash
   cd apps/platform && bun run oauth:register-clients
   ```

2. Add the returned `client_id` to billing's `IAM_SERVICE_CLIENT_IDS`

3. Configure the calling app's environment:
   ```bash
   PLATFORM_CLIENT_ID=<client_id>
   PLATFORM_CLIENT_SECRET=<client_secret>
   PLATFORM_BASE_URL=http://platform:3000
   PLATFORM_AUDIENCE_URL=https://api.platform.apollodeploy.local
   BILLING_BASE_URL=https://billing.apollodeploy.com
   ```

### Step 4: Install the SDK in your app

**Kotlin/JVM:**

```kotlin
// build.gradle.kts
repositories {
    maven {
        name = "apolloCodeArtifact"
        url = uri("https://apollo-deploy-753668406194.d.codeartifact.us-east-1.amazonaws.com/maven/apollo-billing-sdk/")
        credentials {
            username = "aws"
            password = providers.environmentVariable("CODEARTIFACT_AUTH_TOKEN").get()
        }
    }
}

dependencies {
    implementation("com.apollodeploy:billing-sdk:1.0.7")
}
```

**TypeScript:**

```bash
# From this repo:
make sdk-ts
cd sdk/typescript && npm pack
# In your app:
npm install /path/to/apollo-deploy-billing-sdk-1.0.7.tgz
```

### Step 5: Use the SDK in your backend

Create a billing client with automatic token refresh:

```kotlin
val m2mClient = OAuthM2mClient(
    httpClient = httpClient,
    platformUrl = System.getenv("PLATFORM_BASE_URL"),
    clientId = System.getenv("PLATFORM_CLIENT_ID"),
    clientSecret = System.getenv("PLATFORM_CLIENT_SECRET"),
    audienceUrl = System.getenv("PLATFORM_AUDIENCE_URL"),
)

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
- [ ] `<App>BillingConfig.kt` created
- [ ] App config values added to `AppConfig.kt` and `application.conf`
- [ ] App registered in `AppAssembly.kt`
- [ ] `platform_apps` row seeded
- [ ] OAuth client registered, `client_id` added to `IAM_SERVICE_CLIENT_IDS`
- [ ] Polar products, meters, and webhooks configured via [Billing-Plan-Setup](https://github.com/Apollo-Deploy/Billing-Plan-Setup)
- [ ] SDK installed in app backend
- [ ] Enforcement calls added to write paths
- [ ] Usage reporting added after successful operations
- [ ] Tests passing (`./gradlew test`)

---

## SDK Generation & Publishing

```bash
make sdk              # generate TypeScript + Java SDKs
make sdk-ts           # TypeScript only
make sdk-java         # Java only
make sdk-publish SDK_VERSION=1.0.8   # publish both
make sdk-codeartifact # refresh CodeArtifact Maven token
make sdk-manifest     # export Tesseract manifest only
```

See [docs/sdk-generation.md](docs/sdk-generation.md) and [docs/sdk-readme.md](docs/sdk-readme.md) for detailed publishing instructions.

---

## API Endpoints

| Endpoint | Auth | Description |
|----------|------|-------------|
| `GET /health` | None | Health check |
| `GET /billing/catalog/{app}` | None (public) | Product catalog for plan selectors |
| `POST /internal/billing/enforce` | Service JWT | Enforce quota/feature/meter check |
| `GET /internal/billing/entitlements/{app}/{org}` | Service JWT | Entitlement snapshot |
| `POST /internal/billing/checkout` | Service JWT | Create checkout session |
| `POST /internal/billing/usage/ingest` | Service JWT | Report metered usage |
| `GET/PUT /internal/billing/customer/*` | Service JWT | Customer billing profile |
| `POST /webhooks/polar` | Polar signature | Polar webhook receiver |

---

## Project Structure

```
src/main/kotlin/com/apollodeploy/billing/
├── BillingApplication.kt          # Entry point, Ktor configuration
├── bootstrap/AppAssembly.kt       # Dependency wiring, app registration
├── core/                          # Shared domain (enforcer, config, registry)
├── feature/
│   ├── catalog/                   # Public product catalog
│   ├── checkout/                  # Checkout session creation
│   ├── customer/                  # Customer billing profile
│   ├── enforce/                   # Billing enforcement
│   ├── entitlements/              # Entitlement resolution
│   ├── health/                    # Health endpoint
│   ├── signal/                    # Signal app (reference implementation)
│   ├── usage/                     # Usage ingestion
│   └── webhook/                   # Polar webhook handling
├── infrastructure/
│   ├── config/AppConfig.kt        # Environment configuration
│   ├── iam/                       # OAuth M2M verification
│   ├── persistence/               # Database pool management
│   └── validation/                # Input validation
└── tesseract/                     # SDK generation annotations
```

---

## Further Documentation

- [Internal Backend Integration](docs/internal-backend-integration.md) — full integration guide for consuming apps
- [Adding Billing Apps](docs/adding-billing-apps.md) — step-by-step for registering a new app
- [SDK README](docs/sdk-readme.md) — SDK install, usage, and error handling
- [SDK Generation](docs/sdk-generation.md) — how SDKs are generated and published
- [Enterprise Provisioning](docs/enterprise-plan-provisioning.md) — attaching custom-priced enterprise plans
- [Billing Reader Role](docs/billing-reader-role.md) — read-only PostgreSQL role setup
