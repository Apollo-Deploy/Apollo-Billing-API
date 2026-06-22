package com.apollodeploy.billing.feature.webhook.application

import com.apollodeploy.billing.feature.webhook.domain.PolarWebhookResult
import com.apollodeploy.billing.feature.webhook.infrastructure.persistence.PolarWebhookRepo
import com.apollodeploy.billing.infrastructure.config.AppConfig
import com.apollodeploy.billing.infrastructure.polar.PolarWebhookEvent
import com.apollodeploy.billing.infrastructure.polar.PolarWebhookVerifier
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class PolarWebhookService(
    private val polarWebhookRepo: PolarWebhookRepo,
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
                secret = AppConfig.polarWebhookSecret,
            )
        ) {
            logger.warn("[billing:webhook] invalid signature from Polar - rejecting")
            return PolarWebhookResult.InvalidSignature
        }

        val event =
            try {
                json.decodeFromString<PolarWebhookEvent>(rawBody.decodeToString())
            } catch (e: Exception) {
                logger.error("[billing:webhook] failed to parse Polar event", e)
                return PolarWebhookResult.InvalidPayload
            }

        return try {
            polarWebhookRepo.handle(event)
            PolarWebhookResult.Received
        } catch (e: Exception) {
            logger.error("[billing:webhook] handler threw for event type={}", event.type, e)
            PolarWebhookResult.HandlerError
        }
    }
}
