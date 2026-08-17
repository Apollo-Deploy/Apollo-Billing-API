package com.apollodeploy.billing.feature.webhook.infrastructure.persistence

import com.apollodeploy.billing.infrastructure.polar.PolarWebhookHandler
import com.apollodeploy.billing.infrastructure.polar.model.PolarWebhookEvent

class PolarWebhookRepo(
    private val polarWebhookHandler: PolarWebhookHandler,
) {
    suspend fun handle(event: PolarWebhookEvent) {
        polarWebhookHandler.handle(event)
    }
}
