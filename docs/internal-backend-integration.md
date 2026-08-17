# Internal Backend Billing Integration

This guide is for backend services that consume Apollo Billing. It explains how
an internal app should check entitlements, block paid operations, report usage,
and expose billing errors in a consistent way.

The goal is the same pattern used by large platform teams: product backends do
not implement billing rules locally. They call a central billing control plane
through a small local client, then enforce a local decision before running
expensive or premium work.

## Integration Model

Each internal app should own one small billing module:

```text
src/billing/
  BillingClient
  BillingGate
  BillingErrors
  BillingUsageReporter
```

The product backend should not call billing endpoints directly from arbitrary
routes. Route handlers should depend on app-level helpers such as:

- `billing.requireFeature(orgId, "advancedWebhooks")`
- `billing.requireQuota(orgId, "project", "maxProjects")`
- `billing.requireMeterBalance(orgId, "automationRunBalance", 1)`
- `billing.requireMeterBalance(orgId, "aiCreditBalance", 1)`
- `billing.reportUsage(orgId, "signal.automation.run", 1, metadata)`
- `billing.reportUsage(orgId, "signal.ai.credit.used", 1, metadata)`
- `billing.getProductCatalog("signal")` for plan/add-on/pack selectors
- `billing.getEntitlements(orgId)` for dashboards

This keeps billing behavior consistent across controllers, jobs, workers, and
RPC methods.

Examples in this guide are shown in both TypeScript and Kotlin because Apollo
internal apps may be built on either stack.

## What Apollo Billing Owns

Apollo Billing is the source of truth for billing state and billing decisions.
Internal apps should treat it as a control plane, not as a helper database.

The billing service owns:

- customer-to-organization billing mappings;
- plan, add-on, and one-time purchase catalog IDs;
- subscription and purchase state synchronized from Polar webhooks;
- entitlement resolution for limits, features, usage, and remaining quota;
- billing enforcement decisions for paid, quota-limited, and meter-backed work;
- checkout session creation for subscriptions, add-ons, and one-time purchases;
- customer billing profile operations that proxy Polar Customer Portal APIs;
- metered usage ingestion and forwarding to Polar;
- Polar webhook verification and state updates;
- service-to-service authentication for internal billing APIs.

Internal apps own:

- authenticating the user;
- authorizing the user for the target organization;
- deciding where in product workflows billing gates apply;
- calling the generated billing SDK from backend code;
- mapping billing failures to product-specific responses;
- reporting usage only after successful work;
- local consistency controls for app-owned resources, such as transactions or
  locks around hard quotas.

## What To Never Do Manually

Do not bypass Apollo Billing for billing behavior. These operations must not be
implemented manually inside internal apps:

- Do not call Polar directly from internal app backends for subscriptions,
  products, checkout, customer billing details, payment methods, or metered
  usage.
- Do not create checkout sessions in browser code.
- Do not expose Polar API keys, service JWTs, Maven publishing credentials, or billing
  secrets to browsers, mobile apps, logs, analytics tools, or customer-facing
  error payloads.
- Do not hardcode Polar product IDs, meter IDs, plan prices, or entitlement
  limits inside product apps. Use the SDK and Apollo Billing catalog responses.
- Do not decide plan access locally from a cached plan name alone. Use
  enforcement for write paths and expensive work.
- Do not report usage before the product operation has committed successfully.
- Do not let public request bodies choose arbitrary `appSlug`, `eventKey`,
  product slug, meter key, or feature key values. Product code should map user
  actions to known constants.
- Do not treat dashboard entitlements as authorization for later writes. Always
  re-check billing in the backend path that performs the work.
- Do not retry usage ingestion in a tight request loop. Use an outbox or bounded
  background retry when exact accounting matters.
- Do not store copies of customer billing profile data unless the product has a
  clear business requirement and data-retention policy.

## Request Lifecycle

Use this sequence for user-facing operations:

1. Authenticate the user.
2. Resolve the organization ID.
3. Authorize the user for that org.
4. Run billing preflight checks.
5. Run the operation.
6. Commit app-side state.
7. Report billable usage asynchronously or with a short timeout.
8. Return the product response.

Do not report usage before the operation commits. If the app fails after usage
is reported, the customer can be charged for work that did not happen.

Example:

```text
POST /projects
  auth user
  resolve org
  require org role
  require quota maxProjects
  create project
  return project
```

Metered example:

```text
POST /automations/run
  auth user
  resolve org
  require feature automations
  require meter balance automationRunBalance >= estimated runs
  run automation
  save result
  report actual automation run usage
  return result
```

## Billing SDK

Use the generated SDKs instead of writing raw HTTP calls in each app:

- Kotlin/JVM: `com.apollodeploy:billing-sdk`
- TypeScript: `@apollo-deploy/billing-sdk`

The Kotlin/JVM SDK is published publicly to Maven Central. Installation and
release instructions are in [SDK README](sdk-readme.md).

## Service-to-Service Authentication

Apollo Billing guards all `/internal/billing/*` endpoints with **OAuth 2.1
`client_credentials`** verification. Callers obtain a short-lived EdDSA-signed
JWT from the platform and send it as a bearer token. Billing verifies it locally
against the platform's public JWKS — no shared secret required.

### How it works

1. The calling service requests a `client_credentials` token from the platform:
   ```
   POST {PLATFORM_URL}/auth/oauth2/token
   Content-Type: application/x-www-form-urlencoded

   grant_type=client_credentials
   &client_id={PLATFORM_CLIENT_ID}
   &client_secret={PLATFORM_CLIENT_SECRET}
   &resource={PLATFORM_AUDIENCE_URL}
   ```
   The `resource` parameter is required — it tells the platform to issue a JWT
   (EdDSA-signed) rather than an opaque token. Its value must match billing's
   `AUTH_OAUTH_VALID_AUDIENCES`.

2. The platform returns a signed EdDSA JWT:
   ```json
   { "access_token": "<jwt>", "expires_in": 3600, "token_type": "Bearer" }
   ```

3. The caller sends the JWT to billing:
   ```http
   Authorization: Bearer <jwt>
   GET /internal/billing/entitlements/signal/org_123
   ```

4. Billing fetches the platform's JWKS (`AUTH_JWKS_URL`) once and caches it for
   300 seconds, verifying:
   - EdDSA signature against the public key
   - `iss` matches `AUTH_OAUTH_ISSUER_URL`
   - `aud` matches `AUTH_OAUTH_VALID_AUDIENCES`
   - `azp`/`sub` (client_id) is in `OAUTH_SERVICE_CLIENT_IDS`
   - `exp` has not passed

### Registering a new calling service

1. Register an OAuth client on the platform:
   ```
   cd apps/platform
   bun run oauth:register-clients
   # key: <service-slug>, grant types: client_credentials, skipConsent: true
   ```

2. Save the returned `client_id` and `client_secret` in the calling service's
   config:
   ```bash
   PLATFORM_CLIENT_ID=<client_id>
   PLATFORM_CLIENT_SECRET=<client_secret>
   PLATFORM_URL=http://platform:3000            # in-cluster token endpoint
   PLATFORM_AUDIENCE_URL=https://api.platform.apollodeploy.local  # JWT audience
   BILLING_BASE_URL=https://billing.apollodeploy.com
   ```

3. Add the `client_id` to the platform's `OAUTH_SERVICE_CLIENT_IDS` and
   `OAUTH_TRUSTED_CLIENT_IDS`, then restart the platform.

4. Add the `client_id` to billing's `OAUTH_SERVICE_CLIENT_IDS`, then restart
   billing.

### SDK client setup (Kotlin — apollo-signal-api pattern)

The Kotlin SDK client is rebuilt whenever the cached OAuth token rotates (the
`MachineOAuthClient` handles refresh automatically):

```kotlin
val platformUrl = System.getenv("PLATFORM_URL")
// MachineOAuthClient fetches and caches the client_credentials JWT.
val m2mClient = MachineOAuthClient {
    httpClient(httpClient)
    tokenEndpoint("${platformUrl.trimEnd('/')}/auth/oauth2/token")
    clientId(System.getenv("PLATFORM_CLIENT_ID"))
    clientSecret(System.getenv("PLATFORM_CLIENT_SECRET"))
    audience(System.getenv("PLATFORM_AUDIENCE_URL").ifBlank { platformUrl })
    clientSecretPost()
    if (platformUrl.startsWith("http://")) allowInsecureHttp()
}

// ApolloBillingClientProvider rebuilds the SDK client when the token rotates.
val billingProvider = ApolloBillingClientProvider(
    baseUrl = System.getenv("BILLING_BASE_URL"),
    m2mClient = m2mClient,
)

// Use in coroutines:
val sdk = billingProvider.get()
val entitlements = sdk.billingEntitlements.getBillingEntitlements("signal", orgId)
```

### Token lifetime and rotation

Tokens expire in 3600 seconds (1 hour). `MachineOAuthClient` refreshes the token
60 seconds before expiry. There is no shared secret to rotate — to revoke a
service's access, remove its `client_id` from `OAUTH_SERVICE_CLIENT_IDS` on
billing and `OAUTH_SERVICE_CLIENT_IDS` on the platform, then rotate the
`client_secret` via `bun run oauth:register-clients` → rotate secret.

## SDK Operations

### Enforce

Use enforcement for blocking decisions. The most important rule: only an SDK
response with `allowed = true` is an unconditional allow.

TypeScript quota check:

```ts
const billing = createBillingSdk();

const decision = await billing.billingEnforcement.enforceBillingCheck({
  orgId: "org_123",
  appSlug: "signal",
  check: {

    type: "quota",
    resource: "project",
    limitKey: "maxProjects",
  },
});

if (!decision.allowed) {
  throw new BillingBlockedError("Project quota exceeded");
}
```

TypeScript feature check:

```ts
await billing.billingEnforcement.enforceBillingCheck({
  orgId: "org_123",
  appSlug: "signal",
  check: {
    type: "feature",
    feature: "dedicatedIps",
  },
});
```

TypeScript meter balance check:

```ts
await billing.billingEnforcement.enforceBillingCheck({
  orgId: "org_123",
  appSlug: "signal",
  check: {
    type: "meter",
    meterKey: "automationRunBalance",
    needed: 1,
  },
});
```

Use `meterKey: "aiCreditBalance"` for AI-credit-backed work. Report consumed
credits with the `signal.ai.credit.used` usage event after the AI operation
commits successfully.

Kotlin quota check:

```kotlin
import com.apollodeploy.billing.sdk.EnforceRequest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

val billing = createBillingSdk()

val decision = billing.billingEnforcement.enforceBillingCheck(
    EnforceRequest(
        orgId = "org_123",
        appSlug = "signal",
        check = buildJsonObject {
            put("type", "quota")
            put("resource", "project")
            put("limitKey", "maxProjects")
        },
    ),
)

if (!decision.allowed) {
    throw BillingBlockedError("Project quota exceeded")
}
```

The SDK returns `EnforceResponse` for `200` responses and throws
`SDKError`/`SdkException` for non-2xx responses. Map those failures as follows:

| Status | Meaning | Consumer action |
| --- | --- | --- |
| `200` | Allowed | Continue the operation. |
| `401` | Missing, expired, or invalid service token | Treat as a deployment/configuration error. Do not retry blindly. |
| `402` | Quota exceeded, feature unavailable, or meter balance too low | Block the operation and return an upgrade/payment error. |
| `404` | No active subscription found | Apply the app's free-tier policy or block if the feature requires a paid plan. |
| `422` | Unknown app/product/configuration issue | Treat as an internal integration bug. |
| `500` | Billing service failed | Use the operation's fail-open/fail-closed policy. |

The most important rule: only `allowed = true` is an unconditional allow.

### Entitlements

Use entitlements for dashboards, settings pages, and explaining why something
is blocked.

TypeScript:

```ts
const entitlements = await billing.billingEntitlements.getBillingEntitlements(
  "signal",
  "org_123",
);
```

Kotlin:

```kotlin
val entitlements = billing.billingEntitlements.getBillingEntitlements(
    appSlug = "signal",
    orgId = "org_123",
)
```

The SDK response includes:

- `planId`
- `limits`
- `features`
- `usage`
- `remaining`

Do not use dashboard entitlements as the only gate for write operations. A user
can open a page, wait, then submit after usage has changed. Always re-check
inside the backend path that performs the work.

### Usage Ingest

Use usage ingest after successful billable work.

TypeScript:

```ts
await billing.billingUsage.ingestBillingUsage({
  orgId: "org_123",
  eventKey: "signal.automation.run",
  quantity: 3,
  metadata: {
    requestId: "req_123",
    workflowId: "workflow_123",
    runId: "run_123",
  },
});
```

Kotlin:

```kotlin
import com.apollodeploy.billing.sdk.UsageIngestRequest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

billing.billingUsage.ingestBillingUsage(
    UsageIngestRequest(
        orgId = "org_123",
        eventKey = "signal.automation.run",
        quantity = 3,
        metadata = buildJsonObject {
            put("requestId", "req_123")
            put("workflowId", "workflow_123")
            put("runId", "run_123")
        },
    ),
)
```

The billing API forwards this to Polar with `external_customer_id = orgId`.
Failures return `202` with `accepted = false` so callers do not create retry
storms. If exact usage accounting matters, the product backend should write an
outbox event locally and let a worker retry until accepted.

### Checkout

Use checkout when the backend needs to create a billing session.

First read the public product catalog so product selection and displayed prices
come from billing/Polar, not hardcoded Polar IDs:

```ts
const catalog = await fetch("https://billing.apollodeploy.com/billing/catalog/signal")
  .then((response) => response.json());

const aiPacks = catalog.products.filter(
  (product) => product.kind === "CREDIT_PACK" && product.slug.startsWith("signal-ai-credits-"),
);
const dedicatedIp = catalog.products.find(
  (product) => product.slug === "signal-dedicated-ip-addon",
);
```

This catalog endpoint is public and does not require a service JWT. The
frontend may receive product slugs and display names/prices from it. It should
send the selected slug back to the app backend. The backend must still map
allowed customer actions to known product categories before calling checkout.

TypeScript:

```ts
const checkout = await billing.billingCheckout.createBillingCheckout({
  orgId: "org_123",
  appSlug: "signal",
  productSlug: "signal-growth",
  customerEmail: "billing@example.com",
  successUrl: "https://signal.apollodeploy.com/settings/billing/success",
  returnUrl: "https://signal.apollodeploy.com/settings/billing",
});

return checkout.url;
```

Kotlin:

```kotlin
import com.apollodeploy.billing.sdk.CreateCheckoutRequest

val checkout = billing.billingCheckout.createBillingCheckout(
    CreateCheckoutRequest(
        orgId = "org_123",
        appSlug = "signal",
        productSlug = "signal-growth",
        customerEmail = "billing@example.com",
        successUrl = "https://signal.apollodeploy.com/settings/billing/success",
        returnUrl = "https://signal.apollodeploy.com/settings/billing",
    ),
)

return checkout.url
```

Return the checkout URL to the frontend. Do not expose Polar API keys or create
Polar sessions from the browser.

### Customer Billing Profile

Use the billing profile endpoints from the app backend after the user has been
authorized for the org. These calls proxy Polar Customer Portal APIs through a
short-lived customer session created server-side.

Update billing details:

```ts
await billing.billingCustomer.updateCustomerBillingInfo({
  orgId: "org_123",
  email: "billing@example.com",
  billingName: "Apollo Signal Ltd",
  billingAddress: {
    line1: "123 Market St",
    city: "San Francisco",
    state: "CA",
    postal_code: "94105",
    country: "US",
  },
  taxId: "911144442",
});
```

```kotlin
import com.apollodeploy.billing.sdk.PolarBillingAddressInput
import com.apollodeploy.billing.sdk.UpdateCustomerBillingInfoRequest

billing.billingCustomer.updateCustomerBillingInfo(
    UpdateCustomerBillingInfoRequest(
        orgId = "org_123",
        email = "billing@example.com",
        billingName = "Apollo Signal Ltd",
        billingAddress = PolarBillingAddressInput(
            line1 = "123 Market St",
            city = "San Francisco",
            state = "CA",
            postal_code = "94105",
            country = "US",
        ),
        taxId = "911144442",
    ),
)
```

List saved payment methods:

```ts
const methods = await billing.billingCustomer.listCustomerPaymentMethods({
  orgId: "org_123",
  page: 1,
  limit: 10,
});
// methods.paymentMethods.items[].is_default marks the default card
```

Delete a saved payment method:

```ts
await billing.billingCustomer.deleteCustomerPaymentMethod("pm_123", {
  orgId: "org_123",
});
```

Polar may reject deleting a payment method that is used by active
subscriptions. Surface that as a customer-facing "set another payment method
first" action. Do not log full billing profile payloads, tax IDs, or payment
method details.

## Client Module Design

Every internal backend should wrap the generated SDK in a small product-level
billing module. The wrapper should set:

- base URL from config,
- short-lived service JWT through the SDK config,
- request timeout,
- structured logging,
- metrics for latency/error/blocked decisions,
- request IDs for trace correlation.

Recommended timeouts:

- enforcement: 300 ms to 800 ms inside latency-sensitive requests,
- entitlements: 1 s to 2 s for dashboard reads,
- checkout: 2 s to 5 s,
- usage ingest: 300 ms to 1 s or async outbox.

TypeScript wrapper shape:

```ts
import {
  SDKError,
  createApolloBillingClient,
} from "@apollo-deploy/billing-sdk";

type BillingCheck = {
  type: "quota" | "feature" | "meter";
  resource?: string;
  limitKey?: string;
  feature?: string;
  meterKey?: string;
  needed?: number;
};

class BillingClient {
  constructor(private readonly appSlug: string) {}

  private sdk(): ReturnType<typeof createApolloBillingClient> {
    return createApolloBillingClient({
      baseUrl: process.env.BILLING_API_BASE_URL ?? "https://billing.apollodeploy.com",
      defaultHeaders: {
        Authorization: `Bearer ${createBillingServiceJwt()}`,
      },
    });
  }

  async enforce(orgId: string, check: BillingCheck): Promise<void> {
    try {
      const decision = await this.sdk().billingEnforcement.enforceBillingCheck({
        orgId,
        appSlug: this.appSlug,
        check,
      });

      if (!decision.allowed) {
        throw new BillingBlockedError("Billing blocked this operation");
      }
    } catch (error) {
      if (error instanceof SDKError) {
        if (error.status === 402) throw BillingBlockedError.from(error);
        if (error.status === 404) throw new BillingNoSubscriptionError(error);
        throw new BillingUnavailableError(error.status, error);
      }
      throw error;
    }
  }

  async reportUsage(
    orgId: string,
    eventKey: string,
    quantity = 1,
    metadata: Record<string, unknown> = {},
  ): Promise<void> {
    await this.sdk().billingUsage.ingestBillingUsage({
      orgId,
      eventKey,
      quantity,
      metadata,
    });
  }

  async getEntitlements(orgId: string) {
    return this.sdk().billingEntitlements.getBillingEntitlements(
      this.appSlug,
      orgId,
    );
  }
}
```

Then expose product-level helpers in TypeScript:

```ts
class SignalBilling {
  constructor(private readonly client: BillingClient) {}

  requireProjectCreate(orgId: string) {
    return this.client.enforce(orgId, {
      type: "quota",
      resource: "project",
      limitKey: "maxProjects",
    });
  }

  requireAutomationRuns(orgId: string, needed: number) {
    return this.client.enforce(orgId, {
      type: "meter",
      meterKey: "automationRunBalance",
      needed,
    });
  }

  requireAiCredits(orgId: string, needed: number) {
    return this.client.enforce(orgId, {
      type: "meter",
      meterKey: "aiCreditBalance",
      needed,
    });
  }

  reportAutomationRuns(orgId: string, quantity: number, requestId: string) {
    return this.client.reportUsage(orgId, "signal.automation.run", quantity, {
      requestId,
    });
  }

  reportAiCredits(orgId: string, quantity: number, requestId: string) {
    return this.client.reportUsage(orgId, "signal.ai.credit.used", quantity, {
      requestId,
    });
  }
}
```

Kotlin wrapper shape:

```kotlin
import com.apollodeploy.billing.sdk.ApolloBillingClient
import com.apollodeploy.billing.sdk.ClientConfig
import com.apollodeploy.billing.sdk.EnforceRequest
import com.apollodeploy.billing.sdk.SdkException
import com.apollodeploy.billing.sdk.UsageIngestRequest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

class BillingClient(
    private val appSlug: String,
) {
    private fun sdk(): ApolloBillingClient =
        ApolloBillingClient(
            ClientConfig(
                baseUrl = System.getenv("BILLING_API_BASE_URL") ?: "https://billing.apollodeploy.com",
                serviceToken = createBillingServiceJwt(),
            ),
        )

    suspend fun enforce(orgId: String, check: JsonElement) {
        try {
            val decision = sdk().billingEnforcement.enforceBillingCheck(
                EnforceRequest(
                    orgId = orgId,
                    appSlug = appSlug,
                    check = check,
                ),
            )

            if (!decision.allowed) {
                throw BillingBlockedError("Billing blocked this operation")
            }
        } catch (error: SdkException) {
            when (error.status) {
                402 -> throw BillingBlockedError(error.message)
                404 -> throw BillingNoSubscriptionError(error.message)
                else -> throw BillingUnavailableError(error.status, error)
            }
        }
    }

    suspend fun reportUsage(
        orgId: String,
        eventKey: String,
        quantity: Int = 1,
        metadata: JsonObject? = null,
    ) {
        sdk().billingUsage.ingestBillingUsage(
            UsageIngestRequest(
                orgId = orgId,
                eventKey = eventKey,
                quantity = quantity,
                metadata = metadata,
            ),
        )
    }

    suspend fun getEntitlements(orgId: String) =
        sdk().billingEntitlements.getBillingEntitlements(
            appSlug = appSlug,
            orgId = orgId,
        )
    }
}
```

Then expose product-level helpers in Kotlin:

```kotlin
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SignalBilling(private val client: BillingClient) {
    suspend fun requireProjectCreate(orgId: String) {
        client.enforce(
            orgId,
            buildJsonObject {
                put("type", "quota")
                put("resource", "project")
                put("limitKey", "maxProjects")
            },
        )
    }

    suspend fun requireAutomationRuns(orgId: String, needed: Int) {
        client.enforce(
            orgId,
            buildJsonObject {
                put("type", "meter")
                put("meterKey", "automationRunBalance")
                put("needed", needed)
            },
        )
    }

    suspend fun requireAiCredits(orgId: String, needed: Int) {
        client.enforce(
            orgId,
            buildJsonObject {
                put("type", "meter")
                put("meterKey", "aiCreditBalance")
                put("needed", needed)
            },
        )
    }

    suspend fun reportAutomationRuns(orgId: String, quantity: Int, requestId: String) {
        client.reportUsage(
            orgId = orgId,
            eventKey = "signal.automation.run",
            quantity = quantity,
            metadata = buildJsonObject {
                put("requestId", requestId)
            },
        )
    }

    suspend fun reportAiCredits(orgId: String, quantity: Int, requestId: String) {
        client.reportUsage(
            orgId = orgId,
            eventKey = "signal.ai.credit.used",
            quantity = quantity,
            metadata = buildJsonObject {
                put("requestId", requestId)
            },
        )
    }
}
```

Route handlers should call `SignalBilling`, not `BillingClient` directly.

## Blocking Policy

Use explicit policy per operation.

### Fail closed

Fail closed when allowing the operation could cause material cost, security
risk, or contractual exposure:

- paid AI generation,
- build minutes,
- email sends at scale,
- data exports,
- premium compliance features,
- destructive provisioning actions,
- one-time paid unlocks.

If billing is unavailable, return a retryable product error:

```json
{
  "code": "billing_unavailable",
  "message": "Billing checks are temporarily unavailable. Try again shortly."
}
```

### Fail open

Fail open only for low-cost, read-only, or customer-support-sensitive paths:

- viewing dashboards,
- listing resources,
- reading settings,
- webhook delivery retries,
- low-cost background refreshes.

Log and meter these decisions. Fail-open should be visible in observability,
not hidden.

### No subscription

`404 billing.no_subscription` is not the same as `200 allowed`.

Recommended policy:

- If the app has a real free tier, route the operation through free-tier checks
  or allow only operations that are free-tier safe.
- If the operation requires a paid plan, block and return an upgrade-required
  response.
- If the app is invitation-only or enterprise-only, treat no subscription as a
  provisioning error.

## Where To Put Checks

Put checks as close as possible to the operation that consumes the entitlement.

Good:

```text
ProjectService.createProject()
  requireProjectCreate()
  insert project
```

Risky:

```text
GET /settings page checks quota
POST /projects trusts that the page checked quota earlier
```

Use route middleware only for broad feature gates. Use service-level guards for
quota and usage-sensitive work.

Recommended patterns:

- Check feature gates before showing or running premium paths.
- Check quota before creating persistent resources.
- Check meter balance before expensive metered work.
- Report usage after successful work.
- Use database constraints or transactions for app-owned usage where races can
  exceed a quota.

## Race Conditions

Central billing checks are not a substitute for app-level concurrency control.

Example: if an org has 9 projects and a limit of 10, two concurrent
`createProject` calls can both pass the quota check before either insert
commits.

For hard quotas, combine billing checks with app-side locking or transactional
rechecks:

```text
begin transaction
  lock organization quota row
  count current projects
  billing require maxProjects
  insert project
commit
```

For soft quotas, allow small overshoot and reconcile through periodic jobs.

Use hard quotas for:

- resource counts,
- irreversible provisioning,
- abuse-sensitive features.

Use soft quotas for:

- analytics events,
- low-cost counters,
- eventually consistent usage summaries.

## Metered Work

For metered operations, distinguish estimated cost from actual cost.

Recommended flow:

1. Estimate cost.
2. Require enough meter balance for the estimate.
3. Run the operation.
4. Calculate actual cost.
5. Report actual usage.

Example:

```ts
const estimatedRuns = estimateAutomationRuns(workflow);
await billing.requireAutomationRuns(orgId, estimatedRuns);

const result = await runAutomation(workflow);
await saveAutomationResult(orgId, result);

const actualRuns = calculateAutomationRuns(result.usage);
await billing.reportAutomationRuns(orgId, actualRuns, requestId);
```

If actual usage can exceed the estimate materially, either add a buffer or break
the work into smaller chunks with repeated checks.

## Background Jobs

Workers need billing checks too. Treat them like product APIs:

- include org ID in every job payload,
- check feature/quota/meter before expensive work,
- stop the job with a blocked status if billing blocks it,
- report usage only after successful completion,
- include job ID in usage metadata.

For scheduled jobs, check billing at execution time, not only when the schedule
is created.

## Error Mapping

Map billing failures to product errors consistently:

| Billing code | Product response |
| --- | --- |
| `billing.quota_exceeded` | `403` or `402` with upgrade CTA and current/limit values. |
| `billing.feature_unavailable` | `403` or `402` with required feature/plan message. |
| `billing.no_subscription` | Free-tier fallback, upgrade CTA, or provisioning error. |
| `billing.unauthenticated` | Internal server error; fix service-token configuration. |
| `billing.unknown_app` | Internal server error; fix deployment/configuration. |
| `billing.internal_error` | Retryable billing unavailable error unless operation is fail-open. |

Avoid leaking raw billing internals directly to public clients. Translate them
into app-specific messages while preserving a machine-readable code.

## Observability

Every backend should emit metrics around billing decisions:

- `billing.enforce.allowed`
- `billing.enforce.blocked`
- `billing.enforce.error`
- `billing.enforce.latency_ms`
- `billing.usage.report.accepted`
- `billing.usage.report.failed`
- `billing.fail_open.count`
- `billing.checkout.created`
- `billing.checkout.failed`

Log fields:

- `orgId`
- `appSlug`
- `checkType`
- `limitKey`, `feature`, or `meterKey`
- `billingStatus`
- `billingCode`
- `requestId`
- `jobId` when applicable

Do not log secrets, checkout session tokens, or full customer billing details.

## Security

- Keep `INTERNAL_SERVICE_JWT_SECRET` server-side only.
- Never call billing internal endpoints from frontend code.
- Never call Polar directly for billing actions that Apollo Billing owns.
- Never hardcode product IDs, meter IDs, prices, or entitlement limits in
  product apps.
- Use the app backend to create checkout sessions.
- Validate the authenticated user has access to `orgId` before calling billing.
- Do not accept `appSlug` from public clients for enforcement. It should be a
  constant in the app backend.
- Do not let users choose arbitrary `eventKey` values. Product code should map
  operations to known event keys.
- Use short token TTLs. The billing API defaults to 300 seconds max token age.
- Rotate `INTERNAL_SERVICE_JWT_SECRET` through deployment config, not code.

## Recommended Backend Checklist

- Install and use the generated billing SDK.
- Create a typed product-level billing wrapper around the SDK.
- Make `appSlug` a constant.
- Add product-level guard helpers.
- Add product-level usage reporter helpers.
- Define fail-open/fail-closed policy per operation.
- Add billing checks to write paths and expensive background jobs.
- Add post-commit usage reporting.
- Add an outbox for usage events that must be retried exactly.
- Map billing errors to product API errors.
- Add metrics and structured logs.
- Add integration tests with mocked billing responses:
  - `200` allowed,
  - `402` blocked,
  - `404` no subscription,
  - `500` fail-open/fail-closed behavior,
  - usage ingest accepted/unavailable.

## Minimal Example

```ts
export async function createProject(req: Request) {
  const user = await requireUser(req);
  const orgId = await requireOrgAccess(user, req.params.orgId);

  await signalBilling.requireProjectCreate(orgId);

  const project = await projects.create({
    orgId,
    name: req.body.name,
  });

  return Response.json(project);
}
```

```ts
export async function generateEmail(req: Request) {
  const user = await requireUser(req);
  const orgId = await requireOrgAccess(user, req.params.orgId);
  const requestId = req.headers.get("x-request-id") ?? crypto.randomUUID();

  const estimatedRuns = 1;
  await signalBilling.requireAutomationRuns(orgId, estimatedRuns);

  const result = await automations.run(req.body);
  await automations.saveRunResult(orgId, result);

  await signalBilling.reportAutomationRuns(orgId, result.runsUsed, requestId);

  return Response.json(result);
}
```

This is the operating rule: enforce before you spend or create, meter after you
successfully spend or create, and keep all billing behavior behind one local
backend module per app.
