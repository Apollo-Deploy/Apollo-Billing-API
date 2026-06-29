package com.apollodeploy.billing.infrastructure.redis

import com.apollodeploy.billing.infrastructure.config.AppConfig
import io.lettuce.core.RedisClient as LettuceClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import kotlinx.coroutines.future.await
import org.slf4j.LoggerFactory

/**
 * Apollo Billing — Redis client wrapper.
 *
 * Provides async coroutine-friendly access to Redis for:
 *   - Polar customer state caching (fallback when Polar is offline)
 *   - Usage ingestion idempotency keys
 *   - Webhook deduplication
 *
 * Uses Lettuce with async commands + Kotlin coroutine `await()`.
 */
class RedisPool private constructor(
    private val client: LettuceClient?,
    private val connection: StatefulRedisConnection<String, String>?,
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(RedisPool::class.java)

    val isAvailable: Boolean get() = connection != null && connection.isOpen

    companion object {
        fun create(): RedisPool {
            val logger = LoggerFactory.getLogger(RedisPool::class.java)
            return try {
                val uri = RedisURI.builder()
                    .withHost(AppConfig.redisHost)
                    .withPort(AppConfig.redisPort)
                    .apply {
                        val pass = AppConfig.redisPassword
                        if (pass.isNotBlank()) withPassword(pass.toCharArray())
                    }
                    .build()

                val client = LettuceClient.create(uri)
                val connection = client.connect()
                logger.info("[billing:redis] Connected to Redis at {}:{}", AppConfig.redisHost, AppConfig.redisPort)
                RedisPool(client, connection)
            } catch (e: Exception) {
                logger.warn("[billing:redis] Failed to connect to Redis: {} — fallback cache disabled", e.message)
                RedisPool(null, null)
            }
        }

        /** Stub for manifest/SDK generation mode. */
        fun createStub(): RedisPool = RedisPool(null, null)
    }

    private fun commands(): RedisAsyncCommands<String, String>? = connection?.async()

    /** GET with coroutine await. Returns null if Redis unavailable or key absent. */
    suspend fun get(key: String): String? {
        val cmd = commands() ?: return null
        return try {
            cmd.get(key).await()
        } catch (e: Exception) {
            logger.warn("[billing:redis] GET failed key={}: {}", key, e.message)
            null
        }
    }

    /** SET with TTL (seconds). Fire-and-forget on failure. */
    suspend fun setEx(key: String, value: String, ttlSeconds: Long) {
        val cmd = commands() ?: return
        try {
            cmd.setex(key, ttlSeconds, value).await()
        } catch (e: Exception) {
            logger.warn("[billing:redis] SETEX failed key={}: {}", key, e.message)
        }
    }

    /** SET NX (only if not exists) with TTL. Returns true if set, false if already exists. */
    suspend fun setNx(key: String, value: String, ttlSeconds: Long): Boolean {
        val cmd = commands() ?: return false
        return try {
            val result = cmd.setnx(key, value).await()
            if (result) cmd.expire(key, ttlSeconds).await()
            result
        } catch (e: Exception) {
            logger.warn("[billing:redis] SETNX failed key={}: {}", key, e.message)
            false
        }
    }

    /** DEL a key. */
    suspend fun del(key: String) {
        val cmd = commands() ?: return
        try {
            cmd.del(key).await()
        } catch (e: Exception) {
            logger.warn("[billing:redis] DEL failed key={}: {}", key, e.message)
        }
    }

    override fun close() {
        runCatching { connection?.close() }
        runCatching { client?.shutdown() }
    }
}
