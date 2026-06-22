package com.apollodeploy.billing.feature.health.api

import com.apollodeploy.billing.feature.health.application.HealthService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

class HealthController(
    private val healthService: HealthService,
) {
    suspend fun getHealth(call: ApplicationCall) {
        call.respond(HttpStatusCode.OK, healthService.getHealth())
    }
}
