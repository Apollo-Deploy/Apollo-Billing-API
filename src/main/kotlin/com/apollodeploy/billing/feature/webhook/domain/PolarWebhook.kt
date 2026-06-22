package com.apollodeploy.billing.feature.webhook.domain

import kotlinx.serialization.Serializable

@Serializable
data class PolarWebhookResponse(
    val received: Boolean,
)

sealed class PolarWebhookResult {
    data object Received : PolarWebhookResult()

    data object InvalidSignature : PolarWebhookResult()

    data object InvalidPayload : PolarWebhookResult()

    data object HandlerError : PolarWebhookResult()
}
