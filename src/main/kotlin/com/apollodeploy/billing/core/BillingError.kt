package com.apollodeploy.billing.core

/**
 * Apollo Billing — Arrow typed errors.
 *
 * Replaces exception-based error handling with sealed hierarchies
 * that can be used with Arrow's `Raise<BillingError>` DSL.
 *
 * Benefits:
 *   - Errors are explicit in the type signature (no hidden throws)
 *   - Exhaustive `when` matching enforced by the compiler
 *   - Composable with `either {}` and `recover {}`
 *   - No stack trace allocation for expected business errors
 */
sealed interface BillingError {
    val code: String
    val message: String

    /** Org's plan quota has been exceeded. */
    data class QuotaExceeded(
        val resource: String,
        val current: Int,
        val limit: Int,
        val appSlug: String,
    ) : BillingError {
        override val code: String = "billing.quota_exceeded"
        override val message: String = "Quota exceeded for \"$resource\": $current/$limit (app: $appSlug)"
    }

    /** Feature not available on the current plan. */
    data class FeatureNotAvailable(
        val feature: String,
        val currentPlan: String,
        val appSlug: String,
    ) : BillingError {
        override val code: String = "billing.feature_unavailable"
        override val message: String = "Feature \"$feature\" is not available on the \"$currentPlan\" plan (app: $appSlug)"
    }

    /** No active subscription for the org. */
    data class NoSubscription(
        val orgId: String,
        val appSlug: String,
    ) : BillingError {
        override val code: String = "billing.no_subscription"
        override val message: String = "No active subscription found for org \"$orgId\" on app \"$appSlug\""
    }

    /** App slug not recognized in the billing registry. */
    data class UnknownApp(
        val appSlug: String,
    ) : BillingError {
        override val code: String = "billing.unknown_app"
        override val message: String = "Unknown app slug: $appSlug"
    }

    /** A backing service (Signal DB, Redis) is temporarily unavailable. */
    data class ServiceUnavailable(
        val service: String,
        val reason: String? = null,
    ) : BillingError {
        override val code: String = "billing.service_unavailable"
        override val message: String = "Service \"$service\" is temporarily unavailable${reason?.let { ": $it" } ?: ""}"
    }

    /** Meter balance depleted — Polar credits exhausted. */
    data class MeterExhausted(
        val meterKey: String,
        val balance: Int,
        val needed: Int,
        val appSlug: String,
    ) : BillingError {
        override val code: String = "billing.meter_exhausted"
        override val message: String = "Meter \"$meterKey\" exhausted: balance=$balance, needed=$needed (app: $appSlug)"
    }

    /** Invalid request input. */
    data class InvalidInput(
        val field: String,
        val reason: String,
    ) : BillingError {
        override val code: String = "billing.invalid_input"
        override val message: String = "Invalid $field: $reason"
    }
}

/**
 * HTTP status code for a billing error.
 */
fun BillingError.httpStatus(): Int =
    when (this) {
        is BillingError.QuotaExceeded -> 402
        is BillingError.FeatureNotAvailable -> 402
        is BillingError.MeterExhausted -> 402
        is BillingError.NoSubscription -> 404
        is BillingError.UnknownApp -> 422
        is BillingError.ServiceUnavailable -> 503
        is BillingError.InvalidInput -> 400
    }
