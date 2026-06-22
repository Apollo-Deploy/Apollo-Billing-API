package com.apollodeploy.billing.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import java.util.concurrent.ConcurrentHashMap

/**
 * Apollo Billing — core enforcement engine.
 * Ported from apollo-signal-api core/billing/BillingEnforcer.kt.
 *
 * Resolves entitlements via injected config (plan + usage), caches results
 * with single-flight deduplication, then checks limits or feature flags.
 */
class BillingEnforcer(
    val config: BillingConfig,
) {
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val inflight = ConcurrentHashMap<String, Deferred<AppEntitlements>>()
    private val cacheMaxEntries = 10_000

    private data class CacheEntry(
        val value: AppEntitlements,
        val expiresAt: Long,
    )

    /**
     * Resolves entitlements with in-process caching and single-flight dedup.
     * Throws [SubscriptionNotFoundError] when [BillingConfig.resolvePlan] cannot
     * find an active subscription.
     */
    suspend fun resolveEntitlements(orgId: String): AppEntitlements {
        val now = System.currentTimeMillis()

        val cached = cache[orgId]
        if (cached != null && cached.expiresAt > now) return cached.value
        cache.remove(orgId)

        val deferred = CompletableDeferred<AppEntitlements>()
        val pending = inflight.putIfAbsent(orgId, deferred)
        if (pending != null) return pending.await()

        return try {
            val plan = config.resolvePlan(orgId)
            val usage = config.resolveUsage(orgId)
            val entitlements =
                AppEntitlements(
                    appSlug = config.appSlug,
                    orgId = orgId,
                    planId = plan.planId,
                    limits = plan.config,
                    usage = usage,
                    remaining = plan.config.computeRemaining(usage),
                )
            if (config.cacheTtlMs > 0) {
                if (cache.size >= cacheMaxEntries) cache.keys.firstOrNull()?.let { cache.remove(it) }
                cache[orgId] = CacheEntry(entitlements, now + config.cacheTtlMs)
            }
            deferred.complete(entitlements)
            entitlements
        } catch (e: Exception) {
            deferred.completeExceptionally(e)
            throw e
        } finally {
            inflight.remove(orgId, deferred)
        }
    }

    /** Invalidates the cache entry for an org (call after subscription changes). */
    fun invalidate(orgId: String) {
        cache.remove(orgId)
    }

    /**
     * Throws [QuotaExceededError] if usage meets or exceeds the limit for [limitKey].
     * Throws [SubscriptionNotFoundError] when no subscription exists.
     */
    suspend fun enforceQuota(
        orgId: String,
        resource: String,
        limitKey: String,
    ) {
        val entitlements = resolveEntitlements(orgId)
        val limit = entitlements.limits.getLimit(limitKey)
        val current = entitlements.usage[limitKey] ?: 0
        if (!limit.isWithinLimit(current)) {
            throw QuotaExceededError(
                resource = resource,
                current = current,
                limit = limit,
                appSlug = config.appSlug,
            )
        }
    }

    /**
     * Throws [FeatureNotAvailableError] if the org's plan does not include [feature].
     * Throws [SubscriptionNotFoundError] when no subscription exists.
     */
    suspend fun enforceFeature(
        orgId: String,
        feature: String,
    ) {
        val entitlements = resolveEntitlements(orgId)
        if (!entitlements.limits.isFeatureEnabled(feature)) {
            throw FeatureNotAvailableError(
                feature = feature,
                currentPlan = entitlements.planId,
                appSlug = config.appSlug,
            )
        }
    }

    /**
     * Meter balance enforcement for Polar Credits-backed resources (e.g. automation runs).
     *
     * Throws [QuotaExceededError] if usage[[meterKey]] < [needed].
     * Throws [SubscriptionNotFoundError] when no subscription exists.
     *
     * Fail-open: if [meterKey] is absent from the usage map (Polar was unavailable
     * when entitlements were resolved), the check passes — customers are never
     * blocked by a Polar API outage.
     */
    suspend fun enforceMeter(
        orgId: String,
        meterKey: String,
        needed: Int = 1,
    ) {
        val entitlements = resolveEntitlements(orgId)
        val balance = entitlements.usage[meterKey] ?: return // absent → fail-open
        if (balance < needed) {
            throw QuotaExceededError(
                resource = meterKey,
                current = balance,
                limit = needed,
                appSlug = config.appSlug,
            )
        }
    }
}
