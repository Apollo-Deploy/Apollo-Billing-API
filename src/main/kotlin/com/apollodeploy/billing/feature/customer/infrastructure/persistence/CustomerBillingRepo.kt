package com.apollodeploy.billing.feature.customer.infrastructure.persistence

import com.apollodeploy.billing.infrastructure.polar.PolarBillingAddressInput
import com.apollodeploy.billing.infrastructure.polar.PolarCallResult
import com.apollodeploy.billing.infrastructure.polar.PolarClient
import kotlinx.serialization.json.JsonObject

class CustomerBillingRepo(
    private val polarClient: PolarClient,
) {
    suspend fun updateCustomerBillingInfo(
        orgId: String,
        email: String?,
        billingName: String?,
        billingAddress: PolarBillingAddressInput?,
        taxId: String?,
        defaultPaymentMethodId: String?,
    ): PolarCallResult<JsonObject> =
        polarClient.updateCustomerBillingInfo(
            orgId = orgId,
            email = email,
            billingName = billingName,
            billingAddress = billingAddress,
            taxId = taxId,
            defaultPaymentMethodId = defaultPaymentMethodId,
        )

    suspend fun listCustomerPaymentMethods(
        orgId: String,
        page: Int,
        limit: Int,
    ): PolarCallResult<JsonObject> = polarClient.listCustomerPaymentMethods(orgId, page, limit)

    suspend fun deleteCustomerPaymentMethod(
        orgId: String,
        paymentMethodId: String,
    ): PolarCallResult<Unit> = polarClient.deleteCustomerPaymentMethod(orgId, paymentMethodId)
}
