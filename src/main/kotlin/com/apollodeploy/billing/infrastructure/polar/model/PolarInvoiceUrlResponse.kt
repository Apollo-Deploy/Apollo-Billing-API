package com.apollodeploy.billing.infrastructure.polar.model

import kotlinx.serialization.Serializable

@Serializable
internal data class PolarInvoiceUrlResponse(
    val url: String,
)
