package com.apollodeploy.billing.feature.customer.api

import com.apollodeploy.billing.feature.customer.application.CustomerBillingService
import com.apollodeploy.billing.feature.customer.domain.CustomerBillingProfile
import com.apollodeploy.billing.feature.customer.domain.CustomerBillingResult
import com.apollodeploy.billing.feature.customer.domain.CustomerPaymentMethodsPage
import com.apollodeploy.billing.feature.customer.domain.ListCustomerPaymentMethodsResponse
import com.apollodeploy.billing.feature.customer.domain.UpdateCustomerBillingInfoResponse
import com.apollodeploy.billing.support.billingTestApplication
import com.apollodeploy.billing.support.machineAuthenticatedRoutes
import com.apollodeploy.billing.support.validServiceToken
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.string
import io.kotest.property.forAll
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
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

class CustomerBillingControllerTest {
    private val customerBillingService = mockk<CustomerBillingService>()
    private val controller = CustomerBillingController(customerBillingService)

    private val patchRequestBody = """{"orgId":"org_1","email":"test@example.com"}"""

    // ─────────────────────────────────────────────────────────────────────────
    // PATCH /internal/billing/customer/billing-info
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `PATCH billing-info without Authorization header returns HTTP 401`() =
        billingTestApplication(
            routes = { machineAuthenticatedRoutes { customerBillingRoutes(controller) } },
        ) {
            val response =
                client.patch("/internal/billing/customer/billing-info") {
                    contentType(ContentType.Application.Json)
                    setBody(patchRequestBody)
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `PATCH billing-info Success returns HTTP 200 with customer field`() =
        billingTestApplication(
            routes = { machineAuthenticatedRoutes { customerBillingRoutes(controller) } },
        ) {
            coEvery { customerBillingService.updateBillingInfo(any()) } returns
                CustomerBillingResult.Success(
                    UpdateCustomerBillingInfoResponse(CustomerBillingProfile(id = "cust_1")),
                )

            val response =
                client.patch("/internal/billing/customer/billing-info") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                    contentType(ContentType.Application.Json)
                    setBody(patchRequestBody)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertNotNull(body["customer"])
        }

    @Test
    fun `PATCH billing-info InvalidRequest returns HTTP 400 with code and message`() =
        billingTestApplication(
            routes = { machineAuthenticatedRoutes { customerBillingRoutes(controller) } },
        ) {
            coEvery { customerBillingService.updateBillingInfo(any()) } returns
                CustomerBillingResult.InvalidRequest("orgId is required")

            val response =
                client.patch("/internal/billing/customer/billing-info") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                    contentType(ContentType.Application.Json)
                    setBody(patchRequestBody)
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("billing.invalid_request", body["code"]?.jsonPrimitive?.content)
            assertNotNull(body["message"])
        }

    @Test
    fun `PATCH billing-info PolarFailure 404 returns HTTP 404 with all error fields`() =
        billingTestApplication(
            routes = { machineAuthenticatedRoutes { customerBillingRoutes(controller) } },
        ) {
            coEvery { customerBillingService.updateBillingInfo(any()) } returns
                CustomerBillingResult.PolarFailure(
                    fallbackCode = "billing.customer_update_failed",
                    statusCode = 404,
                    errorBody = "not found",
                )

            val response =
                client.patch("/internal/billing/customer/billing-info") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                    contentType(ContentType.Application.Json)
                    setBody(patchRequestBody)
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertNotNull(body["code"])
            assertNotNull(body["message"])
            assertNotNull(body["status"]) // Replaced polarStatus+polarError with sanitized 'status' field
        }

    @Test
    fun `PATCH billing-info PolarFailure 422 returns HTTP 422`() =
        billingTestApplication(
            routes = { machineAuthenticatedRoutes { customerBillingRoutes(controller) } },
        ) {
            coEvery { customerBillingService.updateBillingInfo(any()) } returns
                CustomerBillingResult.PolarFailure(
                    fallbackCode = "billing.customer_update_failed",
                    statusCode = 422,
                    errorBody = "unprocessable entity",
                )

            val response =
                client.patch("/internal/billing/customer/billing-info") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                    contentType(ContentType.Application.Json)
                    setBody(patchRequestBody)
                }

            assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        }

    @Test
    fun `PATCH billing-info PolarFailure 500 returns HTTP 502 BadGateway`() =
        billingTestApplication(
            routes = { machineAuthenticatedRoutes { customerBillingRoutes(controller) } },
        ) {
            coEvery { customerBillingService.updateBillingInfo(any()) } returns
                CustomerBillingResult.PolarFailure(
                    fallbackCode = "billing.customer_update_failed",
                    statusCode = 500,
                    errorBody = "internal server error",
                )

            val response =
                client.patch("/internal/billing/customer/billing-info") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                    contentType(ContentType.Application.Json)
                    setBody(patchRequestBody)
                }

            assertEquals(HttpStatusCode.BadGateway, response.status)
        }

    // ─────────────────────────────────────────────────────────────────────────
    // GET /internal/billing/customer/payment-methods
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `GET payment-methods without Authorization header returns HTTP 401`() =
        billingTestApplication(
            routes = { machineAuthenticatedRoutes { customerBillingRoutes(controller) } },
        ) {
            val response = client.get("/internal/billing/customer/payment-methods?orgId=org_1")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `GET payment-methods Success returns HTTP 200 with paymentMethods field`() =
        billingTestApplication(
            routes = { machineAuthenticatedRoutes { customerBillingRoutes(controller) } },
        ) {
            coEvery { customerBillingService.listPaymentMethods(any(), any(), any()) } returns
                CustomerBillingResult.Success(
                    ListCustomerPaymentMethodsResponse(CustomerPaymentMethodsPage()),
                )

            val response =
                client.get("/internal/billing/customer/payment-methods?orgId=org_1") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertNotNull(body["paymentMethods"])
        }

    @Test
    fun `GET payment-methods InvalidRequest returns HTTP 400`() =
        billingTestApplication(
            routes = { machineAuthenticatedRoutes { customerBillingRoutes(controller) } },
        ) {
            coEvery { customerBillingService.listPaymentMethods(any(), any(), any()) } returns
                CustomerBillingResult.InvalidRequest("orgId required")

            val response =
                client.get("/internal/billing/customer/payment-methods") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    // ─────────────────────────────────────────────────────────────────────────
    // DELETE /internal/billing/customer/payment-methods/{paymentMethodId}
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `DELETE payment-method without Authorization header returns HTTP 401`() =
        billingTestApplication(
            routes = { machineAuthenticatedRoutes { customerBillingRoutes(controller) } },
        ) {
            val response = client.delete("/internal/billing/customer/payment-methods/pm_abc?orgId=org_1")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `DELETE payment-method Success returns HTTP 204 with empty body`() =
        billingTestApplication(
            routes = { machineAuthenticatedRoutes { customerBillingRoutes(controller) } },
        ) {
            coEvery { customerBillingService.deletePaymentMethod(any(), any()) } returns
                CustomerBillingResult.Success(Unit)

            val response =
                client.delete("/internal/billing/customer/payment-methods/pm_abc?orgId=org_1") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                }

            assertEquals(HttpStatusCode.NoContent, response.status)
            assertEquals("", response.bodyAsText())
        }

    @Test
    fun `DELETE payment-method InvalidRequest returns HTTP 400 with billing_invalid_request code`() =
        billingTestApplication(
            routes = { machineAuthenticatedRoutes { customerBillingRoutes(controller) } },
        ) {
            coEvery { customerBillingService.deletePaymentMethod(any(), any()) } returns
                CustomerBillingResult.InvalidRequest("orgId required")

            val response =
                client.delete("/internal/billing/customer/payment-methods/pm_abc") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("billing.invalid_request", body["code"]?.jsonPrimitive?.content)
        }

    @Test
    fun `DELETE payment-method PolarFailure 404 returns HTTP 404`() =
        billingTestApplication(
            routes = { machineAuthenticatedRoutes { customerBillingRoutes(controller) } },
        ) {
            coEvery { customerBillingService.deletePaymentMethod(any(), any()) } returns
                CustomerBillingResult.PolarFailure(
                    fallbackCode = "billing.payment_method_delete_failed",
                    statusCode = 404,
                    errorBody = "not found",
                )

            val response =
                client.delete("/internal/billing/customer/payment-methods/pm_abc?orgId=org_1") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }

    // ─────────────────────────────────────────────────────────────────────────
    // Property 7: Customer InvalidRequest message passthrough
    //
    // For any non-blank message string, when a customer billing service method is stubbed to return
    // CustomerBillingResult.InvalidRequest(message), the HTTP response SHALL be 400,
    // the body code field SHALL equal "billing.invalid_request", and the body message field
    // SHALL equal the stub's message.
    //
    // **Validates: Requirements 7.3, 7.9, 7.12**
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `Property 7 - InvalidRequest message is passed through in response body`() =
        runBlocking {
            val nonBlankMessage = Arb.string(minSize = 1, maxSize = 50).filter { it.isNotBlank() }

            forAll(nonBlankMessage) { message ->
                var passed = false
                billingTestApplication(
                    routes = { machineAuthenticatedRoutes { customerBillingRoutes(controller) } },
                ) {
                    coEvery { customerBillingService.updateBillingInfo(any()) } returns
                        CustomerBillingResult.InvalidRequest(message)

                    val response =
                        client.patch("/internal/billing/customer/billing-info") {
                            header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                            contentType(ContentType.Application.Json)
                            setBody(patchRequestBody)
                        }

                    if (response.status == HttpStatusCode.BadRequest) {
                        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                        val codeMatch = body["code"]?.jsonPrimitive?.content == "billing.invalid_request"
                        val messageMatch = body["message"]?.jsonPrimitive?.content == message
                        passed = codeMatch && messageMatch
                    }
                }
                passed
            }
            Unit
        }

    // ─────────────────────────────────────────────────────────────────────────
    // Property 8: PolarFailure status code mapping
    //
    // For any CustomerBillingResult.PolarFailure(statusCode = s), the HTTP response status SHALL
    // follow: s == 400 → HTTP 400, s == 404 → HTTP 404, s == 422 → HTTP 422, and for any other
    // value of s → HTTP 502. The body SHALL contain code, message, polarStatus, and polarError fields.
    //
    // **Validates: Requirements 7.4, 7.5, 7.6, 7.13**
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `Property 8 - PolarFailure statusCode maps to correct HTTP status`() =
        runBlocking {
            forAll(Arb.element(400, 404, 422, 500, 503)) { polarStatus ->
                var passed = false
                billingTestApplication(
                    routes = { machineAuthenticatedRoutes { customerBillingRoutes(controller) } },
                ) {
                    coEvery { customerBillingService.updateBillingInfo(any()) } returns
                        CustomerBillingResult.PolarFailure(
                            fallbackCode = "billing.customer_update_failed",
                            statusCode = polarStatus,
                            errorBody = "polar error",
                        )

                    val response =
                        client.patch("/internal/billing/customer/billing-info") {
                            header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                            contentType(ContentType.Application.Json)
                            setBody(patchRequestBody)
                        }

                    val expectedStatus =
                        when (polarStatus) {
                            400 -> HttpStatusCode.BadRequest
                            404 -> HttpStatusCode.NotFound
                            422 -> HttpStatusCode.UnprocessableEntity
                            else -> HttpStatusCode.BadGateway
                        }

                    if (response.status == expectedStatus) {
                        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                        passed = body["code"] != null &&
                            body["message"] != null &&
                            body["status"] != null // sanitized: polarStatus+polarError removed for security
                    }
                }
                passed
            }
            Unit
        }
}
