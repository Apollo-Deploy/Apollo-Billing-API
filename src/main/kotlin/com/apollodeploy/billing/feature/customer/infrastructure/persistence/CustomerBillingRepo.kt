package com.apollodeploy.billing.feature.customer.infrastructure.persistence

import com.apollodeploy.billing.infrastructure.polar.PolarClient
import com.apollodeploy.billing.infrastructure.polar.model.PolarBillingAddressInput
import com.apollodeploy.billing.infrastructure.polar.model.PolarCallResult
import com.apollodeploy.billing.infrastructure.polar.model.PolarCustomerSession
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
        externalMemberId: String? = null,
    ): PolarCallResult<JsonObject> =
        polarClient.updateCustomerBillingInfo(
            orgId = orgId,
            email = email,
            billingName = billingName,
            billingAddress = billingAddress,
            taxId = taxId,
            defaultPaymentMethodId = defaultPaymentMethodId,
            externalMemberId = externalMemberId,
        )

    suspend fun listCustomerPaymentMethods(
        orgId: String,
        page: Int,
        limit: Int,
        externalMemberId: String? = null,
    ): PolarCallResult<JsonObject> = polarClient.listCustomerPaymentMethods(orgId, page, limit, externalMemberId)

    suspend fun deleteCustomerPaymentMethod(
        orgId: String,
        paymentMethodId: String,
        externalMemberId: String? = null,
    ): PolarCallResult<Unit> = polarClient.deleteCustomerPaymentMethod(orgId, paymentMethodId, externalMemberId)

    suspend fun createCustomerPortalSession(
        orgId: String,
        returnUrl: String? = null,
        externalMemberId: String? = null,
    ): PolarCallResult<PolarCustomerSession> = polarClient.createCustomerSession(orgId, returnUrl, externalMemberId)

    suspend fun provisionCustomer(
        orgId: String,
        name: String,
        ownerEmail: String,
        ownerMemberId: String? = null,
        ownerName: String? = null,
        billingEmail: String? = null,
    ): PolarCallResult<JsonObject> =
        polarClient.createTeamCustomer(
            orgId = orgId,
            name = name,
            ownerEmail = ownerEmail,
            ownerMemberId = ownerMemberId,
            ownerName = ownerName,
            billingEmail = billingEmail,
        )

    suspend fun setDefaultPaymentMethod(
        orgId: String,
        paymentMethodId: String,
    ): PolarCallResult<JsonObject> =
        polarClient.updateCustomerBillingInfo(
            orgId = orgId,
            defaultPaymentMethodId = paymentMethodId,
        )
}
