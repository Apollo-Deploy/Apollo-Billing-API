# Apollo Billing SDKs

Apollo Billing SDKs are generated from Billing's exported OpenAPI 3.1 contract. Apps should use these SDKs from their backend only. Do not call Apollo Billing directly from browser code, because service JWTs must stay server-side.

## Packages

| SDK | Package | Status |
| --- | --- | --- |
| Kotlin/JVM | `com.apollodeploy:billing-sdk` | Published publicly to Maven Central |
| TypeScript | `@apollo-deploy/billing-sdk` | Generated in `sdk/typescript`; publish with the repo npm flow when needed |

Latest verified public Kotlin/JVM version:

```kotlin
implementation("com.apollodeploy:billing-sdk:1.0.3")
```

## Kotlin/JVM Install

Add Maven Central and the public dependency to the backend app's `build.gradle.kts`:

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("com.apollodeploy:billing-sdk:<version>")
}
```

## Kotlin/JVM Usage

Set these environment variables in the internal app backend:

```bash
BILLING_BASE_URL=https://billing.apollodeploy.com
APOLLO_BILLING_SERVICE_JWT=...
```

Create the client:

```kotlin
import com.apollodeploy.billing.sdk.ApolloBillingClient
import com.apollodeploy.billing.sdk.ClientConfig
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

val billing = ApolloBillingClient(
    ClientConfig(
        baseUrl = System.getenv("BILLING_BASE_URL") ?: "https://billing.apollodeploy.com",
        serviceToken = System.getenv("APOLLO_BILLING_SERVICE_JWT"),
    ),
)
```

Check health:

```kotlin
val health = billing.health.getHealth()
println(health.status)
```

Check entitlements:

```kotlin
val entitlements = billing.billingEntitlements.getBillingEntitlements(
    appSlug = "signal",
    orgId = "org_123",
)
```

Enforce a billing-gated action before doing work:

```kotlin
import com.apollodeploy.billing.sdk.EnforceRequest

val decision = billing.billingEnforcement.enforceBillingCheck(
    EnforceRequest(
        appSlug = "signal",
        orgId = "org_123",
        check = buildJsonObject {
            put("kind", "usage")
            put("eventKey", "email.sent")
            put("quantity", 1)
        },
    ),
)

if (!decision.allowed) {
    error("Billing blocked this action")
}
```

Ingest usage after successful work:

```kotlin
import com.apollodeploy.billing.sdk.UsageIngestRequest

billing.billingUsage.ingestBillingUsage(
    UsageIngestRequest(
        orgId = "org_123",
        eventKey = "email.sent",
        quantity = 1,
        metadata = buildJsonObject {
            put("idempotencyKey", "email-send-evt-123")
        },
    ),
)
```

Get the public product catalog for checkout selectors:

```kotlin
val catalog = billing.billingCatalog.getBillingProductCatalog("signal")

val aiPacks = catalog.products.filter {
    it.kind == "CREDIT_PACK" && it.slug.startsWith("signal-ai-credits-")
}

val dedicatedIpAddon = catalog.products.firstOrNull {
    it.slug == "signal-dedicated-ip-addon"
}
```

The route behind this SDK method is public: `GET /billing/catalog/signal`.
Use catalog `slug` values for checkout. Do not send Polar product IDs from
frontend code.

## Kotlin Error Handling

```kotlin
import com.apollodeploy.billing.sdk.SdkException

try {
    val result = billing.health.getHealth()
    println(result.status)
} catch (error: SdkException) {
    when {
        error.isClientError() -> println("Billing request failed: ${error.message}")
        error.isServerError() -> println("Billing service error: ${error.message}")
        error.isNetworkError() -> println("Billing network error: ${error.message}")
        else -> println("Billing error: ${error.message}")
    }
}
```

## TypeScript Install

The TypeScript SDK is generated into `sdk/typescript`:

```bash
make sdk
```

For local development in another internal app, install from a packed tarball:

```bash
cd sdk/typescript
npm install
npm run build
npm pack
```

Then in the internal app:

```bash
npm install /path/to/apollo-deploy-billing-sdk-1.6.0.tgz
```

Catalog usage:

```ts
const catalog = await fetch("https://billing.apollodeploy.com/billing/catalog/signal")
  .then((response) => response.json());

const aiPacks = catalog.products.filter(
  (product) => product.kind === "CREDIT_PACK" && product.slug.startsWith("signal-ai-credits-"),
);

const dedicatedIpAddon = catalog.products.find(
  (product) => product.slug === "signal-dedicated-ip-addon",
);
```

Publish both SDKs through the release workflow:

```bash
NPM_TOKEN=... make sdk-publish SDK_VERSION=<next-version>
```

## TypeScript Usage

Set backend-only environment variables:

```bash
BILLING_BASE_URL=https://billing.apollodeploy.com
APOLLO_BILLING_SERVICE_JWT=...
```

Create the client:

```ts
import { createApolloBillingClient } from '@apollo-deploy/billing-sdk';

export const billing = createApolloBillingClient({
  baseUrl: process.env.BILLING_BASE_URL ?? 'https://billing.apollodeploy.com',
  defaultHeaders: {
    Authorization: `Bearer ${process.env.APOLLO_BILLING_SERVICE_JWT}`,
  },
});
```

Enforce before work:

```ts
const decision = await billing.billingEnforcement.enforceBillingCheck({
  appSlug: 'signal',
  orgId: 'org_123',
  check: {
    kind: 'usage',
    eventKey: 'email.sent',
    quantity: 1,
  },
});

if (!decision.allowed) {
  throw new Error('Billing blocked this action');
}
```

Ingest usage after successful work:

```ts
await billing.billingUsage.ingestBillingUsage({
  orgId: 'org_123',
  eventKey: 'email.sent',
  quantity: 1,
  metadata: {
    idempotencyKey: 'email-send-evt-123',
  },
});
```

## TypeScript Error Handling

```ts
import { SDKError } from '@apollo-deploy/billing-sdk';

try {
  await billing.health.getHealth();
} catch (error) {
  if (error instanceof SDKError) {
    console.error(error.status, error.code, error.message);
  }
  throw error;
}
```

## Publishing A New Kotlin/JVM Version

Generate both SDKs and publish the Kotlin/JVM package:

```bash
make sdk-publish-kotlin SDK_VERSION=<next-version>
```

Verify the package:

```bash
curl --fail --silent --show-error \
  https://repo1.maven.org/maven2/com/apollodeploy/billing-sdk/<version>/billing-sdk-<version>.pom
```

## Security Rules

- Keep `APOLLO_BILLING_SERVICE_JWT` on backend servers only.
- Keep Maven Central Portal and GPG signing credentials in maintainer shells or CI secrets only.
- Do not expose billing SDK calls through browser requests without your backend authorizing the user and organization first.
- Use idempotency keys for usage ingestion so retries do not double-count usage.
- Treat billing enforcement as a pre-check and usage ingestion as a post-work accounting event.
