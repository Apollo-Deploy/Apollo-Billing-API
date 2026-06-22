package com.apollodeploy.billing.feature.health.domain

import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(
    val status: String,
    val service: String,
)
