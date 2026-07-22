package com.apollodeploy.billing.infrastructure.polar.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class PolarMemberListResponse(
    val items: List<PolarMember> = emptyList(),
)

@Serializable
internal data class PolarMember(
    val id: String,
    @SerialName("external_id") val externalId: String? = null,
    val role: String? = null,
)
