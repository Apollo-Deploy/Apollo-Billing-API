package com.apollodeploy.billing.feature.usage.api

import com.apollodeploy.billing.feature.usage.application.UsageIngestService
import com.apollodeploy.billing.feature.usage.domain.UsageIngestResponse
import com.apollodeploy.billing.support.billingTestApplication
import com.apollodeploy.billing.support.machineAuthenticatedRoutes
import com.apollodeploy.billing.support.validServiceToken
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertTrue

class UsageIngestControllerEdgeCaseTest {
    private val usageIngestService = mockk<UsageIngestService>()
    private val controller = UsageIngestController(usageIngestService)

    // 13.1: quantity = 0 is valid → HTTP 200 or 202
    @Test
    fun `POST usage ingest with quantity 0 returns HTTP 200 or 202`() =
        billingTestApplication(
            routes = { machineAuthenticatedRoutes { usageIngestRoutes(controller) } },
        ) {
            coEvery { usageIngestService.ingest(any()) } returns UsageIngestResponse(accepted = true, reason = null)

            val response =
                client.post("/internal/billing/usage/ingest") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"orgId":"org_1","eventKey":"email.sent","quantity":0}""")
                }

            assertTrue(
                response.status == HttpStatusCode.OK || response.status == HttpStatusCode.Accepted,
                "Expected HTTP 200 or 202 but got ${response.status}",
            )
        }

    // 13.2: blank orgId passes HTTP boundary → service is called (not rejected at HTTP layer)
    @Test
    fun `POST usage ingest with blank orgId is not rejected at HTTP boundary`() =
        billingTestApplication(
            routes = { machineAuthenticatedRoutes { usageIngestRoutes(controller) } },
        ) {
            coEvery { usageIngestService.ingest(any()) } returns UsageIngestResponse(accepted = true, reason = null)

            val response =
                client.post("/internal/billing/usage/ingest") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"orgId":"  ","eventKey":"email.sent","quantity":1}""")
                }

            assertTrue(
                response.status == HttpStatusCode.OK || response.status == HttpStatusCode.Accepted,
                "Expected HTTP 200 or 202 but got ${response.status}",
            )
            coVerify(exactly = 1) { usageIngestService.ingest(any()) }
        }

    // 13.3: missing required eventKey returns HTTP 400
    @Test
    fun `POST usage ingest missing eventKey returns HTTP 400`() =
        billingTestApplication(
            routes = { machineAuthenticatedRoutes { usageIngestRoutes(controller) } },
        ) {
            val response =
                client.post("/internal/billing/usage/ingest") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"orgId":"org_1","quantity":1}""")
                }

            assertTrue(
                response.status == HttpStatusCode.BadRequest,
                "Expected HTTP 400 but got ${response.status}",
            )
        }

    // 13.4: missing required orgId field returns HTTP 400
    @Test
    fun `POST usage ingest missing orgId returns HTTP 400`() =
        billingTestApplication(
            routes = { machineAuthenticatedRoutes { usageIngestRoutes(controller) } },
        ) {
            val response =
                client.post("/internal/billing/usage/ingest") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"eventKey":"email.sent","quantity":1}""")
                }

            assertTrue(
                response.status == HttpStatusCode.BadRequest,
                "Expected HTTP 400 but got ${response.status}",
            )
        }
}
