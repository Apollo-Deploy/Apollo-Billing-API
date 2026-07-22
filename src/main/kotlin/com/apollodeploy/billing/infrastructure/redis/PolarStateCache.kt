package com.apollodeploy.billing.infrastructure.redis

import com.apollodeploy.billing.infrastructure.polar.PolarClient
import com.apollodeploy.billing.infrastructure.polar.model.PolarCustomerState
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

private const val DEFAULT_CACHE_TTL_SECONDS = 300L
private const val CACHE_KEY_PREFIX = "billing:polar:state:"

private val CACHE_JSON =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

/**
 * Redis-backed fallback cache for Polar customer state.
 *
 * Fresh state is requested from Polar first. When Polar is unavailable,
 * the last successfully cached state is loaded from Redis.
 */
class PolarStateCache(
    private val polarClient: PolarClient,
    private val redis: RedisPool,
    private val cacheTtlSeconds: Long = DEFAULT_CACHE_TTL_SECONDS,
) {
    init {
        require(cacheTtlSeconds > 0) {
            "Cache TTL must be greater than zero"
        }
    }

    suspend fun getCustomerState(orgId: String): PolarCustomerState? {
        val key = CACHE_KEY_PREFIX + orgId

        val freshState =
            try {
                polarClient.getCustomerState(orgId)
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Exception) {
                logger.warn(
                    "Polar customer state unavailable for org={}: {}",
                    orgId,
                    cause.message,
                )
                null
            }

        if (freshState != null) {
            cacheState(
                key = key,
                state = freshState,
                orgId = orgId,
            )
            return freshState
        }

        return readCachedState(
            key = key,
            orgId = orgId,
        )
    }

    suspend fun invalidate(orgId: String) {
        try {
            redis.del(CACHE_KEY_PREFIX + orgId)
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            logger.warn(
                "Failed to invalidate Polar state for org={}: {}",
                orgId,
                cause.message,
            )
        }
    }

    private suspend fun cacheState(
        key: String,
        state: PolarCustomerState,
        orgId: String,
    ) {
        try {
            redis.setEx(
                key = key,
                value = CACHE_JSON.encodeToString(state),
                ttlSeconds = cacheTtlSeconds,
            )
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            logger.warn(
                "Failed to cache Polar state for org={}: {}",
                orgId,
                cause.message,
            )
        }
    }

    private suspend fun readCachedState(
        key: String,
        orgId: String,
    ): PolarCustomerState? {
        val cached =
            try {
                redis.get(key)
            } catch (cause: CancellationException) {
                throw cause
            } catch (cause: Exception) {
                logger.warn(
                    "Redis fallback unavailable for org={}: {}",
                    orgId,
                    cause.message,
                )
                return null
            }

        if (cached == null) {
            logger.debug(
                "No cached Polar state available for org={}",
                orgId,
            )
            return null
        }

        return try {
            CACHE_JSON.decodeFromString<PolarCustomerState>(cached)
        } catch (cause: SerializationException) {
            logger.warn(
                "Invalid cached Polar state for org={}: {}",
                orgId,
                cause.message,
            )
            null
        }
    }

    private companion object {
        val logger =
            LoggerFactory.getLogger(PolarStateCache::class.java)
    }
}