package com.apollodeploy.billing.feature.entitlements.api

import com.apollodeploy.billing.feature.entitlements.application.EntitlementsService
import com.apollodeploy.billing.feature.entitlements.domain.EntitlementsResponse
import com.apollodeploy.billing.feature.entitlements.domain.EntitlementsResult
import com.apollodeploy.billing.support.billingTestApplication
import com.apollodeploy.billing.support.noAuthInternalRoutes
import com.apollodeploy.billing.support.validServiceToken
import io.kotest.property.Arb
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.string
import io.kotest.property.forAll
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class EntitlementsControllerTest {
    private val entitlementsService = mockk<EntitlementsService>()
    private val controller = EntitlementsController(entitlementsService)

    @Test
    fun `GET entitlements without Authorization header returns HTTP 401`() =
        billingTestApplication(
            routes = { noAuthInternalRoutes { entitlementsRoutes(controller) } },
        ) {
            val response = client.get("/internal/billing/entitlements/signal/org_1")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }

    @Test
    fun `GET entitlements Found returns HTTP 200 with all fields`() =
        billingTestApplication(
            routes = { noAuthInternalRoutes { entitlementsRoutes(controller) } },
        ) {
            coEvery { entitlementsService.getEntitlements("signal", "org_1") } returns
                EntitlementsResult.Found(
                    EntitlementsResponse(
                        appSlug = "signal",
                        orgId = "org_1",
                        planId = "plan_free",
                        limits = emptyMap(),
                        features = emptyMap(),
                        usage = emptyMap(),
                        remaining = emptyMap(),
                    ),
                )

            val response =
                client.get("/internal/billing/entitlements/signal/org_1") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertNotNull(body["appSlug"])
            assertNotNull(body["orgId"])
            assertNotNull(body["planId"])
            assertNotNull(body["limits"])
            assertNotNull(body["features"])
            assertNotNull(body["usage"])
            assertNotNull(body["remaining"])
        }

    @Test
    fun `GET entitlements UnknownApp returns HTTP 404 with message field`() =
        billingTestApplication(
            routes = { noAuthInternalRoutes { entitlementsRoutes(controller) } },
        ) {
            coEvery { entitlementsService.getEntitlements("signal", "org_1") } returns
                EntitlementsResult.UnknownApp("signal")

            val response =
                client.get("/internal/billing/entitlements/signal/org_1") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertNotNull(body["message"])
        }

    @Test
    fun `GET entitlements NoSubscription returns HTTP 404 with billing_no_subscription code`() =
        billingTestApplication(
            routes = { noAuthInternalRoutes { entitlementsRoutes(controller) } },
        ) {
            coEvery { entitlementsService.getEntitlements("signal", "org_1") } returns
                EntitlementsResult.NoSubscription

            val response =
                client.get("/internal/billing/entitlements/signal/org_1") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                }

            assertEquals(HttpStatusCode.NotFound, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("billing.no_subscription", body["code"]?.jsonPrimitive?.content)
        }

    @Test
    fun `GET entitlements InternalError returns HTTP 500`() =
        billingTestApplication(
            routes = { noAuthInternalRoutes { entitlementsRoutes(controller) } },
        ) {
            coEvery { entitlementsService.getEntitlements("signal", "org_1") } returns
                EntitlementsResult.InternalError

            val response =
                client.get("/internal/billing/entitlements/signal/org_1") {
                    header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                }

            assertEquals(HttpStatusCode.InternalServerError, response.status)
        }

    /**
     * Property 5: Entitlements Found response preserves all fields.
     *
     * For any EntitlementsResponse with arbitrary planId, limits, features, usage, and remaining maps,
     * when EntitlementsService.getEntitlements is stubbed to return EntitlementsResult.Found(response),
     * the JSON body SHALL contain fields appSlug, orgId, planId, limits, features, usage, and remaining,
     * each matching the corresponding field in the stub response.
     *
     * **Validates: Requirements 5.2**
     */
    @Test
    fun `Property 5 - Found response preserves all fields for arbitrary planId`() =
        runBlocking {
            val nonBlankPlanId = Arb.string(minSize = 1, maxSize = 50).filter { it.isNotBlank() }

            forAll(nonBlankPlanId) { planId ->
                var result = false
                val stubResponse =
                    EntitlementsResponse(
                        appSlug = "signal",
                        orgId = "org_1",
                        planId = planId,
                        limits = mapOf("deployments" to 10, "seats" to 5),
                        features = mapOf("ci" to true, "analytics" to false),
                        usage = mapOf("deployments" to 3),
                        remaining = mapOf("deployments" to "7"),
                    )

                billingTestApplication(
                    routes = { noAuthInternalRoutes { entitlementsRoutes(controller) } },
                ) {
                    coEvery { entitlementsService.getEntitlements("signal", "org_1") } returns
                        EntitlementsResult.Found(stubResponse)

                    val response =
                        client.get("/internal/billing/entitlements/signal/org_1") {
                            header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
                        }

                    if (response.status == HttpStatusCode.OK) {
                        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                        val appSlugMatch = body["appSlug"]?.jsonPrimitive?.content == stubResponse.appSlug
                        val orgIdMatch = body["orgId"]?.jsonPrimitive?.content == stubResponse.orgId
                        val planIdMatch = body["planId"]?.jsonPrimitive?.content == stubResponse.planId
                        result = appSlugMatch &&
                            orgIdMatch &&
                            planIdMatch &&
                            body["limits"] != null &&
                            body["features"] != null &&
                            body["usage"] != null &&
                            body["remaining"] != null
                    }
                }
                result
            }
            Unit
        }
}
