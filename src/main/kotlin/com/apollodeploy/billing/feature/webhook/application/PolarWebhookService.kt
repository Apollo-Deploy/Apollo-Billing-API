package com.apollodeploy.billing.feature.webhook.application

import com.apollodeploy.billing.feature.webhook.domain.PolarWebhookResult
import com.apollodeploy.billing.feature.webhook.infrastructure.persistence.PolarWebhookRepo
import com.apollodeploy.billing.infrastructure.config.AppConfig
import com.apollodeploy.billing.infrastructure.polar.PolarWebhookVerifier
import com.apollodeploy.billing.infrastructure.polar.model.PolarWebhookEvent
import com.apollodeploy.billing.infrastructure.webhook.WebhookDeduplicator
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class PolarWebhookService(
    private val repository: PolarWebhookRepo,
    private val deduplicator: WebhookDeduplicator = WebhookDeduplicator(),
) {
    private val logger = LoggerFactory.getLogger(PolarWebhookService::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun receive(
        rawBody: ByteArray,
        webhookId: String,
        webhookTimestamp: String,
        signature: String,
    ): PolarWebhookResult {
        if (!PolarWebhookVerifier.verify(
                payload = rawBody,
                webhookId = webhookId,
                webhookTimestamp = webhookTimestamp,
                signatureHeader = signature,
                secret = AppConfig.polar.webhookSecret,
            )
        ) {
            logger.warn("[billing:webhook] invalid signature from Polar - rejecting")
            return PolarWebhookResult.InvalidSignature
        }

        // Deduplicate: reject replayed webhooks within the dedup window
        if (!deduplicator.tryProcess(webhookId)) {
            logger.info("[billing:webhook] duplicate webhook-id={} — skipping", webhookId)
            return PolarWebhookResult.Received // Return 200 so Polar doesn't retry
        }

        val event =
            try {
                json.decodeFromString<PolarWebhookEvent>(rawBody.decodeToString())
            } catch (e: Exception) {
                logger.error("[billing:webhook] failed to parse Polar event", e)
                return PolarWebhookResult.InvalidPayload
            }

        return try {
            repository.handle(event)
            PolarWebhookResult.Received
        } catch (e: Exception) {
            logger.error("[billing:webhook] handler threw for event type={}", event.type, e)
            PolarWebhookResult.HandlerError
        }
    }
}
