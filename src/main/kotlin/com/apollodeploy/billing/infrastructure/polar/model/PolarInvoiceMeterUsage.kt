package com.apollodeploy.billing.infrastructure.polar.model

data class PolarInvoiceMeterUsage(
    val meterId: String,
    val meterName: String,
    val unit: String?,
    val usedUnits: Long,
    val consumedUnits: Long,
    val creditedUnits: Long,
    val balance: Long,
)
