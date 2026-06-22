package com.apollodeploy.billing.feature.webhook.api

import com.apollodeploy.billing.feature.webhook.application.PolarWebhookService
import com.apollodeploy.billing.feature.webhook.domain.PolarWebhookResponse
import com.apollodeploy.billing.feature.webhook.domain.PolarWebhookResult
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.respond

class PolarWebhookController(
    private val polarWebhookService: PolarWebhookService,
) {
    suspend fun receive(call: ApplicationCall) {
        val result =
            polarWebhookService.receive(
                rawBody = call.receive<ByteArray>(),
                webhookId = call.request.header("webhook-id") ?: "",
                webhookTimestamp = call.request.header("webhook-timestamp") ?: "",
                signature = call.request.header("webhook-signature") ?: "",
            )

        when (result) {
            PolarWebhookResult.Received -> call.respond(HttpStatusCode.OK, PolarWebhookResponse(received = true))
            PolarWebhookResult.InvalidSignature ->
                call.respond(
                    HttpStatusCode.Unauthorized,
                    mapOf("error" to "invalid_signature"),
                )
            PolarWebhookResult.InvalidPayload ->
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "invalid_payload"),
                )
            PolarWebhookResult.HandlerError ->
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to "handler_error"),
                )
        }
    }
}
