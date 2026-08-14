package com.apollodeploy.billing.feature.catalog.api

import com.apollodeploy.billing.feature.catalog.domain.ProductCatalogResponse
import com.apollodeploy.billing.feature.common.api.BillingApiErrorResponse
import io.github.smiley4.ktoropenapi.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.route

fun Route.productCatalogRoutes(controller: ProductCatalogController) {
    route("/billing/catalog") {
        get("/{appSlug}", {
            operationId = "getBillingProductCatalog"
            summary = "Get product catalog"
            description =
                "Returns the registered product catalog for an internal app, including plans, add-ons, usage-based " +
                "products, and one-time purchases. This endpoint is public so client and marketing pricing pages " +
                "can show live Polar prices without exposing Polar API keys."
            tags("Catalog")
            request {
                pathParameter<String>("appSlug") {
                    description = "Registered billing app slug, for example `signal`."
                    required = true
                }
            }
            response {
                code(HttpStatusCode.OK) {
                    description = "Product catalog resolved successfully."
                    body<ProductCatalogResponse> {
                        description = "Product catalog with checkout slugs, product kinds, current prices, and plan entitlements."
                    }
                }
                code(HttpStatusCode.BadRequest) {
                    description = "A required path parameter is missing."
                    body<BillingApiErrorResponse>()
                }
                code(HttpStatusCode.Unauthorized) {
                    description = "Reserved for deployments that add edge or gateway-level protection."
                    body<BillingApiErrorResponse>()
                }
                code(HttpStatusCode.NotFound) {
                    description = "The requested app is not registered in billing."
                    body<BillingApiErrorResponse>()
                }
                code(HttpStatusCode.BadGateway) {
                    description = "Live Polar product pricing is temporarily unavailable."
                    body<BillingApiErrorResponse>()
                }
            }
        }) {
            controller.getCatalog(call)
        }
    }
}
