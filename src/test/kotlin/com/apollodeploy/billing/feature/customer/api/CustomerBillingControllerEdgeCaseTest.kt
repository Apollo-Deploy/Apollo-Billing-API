package com.apollodeploy.billing.feature.customer.api

import com.apollodeploy.billing.feature.customer.application.CustomerBillingService
import com.apollodeploy.billing.feature.customer.domain.CustomerBillingResult
import com.apollodeploy.billing.support.billingTestApplication
import com.apollodeploy.billing.support.noAuthInternalRoutes
import com.apollodeploy.billing.support.validServiceToken
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class CustomerBillingControllerEdgeCaseTest {
    private val customerBillingService = mockk<CustomerBillingService>()
    private val controller = CustomerBillingController(customerBillingService)

    // ─────────────────────────────────────────────────────────────────────────
    // 12.1: GET payment-methods without orgId query param returns HTTP 400
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `12_1 - GET payment-methods without orgId query param returns HTTP 400`() =
        billingTestApplication(
            routes = { noAuthInternalRoutes { customerBillingRoutes(controller) } },
        ) {
            coEvery { customerBillingService.listPaymentMethods(null, any(), any()) } returns
                CustomerBillingResult.InvalidRequest("orgId query parameter is required")

            val response =
                client.get("/internal/billing/customer/payment-methods") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    // ─────────────────────────────────────────────────────────────────────────
    // 12.2: GET payment-methods with whitespace orgId returns HTTP 400
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `12_2 - GET payment-methods with whitespace orgId returns HTTP 400`() =
        billingTestApplication(
            routes = { noAuthInternalRoutes { customerBillingRoutes(controller) } },
        ) {
            coEvery { customerBillingService.listPaymentMethods(" ", any(), any()) } returns
                CustomerBillingResult.InvalidRequest("orgId query parameter is required")

            val response =
                client.get("/internal/billing/customer/payment-methods?orgId=%20") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    // ─────────────────────────────────────────────────────────────────────────
    // 12.3: DELETE payment-method without orgId query param returns HTTP 400
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `12_3 - DELETE payment-method without orgId query param returns HTTP 400`() =
        billingTestApplication(
            routes = { noAuthInternalRoutes { customerBillingRoutes(controller) } },
        ) {
            coEvery { customerBillingService.deletePaymentMethod(null, any()) } returns
                CustomerBillingResult.InvalidRequest("orgId query parameter and paymentMethodId path parameter are required")

            val response =
                client.delete("/internal/billing/customer/payment-methods/pm_abc") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    // ─────────────────────────────────────────────────────────────────────────
    // 12.4: PATCH billing-info with blank orgId body returns HTTP 400
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `12_4 - PATCH billing-info with blank orgId returns HTTP 400`() =
        billingTestApplication(
            routes = { noAuthInternalRoutes { customerBillingRoutes(controller) } },
        ) {
            coEvery { customerBillingService.updateBillingInfo(any()) } returns
                CustomerBillingResult.InvalidRequest("orgId is required")

            val response =
                client.patch("/internal/billing/customer/billing-info") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"orgId":"  ","email":"x@x.com"}""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }

    // ─────────────────────────────────────────────────────────────────────────
    // 12.5: PolarFailure with null statusCode returns HTTP 502 with non-blank
    //       code field and no NPE
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `12_5 - PolarFailure with null statusCode returns HTTP 502 with non-blank code field`() =
        billingTestApplication(
            routes = { noAuthInternalRoutes { customerBillingRoutes(controller) } },
        ) {
            coEvery { customerBillingService.updateBillingInfo(any()) } returns
                CustomerBillingResult.PolarFailure(
                    fallbackCode = "billing.customer_update_failed",
                    statusCode = null,
                    errorBody = null,
                )

            val response =
                client.patch("/internal/billing/customer/billing-info") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                    contentType(ContentType.Application.Json)
                    setBody("""{"orgId":"org_1","email":"x@x.com"}""")
                }

            assertEquals(HttpStatusCode.BadGateway, response.status)

            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            val code = body["code"]?.jsonPrimitive?.content
            assertNotNull(code)
            assertFalse(code.isBlank(), "code field must not be blank")
        }

    // ─────────────────────────────────────────────────────────────────────────
    // 12.6: PATCH billing-info missing orgId field returns HTTP 400
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `12_6 - PATCH billing-info missing orgId field returns HTTP 400`() =
        billingTestApplication(
            routes = { noAuthInternalRoutes { customerBillingRoutes(controller) } },
        ) {
            val response =
                client.patch("/internal/billing/customer/billing-info") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                    contentType(ContentType.Application.Json)
                    setBody("{}")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
}
