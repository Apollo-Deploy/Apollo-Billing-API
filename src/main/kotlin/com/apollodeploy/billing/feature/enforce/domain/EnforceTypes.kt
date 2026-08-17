package com.apollodeploy.billing.feature.enforce.domain

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

/**
 * A concrete billing check selected by [type]: `quota`, `feature`, or `meter`.
 */
@Serializable
data class BillingCheck(
    val type: String,
    val resource: String? = null,
    val limitKey: String? = null,
    val feature: String? = null,
    val meterKey: String? = null,
    val needed: Int? = null,
) {
    companion object {
        fun Quota(resource: String, limitKey: String) =
            BillingCheck(type = "quota", resource = resource, limitKey = limitKey)

        fun Feature(feature: String) =
            BillingCheck(type = "feature", feature = feature)

        fun Meter(meterKey: String, needed: Int = 1) =
            BillingCheck(type = "meter", meterKey = meterKey, needed = needed)
    }
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
