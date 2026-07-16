package com.apollodeploy.billing.feature.subscriptions.api

import com.apollodeploy.billing.feature.common.api.BillingApiErrorResponse
import com.apollodeploy.billing.feature.subscriptions.domain.ActiveSubscriptionsResponse
import com.apollodeploy.tesseract.sdk
import com.apollodeploy.tesseract.sdkDomain
import io.github.smiley4.ktoropenapi.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.route

/**
 * GET /internal/billing/subscriptions
 *
 * Returns all active subscriptions for an org, grouped by app slug.
 * Requires orgId query parameter.
 */
fun Route.subscriptionsRoutes(controller: SubscriptionsController) {
    sdkDomain("/internal/billing/subscriptions", "billingSubscriptions", stability = "internal")

    route("/internal/billing") {
        get("/subscriptions", {
            operationId = "getActiveSubscriptions"
            summary = "Get active subscriptions grouped by app"
            description =
                "Returns all active, trialing, or past-due subscriptions for an organization, grouped by " +
                "registered billing app slug."
            tags("Subscriptions")
            protected = true
            securitySchemeNames("serviceToken")
            request {
                queryParameter<String>("orgId") {
                    description = "Organization ID to retrieve subscriptions for."
                    required = true
                }
            }
            response {
                code(HttpStatusCode.OK) {
                    description = "Active subscriptions grouped by app slug."
                    body<ActiveSubscriptionsResponse> {
                        description =
                            "Map of app slugs to their active subscription items, plus a total count."
                    }
                }
                code(HttpStatusCode.BadRequest) {
                    description = "The required `orgId` query parameter is missing or blank."
                    body<BillingApiErrorResponse>()
                }
                code(HttpStatusCode.Unauthorized) {
                    description = "Missing, expired, or invalid internal service JWT."
                    body<BillingApiErrorResponse>()
                }
                code(HttpStatusCode.InternalServerError) {
                    description = "Failed to retrieve subscriptions due to an unexpected server-side error."
                    body<BillingApiErrorResponse>()
                }
            }
        }) {
            controller.getActiveSubscriptions(call)
        }.sdk {
            operationId = "getActiveSubscriptions"
            methodName = "getActiveSubscriptions"
            internal = true
            queryParam("orgId", required = true, description = "Organization ID to retrieve subscriptions for.")
            response<ActiveSubscriptionsResponse>()
        }
    }
}
