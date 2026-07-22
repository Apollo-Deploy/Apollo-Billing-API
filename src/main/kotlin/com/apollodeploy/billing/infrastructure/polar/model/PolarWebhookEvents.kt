package com.apollodeploy.billing.infrastructure.polar.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Apollo Billing — Polar webhook event wire types.
 *
 * Only the fields the billing service actually uses are mapped.
 * All payloads use `ignoreUnknownKeys = true` at the call site.
 */

@Serializable
data class PolarWebhookEvent(
    val type: String,
    val data: JsonElement,
)

// ─── Subscription events ──────────────────────────────────────────────────────

@Serializable
data class PolarSubscriptionPayload(
    val id: String,
    val status: String,
    @SerialName("product_id") val productId: String,
    @SerialName("customer_id") val customerId: String,
    val quantity: Int? = null,
    val amount: Int? = null,
    val currency: String? = null,
    @SerialName("recurring_interval") val recurringInterval: String? = null,
    @SerialName("current_period_end") val currentPeriodEnd: String? = null,
    @SerialName("cancel_at_period_end") val cancelAtPeriodEnd: Boolean = false,
    @SerialName("ends_at") val endsAt: String? = null,
    val customer: PolarCustomer,
)

@Serializable
data class PolarCustomer(
    val id: String,
    @SerialName("external_id") val externalId: String? = null,
    val email: String = "",
)

// ─── Order events ─────────────────────────────────────────────────────────────

@Serializable
data class PolarOrderPayload(
    val id: String,
    @SerialName("product_id") val productId: String,
    @SerialName("customer_id") val customerId: String,
    @SerialName("amount") val amountCents: Int = 0,
    val customer: PolarCustomer,
)
