package com.apollodeploy.billing.feature.enforce.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire types for POST /internal/billing/enforce.
 */

@Serializable
data class EnforceRequest(
    val orgId: String,
    val appSlug: String,
    val check: BillingCheck,
)

@Serializable
sealed class BillingCheck {
    @Serializable
    @SerialName("quota")
    data class Quota(
        val resource: String,
        val limitKey: String,
    ) : BillingCheck()

    @Serializable
    @SerialName("feature")
    data class Feature(
        val feature: String,
    ) : BillingCheck()

    /**
     * Polar meter balance check. Used for automation runs and other Polar Credits-backed resources.
     *
     * [meterKey]  — key in the usage map (e.g. "automationRunBalance"); populated by
     *               SignalBillingConfig.resolveUsage() from active_meters[].balance.
     * [needed]    — minimum balance required (default 1).
     *
     * Enforcement: usage[meterKey] >= needed. Unlike quota checks, a higher balance
     * is BETTER (it’s remaining credits, not consumed ones).
     *
     * Fail-open: if the meterKey is absent from usage (Polar unavailable), the
     * check passes so customers aren’t blocked by billing infrastructure outages.
     */
    @Serializable
    @SerialName("meter")
    data class Meter(
        val meterKey: String,
        val needed: Int = 1,
    ) : BillingCheck()
}

@Serializable
data class EnforceResponse(
    val allowed: Boolean,
)

@Serializable
data class BillingErrorResponse(
    val code: String,
    val message: String,
    val resource: String? = null,
    val current: Int? = null,
    val limit: Int? = null,
    val feature: String? = null,
    val currentPlan: String? = null,
)

sealed class EnforceResult {
    data object Allowed : EnforceResult()

    data class Rejected(
        val statusCode: Int,
        val error: BillingErrorResponse,
    ) : EnforceResult()
}
