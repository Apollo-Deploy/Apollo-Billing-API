package com.apollodeploy.billing.feature.enforce.api

import com.apollodeploy.billing.feature.enforce.application.EnforceService
import com.apollodeploy.billing.feature.enforce.domain.BillingCheck
import com.apollodeploy.billing.feature.enforce.domain.BillingErrorResponse
import com.apollodeploy.billing.feature.enforce.domain.EnforceRequest
import com.apollodeploy.billing.feature.enforce.domain.EnforceResult
import com.apollodeploy.billing.support.billingTestApplication
import com.apollodeploy.billing.support.machineAuthenticatedRoutes
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
import io.mockk.mockk
import io.mockk.slot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EnforceControllerEdgeCaseTest {
    private val enforceService = mockk<EnforceService>()
    private val controller = EnforceController(enforceService)

    private val validBody =
        """{"orgId":"org_1","appSlug":"signal","check":{"type":"feature","feature":"deployments"}}"""

    // ─── 9.1–9.4: JWT validation edge cases removed (now using OAuth M2M) ───

    // ─── 9.5–9.7: BillingCheck deserialisation ────────────────────────────

    @Test
    fun `9_5 - quota body deserialises and preserves its concrete fields`() =
        billingTestApplication(
            routes = { machineAuthenticatedRoutes { enforceRoutes(controller) } },
        ) {
            val capturedRequest = slot<EnforceRequest>()
            coEvery { enforceService.enforce(capture(capturedRequest), any()) } returns EnforceResult.Allowed

            val response =
                client.post("/internal/billing/enforce") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"orgId":"org_1","appSlug":"signal","check":{"type":"quota","resource":"projects","limitKey":"maxProjects"}}""",
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val check = capturedRequest.captured.check
            assertEquals("quota", check.type)
            assertEquals("projects", check.resource)
            assertEquals("maxProjects", check.limitKey)
        }

    @Test
    fun `9_6 - meter body deserialises and preserves its concrete fields`() =
        billingTestApplication(
            routes = { machineAuthenticatedRoutes { enforceRoutes(controller) } },
        ) {
            val capturedRequest = slot<EnforceRequest>()
            coEvery { enforceService.enforce(capture(capturedRequest), any()) } returns EnforceResult.Allowed

            val response =
                client.post("/internal/billing/enforce") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"orgId":"org_1","appSlug":"signal","check":{"type":"meter","meterKey":"automationRunBalance","needed":1}}""",
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val check = capturedRequest.captured.check
            assertEquals("meter", check.type)
            assertEquals("automationRunBalance", check.meterKey)
            assertEquals(1, check.needed)
        }

    @Test
    fun `9_7 - feature body deserialises and preserves its concrete fields`() =
        billingTestApplication(
            routes = { machineAuthenticatedRoutes { enforceRoutes(controller) } },
        ) {
            val capturedRequest = slot<EnforceRequest>()
            coEvery { enforceService.enforce(capture(capturedRequest), any()) } returns EnforceResult.Allowed

            val response =
                client.post("/internal/billing/enforce") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                    contentType(ContentType.Application.Json)
                    setBody(
                        """{"orgId":"org_1","appSlug":"signal","check":{"type":"feature","feature":"multiRegion"}}""",
                    )
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val check = capturedRequest.captured.check
            assertEquals("feature", check.type)
            assertEquals("multiRegion", check.feature)
        }

    // ─── 9.8–9.9: Rejected 402 response body fields ───────────────────────

    @Test
    fun `9_8 - Rejected 402 response body contains resource, current, limit fields`() =
        billingTestApplication(
            routes = { machineAuthenticatedRoutes { enforceRoutes(controller) } },
        ) {
            coEvery { enforceService.enforce(any(), any()) } returns
                EnforceResult.Rejected(
                    statusCode = 402,
                    error =
                        BillingErrorResponse(
                            code = "billing.quota_exceeded",
                            message = "Quota exceeded",
                            resource = "projects",
                            current = 5,
                            limit = 5,
                        ),
                )

            val response =
                client.post("/internal/billing/enforce") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                    contentType(ContentType.Application.Json)
                    setBody(validBody)
                }

            assertEquals(HttpStatusCode.PaymentRequired, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertNotNull(body["resource"], "Expected 'resource' field in response body")
            assertNotNull(body["current"], "Expected 'current' field in response body")
            assertNotNull(body["limit"], "Expected 'limit' field in response body")
        }

    @Test
    fun `9_9 - Rejected 402 response body contains feature and currentPlan fields`() =
        billingTestApplication(
            routes = { machineAuthenticatedRoutes { enforceRoutes(controller) } },
        ) {
            coEvery { enforceService.enforce(any(), any()) } returns
                EnforceResult.Rejected(
                    statusCode = 402,
                    error =
                        BillingErrorResponse(
                            code = "billing.feature_not_available",
                            message = "Feature not available on your plan",
                            feature = "multiRegion",
                            currentPlan = "signal-spark",
                        ),
                )

            val response =
                client.post("/internal/billing/enforce") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                    contentType(ContentType.Application.Json)
                    setBody(validBody)
                }

            assertEquals(HttpStatusCode.PaymentRequired, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertNotNull(body["feature"], "Expected 'feature' field in response body")
            assertNotNull(body["currentPlan"], "Expected 'currentPlan' field in response body")
        }

    // ─── 9.10–9.12: Request body validation ──────────────────────────────

    @Test
    fun `9_10 - empty body with valid JWT returns 4xx (not 200 and not 500)`() =
        billingTestApplication(
            routes = { machineAuthenticatedRoutes { enforceRoutes(controller) } },
        ) {
            val response =
                client.post("/internal/billing/enforce") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                    contentType(ContentType.Application.Json)
                    setBody("")
                }

            val status = response.status.value
            assertTrue(status in 400..499, "Expected 4xx status but got $status")
        }

    @Test
    fun `9_11 - JSON body with missing required fields returns HTTP 400`() =
        billingTestApplication(
            routes = { machineAuthenticatedRoutes { enforceRoutes(controller) } },
        ) {
            val response =
                client.post("/internal/billing/enforce") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    @Test
    fun `9_12 - 1000-character orgId passes through without HTTP error from length alone`() =
        billingTestApplication(
            routes = { machineAuthenticatedRoutes { enforceRoutes(controller) } },
        ) {
            coEvery { enforceService.enforce(any(), any()) } returns EnforceResult.Allowed

            val longOrgId = "o".repeat(1000)
            val body =
                """{"orgId":"$longOrgId","appSlug":"signal","check":{"type":"feature","feature":"deployments"}}"""

            val response =
                client.post("/internal/billing/enforce") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }

            // Must not be 4xx or 5xx caused purely by the orgId length
            val status = response.status.value
            assertTrue(status !in 400..599, "Expected success but got HTTP $status for 1000-char orgId")
        }
}
