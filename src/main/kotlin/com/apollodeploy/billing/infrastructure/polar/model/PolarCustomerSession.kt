package com.apollodeploy.billing.infrastructure.polar.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PolarCustomerSession(
    val id: String,
    val token: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("customer_portal_url") val customerPortalUrl: String,
    @SerialName("customer_id") val customerId: String,
)

@Serializable
internal data class PolarCreateCustomerSessionRequest(
    @SerialName("external_customer_id") val externalCustomerId: String,
    @SerialName("return_url") val returnUrl: String? = null,
    /** Polar-internal member UUID. Prefer [externalMemberId] when available. */
    @SerialName("member_id") val memberId: String? = null,
    @SerialName("external_member_id") val externalMemberId: String? = null,
)
