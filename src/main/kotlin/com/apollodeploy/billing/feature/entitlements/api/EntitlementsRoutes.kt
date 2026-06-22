package com.apollodeploy.billing.feature.entitlements.api

import com.apollodeploy.billing.feature.common.api.BillingApiErrorResponse
import com.apollodeploy.billing.feature.entitlements.domain.EntitlementsResponse
import com.apollodeploy.tesseract.sdk
import com.apollodeploy.tesseract.sdkDomain
import io.github.smiley4.ktoropenapi.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.route

fun Route.entitlementsRoutes(controller: EntitlementsController) {
    sdkDomain("/internal/billing/entitlements", "billingEntitlements", stability = "internal")

    /**
     * GET /internal/billing/entitlements/{appSlug}/{orgId}
     * Returns the full entitlement snapshot for an org on a given app.
     * Used by consumer apps to render plan/usage dashboards without a DB query.
     */
    route("/internal/billing/entitlements") {
        get("/{appSlug}/{orgId}", {
            operationId = "getBillingEntitlements"
            summary = "Get organization entitlements"
            description =
                "Returns the resolved plan, limits, features, usage, and remaining quota snapshot for an organization " +
                "inside a registered internal app."
            tags("Entitlements")
            protected = true
            securitySchemeNames("serviceToken")
            request {
                pathParameter<String>("appSlug") {
                    description = "Registered billing app slug, for example `signal`."
                    required = true
                }
                pathParameter<String>("orgId") {
                    description = "Internal organization identifier owned by the calling app."
                    required = true
                }
            }
            response {
                code(HttpStatusCode.OK) {
                    description = "Entitlements were resolved successfully."
                    body<EntitlementsResponse> {
                        description = "Current plan and entitlement snapshot for the organization."
                    }
                }
                code(HttpStatusCode.BadRequest) {
                    description = "A required path parameter is missing."
                    body<BillingApiErrorResponse>()
                }
                code(HttpStatusCode.Unauthorized) {
                    description = "Missing, expired, or invalid internal service JWT."
                    body<BillingApiErrorResponse>()
                }
                code(HttpStatusCode.NotFound) {
                    description = "The app is unknown or the organization has no active subscription."
                    body<BillingApiErrorResponse>()
                }
                code(HttpStatusCode.InternalServerError) {
                    description = "Billing could not resolve entitlements due to an unexpected server-side error."
                    body<BillingApiErrorResponse>()
                }
            }
        }) {
            controller.getEntitlements(call)
        }.sdk {
            operationId = "getBillingEntitlements"
            methodName = "getBillingEntitlements"
            internal = true
            response<EntitlementsResponse>()
        }
    }
}
