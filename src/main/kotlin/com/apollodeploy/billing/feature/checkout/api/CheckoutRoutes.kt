package com.apollodeploy.billing.feature.checkout.api

import com.apollodeploy.billing.feature.checkout.domain.CreateCheckoutRequest
import com.apollodeploy.billing.feature.checkout.domain.CreateCheckoutResponse
import com.apollodeploy.billing.feature.common.api.BillingApiErrorResponse
import com.apollodeploy.tesseract.sdk
import com.apollodeploy.tesseract.sdkDomain
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.route

/**
 * POST /internal/billing/checkout
 *
 * Creates a Polar checkout session for a registered product. Works for both
 * subscription products and one-time products because product ownership/type
 * comes from the shared [AppRegistry].
 */
fun Route.checkoutRoutes(controller: CheckoutController) {
    sdkDomain("/internal/billing/checkout", "billingCheckout", stability = "internal")

    route("/internal/billing") {
        post("/checkout", {
            operationId = "createBillingCheckout"
            summary = "Create checkout session"
            description =
                "Creates a Polar checkout session for a registered subscription, add-on, or one-time product. " +
                "Internal app backends call this after authorizing the user for the target organization."
            tags("Checkout")
            protected = true
            securitySchemeNames("serviceToken")
            request {
                body<CreateCheckoutRequest> {
                    description =
                        "Checkout target and optional customer/session details. `appSlug` and `productSlug` must " +
                        "match a product registered in the billing app catalog."
                    required = true
                }
            }
            response {
                code(HttpStatusCode.OK) {
                    description = "Polar checkout session created successfully."
                    body<CreateCheckoutResponse> {
                        description = "Checkout session identifier, customer-facing URL, expiration, and product kind."
                    }
                }
                code(HttpStatusCode.Unauthorized) {
                    description = "Missing, expired, or invalid internal service JWT."
                    body<BillingApiErrorResponse>()
                }
                code(HttpStatusCode.UnprocessableEntity) {
                    description = "The requested app/product pair is not registered in billing."
                    body<BillingApiErrorResponse>()
                }
                code(HttpStatusCode.BadGateway) {
                    description = "Polar did not create a checkout session or was temporarily unavailable."
                    body<BillingApiErrorResponse>()
                }
            }
        }) {
            controller.createCheckout(call)
        }.sdk {
            operationId = "createBillingCheckout"
            methodName = "createBillingCheckout"
            internal = true
            requestBody<CreateCheckoutRequest>()
            response<CreateCheckoutResponse>()
        }
    }
}
