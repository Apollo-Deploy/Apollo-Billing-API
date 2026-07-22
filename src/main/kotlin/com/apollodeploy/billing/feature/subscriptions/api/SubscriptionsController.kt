package com.apollodeploy.billing.feature.subscriptions.api

import com.apollodeploy.billing.feature.subscriptions.application.SubscriptionsService
import com.apollodeploy.billing.feature.subscriptions.domain.ActiveSubscriptionsResult
import com.apollodeploy.billing.feature.subscriptions.domain.CancelSubscriptionResult
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

class SubscriptionsController(
    private val subscriptionsService: SubscriptionsService,
) {
    suspend fun getActiveSubscriptions(call: ApplicationCall) {
        val orgId =
            call.request.queryParameters["orgId"]?.takeIf { it.isNotBlank() }
                ?: return call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("code" to "billing.invalid_request", "message" to "Missing required query parameter: orgId"),
                )

        when (val result = subscriptionsService.getActiveSubscriptions(orgId)) {
            is ActiveSubscriptionsResult.Found -> call.respond(HttpStatusCode.OK, result.response)
            ActiveSubscriptionsResult.InternalError ->
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("code" to "billing.internal_error", "message" to "Failed to retrieve subscriptions"),
                )
        }
    }

    suspend fun cancelSubscriptionAtPeriodEnd(call: ApplicationCall) {
        val subscriptionId =
            call.parameters["subscriptionId"]
                ?: return call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("code" to "billing.invalid_request", "message" to "Missing subscriptionId path parameter"),
                )
        val orgId =
            call.request.queryParameters["orgId"]?.takeIf { it.isNotBlank() }
                ?: return call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("code" to "billing.invalid_request", "message" to "Missing required query parameter: orgId"),
                )

        when (val result = subscriptionsService.cancelSubscriptionAtPeriodEnd(orgId, subscriptionId)) {
            is CancelSubscriptionResult.Canceled -> call.respond(HttpStatusCode.OK, result.response)
            is CancelSubscriptionResult.NotFound ->
                call.respond(
                    HttpStatusCode.NotFound,
                    mapOf(
                        "code" to "billing.subscription_not_found",
                        "message" to "Subscription not found: $subscriptionId",
                    ),
                )
            CancelSubscriptionResult.PolarUnavailable ->
                call.respond(
                    HttpStatusCode.BadGateway,
                    mapOf("code" to "billing.provider_unavailable", "message" to "Billing provider unavailable"),
                )
        }
    }
}
