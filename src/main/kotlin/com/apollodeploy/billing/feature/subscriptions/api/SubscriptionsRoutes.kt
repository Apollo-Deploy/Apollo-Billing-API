package com.apollodeploy.billing.feature.subscriptions.api

import com.apollodeploy.billing.feature.common.api.BillingApiErrorResponse
import com.apollodeploy.billing.feature.subscriptions.domain.ActiveSubscriptionsResponse
import com.apollodeploy.billing.feature.subscriptions.domain.CancelSubscriptionResponse
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
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
    route("/internal/billing") {
        get("/subscriptions", {
            operationId = "getActiveSubscriptions"
            summary = "Get active subscriptions grouped by app"
            description =
                "Returns all active, trialing, or past-due subscriptions for an organization, grouped by " +
                "registered billing app slug. Each subscription includes app/plan details, pricing, renewal/cancel " +
                "dates, and status fields needed for a client subscription dashboard."
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
        }

        post("/subscriptions/{subscriptionId}/cancel", {
            operationId = "cancelSubscriptionAtPeriodEnd"
            summary = "Cancel subscription at period end"
            description =
                "Schedules the subscription to cancel at the end of the current billing period. " +
                "Access continues until `endsAt` / `renewalDate`; Polar webhooks keep local state in sync."
            tags("Subscriptions")
            protected = true
            securitySchemeNames("serviceToken")
            request {
                pathParameter<String>("subscriptionId") {
                    description = "Polar subscription ID to cancel."
                    required = true
                }
                queryParameter<String>("orgId") {
                    description = "Organization ID that owns the subscription."
                    required = true
                }
            }
            response {
                code(HttpStatusCode.OK) {
                    description = "Subscription scheduled to cancel at period end."
                    body<CancelSubscriptionResponse>()
                }
                code(HttpStatusCode.BadRequest) {
                    description = "Missing subscriptionId path parameter or orgId query parameter."
                    body<BillingApiErrorResponse>()
                }
                code(HttpStatusCode.Unauthorized) {
                    description = "Missing, expired, or invalid internal service JWT."
                    body<BillingApiErrorResponse>()
                }
                code(HttpStatusCode.NotFound) {
                    description = "No active subscription exists for the org with the given ID."
                    body<BillingApiErrorResponse>()
                }
                code(HttpStatusCode.BadGateway) {
                    description = "Polar was unavailable or returned an unexpected failure."
                    body<BillingApiErrorResponse>()
                }
            }
        }) {
            controller.cancelSubscriptionAtPeriodEnd(call)
        }
    }
}
