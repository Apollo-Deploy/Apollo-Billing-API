package com.apollodeploy.billing.core

/**
 * Apollo Billing — core domain types.
 * Ported from apollo-signal-api core/billing/BillingTypes.kt.
 * Pure domain: no Ktor, no JDBC imports.
 */

const val UNLIMITED = "unlimited"

typealias LimitValue = Any // Int or "unlimited"

data class PlanFeatureConfig(
    val features: Map<String, Boolean> = emptyMap(),
    val limits: Map<String, Int> = emptyMap(),
    val levels: Map<String, String> = emptyMap(),
)

data class AppEntitlements(
    val appSlug: String,
    val orgId: String,
    val planId: String,
    val limits: PlanFeatureConfig,
    val usage: Map<String, Int>,
    val remaining: Map<String, LimitValue>,
)

data class BillingConfig(
    val appSlug: String,
    val resolvePlanAndUsage: suspend (orgId: String) -> PlanAndUsageResolution,
    val cacheTtlMs: Long = 5_000,
)

data class PlanResolution(
    val planId: String,
    val config: PlanFeatureConfig,
)

/**
 * Combined plan + usage resolution result.
 * Allows a single DB round-trip to resolve both plan and usage.
 */
data class PlanAndUsageResolution(
    val plan: PlanResolution,
    val usage: Map<String, Int>,
)

// ─── Billing errors ──────────────────────────────────────────────────────────

class QuotaExceededError(
    val resource: String,
    val current: Int,
    val limit: Int,
    val appSlug: String,
) : RuntimeException("Quota exceeded for \"$resource\": $current/$limit (app: $appSlug)")

class FeatureNotAvailableError(
    val feature: String,
    val currentPlan: String,
    val appSlug: String,
) : RuntimeException("Feature \"$feature\" is not available on the \"$currentPlan\" plan (app: $appSlug)")

class SubscriptionNotFoundError(
    val orgId: String,
    val appSlug: String,
) : RuntimeException("No active subscription found for org \"$orgId\" on app \"$appSlug\"")

class SignalDbUnavailableError(
    val orgId: String,
) : RuntimeException("Signal database is unavailable — cannot resolve usage for org \"$orgId\". Refusing to fail-open.")
