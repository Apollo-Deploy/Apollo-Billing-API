package com.apollodeploy.billing.infrastructure.redis

import com.apollodeploy.billing.infrastructure.config.AppConfig
import io.lettuce.core.RedisURI
import io.lettuce.core.SetArgs
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import kotlinx.coroutines.future.await
import org.slf4j.LoggerFactory
import io.lettuce.core.RedisClient as LettuceClient

/**
 * Shared asynchronous Redis connection.
 *
 * Lettuce connections are thread-safe and support concurrent async commands,
 * so a separate connection pool is unnecessary for normal Redis operations.
 */
class RedisPool private constructor(
    private val client: LettuceClient?,
    private val connection: StatefulRedisConnection<String, String>?,
    private val commands: RedisAsyncCommands<String, String>?,
) : AutoCloseable {
    /**
     * Indicates whether the Redis connection exists and is currently open.
     *
     * This is not a health check. Redis can still become unavailable between
     * this check and the next command.
     */
    val isAvailable: Boolean
        get() = connection?.isOpen == true

    /**
     * Returns the stored value.
     *
     * Returns null when:
     * - the key does not exist
     * - Redis was unavailable when this instance was created
     *
     * Runtime Redis failures are propagated to the caller.
     */
    suspend fun get(key: String): String? = commands?.get(key)?.await()

    /**
     * Stores a value with an expiry.
     *
     * Returns false when Redis was unavailable during startup.
     * Runtime Redis failures are propagated to the caller.
     */
    suspend fun setEx(
        key: String,
        value: String,
        ttlSeconds: Long,
    ): Boolean {
        requirePositiveTtl(ttlSeconds)

        val commands = commands ?: return false
        val arguments = SetArgs.Builder.ex(ttlSeconds)

        return commands.set(key, value, arguments).await() == OK
    }

    /**
     * Atomically stores a value only when the key does not already exist,
     * with the expiry applied in the same Redis command.
     *
     * Returns:
     * - true when the key was created
     * - false when the key already existed
     * - null when Redis was unavailable during startup
     *
     * Runtime Redis failures are propagated to the caller.
     */
    suspend fun setNx(
        key: String,
        value: String,
        ttlSeconds: Long,
    ): Boolean? {
        requirePositiveTtl(ttlSeconds)

        val commands = commands ?: return null
        val arguments =
            SetArgs.Builder
                .nx()
                .ex(ttlSeconds)

        return commands.set(key, value, arguments).await() == OK
    }

    /**
     * Deletes a key.
     *
     * Returns true when a key was removed.
     * Returns false when the key did not exist or Redis was unavailable
     * during startup.
     */
    suspend fun del(key: String): Boolean {
        val commands = commands ?: return false
        return commands.del(key).await() > 0
    }

    override fun close() {
        try {
            connection?.close()
        } finally {
            client?.shutdown()
        }
    }

    companion object {
        private const val OK = "OK"

        private val logger =
            LoggerFactory.getLogger(RedisPool::class.java)

        private val unavailable =
            RedisPool(
                client = null,
                connection = null,
                commands = null,
            )

        fun create(): RedisPool {
            var client: LettuceClient? = null

            return try {
                val uri =
                    RedisURI
                        .builder()
                        .withHost(AppConfig.redis.host)
                        .withPort(AppConfig.redis.port)
                        .withDatabase(AppConfig.redis.database)
                        .apply {
                            val password = AppConfig.redis.password

                            if (password.isNotBlank()) {
                                withPassword(password.toCharArray())
                            }
                        }.build()

                val createdClient = LettuceClient.create(uri)
                client = createdClient

                val connection = createdClient.connect()

                logger.info(
                    "Connected to Redis at {}:{} using database {}",
                    AppConfig.redis.host,
                    AppConfig.redis.port,
                    AppConfig.redis.database,
                )

                RedisPool(
                    client = createdClient,
                    connection = connection,
                    commands = connection.async(),
                )
            } catch (cause: Exception) {
                try {
                    client?.shutdown()
                } catch (shutdownCause: Exception) {
                    cause.addSuppressed(shutdownCause)
                }

                logger.warn(
                    "Failed to connect to Redis; Redis-backed features are disabled: {}",
                    cause.message,
                )

                unavailable
            }
        }

        /**
         * Connection-free instance for offline OpenAPI export.
         */
        fun createStub(): RedisPool = unavailable

        private fun requirePositiveTtl(ttlSeconds: Long) {
            require(ttlSeconds > 0) {
                "Redis TTL must be greater than zero"
            }
        }
    }
}
