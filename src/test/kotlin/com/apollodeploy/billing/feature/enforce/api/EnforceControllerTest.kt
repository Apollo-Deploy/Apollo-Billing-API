package com.apollodeploy.billing.feature.enforce.api

import com.apollodeploy.billing.feature.enforce.application.EnforceService
import com.apollodeploy.billing.feature.enforce.domain.BillingErrorResponse
import com.apollodeploy.billing.feature.enforce.domain.EnforceResult
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class EnforceControllerTest {

    private val enforceService = mockk<EnforceService>()
    private val controller = EnforceController(enforceService)

    private val requestBody = """{"orgId":"org_1","appSlug":"signal","check":{"type":"feature","feature":"deployments"}}"""

    @Test
    fun `POST enforce without Authorization header returns HTTP 401 with code field`() = billingTestApplication(
        routes = { noAuthInternalRoutes { enforceRoutes(controller) } },
    ) {
        val response = client.post("/internal/billing/enforce") {
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertNotNull(body["code"])
    }

    @Test
    fun `POST enforce with structurally invalid JWT returns HTTP 401`() = billingTestApplication(
        routes = { noAuthInternalRoutes { enforceRoutes(controller) } },
    ) {
        val response = client.post("/internal/billing/enforce") {
            header(HttpHeaders.Authorization, "Bearer this-is-not-a-valid-jwt")
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST enforce Allowed branch returns HTTP 200 with allowed true`() = billingTestApplication(
        routes = { noAuthInternalRoutes { enforceRoutes(controller) } },
    ) {
        coEvery { enforceService.enforce(any()) } returns EnforceResult.Allowed

        val response = client.post("/internal/billing/enforce") {
            header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["allowed"]?.jsonPrimitive?.content == "true")
    }

    @Test
    fun `POST enforce Rejected 402 returns HTTP 402 with code and message fields`() = billingTestApplication(
        routes = { noAuthInternalRoutes { enforceRoutes(controller) } },
    ) {
        coEvery { enforceService.enforce(any()) } returns EnforceResult.Rejected(
            statusCode = 402,
            error = BillingErrorResponse("billing.quota_exceeded", "Quota exceeded"),
        )

        val response = client.post("/internal/billing/enforce") {
            header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }

        assertEquals(HttpStatusCode.PaymentRequired, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertNotNull(body["code"])
        assertNotNull(body["message"])
    }

    @Test
    fun `POST enforce Rejected 404 returns HTTP 404`() = billingTestApplication(
        routes = { noAuthInternalRoutes { enforceRoutes(controller) } },
    ) {
        coEvery { enforceService.enforce(any()) } returns EnforceResult.Rejected(
            statusCode = 404,
            error = BillingErrorResponse("billing.no_subscription", "No active subscription found"),
        )

        val response = client.post("/internal/billing/enforce") {
            header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `POST enforce Rejected 422 returns HTTP 422`() = billingTestApplication(
        routes = { noAuthInternalRoutes { enforceRoutes(controller) } },
    ) {
        coEvery { enforceService.enforce(any()) } returns EnforceResult.Rejected(
            statusCode = 422,
            error = BillingErrorResponse("billing.unknown_app", "Unknown app slug: signal"),
        )

        val response = client.post("/internal/billing/enforce") {
            header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
    }

    @Test
    fun `POST enforce Rejected 500 returns HTTP 500`() = billingTestApplication(
        routes = { noAuthInternalRoutes { enforceRoutes(controller) } },
    ) {
        coEvery { enforceService.enforce(any()) } returns EnforceResult.Rejected(
            statusCode = 500,
            error = BillingErrorResponse("billing.internal_error", "Internal billing error"),
        )

        val response = client.post("/internal/billing/enforce") {
            header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }

        assertEquals(HttpStatusCode.InternalServerError, response.status)
    }

    /**
     * Property 4: Enforce status code passthrough
     *
     * For any statusCode in {402, 404, 422, 500}, when EnforceService.enforce is stubbed to return
     * EnforceResult.Rejected(statusCode, errorBody), the HTTP response status code SHALL equal statusCode.
     *
     * **Validates: Requirements 4.4, 4.5, 4.6, 4.7**
     */
    @Test
    fun `property - Rejected statusCode is passed through as HTTP response status`() = runBlocking {
        forAll(Arb.element(402, 404, 422, 500)) { statusCode ->
            var actualStatus = -1
            billingTestApplication(
                routes = { noAuthInternalRoutes { enforceRoutes(controller) } },
            ) {
                coEvery { enforceService.enforce(any()) } returns EnforceResult.Rejected(
                    statusCode = statusCode,
                    error = BillingErrorResponse("billing.test_error", "Test error"),
                )

                val response = client.post("/internal/billing/enforce") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }

                actualStatus = response.status.value
            }
            actualStatus == statusCode
        }
        Unit
    }
}
