package com.apollodeploy.billing.feature.webhook.infrastructure.persistence

import com.apollodeploy.billing.infrastructure.polar.PolarWebhookEvent
import com.apollodeploy.billing.infrastructure.polar.PolarWebhookHandler

class PolarWebhookRepo(
    private val polarWebhookHandler: PolarWebhookHandler,
) {
    suspend fun handle(event: PolarWebhookEvent) {
        polarWebhookHandler.handle(event)
    }
}
