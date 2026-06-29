package com.apollodeploy.billing.infrastructure.redis

import com.apollodeploy.billing.infrastructure.polar.PolarClient
import com.apollodeploy.billing.infrastructure.polar.PolarCustomerState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Apollo Billing — Redis-backed Polar customer state cache.
 *
 * Eliminates the fail-open vulnerability: when Polar is unreachable, meter balances
 * are read from Redis (last-known-good values) instead of being omitted entirely.
 *
 * Flow:
 *   1. Call Polar API for customer state.
 *   2. On success → cache in Redis with TTL (5 min), return fresh state.
 *   3. On failure → read from Redis as fallback, return stale-but-valid state.
 *   4. If BOTH Polar AND Redis are down → return null (only then does fail-open apply).
 *
 * This means an attacker would need to take down BOTH Polar AND Redis simultaneously
 * to trigger a fail-open scenario — far harder than just disrupting one service.
 *
 * Redis key format: `billing:polar:state:{orgId}`
 * TTL: 5 minutes (stale data is better than no data for enforcement)
 */
class PolarStateCache(
    private val polarClient: PolarClient,
    private val redis: RedisPool,
    private val cacheTtlSeconds: Long = 300L, // 5 minutes
) {
    private val logger = LoggerFactory.getLogger(PolarStateCache::class.java)
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    /**
     * Get customer state with Redis fallback.
     *
     * @return PolarCustomerState from Polar (fresh) or Redis (stale), or null if both unavailable.
     */
    suspend fun getCustomerState(orgId: String): PolarCustomerState? {
        // Try Polar first
        val freshState = polarClient.getCustomerState(orgId)

        if (freshState != null) {
            // Success — cache in Redis for fallback
            cacheState(orgId, freshState)
            return freshState
        }

        // Polar unavailable — try Redis fallback
        logger.warn("[billing:polar-cache] Polar unavailable for org={}, trying Redis fallback", orgId)
        return readCachedState(orgId)
    }

    /**
     * Invalidate the cached state for an org (e.g. after a webhook updates subscriptions).
     */
    suspend fun invalidate(orgId: String) {
        redis.del(cacheKey(orgId))
    }

    private suspend fun cacheState(
        orgId: String,
        state: PolarCustomerState,
    ) {
        try {
            val serialized = json.encodeToString(state)
            redis.setEx(cacheKey(orgId), serialized, cacheTtlSeconds)
        } catch (e: Exception) {
            logger.warn("[billing:polar-cache] Failed to cache state for org={}: {}", orgId, e.message)
        }
    }

    private suspend fun readCachedState(orgId: String): PolarCustomerState? {
        val cached = redis.get(cacheKey(orgId))
        if (cached == null) {
            logger.warn("[billing:polar-cache] No Redis fallback available for org={}", orgId)
            return null
        }

        return try {
            val state = json.decodeFromString<PolarCustomerState>(cached)
            logger.info("[billing:polar-cache] Using Redis fallback for org={} (stale data)", orgId)
            state
        } catch (e: Exception) {
            logger.error("[billing:polar-cache] Failed to deserialize Redis fallback for org={}", orgId, e)
            null
        }
    }

    private fun cacheKey(orgId: String): String = "billing:polar:state:$orgId"
}
