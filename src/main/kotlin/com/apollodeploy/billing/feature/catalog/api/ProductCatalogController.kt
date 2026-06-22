package com.apollodeploy.billing.feature.catalog.api

import com.apollodeploy.billing.feature.catalog.application.ProductCatalogService
import com.apollodeploy.billing.feature.catalog.domain.ProductCatalogResult
import com.apollodeploy.billing.feature.common.api.BillingApiErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

class ProductCatalogController(
    private val service: ProductCatalogService,
) {
    suspend fun getCatalog(call: ApplicationCall) {
        val appSlug = call.parameters["appSlug"]
        if (appSlug.isNullOrBlank()) {
            call.respond(
                HttpStatusCode.BadRequest,
                BillingApiErrorResponse("billing.missing_app", "Missing appSlug"),
            )
            return
        }

        when (val result = service.getCatalog(appSlug)) {
            is ProductCatalogResult.Found -> call.respond(HttpStatusCode.OK, result.response)
            is ProductCatalogResult.UnknownApp ->
                call.respond(
                    HttpStatusCode.NotFound,
                    BillingApiErrorResponse("billing.unknown_app", "Unknown billing app: ${result.appSlug}"),
                )
            ProductCatalogResult.PricingUnavailable ->
                call.respond(
                    HttpStatusCode.BadGateway,
                    BillingApiErrorResponse(
                        "billing.catalog_pricing_unavailable",
                        "Live product pricing is temporarily unavailable",
                    ),
                )
        }
    }
}
