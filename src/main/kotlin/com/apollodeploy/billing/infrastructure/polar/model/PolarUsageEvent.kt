package com.apollodeploy.billing.infrastructure.polar.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class PolarEventsIngestRequest(
    val events: List<PolarUsageEvent>,
)

@Serializable
internal data class PolarUsageEvent(
    val name: String,
    @SerialName("external_customer_id") val externalCustomerId: String,
    val metadata: Map<String, JsonElement> = emptyMap(),
)

@Serializable
internal data class PolarSubscriptionCancelRequest(
    @SerialName("cancel_at_period_end") val cancelAtPeriodEnd: Boolean,
)
