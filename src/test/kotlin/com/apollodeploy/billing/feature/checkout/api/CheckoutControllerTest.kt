package com.apollodeploy.billing.feature.checkout.api

import com.apollodeploy.billing.feature.checkout.application.CheckoutService
import com.apollodeploy.billing.feature.checkout.domain.CreateCheckoutRequest
import com.apollodeploy.billing.feature.checkout.domain.CreateCheckoutResponse
import com.apollodeploy.billing.feature.checkout.domain.CreateCheckoutResult
import com.apollodeploy.billing.support.noAuthInternalRoutes
import com.apollodeploy.billing.support.billingTestApplication
import com.apollodeploy.billing.support.validServiceToken
import io.kotest.property.Arb
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.string
import io.kotest.property.forAll
import kotlinx.coroutines.runBlocking
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class CheckoutControllerTest {

    private val checkoutService = mockk<CheckoutService>()
    private val controller = CheckoutController(checkoutService)

    private val requestBody = """{"orgId":"org_1","appSlug":"signal","productSlug":"signal-pro"}"""

    @Test
    fun `POST checkout without Authorization header returns HTTP 401`() = billingTestApplication(
        routes = { noAuthInternalRoutes { checkoutRoutes(controller) } },
    ) {
        val response = client.post("/internal/billing/checkout") {
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `POST checkout Created returns HTTP 200 with id, url, productKind fields`() = billingTestApplication(
        routes = { noAuthInternalRoutes { checkoutRoutes(controller) } },
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

        val response = client.post("/internal/billing/checkout") {
            header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("chk_123", body["id"]?.jsonPrimitive?.content)
        assertEquals("https://checkout.polar.sh/chk_123", body["url"]?.jsonPrimitive?.content)
        assertEquals("subscription", body["productKind"]?.jsonPrimitive?.content)
    }

    @Test
    fun `POST checkout UnknownProduct returns HTTP 422 with billing_unknown_product code`() = billingTestApplication(
        routes = { noAuthInternalRoutes { checkoutRoutes(controller) } },
    ) {
        coEvery { checkoutService.createCheckout(any()) } returns
            CreateCheckoutResult.UnknownProduct(appSlug = "signal", productSlug = "signal-pro")

        val response = client.post("/internal/billing/checkout") {
            header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("billing.unknown_product", body["code"]?.jsonPrimitive?.content)
    }

    @Test
    fun `POST checkout Unavailable returns HTTP 502 with billing_checkout_unavailable code`() = billingTestApplication(
        routes = { noAuthInternalRoutes { checkoutRoutes(controller) } },
    ) {
        coEvery { checkoutService.createCheckout(any()) } returns CreateCheckoutResult.Unavailable

        val response = client.post("/internal/billing/checkout") {
            header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }

        assertEquals(HttpStatusCode.BadGateway, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("billing.checkout_unavailable", body["code"]?.jsonPrimitive?.content)
    }

    /**
     * Property 6: Checkout Created response preserves id, url, productKind
     *
     * For any CreateCheckoutResponse with arbitrary id, url, and productKind strings,
     * when CheckoutService.createCheckout is stubbed to return CreateCheckoutResult.Created(response),
     * the JSON body fields id, url, and productKind SHALL equal the corresponding values in the stub response.
     *
     * **Validates: Requirements 6.2**
     */
    @Test
    fun `property - Created response preserves id, url and productKind`() = runBlocking {
        val nonBlankString = Arb.string(minSize = 1, maxSize = 64).filter { it.isNotBlank() }

        forAll(nonBlankString, nonBlankString, nonBlankString) { id, url, productKind ->
            var passed = false
            billingTestApplication(
                routes = { noAuthInternalRoutes { checkoutRoutes(controller) } },
            ) {
                coEvery { checkoutService.createCheckout(any<CreateCheckoutRequest>()) } returns
                    CreateCheckoutResult.Created(
                        CreateCheckoutResponse(
                            id = id,
                            url = url,
                            expiresAt = null,
                            productKind = productKind,
                        ),
                    )

                val response = client.post("/internal/billing/checkout") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }

                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                passed =
                    response.status == HttpStatusCode.OK &&
                    body["id"]?.jsonPrimitive?.content == id &&
                    body["url"]?.jsonPrimitive?.content == url &&
                    body["productKind"]?.jsonPrimitive?.content == productKind
            }
            passed
        }
    }
}
