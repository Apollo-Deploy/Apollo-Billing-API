package com.apollodeploy.billing.feature.entitlements.domain

import kotlinx.serialization.Serializable

@Serializable
data class EntitlementsResponse(
    val appSlug: String,
    val orgId: String,
    val planId: String,
    val limits: Map<String, Int>,
    val features: Map<String, Boolean>,
    val usage: Map<String, Int>,
    val remaining: Map<String, String>,
)

sealed class EntitlementsResult {
    data class Found(
        val response: EntitlementsResponse,
    ) : EntitlementsResult()

    data class UnknownApp(
        val appSlug: String,
    ) : EntitlementsResult()

    data object NoSubscription : EntitlementsResult()

    data object InternalError : EntitlementsResult()
}
