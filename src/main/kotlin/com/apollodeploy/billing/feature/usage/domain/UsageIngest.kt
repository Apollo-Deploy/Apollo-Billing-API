package com.apollodeploy.billing.feature.usage.domain

import kotlinx.serialization.Serializable

@Serializable
data class UsageIngestRequest(
    val orgId: String,
    val eventKey: String,
    val quantity: Int = 1,
    val idempotencyKey: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
data class UsageIngestResponse(
    val accepted: Boolean,
    val reason: String? = null,
    val deduplicated: Boolean = false,
)
