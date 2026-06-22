package com.apollodeploy.billing.feature.usage.api

import com.apollodeploy.billing.feature.usage.application.UsageIngestService
import com.apollodeploy.billing.feature.usage.domain.UsageIngestResponse
import com.apollodeploy.billing.support.noAuthInternalRoutes
import com.apollodeploy.billing.support.billingTestApplication
import com.apollodeploy.billing.support.validServiceToken
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.forAll
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class UsageIngestControllerTest {

    private val usageIngestService = mockk<UsageIngestService>()
    private val controller = UsageIngestController(usageIngestService)

    private val requestBody = """{"orgId":"org_1","eventKey":"signal.automation.run","quantity":1}"""

    @Test
    fun `POST usage ingest without Authorization header returns HTTP 401`() = billingTestApplication(
        routes = { noAuthInternalRoutes { usageIngestRoutes(controller) } },
    ) {
        val response = client.post("/internal/billing/usage/ingest") {
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST usage ingest accepted true returns HTTP 200 with accepted true`() = billingTestApplication(
        routes = { noAuthInternalRoutes { usageIngestRoutes(controller) } },
    ) {
        coEvery { usageIngestService.ingest(any()) } returns UsageIngestResponse(accepted = true)

        val response = client.post("/internal/billing/usage/ingest") {
            header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(true, body["accepted"]?.jsonPrimitive?.boolean ?: false)
    }

    @Test
    fun `POST usage ingest accepted false returns HTTP 202 with accepted false`() = billingTestApplication(
        routes = { noAuthInternalRoutes { usageIngestRoutes(controller) } },
    ) {
        coEvery { usageIngestService.ingest(any()) } returns UsageIngestResponse(accepted = false, reason = "polar_unavailable")

        val response = client.post("/internal/billing/usage/ingest") {
            header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(false, body["accepted"]?.jsonPrimitive?.boolean ?: true)
    }

    /**
     * Property 9: Usage accepted flag drives HTTP status
     *
     * For any UsageIngestResponse(accepted = b), when UsageIngestService.ingest is stubbed to return it,
     * the HTTP response status SHALL be 200 if b == true and 202 if b == false.
     * The body `accepted` field SHALL equal b.
     *
     * **Validates: Requirements 8.2, 8.3**
     */
    @Test
    fun `property - accepted flag drives HTTP status code and body accepted field`() = runBlocking {
        forAll(Arb.element(true, false)) { accepted ->
            var passed = false
            billingTestApplication(
                routes = { noAuthInternalRoutes { usageIngestRoutes(controller) } },
            ) {
                coEvery { usageIngestService.ingest(any()) } returns UsageIngestResponse(accepted = accepted)

                val response = client.post("/internal/billing/usage/ingest") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }

                val expectedStatus = if (accepted) HttpStatusCode.OK else HttpStatusCode.Accepted
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                val bodyAccepted = body["accepted"]?.jsonPrimitive?.boolean

                passed = response.status == expectedStatus && bodyAccepted == accepted
            }
            passed
        }
        Unit
    }
}
