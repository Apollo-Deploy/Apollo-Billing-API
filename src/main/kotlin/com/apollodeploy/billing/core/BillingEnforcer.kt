package com.apollodeploy.billing.core

import arrow.core.Either
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

private const val CACHE_MAX_ENTRIES = 10_000
private const val SIGNAL_DB_SERVICE = "signal-db"
private const val BILLING_SERVICE = "billing"

private val Allowed: Either<Nothing, Unit> = Either.Right(Unit)

/**
 * Resolves and enforces billing entitlements.
 *
 * Successful resolutions are cached using a bounded, access-ordered LRU cache.
 * Concurrent resolutions for the same organization share one request.
 */
class BillingEnforcer(
    val config: BillingConfig,
) {
    private val cacheTtlNanos =
        TimeUnit.MILLISECONDS.toNanos(
            config.cacheTtlMs.coerceAtLeast(0),
        )

    private val cache =
        object : LinkedHashMap<String, CacheEntry>(
            16,
            0.75f,
            true,
        ) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, CacheEntry>?,
            ): Boolean = size > CACHE_MAX_ENTRIES
        }

    private val inflight =
        ConcurrentHashMap<
            String,
            CompletableDeferred<Either<BillingError, AppEntitlements>>,
        >()

    private data class CacheEntry(
        val result: Either<BillingError, AppEntitlements>,
        val expiresAtNanos: Long,
    )

    suspend fun resolveEntitlements(
        orgId: String,
    ): Either<BillingError, AppEntitlements> {
        getCached(orgId)?.let { return it }

        val deferred =
            CompletableDeferred<Either<BillingError, AppEntitlements>>()

        val existing = inflight.putIfAbsent(orgId, deferred)
        if (existing != null) {
            return existing.await()
        }

        try {
            val result = resolveFresh(orgId)
            deferred.complete(result)
            return result
        } catch (cause: CancellationException) {
            deferred.cancel(cause)
            throw cause
        } catch (cause: Throwable) {
            deferred.completeExceptionally(cause)
            throw cause
        } finally {
            inflight.remove(orgId, deferred)
        }
    }

    fun invalidate(orgId: String) {
        synchronized(cache) {
            cache.remove(orgId)
        }
    }

    suspend fun enforceQuota(
        orgId: String,
        resource: String,
        limitKey: String,
    ): Either<BillingError, Unit> =
        when (val result = resolveEntitlements(orgId)) {
            is Either.Left -> result

            is Either.Right -> {
                val entitlements = result.value
                val limit = entitlements.limits.getLimit(limitKey)
                val usage = entitlements.usage[limitKey] ?: 0

                if (limit.isWithinLimit(usage)) {
                    Allowed
                } else {
                    Either.Left(
                        BillingError.QuotaExceeded(
                            resource = resource,
                            current = usage,
                            limit = limit,
                            appSlug = config.appSlug,
                        ),
                    )
                }
            }
        }

    suspend fun enforceFeature(
        orgId: String,
        feature: String,
    ): Either<BillingError, Unit> =
        when (val result = resolveEntitlements(orgId)) {
            is Either.Left -> result

            is Either.Right -> {
                val entitlements = result.value

                if (entitlements.limits.isFeatureEnabled(feature)) {
                    Allowed
                } else {
                    Either.Left(
                        BillingError.FeatureNotAvailable(
                            feature = feature,
                            currentPlan = entitlements.planId,
                            appSlug = config.appSlug,
                        ),
                    )
                }
            }
        }

    suspend fun enforceMeter(
        orgId: String,
        meterKey: String,
        needed: Int = 1,
    ): Either<BillingError, Unit> =
        when (val result = resolveEntitlements(orgId)) {
            is Either.Left -> result

            is Either.Right -> {
                val balance = result.value.usage[meterKey]

                when {
                    balance == null -> Allowed
                    balance >= needed -> Allowed

                    else ->
                        Either.Left(
                            BillingError.MeterExhausted(
                                meterKey = meterKey,
                                balance = balance,
                                needed = needed,
                                appSlug = config.appSlug,
                            ),
                        )
                }
            }
        }

    private suspend fun resolveFresh(
        orgId: String,
    ): Either<BillingError, AppEntitlements> =
        try {
            val resolution = config.resolvePlanAndUsage(orgId)

            val entitlements =
                AppEntitlements(
                    appSlug = config.appSlug,
                    orgId = orgId,
                    planId = resolution.plan.planId,
                    limits = resolution.plan.config,
                    usage = resolution.usage,
                    remaining =
                        resolution.plan.config.computeRemaining(
                            resolution.usage,
                        ),
                )

            val result: Either<BillingError, AppEntitlements> =
                Either.Right(entitlements)

            putCached(orgId, result)
            result
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: SubscriptionNotFoundError) {
            Either.Left(
                BillingError.NoSubscription(
                    orgId = cause.orgId,
                    appSlug = cause.appSlug,
                ),
            )
        } catch (cause: SignalDbUnavailableError) {
            Either.Left(
                BillingError.ServiceUnavailable(
                    service = SIGNAL_DB_SERVICE,
                    reason = cause.message,
                ),
            )
        } catch (cause: Exception) {
            Either.Left(
                BillingError.ServiceUnavailable(
                    service = BILLING_SERVICE,
                    reason = cause.message,
                ),
            )
        }

    private fun getCached(
        orgId: String,
    ): Either<BillingError, AppEntitlements>? {
        if (cacheTtlNanos <= 0) {
            return null
        }

        val now = System.nanoTime()

        return synchronized(cache) {
            val entry =
                cache[orgId]
                    ?: return@synchronized null

            if (entry.expiresAtNanos > now) {
                entry.result
            } else {
                cache.remove(orgId)
                null
            }
        }
    }

    private fun putCached(
        orgId: String,
        result: Either<BillingError, AppEntitlements>,
    ) {
        if (cacheTtlNanos <= 0) {
            return
        }

        val entry =
            CacheEntry(
                result = result,
                expiresAtNanos = System.nanoTime() + cacheTtlNanos,
            )

        synchronized(cache) {
            cache[orgId] = entry
        }
    }
}
