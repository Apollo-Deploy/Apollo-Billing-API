package com.apollodeploy.billing.feature

import com.apollodeploy.billing.feature.checkout.api.CheckoutController
import com.apollodeploy.billing.feature.checkout.api.checkoutRoutes
import com.apollodeploy.billing.feature.checkout.application.CheckoutService
import com.apollodeploy.billing.feature.checkout.domain.CreateCheckoutResponse
import com.apollodeploy.billing.feature.checkout.domain.CreateCheckoutResult
import com.apollodeploy.billing.feature.customer.api.CustomerBillingController
import com.apollodeploy.billing.feature.customer.api.customerBillingRoutes
import com.apollodeploy.billing.feature.customer.application.CustomerBillingService
import com.apollodeploy.billing.feature.customer.domain.CustomerBillingResult
import com.apollodeploy.billing.feature.customer.domain.ListCustomerPaymentMethodsResponse
import com.apollodeploy.billing.feature.customer.domain.UpdateCustomerBillingInfoResponse
import com.apollodeploy.billing.feature.enforce.api.EnforceController
import com.apollodeploy.billing.feature.enforce.api.enforceRoutes
import com.apollodeploy.billing.feature.enforce.application.EnforceService
import com.apollodeploy.billing.feature.enforce.domain.EnforceResult
import com.apollodeploy.billing.feature.entitlements.api.EntitlementsController
import com.apollodeploy.billing.feature.entitlements.api.entitlementsRoutes
import com.apollodeploy.billing.feature.entitlements.application.EntitlementsService
import com.apollodeploy.billing.feature.entitlements.domain.EntitlementsResponse
import com.apollodeploy.billing.feature.entitlements.domain.EntitlementsResult
import com.apollodeploy.billing.feature.usage.api.UsageIngestController
import com.apollodeploy.billing.feature.usage.api.usageIngestRoutes
import com.apollodeploy.billing.feature.usage.application.UsageIngestService
import com.apollodeploy.billing.feature.usage.domain.UsageIngestResponse
import com.apollodeploy.billing.support.billingTestApplication
import com.apollodeploy.billing.support.noAuthInternalRoutes
import com.apollodeploy.billing.support.validServiceToken
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertNotEquals

/**
 * Property 1: Valid JWT is always accepted on any internal endpoint
 *
 * For any route registered under the /internal/ prefix and for any freshly signed JWT produced by
 * ServiceTokenSigner.sign(secret = "test-service-token-secret", issuer = "apollo-signal-api",
 * audience = "apollo-billing"), the HTTP response status SHALL NOT be 401.
 *
 * **Validates: Requirements 1.3**
 */
class InternalEndpointAuthTest {
    // ── Enforce ──────────────────────────────────────────────────────────────
    private val enforceService = mockk<EnforceService>()
    private val enforceController = EnforceController(enforceService)

    // ── Entitlements ─────────────────────────────────────────────────────────
    private val entitlementsService = mockk<EntitlementsService>()
    private val entitlementsController = EntitlementsController(entitlementsService)

    // ── Checkout ──────────────────────────────────────────────────────────────
    private val checkoutService = mockk<CheckoutService>()
    private val checkoutController = CheckoutController(checkoutService)

    // ── Customer billing ──────────────────────────────────────────────────────
    private val customerBillingService = mockk<CustomerBillingService>()
    private val customerBillingController = CustomerBillingController(customerBillingService)

    // ── Usage ingest ──────────────────────────────────────────────────────────
    private val usageIngestService = mockk<UsageIngestService>()
    private val usageIngestController = UsageIngestController(usageIngestService)

    // ─────────────────────────────────────────────────────────────────────────
    // Test 1 — POST /internal/billing/enforce
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `valid JWT is accepted on POST internal billing enforce`() =
        billingTestApplication(
            routes = { noAuthInternalRoutes { enforceRoutes(enforceController) } },
        ) {
            coEvery { enforceService.enforce(any(), any()) } returns EnforceResult.Allowed

            val response =
                client.post("/internal/billing/enforce") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"orgId":"org_1","appSlug":"signal","check":{"type":"feature","feature":"deployments"}}""")
                }

            assertNotEquals(HttpStatusCode.Unauthorized, response.status)
        }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 2 — GET /internal/billing/entitlements/signal/org_1
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `valid JWT is accepted on GET internal billing entitlements`() =
        billingTestApplication(
            routes = { noAuthInternalRoutes { entitlementsRoutes(entitlementsController) } },
        ) {
            coEvery { entitlementsService.getEntitlements(any(), any()) } returns
                EntitlementsResult.Found(
                    EntitlementsResponse(
                        appSlug = "signal",
                        orgId = "org_1",
                        planId = "plan_free",
                        limits = emptyMap(),
                        features = emptyMap(),
                        usage = emptyMap(),
                        remaining = emptyMap(),
                    ),
                )

            val response =
                client.get("/internal/billing/entitlements/signal/org_1") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                }

            assertNotEquals(HttpStatusCode.Unauthorized, response.status)
        }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 3 — POST /internal/billing/checkout
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `valid JWT is accepted on POST internal billing checkout`() =
        billingTestApplication(
            routes = { noAuthInternalRoutes { checkoutRoutes(checkoutController) } },
        ) {
            coEvery { checkoutService.createCheckout(any()) } returns
                CreateCheckoutResult.Created(
                    CreateCheckoutResponse(
                        id = "chk_123",
                        url = "https://checkout.polar.sh/chk_123",
                        expiresAt = null,
                        productKind = "subscription",
                    ),
                )

            val response =
                client.post("/internal/billing/checkout") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"orgId":"org_1","appSlug":"signal","productSlug":"signal-pro"}""")
                }

            assertNotEquals(HttpStatusCode.Unauthorized, response.status)
        }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 4 — PATCH /internal/billing/customer/billing-info
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `valid JWT is accepted on PATCH internal billing customer billing-info`() =
        billingTestApplication(
            routes = { noAuthInternalRoutes { customerBillingRoutes(customerBillingController) } },
        ) {
            coEvery { customerBillingService.updateBillingInfo(any()) } returns
                CustomerBillingResult.Success(UpdateCustomerBillingInfoResponse(buildJsonObject {}))

            val response =
                client.patch("/internal/billing/customer/billing-info") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"orgId":"org_1","email":"test@example.com"}""")
                }

            assertNotEquals(HttpStatusCode.Unauthorized, response.status)
        }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 5 — GET /internal/billing/customer/payment-methods?orgId=org_1
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `valid JWT is accepted on GET internal billing customer payment-methods`() =
        billingTestApplication(
            routes = { noAuthInternalRoutes { customerBillingRoutes(customerBillingController) } },
        ) {
            coEvery { customerBillingService.listPaymentMethods(any(), any(), any()) } returns
                CustomerBillingResult.Success(ListCustomerPaymentMethodsResponse(buildJsonObject {}))

            val response =
                client.get("/internal/billing/customer/payment-methods?orgId=org_1") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                }

            assertNotEquals(HttpStatusCode.Unauthorized, response.status)
        }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 6 — DELETE /internal/billing/customer/payment-methods/pm_abc
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `valid JWT is accepted on DELETE internal billing customer payment-method`() =
        billingTestApplication(
            routes = { noAuthInternalRoutes { customerBillingRoutes(customerBillingController) } },
        ) {
            coEvery { customerBillingService.deletePaymentMethod(any(), any()) } returns
                CustomerBillingResult.Success(Unit)

            val response =
                client.delete("/internal/billing/customer/payment-methods/pm_abc?orgId=org_1") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                }

            assertNotEquals(HttpStatusCode.Unauthorized, response.status)
        }

    // ─────────────────────────────────────────────────────────────────────────
    // Test 7 — POST /internal/billing/usage/ingest
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `valid JWT is accepted on POST internal billing usage ingest`() =
        billingTestApplication(
            routes = { noAuthInternalRoutes { usageIngestRoutes(usageIngestController) } },
        ) {
            coEvery { usageIngestService.ingest(any()) } returns UsageIngestResponse(accepted = true)

            val response =
                client.post("/internal/billing/usage/ingest") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"orgId":"org_1","eventKey":"signal.automation.run","quantity":1}""")
                }

            assertNotEquals(HttpStatusCode.Unauthorized, response.status)
        }
}
