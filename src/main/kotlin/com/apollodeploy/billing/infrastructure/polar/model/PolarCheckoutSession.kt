package com.apollodeploy.billing.infrastructure.polar.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PolarCheckoutSession(
    val id: String,
    val url: String,
    @SerialName("expires_at") val expiresAt: String? = null,
)

@Serializable
internal data class PolarCreateCheckoutSessionRequest(
    @SerialName("external_customer_id") val externalCustomerId: String,
    val products: List<String>,
    @SerialName("customer_email") val customerEmail: String? = null,
    @SerialName("customer_name") val customerName: String? = null,
    @SerialName("success_url") val successUrl: String? = null,
    @SerialName("return_url") val returnUrl: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)
