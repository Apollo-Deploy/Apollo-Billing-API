package com.apollodeploy.billing.feature.usage.api

import com.apollodeploy.billing.feature.common.api.BillingApiErrorResponse
import com.apollodeploy.billing.feature.usage.domain.UsageIngestRequest
import com.apollodeploy.billing.feature.usage.domain.UsageIngestResponse
import com.apollodeploy.tesseract.sdk
import com.apollodeploy.tesseract.sdkDomain
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.route

/**
 * POST /internal/billing/usage/ingest
 *
 * Replaces the TypeScript platform's /internal/billing/usage/ingest endpoint.
 * Called by Signal (and other apps) to report metered usage to Polar.
 *
 * The caller (e.g. PlatformPolarUsageSyncClient in Signal) sends:
 *   { "orgId": "org_xxx", "eventKey": "signal.automation.run", "quantity": 1 }
 *
 * This service forwards it to Polar's event ingestion API.
 */
fun Route.usageIngestRoutes(controller: UsageIngestController) {
    sdkDomain("/internal/billing/usage", "billingUsage", stability = "internal")

    route("/internal/billing/usage") {
        post("/ingest", {
            operationId = "ingestBillingUsage"
            summary = "Ingest metered usage"
            description =
                "Accepts metered usage from an internal app backend and forwards it to Polar event ingestion. " +
                "Callers should treat both 200 and 202 as non-blocking for end-user requests."
            tags("Usage")
            protected = true
            securitySchemeNames("serviceToken")
            request {
                body<UsageIngestRequest> {
                    description =
                        "Usage event to send to Polar, including organization, event key, quantity, and optional " +
                        "metadata."
                    required = true
                }
            }
            response {
                code(HttpStatusCode.OK) {
                    description = "Usage was accepted and forwarded to Polar."
                    body<UsageIngestResponse> {
                        description = "Accepted usage response."
                    }
                }
                code(HttpStatusCode.Accepted) {
                    description =
                        "Usage was accepted by this service but Polar was unavailable. The caller should not retry inline."
                    body<UsageIngestResponse> {
                        description = "Non-fatal usage ingestion response."
                    }
                }
                code(HttpStatusCode.Unauthorized) {
                    description = "Missing, expired, or invalid internal service JWT."
                    body<BillingApiErrorResponse>()
                }
            }
        }) {
            controller.ingest(call)
        }.sdk {
            operationId = "ingestBillingUsage"
            methodName = "ingestBillingUsage"
            internal = true
            requestBody<UsageIngestRequest>()
            response<UsageIngestResponse>()
        }
    }
}
