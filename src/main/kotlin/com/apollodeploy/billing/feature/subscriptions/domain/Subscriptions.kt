package com.apollodeploy.billing.feature.subscriptions.domain

import kotlinx.serialization.Serializable

@Serializable
data class SubscriptionItem(
    val polarSubscriptionId: String,
    val polarProductId: String,
    val orgId: String,
    val email: String?,
    val status: String,
    val quantity: Int,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class ActiveSubscriptionsResponse(
    val apps: Map<String, List<SubscriptionItem>>,
    val totalCount: Int,
)

sealed class ActiveSubscriptionsResult {
    data class Found(val response: ActiveSubscriptionsResponse) : ActiveSubscriptionsResult()
    data object InternalError : ActiveSubscriptionsResult()
}
