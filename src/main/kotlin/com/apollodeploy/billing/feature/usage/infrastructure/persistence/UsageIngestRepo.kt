package com.apollodeploy.billing.feature.usage.infrastructure.persistence

import com.apollodeploy.billing.infrastructure.polar.PolarClient
import kotlinx.serialization.json.JsonElement

class UsageIngestRepo(
    private val polarClient: PolarClient,
) {
    suspend fun ingestUsageEvent(
        orgId: String,
        eventKey: String,
        quantity: Int,
        metadata: Map<String, JsonElement>,
    ): Boolean =
        polarClient.ingestUsageEvent(
            orgId = orgId,
            eventName = eventKey,
            quantity = quantity,
            metadata = metadata,
        )
}
