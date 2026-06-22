package com.apollodeploy.billing.feature.health.api

import com.apollodeploy.billing.feature.health.application.HealthService
import com.apollodeploy.billing.feature.health.domain.HealthResponse
import com.apollodeploy.billing.support.billingTestApplication
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class HealthControllerTest {

    private val healthService = mockk<HealthService>()
    private val controller = HealthController(healthService)

    @Test
    fun `GET health returns HTTP 200`() = billingTestApplication(
        routes = { healthRoutes(controller) },
    ) {
        every { healthService.getHealth() } returns HealthResponse("ok", "apollo-billing")

        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `GET health returns correct status and service fields`() = billingTestApplication(
        routes = { healthRoutes(controller) },
    ) {
        every { healthService.getHealth() } returns HealthResponse("ok", "apollo-billing")

        val response = client.get("/health")

        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("ok", body["status"]?.jsonPrimitive?.content)
        assertEquals("apollo-billing", body["service"]?.jsonPrimitive?.content)
    }
}
