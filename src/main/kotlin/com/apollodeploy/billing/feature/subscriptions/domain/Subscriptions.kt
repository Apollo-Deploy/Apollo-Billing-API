package com.apollodeploy.billing.feature.subscriptions.domain

import kotlinx.serialization.Serializable

@Serializable
data class SubscriptionItem(
    val polarSubscriptionId: String,
    val polarProductId: String,
    val appSlug: String,
    /** Human-readable app name (e.g. "Apollo Signal"). */
    val appName: String? = null,
    /** Registered product slug (e.g. "signal-scale"). */
    val productSlug: String? = null,
    /** Display name from the registered product catalog (e.g. "Scale", "Dedicated IP"). */
    val planName: String? = null,
    /** Catalog product kind (e.g. SUBSCRIPTION, SUBSCRIPTION_ADD_ON). */
    val productKind: String? = null,
    val status: String,
    val quantity: Int,
    /** Recurring price in cents for this subscription line. */
    val amountCents: Int? = null,
    val currency: String? = null,
    /** Billing cadence: month, year, etc. */
    val billingInterval: String? = null,
    /** True when the subscription will cancel at the end of the current period. */
    val cancelAtPeriodEnd: Boolean = false,
    /** End of the current billing period — next renewal date when not canceling. */
    val renewalDate: String? = null,
    /** When access ends after a scheduled cancellation (Polar ends_at / current period end). */
    val endsAt: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val orgId: String,
    val email: String? = null,
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

@Serializable
data class CancelSubscriptionResponse(
    val polarSubscriptionId: String,
    val status: String,
    val cancelAtPeriodEnd: Boolean,
    val endsAt: String?,
)

sealed class CancelSubscriptionResult {
    data class Canceled(val response: CancelSubscriptionResponse) : CancelSubscriptionResult()

    data class NotFound(val subscriptionId: String) : CancelSubscriptionResult()

    data object PolarUnavailable : CancelSubscriptionResult()
}
