package com.apollodeploy.billing.infrastructure.polar.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PolarBillingAddressInput(
    val line1: String? = null,
    val line2: String? = null,
    val postalCode: String? = null,
    val city: String? = null,
    val state: String? = null,
    val country: String? = null,
)

@Serializable
internal data class PolarCustomerExternalUpdateRequest(
    val email: String? = null,
    @SerialName("billing_name") val billingName: String? = null,
    @SerialName("billing_address") val billingAddress: PolarBillingAddressInput? = null,
    @SerialName("tax_id") val taxId: String? = null,
    @SerialName("default_payment_method_id") val defaultPaymentMethodId: String? = null,
)

@Serializable
internal data class PolarCreateTeamCustomerRequest(
    // No Kotlin default — kotlinx.serialization omits defaulted fields when
    // encodeDefaults=false, and Polar then treats the create as `individual`.
    @SerialName("type") val type: String,
    @SerialName("external_id") val externalId: String,
    @SerialName("name") val name: String,
    @SerialName("email") val email: String? = null,
    @SerialName("owner") val owner: PolarMemberOwnerCreate? = null,
)

@Serializable
internal data class PolarMemberOwnerCreate(
    @SerialName("email") val email: String,
    @SerialName("name") val name: String? = null,
    @SerialName("external_id") val externalId: String? = null,
)
