package com.apollodeploy.billing.feature.entitlements.api

import com.apollodeploy.billing.feature.entitlements.application.EntitlementsService
import com.apollodeploy.billing.feature.entitlements.domain.EntitlementsResult
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

class EntitlementsController(
    private val entitlementsService: EntitlementsService,
) {
    suspend fun getEntitlements(call: ApplicationCall) {
        val appSlug =
            call.parameters["appSlug"]
                ?: return call.respond(HttpStatusCode.BadRequest)
        val orgId =
            call.parameters["orgId"]
                ?: return call.respond(HttpStatusCode.BadRequest)

        when (val result = entitlementsService.getEntitlements(appSlug, orgId)) {
            is EntitlementsResult.Found -> call.respond(HttpStatusCode.OK, result.response)
            is EntitlementsResult.UnknownApp ->
                call.respond(
                    HttpStatusCode.NotFound,
                    mapOf("message" to "Unknown app: ${result.appSlug}"),
                )
            EntitlementsResult.NoSubscription ->
                call.respond(
                    HttpStatusCode.NotFound,
                    mapOf(
                        "code" to "billing.no_subscription",
                        "message" to "No active subscription for org",
                    ),
                )
            EntitlementsResult.InternalError ->
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("message" to "Internal error"),
                )
        }
    }
}
