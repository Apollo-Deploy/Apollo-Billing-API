package com.apollodeploy.billing.feature.customer.application

import com.apollodeploy.billing.feature.customer.domain.CustomerBillingResult
import com.apollodeploy.billing.feature.customer.domain.UpdateCustomerBillingInfoRequest
import com.apollodeploy.billing.feature.customer.infrastructure.persistence.CustomerBillingRepo
import com.apollodeploy.billing.infrastructure.polar.PolarCallResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CustomerBillingServiceTest {
    private val repo = mockk<CustomerBillingRepo>()
    private val auditLogClient = mockk<com.apollodeploy.billing.infrastructure.audit.AuditLogClient>(relaxed = true)
    private val service = CustomerBillingService(repo, auditLogClient)

    // ─────────────────────────────────────────────────────────────────────────
    // 4.1: updateBillingInfo with blank orgId (empty string) returns InvalidRequest
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `4_1 updateBillingInfo with empty orgId returns InvalidRequest without calling repo`() =
        runBlocking {
            val result = service.updateBillingInfo(UpdateCustomerBillingInfoRequest(orgId = "", email = "x@x.com"))

            assertIs<CustomerBillingResult.InvalidRequest>(result)
            assertEquals("orgId is required", result.message)
            coVerify(exactly = 0) { repo.updateCustomerBillingInfo(any(), any(), any(), any(), any(), any()) }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // 4.2: updateBillingInfo with whitespace-only orgId returns InvalidRequest
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `4_2 updateBillingInfo with whitespace-only orgId returns InvalidRequest without calling repo`() =
        runBlocking {
            val result = service.updateBillingInfo(UpdateCustomerBillingInfoRequest(orgId = "   ", email = "x@x.com"))

            assertIs<CustomerBillingResult.InvalidRequest>(result)
            assertEquals("orgId is required", result.message)
            coVerify(exactly = 0) { repo.updateCustomerBillingInfo(any(), any(), any(), any(), any(), any()) }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // 4.3: updateBillingInfo with valid orgId and all optional fields null returns InvalidRequest
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `4_3 updateBillingInfo with valid orgId and all optional fields null returns InvalidRequest`() =
        runBlocking {
            val result = service.updateBillingInfo(UpdateCustomerBillingInfoRequest(orgId = "org_1"))

            assertIs<CustomerBillingResult.InvalidRequest>(result)
            assertEquals("No billing fields provided", result.message)
            coVerify(exactly = 0) { repo.updateCustomerBillingInfo(any(), any(), any(), any(), any(), any()) }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // 4.4: updateBillingInfo with valid orgId and non-null email calls repo
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `4_4 updateBillingInfo with valid orgId and non-null email calls repo and returns Success`() =
        runBlocking {
            coEvery {
                repo.updateCustomerBillingInfo(any(), any(), any(), any(), any(), any())
            } returns PolarCallResult(value = buildJsonObject {}, statusCode = 200, errorBody = null)

            val result = service.updateBillingInfo(UpdateCustomerBillingInfoRequest(orgId = "org_1", email = "x@x.com"))

            assertIs<CustomerBillingResult.Success<*>>(result)
            coVerify(exactly = 1) { repo.updateCustomerBillingInfo(any(), any(), any(), any(), any(), any()) }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // 4.5: listPaymentMethods with null orgId returns InvalidRequest without calling repo
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `4_5 listPaymentMethods with null orgId returns InvalidRequest without calling repo`() =
        runBlocking {
            val result = service.listPaymentMethods(orgId = null, page = 1, limit = 10)

            assertIs<CustomerBillingResult.InvalidRequest>(result)
            coVerify(exactly = 0) { repo.listCustomerPaymentMethods(any(), any(), any()) }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // 4.6: listPaymentMethods with blank orgId returns InvalidRequest without calling repo
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `4_6 listPaymentMethods with blank orgId returns InvalidRequest without calling repo`() =
        runBlocking {
            val result = service.listPaymentMethods(orgId = "  ", page = 1, limit = 10)

            assertIs<CustomerBillingResult.InvalidRequest>(result)
            coVerify(exactly = 0) { repo.listCustomerPaymentMethods(any(), any(), any()) }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // 4.7: deletePaymentMethod with null orgId returns InvalidRequest without calling repo
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `4_7 deletePaymentMethod with null orgId returns InvalidRequest without calling repo`() =
        runBlocking {
            val result = service.deletePaymentMethod(orgId = null, paymentMethodId = "pm_abc")

            assertIs<CustomerBillingResult.InvalidRequest>(result)
            coVerify(exactly = 0) { repo.deleteCustomerPaymentMethod(any(), any()) }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // 4.8: deletePaymentMethod with non-blank orgId and blank paymentMethodId returns InvalidRequest
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `4_8 deletePaymentMethod with non-blank orgId and blank paymentMethodId returns InvalidRequest`() =
        runBlocking {
            val result = service.deletePaymentMethod(orgId = "org_1", paymentMethodId = "")

            assertIs<CustomerBillingResult.InvalidRequest>(result)
            coVerify(exactly = 0) { repo.deleteCustomerPaymentMethod(any(), any()) }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // 4.9: deletePaymentMethod with blank orgId returns InvalidRequest
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `4_9 deletePaymentMethod with blank orgId returns InvalidRequest`() =
        runBlocking {
            val result = service.deletePaymentMethod(orgId = "", paymentMethodId = "pm_abc")

            assertIs<CustomerBillingResult.InvalidRequest>(result)
            coVerify(exactly = 0) { repo.deleteCustomerPaymentMethod(any(), any()) }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // 4.10: updateBillingInfo with null-valued PolarCallResult returns PolarFailure without NPE
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `4_10 updateBillingInfo with null-valued PolarCallResult returns PolarFailure without NPE`() =
        runBlocking {
            coEvery {
                repo.updateCustomerBillingInfo(any(), any(), any(), any(), any(), any())
            } returns PolarCallResult(value = null, statusCode = null, errorBody = null)

            val result = service.updateBillingInfo(UpdateCustomerBillingInfoRequest(orgId = "org_1", email = "x@x.com"))

            assertIs<CustomerBillingResult.PolarFailure>(result)
            assertEquals(null, result.statusCode)
            assertEquals(null, result.errorBody)
        }
}
