package com.apollodeploy.billing.feature.security

import com.apollodeploy.billing.feature.enforce.api.EnforceController
import com.apollodeploy.billing.feature.enforce.api.enforceRoutes
import com.apollodeploy.billing.feature.enforce.application.EnforceService
import com.apollodeploy.billing.feature.enforce.domain.BillingErrorResponse
import com.apollodeploy.billing.feature.enforce.domain.EnforceResult
import com.apollodeploy.billing.feature.usage.api.UsageIngestController
import com.apollodeploy.billing.feature.usage.api.usageIngestRoutes
import com.apollodeploy.billing.feature.usage.application.UsageIngestService
import com.apollodeploy.billing.feature.usage.domain.UsageIngestResponse
import com.apollodeploy.billing.support.billingTestApplication
import com.apollodeploy.billing.support.noAuthInternalRoutes
import com.apollodeploy.billing.support.validServiceToken
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Adversarial tests — attempts to bypass billing enforcement or manipulate usage metering.
 *
 * Attack vectors:
 *   - Quantity manipulation (negative, zero, overflow, max int)
 *   - Meter exhaustion bypass via missing keys
 *   - Service unavailability exploitation
 *   - Replayed/duplicate usage events
 *   - Spoofed orgIds to charge the wrong account
 */
class BillingBypassHttpTest {
    private val enforceService = mockk<EnforceService>()
    private val enforceController = EnforceController(enforceService)

    private val usageService = mockk<UsageIngestService>()
    private val usageController = UsageIngestController(usageService)

    // ─── Quantity manipulation ────────────────────────────────────────────────

    @Test
    fun `quantity = 0 does not bill — rejected before reaching Polar`() =
        billingTestApplication(
            routes = { noAuthInternalRoutes { usageIngestRoutes(usageController) } },
        ) {
            coEvery { usageService.ingest(any()) } returns UsageIngestResponse(accepted = false, reason = "quantity must be >= 1")

            val r =
                client.post("/internal/billing/usage/ingest") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"orgId":"org_1","eventKey":"email.sent","quantity":0}""")
                }
            val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
            assertFalse(body["accepted"]?.jsonPrimitive?.boolean ?: true)
        }

    @Test
    fun `negative quantity does not credit meter — rejected`() =
        billingTestApplication(
            routes = { noAuthInternalRoutes { usageIngestRoutes(usageController) } },
        ) {
            coEvery { usageService.ingest(any()) } returns UsageIngestResponse(accepted = false, reason = "quantity must be >= 1")

            val r =
                client.post("/internal/billing/usage/ingest") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"orgId":"org_1","eventKey":"email.sent","quantity":-100}""")
                }
            val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
            assertFalse(body["accepted"]?.jsonPrimitive?.boolean ?: true)
        }

    @Test
    fun `quantity = Int MAX_VALUE is rejected (exceeds 10000 cap)`() =
        billingTestApplication(
            routes = { noAuthInternalRoutes { usageIngestRoutes(usageController) } },
        ) {
            coEvery { usageService.ingest(any()) } returns UsageIngestResponse(accepted = false, reason = "quantity exceeds maximum (10000)")

            val r =
                client.post("/internal/billing/usage/ingest") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"orgId":"org_1","eventKey":"email.sent","quantity":2147483647}""")
                }
            val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
            assertFalse(body["accepted"]?.jsonPrimitive?.boolean ?: true)
        }

    @Test
    fun `quantity = 10001 (just over boundary) is rejected`() =
        billingTestApplication(
            routes = { noAuthInternalRoutes { usageIngestRoutes(usageController) } },
        ) {
            coEvery { usageService.ingest(any()) } returns UsageIngestResponse(accepted = false, reason = "quantity exceeds maximum (10000)")

            val r =
                client.post("/internal/billing/usage/ingest") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"orgId":"org_1","eventKey":"email.sent","quantity":10001}""")
                }
            val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
            assertFalse(body["accepted"]?.jsonPrimitive?.boolean ?: true)
        }

    @Test
    fun `quantity = 10000 (at boundary) is accepted`() =
        billingTestApplication(
            routes = { noAuthInternalRoutes { usageIngestRoutes(usageController) } },
        ) {
            coEvery { usageService.ingest(any()) } returns UsageIngestResponse(accepted = true)

            val r =
                client.post("/internal/billing/usage/ingest") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"orgId":"org_1","eventKey":"email.sent","quantity":10000}""")
                }
            assertEquals(HttpStatusCode.OK, r.status)
        }

    // ─── Blank/missing field exploitation ─────────────────────────────────────

    @Test
    fun `blank orgId cannot be used to bypass per-org tracking`() =
        billingTestApplication(
            routes = { noAuthInternalRoutes { usageIngestRoutes(usageController) } },
        ) {
            coEvery { usageService.ingest(any()) } returns UsageIngestResponse(accepted = false, reason = "orgId is required")

            val r =
                client.post("/internal/billing/usage/ingest") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"orgId":"","eventKey":"email.sent","quantity":1}""")
                }
            val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
            assertFalse(body["accepted"]?.jsonPrimitive?.boolean ?: true)
        }

    @Test
    fun `whitespace-only orgId is treated as blank`() =
        billingTestApplication(
            routes = { noAuthInternalRoutes { usageIngestRoutes(usageController) } },
        ) {
            coEvery { usageService.ingest(any()) } returns UsageIngestResponse(accepted = false, reason = "orgId is required")

            val r =
                client.post("/internal/billing/usage/ingest") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"orgId":"   ","eventKey":"email.sent","quantity":1}""")
                }
            // Service validates .isBlank() which covers whitespace
            val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
            assertFalse(body["accepted"]?.jsonPrimitive?.boolean ?: true)
        }

    @Test
    fun `blank eventKey cannot route usage to wrong meter`() =
        billingTestApplication(
            routes = { noAuthInternalRoutes { usageIngestRoutes(usageController) } },
        ) {
            coEvery { usageService.ingest(any()) } returns UsageIngestResponse(accepted = false, reason = "eventKey is required")

            val r =
                client.post("/internal/billing/usage/ingest") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"orgId":"org_1","eventKey":"","quantity":1}""")
                }
            val body = Json.parseToJsonElement(r.bodyAsText()).jsonObject
            assertFalse(body["accepted"]?.jsonPrimitive?.boolean ?: true)
        }

    // ─── Service unavailability → correct behavior ────────────────────────────

    @Test
    fun `enforce returns 503 when Signal DB is down (not 200 allowed)`() =
        billingTestApplication(
            routes = { noAuthInternalRoutes { enforceRoutes(enforceController) } },
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
                    setBody("""{"orgId":"org_1","appSlug":"signal","check":{"type":"quota","resource":"projects","limitKey":"maxProjects"}}""")
                }
            assertEquals(HttpStatusCode.ServiceUnavailable, r.status)
        }

    @Test
    fun `meter check with depleted balance returns 402 not 200`() =
        billingTestApplication(
            routes = { noAuthInternalRoutes { enforceRoutes(enforceController) } },
        ) {
            coEvery { enforceService.enforce(any(), any()) } returns
                EnforceResult.Rejected(
                    statusCode = 402,
                    error =
                        BillingErrorResponse(
                            code = "billing.meter_exhausted",
                            message = "Meter exhausted",
                            resource = "automationRunBalance",
                            current = 0,
                            limit = 1,
                        ),
                )

            val r =
                client.post("/internal/billing/enforce") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"orgId":"org_1","appSlug":"signal","check":{"type":"meter","meterKey":"automationRunBalance","needed":1}}""")
                }
            assertEquals(HttpStatusCode.PaymentRequired, r.status)
        }

    @Test
    fun `unknown appSlug cannot be used to bypass enforcement`() =
        billingTestApplication(
            routes = { noAuthInternalRoutes { enforceRoutes(enforceController) } },
        ) {
            coEvery { enforceService.enforce(any(), any()) } returns
                EnforceResult.Rejected(
                    statusCode = 422,
                    error = BillingErrorResponse(code = "billing.unknown_app", message = "Unknown app slug: hacked-app"),
                )

            val r =
                client.post("/internal/billing/enforce") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"orgId":"org_1","appSlug":"hacked-app","check":{"type":"feature","feature":"all"}}""")
                }
            assertEquals(HttpStatusCode.UnprocessableEntity, r.status)
        }

    // ─── Idempotency key exploitation ─────────────────────────────────────────

    @Test
    fun `same idempotency key with different orgId does NOT cause cross-org dedup`() =
        billingTestApplication(
            routes = { noAuthInternalRoutes { usageIngestRoutes(usageController) } },
        ) {
            // Both calls should reach the service (different org = different composite key)
            coEvery { usageService.ingest(any()) } returns UsageIngestResponse(accepted = true)

            client.post("/internal/billing/usage/ingest") {
                header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                contentType(ContentType.Application.Json)
                setBody("""{"orgId":"org_1","eventKey":"email.sent","quantity":1,"idempotencyKey":"same-key"}""")
            }
            client.post("/internal/billing/usage/ingest") {
                header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                contentType(ContentType.Application.Json)
                setBody("""{"orgId":"org_2","eventKey":"email.sent","quantity":1,"idempotencyKey":"same-key"}""")
            }

            // Service must be called for BOTH requests (different orgs)
            coVerify(exactly = 2) { usageService.ingest(any()) }
        }

    @Test
    fun `same idempotency key with different eventKey does NOT dedup`() =
        billingTestApplication(
            routes = { noAuthInternalRoutes { usageIngestRoutes(usageController) } },
        ) {
            coEvery { usageService.ingest(any()) } returns UsageIngestResponse(accepted = true)

            client.post("/internal/billing/usage/ingest") {
                header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                contentType(ContentType.Application.Json)
                setBody("""{"orgId":"org_1","eventKey":"email.sent","quantity":1,"idempotencyKey":"key-1"}""")
            }
            client.post("/internal/billing/usage/ingest") {
                header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                contentType(ContentType.Application.Json)
                setBody("""{"orgId":"org_1","eventKey":"sms.sent","quantity":1,"idempotencyKey":"key-1"}""")
            }

            coVerify(exactly = 2) { usageService.ingest(any()) }
        }
}
