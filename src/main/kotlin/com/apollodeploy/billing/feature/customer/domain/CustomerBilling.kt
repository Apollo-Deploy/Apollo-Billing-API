package com.apollodeploy.billing.feature.customer.domain

import com.apollodeploy.billing.infrastructure.polar.PolarBillingAddressInput
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class UpdateCustomerBillingInfoRequest(
    val orgId: String,
    val email: String? = null,
    val billingName: String? = null,
    val billingAddress: PolarBillingAddressInput? = null,
    val taxId: String? = null,
    val defaultPaymentMethodId: String? = null,
)

@Serializable
data class UpdateCustomerBillingInfoResponse(
    val customer: JsonObject,
)

@Serializable
data class ListCustomerPaymentMethodsResponse(
    val paymentMethods: JsonObject,
)

sealed class CustomerBillingResult<out T> {
    data class Success<T>(
        val value: T,
    ) : CustomerBillingResult<T>()

    data class InvalidRequest(
        val message: String,
    ) : CustomerBillingResult<Nothing>()

    data class PolarFailure(
        val fallbackCode: String,
        val statusCode: Int?,
        val errorBody: String?,
    ) : CustomerBillingResult<Nothing>()
}

fun UpdateCustomerBillingInfoRequest.hasAnyUpdate(): Boolean =
    email != null ||
        billingName != null ||
        billingAddress != null ||
        taxId != null ||
        defaultPaymentMethodId != null
