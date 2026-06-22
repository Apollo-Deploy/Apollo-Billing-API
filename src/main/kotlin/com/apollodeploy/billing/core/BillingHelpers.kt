package com.apollodeploy.billing.core

/**
 * Pure billing helper functions — extension functions on [PlanFeatureConfig].
 * Ported from apollo-signal-api core/billing/BillingHelpers.kt.
 */
const val UNLIMITED_SENTINEL = -1

fun PlanFeatureConfig.hasFeature(name: String): Boolean = features[name] == true

fun PlanFeatureConfig.getLimit(key: String): Int = limits[key] ?: 0

fun PlanFeatureConfig.isFeatureEnabled(feature: String): Boolean {
    val colonIdx = feature.indexOf(':')
    return if (colonIdx == -1) {
        hasFeature(feature)
    } else {
        val key = feature.substring(0, colonIdx)
        val required = feature.substring(colonIdx + 1)
        val order = LEVEL_ORDERS[key] ?: return false
        val current = levels[key] ?: "none"
        order.indexOf(current) >= order.indexOf(required)
    }
}

fun PlanFeatureConfig.computeRemaining(usage: Map<String, Int>): Map<String, Any> =
    buildMap {
        for ((key, limit) in limits) {
            val current = usage[key] ?: 0
            put(key, if (limit == UNLIMITED_SENTINEL) "unlimited" else maxOf(0, limit - current))
        }
    }

fun Int.isUnlimited(): Boolean = this == UNLIMITED_SENTINEL

fun Int.isWithinLimit(usage: Int): Boolean = this == UNLIMITED_SENTINEL || usage < this

fun Int.remainingQuota(usage: Int): Int = if (this == UNLIMITED_SENTINEL) Int.MAX_VALUE else maxOf(0, this - usage)

private val LEVEL_ORDERS: Map<String, List<String>> =
    mapOf(
        "rolloutEngine" to listOf("none", "manual", "rules", "dynamic"),
        "releaseApprovals" to listOf("none", "single", "multi"),
        "customPipelineStages" to listOf("none", "basic", "advanced"),
        "policyEnforcement" to listOf("none", "basic", "advanced"),
        "anomalyDetection" to listOf("none", "basic", "advanced"),
    )
