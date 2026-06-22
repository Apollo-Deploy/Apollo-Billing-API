package com.apollodeploy.billing.feature.checkout.api

import com.apollodeploy.billing.feature.checkout.application.CheckoutService
import com.apollodeploy.billing.feature.checkout.domain.CreateCheckoutRequest
import com.apollodeploy.billing.feature.checkout.domain.CreateCheckoutResponse
import com.apollodeploy.billing.feature.checkout.domain.CreateCheckoutResult
import com.apollodeploy.billing.support.noAuthInternalRoutes
import com.apollodeploy.billing.support.billingTestApplication
import com.apollodeploy.billing.support.validServiceToken
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CheckoutControllerEdgeCaseTest {

    private val checkoutService = mockk<CheckoutService>()
    private val controller = CheckoutController(checkoutService)

    private val successResponse = CreateCheckoutResult.Created(
        CreateCheckoutResponse(
            id = "chk_abc",
            url = "https://checkout.polar.sh/chk_abc",
            expiresAt = null,
            productKind = "subscription",
        ),
    )

    /**
     * Task 11.1 – all optional fields populated passes through to service
     *
     * A request body containing all optional fields (customerEmail, customerName,
     * successUrl, returnUrl, and metadata) must be accepted and result in HTTP 200.
     */
    @Test
    fun `POST checkout with all optional fields returns HTTP 200`() = billingTestApplication(
        routes = { noAuthInternalRoutes { checkoutRoutes(controller) } },
    ) {
        coEvery { checkoutService.createCheckout(any()) } returns successResponse

        val body = """
            {
              "orgId": "org_1",
              "appSlug": "signal",
              "productSlug": "signal-pro",
              "customerEmail": "user@example.com",
              "customerName": "Alice",
              "successUrl": "https://app.example.com/success",
              "returnUrl": "https://app.example.com/cancel",
              "metadata": {"key": "val"}
            }
        """.trimIndent()

        val response = client.post("/internal/billing/checkout") {
            header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }

    /**
     * Task 11.2 – no optional fields accepted → service called with empty metadata
     *
     * A request body with only the required fields (orgId, appSlug, productSlug)
     * must be accepted (HTTP 200) and the captured request metadata must be empty.
     */
    @Test
    fun `POST checkout with only required fields returns HTTP 200 and empty metadata`() = billingTestApplication(
        routes = { noAuthInternalRoutes { checkoutRoutes(controller) } },
    ) {
        val capturedRequest = slot<CreateCheckoutRequest>()
        coEvery { checkoutService.createCheckout(capture(capturedRequest)) } returns successResponse

        val body = """{"orgId":"org_1","appSlug":"signal","productSlug":"signal-pro"}"""

        val response = client.post("/internal/billing/checkout") {
            header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val metadata = capturedRequest.captured.metadata
        assertTrue(metadata.isEmpty(), "Expected metadata to be empty but was: $metadata")

        // Optional fields should be null
        assertNull(capturedRequest.captured.customerEmail)
        assertNull(capturedRequest.captured.customerName)
        assertNull(capturedRequest.captured.successUrl)
        assertNull(capturedRequest.captured.returnUrl)
    }

    /**
     * Task 11.3 – caller-supplied metadata round-trips through to service
     *
     * A request body with metadata={"campaign":"summer"} must result in the
     * captured CreateCheckoutRequest having metadata["campaign"] == "summer".
     */
    @Test
    fun `POST checkout with metadata routes campaign key to service`() = billingTestApplication(
        routes = { noAuthInternalRoutes { checkoutRoutes(controller) } },
    ) {
        val capturedRequest = slot<CreateCheckoutRequest>()
        coEvery { checkoutService.createCheckout(capture(capturedRequest)) } returns successResponse

        val body = """
            {
              "orgId": "org_1",
              "appSlug": "signal",
              "productSlug": "signal-pro",
              "metadata": {"campaign": "summer"}
            }
        """.trimIndent()

        val response = client.post("/internal/billing/checkout") {
            header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("summer", capturedRequest.captured.metadata["campaign"])
    }

    /**
     * Task 11.4 – missing productSlug returns HTTP 400
     *
     * A request body that omits the required `productSlug` field must be
     * rejected with HTTP 400 Bad Request.
     */
    @Test
    fun `POST checkout missing productSlug returns HTTP 400`() = billingTestApplication(
        routes = { noAuthInternalRoutes { checkoutRoutes(controller) } },
    ) {
        val body = """{"orgId":"org_1","appSlug":"signal"}"""

        val response = client.post("/internal/billing/checkout") {
            header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}
