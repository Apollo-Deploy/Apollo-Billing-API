package com.apollodeploy.billing.feature.security

import com.apollodeploy.billing.feature.customer.api.CustomerBillingController
import com.apollodeploy.billing.feature.customer.api.customerBillingRoutes
import com.apollodeploy.billing.feature.customer.application.CustomerBillingService
import com.apollodeploy.billing.feature.customer.domain.CustomerBillingResult
import com.apollodeploy.billing.feature.enforce.api.EnforceController
import com.apollodeploy.billing.feature.enforce.api.enforceRoutes
import com.apollodeploy.billing.feature.enforce.application.EnforceService
import com.apollodeploy.billing.feature.enforce.domain.BillingErrorResponse
import com.apollodeploy.billing.feature.enforce.domain.EnforceResult
import com.apollodeploy.billing.feature.webhook.api.PolarWebhookController
import com.apollodeploy.billing.feature.webhook.api.polarWebhookRoutes
import com.apollodeploy.billing.feature.webhook.application.PolarWebhookService
import com.apollodeploy.billing.feature.webhook.domain.PolarWebhookResult
import com.apollodeploy.billing.support.billingTestApplication
import com.apollodeploy.billing.support.machineAuthenticatedRoutes
import com.apollodeploy.billing.support.validServiceToken
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Adversarial tests — information leakage through error responses.
 *
 * Validates that NO error path reveals:
 *   - Stack traces or class names
 *   - Internal service names or URLs
 *   - Database connection strings or table names
 *   - API keys or secrets
 *   - Polar error bodies (third-party internals)
 *   - JWKS URLs or auth configuration
 *   - File paths or line numbers
 */
class InformationLeakageHttpTest {
    private val enforceService = mockk<EnforceService>()
    private val enforceController = EnforceController(enforceService)

    private val customerService = mockk<CustomerBillingService>()
    private val customerController = CustomerBillingController(customerService)

    private val webhookService = mockk<PolarWebhookService>()
    private val webhookController = PolarWebhookController(webhookService)

    private val dangerousStrings =
        listOf(
            "Exception",
            "stackTrace",
            ".kt:",
            "at com.",
            "NullPointer",
            "jdbc:",
            "postgresql://",
            "HikariPool",
            "Connection refused",
            "POLAR_API_KEY",
            "POLAR_WEBHOOK_SECRET",
            "PLATFORM_CLIENT_SECRET",
            "billing_app",
            "billing_superuser",
            "billing_subscriptions",
            "/auth/jwks",
            "EdDSA",
            "Ed25519",
            "/Users/",
            "/home/",
            "apollo-billing-api",
        )

    private fun assertNoLeakage(responseText: String) {
        dangerousStrings.forEach { dangerous ->
            assertTrue(
                !responseText.contains(dangerous, ignoreCase = false),
                "Response leaks sensitive info: contains '$dangerous'",
            )
        }
    }

    // ─── Enforce error responses ──────────────────────────────────────────────

    @Test
    fun `enforce 422 does not leak internal details`() =
        billingTestApplication(
            routes = { machineAuthenticatedRoutes { enforceRoutes(enforceController) } },
        ) {
            coEvery { enforceService.enforce(any(), any()) } returns
                EnforceResult.Rejected(
                    statusCode = 422,
                    error = BillingErrorResponse(code = "billing.unknown_app", message = "Unknown app slug: x"),
                )

            val r =
                client.post("/internal/billing/enforce") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"orgId":"org_1","appSlug":"x","check":{"type":"feature","feature":"y"}}""")
                }
            assertNoLeakage(r.bodyAsText())
        }

    @Test
    fun `enforce 503 does not leak DB connection details`() =
        billingTestApplication(
            routes = { machineAuthenticatedRoutes { enforceRoutes(enforceController) } },
        ) {
            coEvery { enforceService.enforce(any(), any()) } returns
                EnforceResult.Rejected(
                    statusCode = 503,
                    error = BillingErrorResponse(code = "billing.service_unavailable", message = "Service temporarily unavailable"),
                )

            val r =
                client.post("/internal/billing/enforce") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"orgId":"org_1","appSlug":"signal","check":{"type":"quota","resource":"x","limitKey":"y"}}""")
                }
            assertNoLeakage(r.bodyAsText())
        }

    @Test
    fun `enforce malformed body does not leak serializer internals`() =
        billingTestApplication(
            routes = { machineAuthenticatedRoutes { enforceRoutes(enforceController) } },
        ) {
            val r =
                client.post("/internal/billing/enforce") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                    contentType(ContentType.Application.Json)
                    setBody("completely invalid body that will cause parse error!!!")
                }
            assertNoLeakage(r.bodyAsText())
        }

    // ─── Customer billing error responses ─────────────────────────────────────

    @Test
    fun `customer billing Polar 422 does not expose raw Polar error`() =
        billingTestApplication(
            routes = { machineAuthenticatedRoutes { customerBillingRoutes(customerController) } },
        ) {
            coEvery { customerService.updateBillingInfo(any()) } returns
                CustomerBillingResult.PolarFailure(
                    fallbackCode = "billing.customer_update_failed",
                    statusCode = 422,
                    errorBody = """{"detail":"Polar internal: customer pol_cu_abc has invalid tax_id format","type":"validation_error","fields":["tax_id"]}""",
                )

            val r =
                client.patch("/internal/billing/customer/billing-info") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"orgId":"org_1","taxId":"invalid"}""")
                }

            val text = r.bodyAsText()
            assertTrue(!text.contains("pol_cu_abc"), "Polar customer ID leaked")
            assertTrue(!text.contains("Polar internal"), "Polar internal error message leaked")
            assertTrue(!text.contains("validation_error"), "Polar error type leaked")
            assertTrue(!text.contains("tax_id"), "Polar field name leaked")

            // Verify the response has our safe fields
            val body = Json.parseToJsonElement(text).jsonObject
            assertNull(body["polarError"], "polarError field must not exist in response")
        }

    @Test
    fun `customer billing Polar 502 does not expose upstream infrastructure`() =
        billingTestApplication(
            routes = { machineAuthenticatedRoutes { customerBillingRoutes(customerController) } },
        ) {
            coEvery { customerService.updateBillingInfo(any()) } returns
                CustomerBillingResult.PolarFailure(
                    fallbackCode = "billing.customer_update_failed",
                    statusCode = 502,
                    errorBody = "upstream connect error or disconnect/reset before headers. reset reason: connection failure, transport failure reason: delayed connect error: 111",
                )

            val r =
                client.patch("/internal/billing/customer/billing-info") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"orgId":"org_1","email":"x@y.com"}""")
                }

            val text = r.bodyAsText()
            assertTrue(!text.contains("upstream connect"), "Infrastructure error leaked")
            assertTrue(!text.contains("transport failure"), "Transport details leaked")
            assertTrue(!text.contains("111"), "Error code leaked")
            assertNoLeakage(text)
        }

    @Test
    fun `customer billing Polar 500 does not expose Polar stack trace`() =
        billingTestApplication(
            routes = { machineAuthenticatedRoutes { customerBillingRoutes(customerController) } },
        ) {
            coEvery { customerService.updateBillingInfo(any()) } returns
                CustomerBillingResult.PolarFailure(
                    fallbackCode = "billing.customer_update_failed",
                    statusCode = 500,
                    errorBody = """{"error":"InternalServerError","message":"Unexpected error in CustomerService.update_billing at /app/polar/services/customer.py:142"}""",
                )

            val r =
                client.patch("/internal/billing/customer/billing-info") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"orgId":"org_1","billingName":"Test"}""")
                }

            val text = r.bodyAsText()
            assertTrue(!text.contains("customer.py"), "Polar file path leaked")
            assertTrue(!text.contains("CustomerService"), "Polar class name leaked")
            assertTrue(!text.contains("/app/polar"), "Polar app path leaked")
        }

    // ─── Webhook error responses ──────────────────────────────────────────────

    @Test
    fun `webhook 401 does not reveal HMAC implementation`() =
        billingTestApplication(
            routes = { polarWebhookRoutes(webhookController) },
        ) {
            coEvery { webhookService.receive(any(), any(), any(), any()) } returns PolarWebhookResult.InvalidSignature

            val r =
                client.post("/webhooks/polar") {
                    contentType(ContentType.Application.Json)
                    header("webhook-id", "wh_123")
                    header("webhook-timestamp", "1700000000")
                    header("webhook-signature", "v1,wrong")
                    setBody("""{"type":"test","data":{}}""")
                }

            val text = r.bodyAsText()
            assertTrue(!text.contains("HMAC", ignoreCase = true))
            assertTrue(!text.contains("SHA256", ignoreCase = true))
            assertTrue(!text.contains("secret", ignoreCase = true))
            assertTrue(!text.contains("expected", ignoreCase = true))
        }

    @Test
    fun `webhook 500 does not reveal handler implementation`() =
        billingTestApplication(
            routes = { polarWebhookRoutes(webhookController) },
        ) {
            coEvery { webhookService.receive(any(), any(), any(), any()) } returns PolarWebhookResult.HandlerError

            val r =
                client.post("/webhooks/polar") {
                    contentType(ContentType.Application.Json)
                    header("webhook-id", "wh_err")
                    header("webhook-timestamp", "1700000000")
                    header("webhook-signature", "v1,sig")
                    setBody("""{"type":"subscription.created","data":{"id":"sub_1"}}""")
                }

            assertNoLeakage(r.bodyAsText())
        }

    // ─── Auth error responses ─────────────────────────────────────────────────

    @Test
    fun `auth failure does not reveal which field failed (iss vs aud vs exp)`() =
        billingTestApplication(
            routes = { machineAuthenticatedRoutes { enforceRoutes(enforceController) } },
        ) {
            val r =
                client.post("/internal/billing/enforce") {
                    header(HttpHeaders.Authorization, "Bearer eyJ.eyJ.sig")
                    contentType(ContentType.Application.Json)
                    setBody("""{"orgId":"org_1","appSlug":"signal","check":{"type":"feature","feature":"x"}}""")
                }

            val text = r.bodyAsText()
            // Should not reveal which JWT claim failed
            assertTrue(!text.contains("issuer", ignoreCase = true) || text.contains("unauthenticated"))
            assertTrue(!text.contains("audience", ignoreCase = true) || text.contains("unauthenticated"))
            assertTrue(!text.contains("platform.apollodeploy.com"))
        }
}
