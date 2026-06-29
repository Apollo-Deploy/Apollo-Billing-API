package com.apollodeploy.billing.feature.checkout.domain

import kotlinx.serialization.Serializable

@Serializable
data class CreateCheckoutRequest(
    val orgId: String,
    val appSlug: String,
    val productSlug: String,
    val customerEmail: String? = null,
    val customerName: String? = null,
    val successUrl: String? = null,
    val returnUrl: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
data class CreateCheckoutResponse(
    val id: String,
    val url: String,
    val expiresAt: String? = null,
    val productKind: String,
)

sealed class CreateCheckoutResult {
    data class Created(
        val response: CreateCheckoutResponse,
    ) : CreateCheckoutResult()

    data class UnknownProduct(
        val appSlug: String,
        val productSlug: String,
    ) : CreateCheckoutResult()

    data class InvalidUrl(
        val field: String,
        val reason: String,
    ) : CreateCheckoutResult()

    data object Unavailable : CreateCheckoutResult()
}
