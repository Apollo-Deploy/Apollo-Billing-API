package com.apollodeploy.billing.feature.webhook.api

import com.apollodeploy.billing.feature.common.api.BillingApiErrorResponse
import com.apollodeploy.billing.feature.webhook.domain.PolarWebhookResponse
import com.apollodeploy.billing.infrastructure.polar.model.PolarWebhookEvent
import com.apollodeploy.tesseract.sdk
import com.apollodeploy.tesseract.sdkDomain
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.route

/**
 * POST /webhooks/polar
 *
 * Public endpoint — receives Polar lifecycle events.
 * Protected by Standard Webhooks signature verification.
 *
 * Polar sends:
 *   webhook-id, webhook-timestamp, webhook-signature
 */
fun Route.polarWebhookRoutes(controller: PolarWebhookController) {
    sdkDomain("/webhooks/polar", "polarWebhooks", stability = "internal")

    route("/webhooks") {
        post("/polar", {
            operationId = "receivePolarWebhook"
            summary = "Receive Polar webhook"
            description =
                "Public Polar webhook endpoint. The request body is verified with Standard Webhooks headers before " +
                "subscription/customer state is updated."
            tags("Webhooks")
            request {
                headerParameter<String>("webhook-id") {
                    description = "Standard Webhooks message identifier supplied by Polar."
                    required = true
                }
                headerParameter<String>("webhook-timestamp") {
                    description = "Standard Webhooks message timestamp supplied by Polar."
                    required = true
                }
                headerParameter<String>("webhook-signature") {
                    description = "Standard Webhooks HMAC signature supplied by Polar."
                    required = true
                }
                body<PolarWebhookEvent> {
                    description = "Polar lifecycle event payload."
                    required = true
                }
            }
            response {
                code(HttpStatusCode.OK) {
                    description = "Webhook was verified, parsed, and handled successfully."
                    body<PolarWebhookResponse> {
                        description = "Acknowledgement returned to Polar."
                    }
                }
                code(HttpStatusCode.Unauthorized) {
                    description = "Webhook signature verification failed."
                    body<BillingApiErrorResponse>()
                }
                code(HttpStatusCode.BadRequest) {
                    description = "Webhook payload could not be parsed."
                    body<BillingApiErrorResponse>()
                }
                code(HttpStatusCode.InternalServerError) {
                    description = "Webhook handler failed. Polar should retry delivery."
                    body<BillingApiErrorResponse>()
                }
            }
        }) {
            controller.receive(call)
        }.sdk {
            operationId = "receivePolarWebhook"
            methodName = "receivePolarWebhook"
            internal = true
            requestBody<PolarWebhookEvent>()
            response<PolarWebhookResponse>()
        }
    }
}
