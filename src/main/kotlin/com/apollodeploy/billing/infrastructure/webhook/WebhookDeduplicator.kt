package com.apollodeploy.billing.infrastructure.webhook

import com.apollodeploy.billing.infrastructure.redis.RedisPool
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

private const val DEFAULT_DEDUP_WINDOW_SECONDS = 600L
private const val DEDUP_KEY_PREFIX = "billing:webhook:dedup:"
private const val PROCESSED_VALUE = "1"

/**
 * Redis-backed webhook deduplication.
 *
 * Returns true when the webhook may be processed and false when it has
 * already been processed within the configured deduplication window.
 *
 * Fails open when Redis is unavailable because webhook signature
 * verification remains the primary security control.
 */
class WebhookDeduplicator(
    private val redis: RedisPool? = null,
    private val dedupWindowSeconds: Long = DEFAULT_DEDUP_WINDOW_SECONDS,
) {
    init {
        require(dedupWindowSeconds > 0) {
            "Deduplication window must be greater than zero"
        }
    }

    suspend fun tryProcess(webhookId: String): Boolean {
        if (webhookId.isBlank()) {
            return true
        }

        val redis = redis ?: return true

        return try {
            when (
                redis.setNx(
                    key = DEDUP_KEY_PREFIX + webhookId,
                    value = PROCESSED_VALUE,
                    ttlSeconds = dedupWindowSeconds,
                )
            ) {
                true -> true

                false -> {
                    logger.debug(
                        "Duplicate webhook rejected: webhookId={}",
                        webhookId,
                    )
                    false
                }

                null -> true
            }
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            logger.warn(
                "Webhook deduplication unavailable; allowing webhookId={}: {}",
                webhookId,
                cause.message,
            )
            true
        }
    }

    private companion object {
        val logger =
            LoggerFactory.getLogger(WebhookDeduplicator::class.java)
    }
}