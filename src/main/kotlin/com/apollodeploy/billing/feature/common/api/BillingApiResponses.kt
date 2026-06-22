package com.apollodeploy.billing.feature.common.api

import kotlinx.serialization.Serializable

@Serializable
data class BillingApiErrorResponse(
    val code: String? = null,
    val message: String? = null,
    val error: String? = null,
    val polarStatus: String? = null,
    val polarError: String? = null,
)
