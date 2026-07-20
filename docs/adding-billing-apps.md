# Adding Billing For New Internal Apps

This service is the central billing layer for Apollo Deploy products. A product
such as Signal, Deploy, or a future internal app is registered once with:

- an app slug used by internal callers,
- an entitlement resolver,
- a usage resolver,
- every Polar product ID that can appear in checkout or webhook payloads.

The shared routes, Polar client, webhook handler, and subscription repository
then handle the common billing work for both subscriptions and one-time
purchases.

## Current Architecture

Core shared types live in `src/main/kotlin/com/apollodeploy/billing/core`:

- `BillingConfig` wires an app slug to `resolvePlan` and `resolveUsage`.
- `BillingEnforcer` enforces quotas, features, and meter balances.
- `BillingAppRegistration` describes one billed app and its products.
- `BillingProduct` maps an app product slug to a Polar product ID.
- `BillingProductKind` distinguishes `SUBSCRIPTION` and `ONE_TIME_PURCHASE`.
- `BillingCatalogProductKind` is the richer app-catalog taxonomy:
  `SUBSCRIPTION`, `SUBSCRIPTION_ADD_ON`, `USAGE_BASED_SUBSCRIPTION`,
  `ONE_TIME_PURCHASE`, `CREDIT_PACK`, and `PERMANENT_PURCHASE`.
- `AppRegistry` maps app slugs and Polar product IDs back to registered apps.

Common infrastructure lives in:

- `SubscriptionRepo` for shared subscription/customer persistence and lookups.
- `PolarClient` for Polar customer state, checkout sessions, and usage events.
- `PolarWebhookHandler` for customer, subscription, and order webhooks.
- `CheckoutRoutes`, `EnforceRoutes`, `EntitlementsRoutes`, and
  `UsageIngestRoutes` for internal client APIs.

Signal is the reference implementation:

- `feature/signal/domain/SignalPlanCatalog.kt`
- `feature/signal/application/SignalBillingConfig.kt`
- `AppAssembly.kt`

## Polar Model

Use Polar as the billing source of truth:

- Subscription products represent recurring plans.
- Subscription products represent recurring add-ons as well as base plans.
- One-time products represent credit top-ups or permanent purchases.
- `external_customer_id` must be the Apollo org ID. Checkout creation does this
  automatically through `PolarClient.createCheckoutSession`.
- Metered usage is sent to Polar through `/v1/events/ingest` with
  `external_customer_id`.
- Entitlement and meter state is read from Polar Customer State:
  `/v1/customers/external/{external_id}/state`.
- Webhooks should include at least:
  `customer.state_changed`, `subscription.created`, `subscription.updated`,
  `subscription.active`, `subscription.canceled`, `subscription.revoked`,
  `order.created`, and `order.paid`.

For one-time credit/top-up products, prefer Polar Benefits/Meters to grant the
credits. This API should not track credit balances locally. It should read the
resulting `active_meters[].balance` from customer state.

## Add A New App

### 1. Create the plan catalog

Create a new domain package:

```text
src/main/kotlin/com/apollodeploy/billing/feature/<app>/domain/<App>PlanCatalog.kt
```

Define app-specific plans and entitlements. Keep app-specific names in the
catalog, then convert them into shared `PlanFeatureConfig` inside the app
config.

Example shape:

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
        polarProductId = "polar-product-id",
        name = "Pro",
        entitlements = DeployPlanEntitlements(
            maxProjects = 20,
            maxBuildMinutes = 5_000,
            advancedRollouts = true,
        ),
    ),
)

fun deployFindPlanByProductId(polarProductId: String): DeployPlan? =
    deployPlans.find { it.polarProductId.isNotBlank() && it.polarProductId == polarProductId }

data class DeployCatalogProduct(
    val slug: String,
    val polarProductId: String,
    val kind: BillingCatalogProductKind,
)

const val DEPLOY_CREDIT_METER_ID = "polar-meter-id"

val deployCatalogProducts = listOf(
    DeployCatalogProduct(
        slug = "deploy-dedicated-runner-addon",
        polarProductId = "monthly-addon-product-id",
        kind = BillingCatalogProductKind.SUBSCRIPTION_ADD_ON,
    ),
    DeployCatalogProduct(
        slug = "deploy-credit-topup-100",
        polarProductId = "one-time-topup-product-id",
        kind = BillingCatalogProductKind.CREDIT_PACK,
    ),
)
```

Use stable app/product slugs because internal clients will call checkout and
enforcement with those values. Keep Polar product IDs, recurring add-ons,
one-time products, and app-specific meter IDs in the app catalog so there is a
single source of truth for the product model.

### 2. Create the app billing config

Create:

```text
src/main/kotlin/com/apollodeploy/billing/feature/<app>/application/<App>BillingConfig.kt
```

The class should:

- define `APP_SLUG`,
- build a `BillingAppRegistration`,
- register all subscription and one-time Polar products,
- resolve the current plan from `SubscriptionRepo.findLatestActiveProductId`,
- resolve usage from the app's tables or service-owned sources,
- read Polar meter balances from `PolarClient.getCustomerState` when needed.

Minimal skeleton:

```kotlin
class DeployBillingConfig(
    private val db: DatabasePool,
    private val subscriptionRepo: SubscriptionRepo,
    private val polarClient: PolarClient,
) {
    companion object {
        const val APP_SLUG = "deploy"
    }

    private val basePlanProductIds = deployPlans
        .map { it.polarProductId }
        .filter { it.isNotBlank() }

    fun buildRegistration(): BillingAppRegistration = BillingAppRegistration(
        slug = APP_SLUG,
        enforcer = BillingEnforcer(
            BillingConfig(
                appSlug = APP_SLUG,
                resolvePlan = { orgId -> resolvePlan(orgId) },
                resolveUsage = { orgId -> resolveUsage(orgId) },
                cacheTtlMs = 5_000,
            )
        ),
        products = billingProducts(),
    )

    private fun resolvePlan(orgId: String): PlanResolution {
        val plan = subscriptionRepo
            .findLatestActiveProductId(APP_SLUG, orgId, basePlanProductIds)
            ?.let { deployFindPlanByProductId(it) }
            ?: throw SubscriptionNotFoundError(orgId, APP_SLUG)

        return PlanResolution(
            planId = plan.slug,
            config = plan.entitlements.toPlanFeatureConfig(),
        )
    }

    private suspend fun resolveUsage(orgId: String): Map<String, Int> {
        val dbUsage = db.withConnection { conn ->
            // Query app-owned usage here.
            emptyMap<String, Int>()
        }

        val meterBalance = polarClient.getCustomerState(orgId)
            ?.activeMeters
            ?.find { it.meterId == DEPLOY_CREDIT_METER_ID }
            ?.balance

        return if (meterBalance != null) {
            dbUsage + mapOf("deployCreditBalance" to meterBalance)
        } else {
            dbUsage
        }
    }

    private fun billingProducts(): List<BillingProduct> = buildList {
        deployPlans
            .filter { it.polarProductId.isNotBlank() }
            .forEach { plan ->
                add(
                    BillingProduct(
                        appSlug = APP_SLUG,
                        slug = plan.slug,
                        polarProductId = plan.polarProductId,
                        kind = BillingProductKind.SUBSCRIPTION,
                    )
                )
            }

        deployCatalogProducts
            .filter { it.polarProductId.isNotBlank() }
            .forEach { product ->
                add(
                    BillingProduct(
                        appSlug = APP_SLUG,
                        slug = product.slug,
                        polarProductId = product.polarProductId,
                        kind = product.kind.toBillingProductKind(),
                    )
                )
            }
    }
}
```

Convert app entitlements to shared enforcement config:

```kotlin
fun DeployPlanEntitlements.toPlanFeatureConfig() = PlanFeatureConfig(
    limits = mapOf(
        "maxProjects" to maxProjects,
        "maxBuildMinutes" to maxBuildMinutes,
    ),
    features = mapOf(
        "advancedRollouts" to advancedRollouts,
    ),
)
```

Use the same keys in consumer calls to `/internal/billing/enforce`.

### 3. Add configuration

Add app config in `application.conf` and expose environment overrides:

```hocon
apollo-billing {
    deploy {
        credit-meter-id = ""
        credit-meter-id = ${?DEPLOY_CREDIT_METER_ID}

        one-time-product-ids = ""
        one-time-product-ids = ${?DEPLOY_ONE_TIME_PRODUCT_IDS}
    }
}
```

Parse those values in `AppConfig.kt`, following the Signal example:

```kotlin
val deployCreditMeterId: String =
    config.getString("apollo-billing.deploy.credit-meter-id")

val deployOneTimeProductIds: List<String> =
    config.getString("apollo-billing.deploy.one-time-product-ids")
        .split(",")
        .map { it.trim() }
        .filter { it.isNotBlank() }
```

Also update `application.local.conf.example`.

### 4. Register the app

Wire the new app in `AppAssembly.kt`:

```kotlin
val signalApp = SignalBillingConfig(db, subscriptionRepo, polarClient).buildRegistration()
val deployApp = DeployBillingConfig(db, subscriptionRepo, polarClient).buildRegistration()

val appRegistry = AppRegistry(
    listOf(
        signalApp,
        deployApp,
    )
)
```

That is the only central registration point. Do not add app-specific product
matching inside `PolarWebhookHandler`.

### 5. Seed the platform app

The DB must have a `platform_apps` row for the app slug before webhooks can
persist customers/subscriptions:

```sql
INSERT INTO platform_apps (slug, name)
VALUES ('deploy', 'Apollo Deploy')
ON CONFLICT (slug) DO NOTHING;
```

Confirm the actual schema before running this in production. The important
invariant is that `platform_apps.slug` matches `APP_SLUG`.

## Subscription Products

For a recurring plan:

1. Create the subscription product in Polar.
2. Put the Polar product ID in the app plan catalog.
3. Register it as `BillingProductKind.SUBSCRIPTION`.
4. Make sure webhooks are enabled.
5. On subscription webhooks, `PolarWebhookHandler` will:
   - resolve the product through `AppRegistry.productForPolarProductId`,
   - upsert the customer,
   - upsert the subscription,
   - invalidate that app/org entitlement cache.

Plan resolution should use:

```kotlin
subscriptionRepo.findLatestActiveProductId(APP_SLUG, orgId, basePlanProductIds)
```

Active statuses currently include:

```text
active, trialing, past_due
```

If a product is an add-on that renews monthly, register it as
`SUBSCRIPTION` and inspect quantity with:

```kotlin
subscriptionRepo.activeSubscriptionQuantity(APP_SLUG, orgId, addOnPolarProductId)
```

## One-Time Products

For a one-time purchase:

1. Create a one-time product in Polar.
2. Add the product ID to app config or a catalog.
3. Register it as `BillingProductKind.ONE_TIME_PURCHASE`.
4. Prefer Polar Benefits/Meters for the grant.
5. Read the resulting entitlement or meter balance from customer state.

The webhook handler does not create local credit balances. On `order.created`
or `order.paid`, it upserts the customer and invalidates the app cache so the
next entitlement read pulls fresh Polar state.

Use one-time products for:

- credit top-ups,
- paid unlocks,
- setup fees,
- usage packs.

Use subscription add-ons for recurring add-ons that should remain active while
the customer keeps paying.

## Client Integration

All internal clients identify themselves with `appSlug` and `orgId`.
Internal clients must authenticate with a short-lived server-to-server JWT:

```http
Authorization: Bearer <service JWT>
```

The browser must never receive this token. See `docs/internal-backend-integration.md`
for the backend client pattern and token signing example.

### Create checkout

```http
POST /internal/billing/checkout
Authorization: Bearer <service JWT>
content-type: application/json
```

```json
{
  "orgId": "org_123",
  "appSlug": "deploy",
  "productSlug": "deploy-pro",
  "customerEmail": "billing@example.com",
  "successUrl": "https://app.example.com/billing/success",
  "returnUrl": "https://app.example.com/billing"
}
```

Response:

```json
{
  "id": "checkout-session-id",
  "url": "https://checkout.polar.sh/...",
  "expiresAt": "2026-01-01T00:00:00Z",
  "productKind": "subscription"
}
```

The API uses the registered product's Polar product ID and sends
`external_customer_id = orgId` to Polar.

### Get entitlements

```http
GET /internal/billing/entitlements/{appSlug}/{orgId}
Authorization: Bearer <service JWT>
```

Use this for dashboards and billing UI. It returns the plan ID, limits, usage,
and remaining quota snapshot.

### Enforce quota

```http
POST /internal/billing/enforce
Authorization: Bearer <service JWT>
content-type: application/json
```

```json
{
  "orgId": "org_123",
  "appSlug": "deploy",
  "check": {
    "type": "quota",
    "resource": "project",
    "limitKey": "maxProjects"
  }
}
```

### Enforce feature

```json
{
  "orgId": "org_123",
  "appSlug": "deploy",
  "check": {
    "type": "feature",
    "feature": "advancedRollouts"
  }
}
```

### Enforce meter balance

Meter checks use remaining balance, not consumed usage:

```json
{
  "orgId": "org_123",
  "appSlug": "deploy",
  "check": {
    "type": "meter",
    "meterKey": "deployCreditBalance",
    "needed": 1
  }
}
```

If Polar is unavailable and the meter key is absent from the usage map,
`BillingEnforcer.enforceMeter` fails open.

### Ingest usage

```http
POST /internal/billing/usage/ingest
Authorization: Bearer <service JWT>
content-type: application/json
```

```json
{
  "orgId": "org_123",
  "eventKey": "deploy.build_minutes",
  "quantity": 12,
  "metadata": {
    "projectId": "proj_123"
  }
}
```

`PolarClient` sends this to Polar event ingestion with `external_customer_id`.
The request includes `quantity` and `units` metadata so Polar meters can use a
property aggregation.

## Polar Setup Checklist

For each new app:

- Create subscription products for every plan.
- Create subscription products for every recurring add-on.
- Create one-time products for top-ups or non-recurring purchases.
- Create meters for metered resources.
- Attach Polar Benefits where a purchase should grant credits or access.
- Configure checkout products with the product IDs registered in code.
- Configure webhooks to the billing API `/webhooks/polar`.
- Set `POLAR_WEBHOOK_SECRET` and `POLAR_API_KEY`.
- Set `INTERNAL_SERVICE_SECRET` and include the app backend issuer in
  `OAUTH_SERVICE_CLIENT_IDS`.
- Ensure checkout uses `external_customer_id`; this API does it automatically.
- Confirm customer state shows expected `active_subscriptions`,
  `granted_benefits`, and `active_meters`.

## Signal Polar Catalog Bootstrap

To create or update the Signal product catalog in Polar, run:

```bash
# Sandbox
POLAR_API_KEY="..." make polar-sandbox

# Production
POLAR_API_KEY="..." make polar-production

# Or directly, with fine-grained control
POLAR_API_KEY="..." scripts/polar/setup-signal.sh --env sandbox --setup both
POLAR_API_KEY="..." scripts/polar/setup-signal.sh --env production --setup email
```

Use `--setup email`, `--setup sms`, or `--setup both` to control which parts of
the catalog are created. The script is idempotent — it reuses existing resources
tagged with the namespace metadata.

The script reads pricing and product definitions from
`scripts/polar/signal-catalog.sh`. Update that file to change prices,
descriptions, or add new tiers.

It writes the created IDs to `build/polar/signal-{env}-products.json` and
prints `const val` declarations ready to paste into `SignalPlanCatalog.kt`.

For provisioning enterprise plans to individual clients, see
`docs/enterprise-plan-provisioning.md`.

## Implementation Checklist

- Add `<App>PlanCatalog.kt`.
- Add `<App>BillingConfig.kt`.
- Add app-specific config values to `AppConfig.kt`.
- Add defaults and env overrides to `application.conf`.
- Update `application.local.conf.example`.
- Register the app in `AppAssembly.kt`.
- Seed `platform_apps.slug`.
- Add the app backend issuer to `SERVICE_AUTH_ALLOWED_ISSUERS`.
- Configure Polar products, meters, benefits, and webhooks.
- Add tests for plan resolution, product registration, and any meter/feature
  mapping with non-trivial logic.
- Run `./gradlew test`.

## Common Pitfalls

- Do not hardcode product ownership in `PolarWebhookHandler`. Register products
  through `BillingAppRegistration`.
- Do not use user IDs as Polar `external_customer_id` when billing is per org.
  Use the org ID.
- Do not track Polar credit balances locally. Let Polar Benefits/Meters own the
  lifecycle and read customer state.
- Do not register duplicate Polar product IDs across apps. `AppRegistry` rejects
  duplicates at startup.
- Keep entitlement keys stable. Consumer apps call enforcement using those keys.
- Keep one-time products and subscription add-ons separate. The former produces
  orders; the latter produces subscriptions.
- Do not expose service JWTs to browsers. Internal app backends mint them and
  call billing server-to-server.
