package com.apollodeploy.billing.feature.checkout.api

import com.apollodeploy.billing.feature.checkout.application.CheckoutService
import com.apollodeploy.billing.feature.checkout.domain.CreateCheckoutRequest
import com.apollodeploy.billing.feature.checkout.domain.CreateCheckoutResult
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond

class CheckoutController(
    private val checkoutService: CheckoutService,
) {
    suspend fun createCheckout(call: ApplicationCall) {
        val req = call.receive<CreateCheckoutRequest>()

        when (val result = checkoutService.createCheckout(req)) {
            is CreateCheckoutResult.Created -> call.respond(HttpStatusCode.OK, result.response)
            is CreateCheckoutResult.UnknownProduct ->
                call.respond(
                    HttpStatusCode.UnprocessableEntity,
                    mapOf(
                        "code" to "billing.unknown_product",
                        "message" to "Unknown product ${result.productSlug} for app ${result.appSlug}",
                    ),
                )
            CreateCheckoutResult.Unavailable ->
                call.respond(
                    HttpStatusCode.BadGateway,
                    mapOf("code" to "billing.checkout_unavailable", "message" to "Unable to create checkout"),
                )
        }
    }
}
