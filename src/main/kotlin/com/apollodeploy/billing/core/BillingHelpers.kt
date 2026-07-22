package com.apollodeploy.billing.core

/**
 * Value representing a quota with no limit.
 */
const val UNLIMITED_SENTINEL: Int = -1

private const val DEFAULT_LEVEL = "none"
private const val UNLIMITED_LABEL = "unlimited"
private const val INVALID_LEVEL = -1

fun PlanFeatureConfig.hasFeature(name: String): Boolean =
    features[name] == true

fun PlanFeatureConfig.getLimit(name: String): Int =
    limits[name] ?: 0

fun PlanFeatureConfig.isFeatureEnabled(requirement: String): Boolean {
    val separator = requirement.indexOf(':')

    if (separator < 0) {
        return hasFeature(requirement)
    }

    if (separator == 0 || separator == requirement.lastIndex) {
        return false
    }

    return isFeatureEnabled(
        feature = requirement.substring(0, separator),
        requiredLevel = requirement.substring(separator + 1),
    )
}

/**
 * Prefer this overload when the feature and level are already separate.
 * It avoids parsing and substring allocations.
 */
fun PlanFeatureConfig.isFeatureEnabled(
    feature: String,
    requiredLevel: String,
): Boolean {
    val requiredRank = levelRank(feature, requiredLevel)
    if (requiredRank == INVALID_LEVEL) {
        return false
    }

    val currentRank =
        levelRank(
            feature = feature,
            level = levels[feature] ?: DEFAULT_LEVEL,
        )

    return currentRank >= requiredRank
}

fun PlanFeatureConfig.computeRemaining(
    usage: Map<String, Int>,
): Map<String, Any> {
    if (limits.isEmpty()) {
        return emptyMap()
    }

    val remaining = HashMap<String, Any>(limits.size)

    for ((name, limit) in limits) {
        remaining[name] =
            if (limit.isUnlimited()) {
                UNLIMITED_LABEL
            } else {
                limit.remainingQuota(usage[name] ?: 0)
            }
    }

    return remaining
}

fun Int.isUnlimited(): Boolean =
    this == UNLIMITED_SENTINEL

fun Int.isWithinLimit(usage: Int): Boolean =
    isUnlimited() || usage < this

fun Int.remainingQuota(usage: Int): Int =
    when {
        isUnlimited() -> Int.MAX_VALUE
        usage >= this -> 0
        else -> this - usage
    }

private fun levelRank(
    feature: String,
    level: String,
): Int =
    when (feature) {
        "rolloutEngine" -> rolloutEngineRank(level)
        "releaseApprovals" -> releaseApprovalRank(level)

        "customPipelineStages",
        "policyEnforcement",
        "anomalyDetection",
        -> standardLevelRank(level)

        else -> INVALID_LEVEL
    }

private fun rolloutEngineRank(level: String): Int =
    when (level) {
        "none" -> 0
        "manual" -> 1
        "rules" -> 2
        "dynamic" -> 3
        else -> INVALID_LEVEL
    }

private fun releaseApprovalRank(level: String): Int =
    when (level) {
        "none" -> 0
        "single" -> 1
        "multi" -> 2
        else -> INVALID_LEVEL
    }

private fun standardLevelRank(level: String): Int =
    when (level) {
        "none" -> 0
        "basic" -> 1
        "advanced" -> 2
        else -> INVALID_LEVEL
    }