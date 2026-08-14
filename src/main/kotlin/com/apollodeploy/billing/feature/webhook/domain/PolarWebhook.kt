package com.apollodeploy.billing.feature.webhook.domain

import kotlinx.serialization.Serializable

@Serializable
data class PolarWebhookResponse(
    val received: Boolean,
)

/**
 * SDK/OpenAPI stand-in for Polar webhook payloads.
 *
 * The handler verifies and parses the raw body as
 * [com.apollodeploy.billing.infrastructure.polar.model.PolarWebhookEvent];
 * event `data` varies by type and must not appear as JsonElement/unknown in the generated SDK.
 */
@Serializable
data class PolarWebhookEventRequest(
    val type: String,
)

sealed class PolarWebhookResult {
    data object Received : PolarWebhookResult()

    data object InvalidSignature : PolarWebhookResult()

    data object InvalidPayload : PolarWebhookResult()

    data object HandlerError : PolarWebhookResult()
}
