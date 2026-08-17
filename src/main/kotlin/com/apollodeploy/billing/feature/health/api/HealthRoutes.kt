package com.apollodeploy.billing.feature.health.api

import com.apollodeploy.billing.feature.health.domain.HealthResponse
import io.github.smiley4.ktoropenapi.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route

fun Route.healthRoutes(controller: HealthController) {
    get("/health", {
        operationId = "getHealth"
        summary = "Check service health"
        description = "Returns a lightweight readiness response for load balancers, uptime checks, and local development."
        tags("Health")
        response {
            code(HttpStatusCode.OK) {
                description = "The billing API is running and able to serve requests."
                body<HealthResponse> {
                    description = "Service health payload."
                }
            }
        }
    }) {
        controller.getHealth(call)
    }
}
