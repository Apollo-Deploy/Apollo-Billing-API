package com.apollodeploy.billing.infrastructure.polar

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Apollo Billing — Polar Customer State wire types.
 *
 * Used by:
 *   - PolarClient.getCustomerState() → GET /v1/customers/external/{external_id}/state
 *   - PolarWebhookHandler.handleCustomerStateChanged() → customer.state_changed event data
 *
 * Docs:
 *   https://polar.sh/docs/integrate/customer-state
 *   https://polar.sh/docs/api-reference/webhooks/customer.state_changed
 */

@Serializable
data class PolarCustomerState(
    val id: String,
    val email: String = "",
    @SerialName("external_id") val externalId: String? = null,
    @SerialName("active_subscriptions") val activeSubscriptions: List<CustomerStateSubscription> = emptyList(),
    @SerialName("granted_benefits") val grantedBenefits: List<CustomerStateBenefitGrant> = emptyList(),
    @SerialName("active_meters") val activeMeters: List<CustomerStateMeter> = emptyList(),
)

/** An active subscription within the customer state. */
@Serializable
data class CustomerStateSubscription(
    val id: String,
    @SerialName("product_id") val productId: String,
    val status: String,
)

/**
 * A granted benefit within the customer state.
 *
 * [benefitMetadata] carries key-value pairs set in the Polar dashboard.
 * For Feature Flag benefits this can encode per-customer config, e.g.:
 *   "maxUploadSizeMb" → "100", "betaFeatures" → "true"
 */
@Serializable
data class CustomerStateBenefitGrant(
    val id: String,
    @SerialName("benefit_id") val benefitId: String,
    @SerialName("benefit_metadata") val benefitMetadata: Map<String, String> = emptyMap(),
)

/**
 * An active Usage Meter for the customer.
 *
 * [meterId]        — Polar meter ID configured in the dashboard.
 * [creditedUnits]  — Total units credited (plan benefit + top-up purchases).
 * [consumedUnits]  — Total units consumed (ingested usage events).
 * [balance]        — Remaining units: creditedUnits - consumedUnits.
 *
 * For credit-backed resources:
 *   - Plan subscriptions can grant credits at each billing cycle start (Credits benefit).
 *   - One-time purchases grant credits immediately (Credits benefit).
 *   - Usage events (ingestUsageEvent calls) decrement the balance.
 */
@Serializable
data class CustomerStateMeter(
    val id: String,
    @SerialName("meter_id") val meterId: String,
    @SerialName("consumed_units") val consumedUnits: Int = 0,
    @SerialName("credited_units") val creditedUnits: Int = 0,
    val balance: Int = 0,
)
