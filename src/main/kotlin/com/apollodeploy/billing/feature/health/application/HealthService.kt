package com.apollodeploy.billing.feature.health.application

import com.apollodeploy.billing.feature.health.domain.HealthResponse

class HealthService {
    fun getHealth(): HealthResponse = HealthResponse(status = "ok", service = "apollo-billing")
}
