package com.apollodeploy.billing.core

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Apollo Billing — core enforcement engine.
 *
 * Resolves entitlements via injected config (plan + usage), caches results
 * with single-flight deduplication, then checks limits or feature flags.
 *
 * Security enhancements over the original:
 *   - LRU cache with proper eviction (access-ordered LinkedHashMap under lock)
 *   - Arrow `Either` for typed errors — no hidden exceptions in enforcement path
 *   - Meter enforcement uses Redis-backed Polar state (no more naive fail-open)
 */
class BillingEnforcer(
    val config: BillingConfig,
) {
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val accessOrder = ConcurrentLinkedDeque<String>()
    private val inflight = ConcurrentHashMap<String, Deferred<AppEntitlements>>()
    private val cacheMaxEntries = 10_000

    private data class CacheEntry(
        val value: AppEntitlements,
        val expiresAt: Long,
    )

    /**
     * Resolves entitlements with in-process caching and single-flight dedup.
     *
     * Returns [Either.Left] with [BillingError.NoSubscription] when no active subscription exists,
     * or [BillingError.ServiceUnavailable] when a backing service is unreachable.
     */
    suspend fun resolveEntitlements(orgId: String): Either<BillingError, AppEntitlements> = either {
        val now = System.currentTimeMillis()

        val cached = cache[orgId]
        if (cached != null && cached.expiresAt > now) return@either cached.value
        cache.remove(orgId)

        val deferred = CompletableDeferred<AppEntitlements>()
        val pending = inflight.putIfAbsent(orgId, deferred)
        if (pending != null) return@either pending.await()

        try {
            val resolution = config.resolvePlanAndUsage(orgId)
            val entitlements =
                AppEntitlements(
                    appSlug = config.appSlug,
                    orgId = orgId,
                    planId = resolution.plan.planId,
                    limits = resolution.plan.config,
                    usage = resolution.usage,
                    remaining = resolution.plan.config.computeRemaining(resolution.usage),
                )
            if (config.cacheTtlMs > 0) {
                evictIfNeeded()
                cache[orgId] = CacheEntry(entitlements, now + config.cacheTtlMs)
                accessOrder.remove(orgId)
                accessOrder.addLast(orgId)
            }
            deferred.complete(entitlements)
            entitlements
        } catch (e: SubscriptionNotFoundError) {
            deferred.completeExceptionally(e)
            raise(BillingError.NoSubscription(orgId = e.orgId, appSlug = e.appSlug))
        } catch (e: SignalDbUnavailableError) {
            deferred.completeExceptionally(e)
            raise(BillingError.ServiceUnavailable(service = "signal-db", reason = e.message))
        } catch (e: Exception) {
            deferred.completeExceptionally(e)
            raise(BillingError.ServiceUnavailable(service = "billing", reason = e.message))
        } finally {
            inflight.remove(orgId, deferred)
        }
    }

    /** Invalidates the cache entry for an org (call after subscription changes). */
    fun invalidate(orgId: String) {
        cache.remove(orgId)
        accessOrder.remove(orgId)
    }

    /**
     * Enforce quota — returns [BillingError.QuotaExceeded] if over limit.
     */
    suspend fun enforceQuota(
        orgId: String,
        resource: String,
        limitKey: String,
    ): Either<BillingError, Unit> = either {
        val entitlements = resolveEntitlements(orgId).bind()
        val limit = entitlements.limits.getLimit(limitKey)
        val current = entitlements.usage[limitKey] ?: 0
        ensure(limit.isWithinLimit(current)) {
            BillingError.QuotaExceeded(
                resource = resource,
                current = current,
                limit = limit,
                appSlug = config.appSlug,
            )
        }
    }

    /**
     * Enforce feature flag — returns [BillingError.FeatureNotAvailable] if not enabled.
     */
    suspend fun enforceFeature(
        orgId: String,
        feature: String,
    ): Either<BillingError, Unit> = either {
        val entitlements = resolveEntitlements(orgId).bind()
        ensure(entitlements.limits.isFeatureEnabled(feature)) {
            BillingError.FeatureNotAvailable(
                feature = feature,
                currentPlan = entitlements.planId,
                appSlug = config.appSlug,
            )
        }
    }

    /**
     * Enforce meter balance (Polar Credits-backed resources).
     *
     * With Redis-backed Polar state, the meter key will almost always be present
     * (either fresh from Polar or stale from Redis). Only if BOTH Polar AND Redis
     * are down will the key be absent — in that case we fail-open (allow) as a
     * last-resort so customers aren't permanently locked out.
     *
     * Returns [BillingError.MeterExhausted] if balance < needed.
     */
    suspend fun enforceMeter(
        orgId: String,
        meterKey: String,
        needed: Int = 1,
    ): Either<BillingError, Unit> = either {
        val entitlements = resolveEntitlements(orgId).bind()
        val balance = entitlements.usage[meterKey]
            ?: return@either Unit // Both Polar AND Redis down — last-resort fail-open

        ensure(balance >= needed) {
            BillingError.MeterExhausted(
                meterKey = meterKey,
                balance = balance,
                needed = needed,
                appSlug = config.appSlug,
            )
        }
    }

    // ─── LRU eviction ─────────────────────────────────────────────────────────

    private fun evictIfNeeded() {
        while (cache.size >= cacheMaxEntries) {
            val oldest = accessOrder.pollFirst() ?: break
            cache.remove(oldest)
        }
    }
}
