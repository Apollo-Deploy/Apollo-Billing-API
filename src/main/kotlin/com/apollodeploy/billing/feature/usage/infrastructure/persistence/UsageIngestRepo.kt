package com.apollodeploy.billing.feature.usage.infrastructure.persistence

import com.apollodeploy.billing.infrastructure.polar.PolarClient
import com.apollodeploy.billing.infrastructure.redis.RedisPool
import kotlinx.serialization.json.JsonElement

class UsageIngestRepo(
    private val polarClient: PolarClient,
    private val redis: RedisPool? = null,
) {
    companion object {
        private const val IDEMPOTENCY_TTL_SECONDS = 3600L // 1 hour
    }

    suspend fun ingestUsageEvent(
        orgId: String,
        eventKey: String,
        quantity: Int,
        idempotencyKey: String? = null,
        metadata: Map<String, JsonElement>,
    ): Boolean {
        // Redis-backed idempotency check
        if (idempotencyKey != null && redis != null) {
            val redisKey = "billing:usage:idem:$orgId:$eventKey:$idempotencyKey"
            val isNew = redis.setNx(redisKey, "1", IDEMPOTENCY_TTL_SECONDS)
            if (!isNew) {
                return true // Already processed — return success without re-ingesting
            }
        }

        return polarClient.ingestUsageEvent(
            orgId = orgId,
            eventName = eventKey,
            quantity = quantity,
            metadata = metadata,
        )
    }
}
