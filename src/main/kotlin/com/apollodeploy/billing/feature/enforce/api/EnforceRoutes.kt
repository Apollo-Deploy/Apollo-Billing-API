package com.apollodeploy.billing.feature.enforce.api

import com.apollodeploy.billing.feature.common.api.BillingApiErrorResponse
import com.apollodeploy.billing.feature.enforce.domain.*
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.route

fun Route.enforceRoutes(controller: EnforceController) {
    route("/internal/billing") {
        post("/enforce", {
            operationId = "enforceBillingCheck"
            summary = "Enforce billing access"
            description =
                "Checks whether an organization may perform a billing-gated action for an internal app. " +
                "Use this before executing paid features, quota-limited actions, or credit-backed work."
            tags("Enforcement")
            protected = true
            securitySchemeNames("serviceToken")
            request {
                body<EnforceRequest> {
                    description =
                        "The app, organization, and check to enforce. The check may be a quota, feature flag, or " +
                        "Polar credit-backed meter balance."
                    required = true
                }
            }
            response {
                code(HttpStatusCode.OK) {
                    description = "The action is allowed."
                    body<EnforceResponse> {
                        description = "Allow/deny decision for the requested billing check."
                    }
                }
                code(HttpStatusCode.Unauthorized) {
                    description = "Missing, expired, or invalid internal service JWT."
                    body<BillingApiErrorResponse>()
                }
                code(HttpStatusCode.NotFound) {
                    description = "No active subscription exists for the requested app and organization."
                    body<BillingErrorResponse>()
                }
                code(HttpStatusCode.PaymentRequired) {
                    description =
                        "The subscription does not include the requested feature or has exhausted a quota/meter."
                    body<BillingErrorResponse>()
                }
                code(HttpStatusCode.UnprocessableEntity) {
                    description = "The requested app slug is not registered in billing."
                    body<BillingErrorResponse>()
                }
                code(HttpStatusCode.InternalServerError) {
                    description = "Billing could not complete the check due to an unexpected server-side error."
                    body<BillingErrorResponse>()
                }
            }
        }) {
            controller.enforce(call)
        }
    }
}
