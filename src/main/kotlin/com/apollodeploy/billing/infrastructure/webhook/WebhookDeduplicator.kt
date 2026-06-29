package com.apollodeploy.billing.infrastructure.webhook

import com.apollodeploy.billing.infrastructure.redis.RedisPool
import org.slf4j.LoggerFactory

/**
 * Apollo Billing — Redis-backed webhook event deduplicator.
 *
 * Prevents replay attacks by tracking processed webhook IDs in Redis.
 * The dedup window (10 min) is 2x the webhook signature tolerance (5 min)
 * to cover the full replay attack surface.
 *
 * Falls back to allowing the request if Redis is unavailable (signature
 * verification is still the primary security control).
 */
class WebhookDeduplicator(
    private val redis: RedisPool? = null,
    private val dedupWindowSeconds: Long = 600L, // 10 minutes
) {
    private val logger = LoggerFactory.getLogger(WebhookDeduplicator::class.java)

    /**
     * Attempt to mark a webhook as processed.
     * Returns true if this is the FIRST time (not a duplicate).
     * Returns false if it's a DUPLICATE (already processed within dedup window).
     */
    suspend fun tryProcess(webhookId: String): Boolean {
        if (webhookId.isBlank()) return true // Blank IDs can't be deduped

        if (redis == null || !redis.isAvailable) {
            // Redis unavailable — fall through to allow (signature is the primary check)
            logger.debug("[billing:webhook-dedup] Redis unavailable, skipping dedup for webhook-id={}", webhookId)
            return true
        }

        val redisKey = "billing:webhook:dedup:$webhookId"
        val isNew = redis.setNx(redisKey, "1", dedupWindowSeconds)

        if (!isNew) {
            logger.info("[billing:webhook-dedup] Duplicate webhook-id={} — rejecting", webhookId)
            return false
        }

        return true
    }
}
