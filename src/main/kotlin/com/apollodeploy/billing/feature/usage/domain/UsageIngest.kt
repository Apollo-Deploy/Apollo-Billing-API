package com.apollodeploy.billing.feature.usage.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class UsageIngestRequest(
    val orgId: String,
    val eventKey: String,
    val quantity: Int = 1,
    val metadata: Map<String, JsonElement> = emptyMap(),
)

@Serializable
data class UsageIngestResponse(
    val accepted: Boolean,
    val reason: String? = null,
)
