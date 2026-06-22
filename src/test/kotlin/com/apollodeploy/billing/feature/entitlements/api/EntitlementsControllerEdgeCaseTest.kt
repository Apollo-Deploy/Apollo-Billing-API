package com.apollodeploy.billing.feature.entitlements.api

import com.apollodeploy.billing.feature.entitlements.application.EntitlementsService
import com.apollodeploy.billing.support.noAuthInternalRoutes
import com.apollodeploy.billing.support.billingTestApplication
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class EntitlementsControllerEdgeCaseTest {

    private val entitlementsService = mockk<EntitlementsService>()
    private val controller = EntitlementsController(entitlementsService)

    /**
     * Task 10.1: GET entitlements without orgId path segment returns non-200.
     *
     * The route pattern is /{appSlug}/{orgId}, so a URL with only one path segment
     * (e.g. /internal/billing/entitlements/signal) does not match any registered route
     * and should return a non-200 response (typically 404).
     */
    @Test
    fun `GET entitlements without orgId path segment returns non-200`() = billingTestApplication(
        routes = { noAuthInternalRoutes { entitlementsRoutes(controller) } },
    ) {
        val response = client.get("/internal/billing/entitlements/signal")

        assertNotEquals(HttpStatusCode.OK, response.status)
    }

    /**
     * Task 10.2: GET entitlements without appSlug and orgId path segments returns non-200.
     *
     * The route pattern is /{appSlug}/{orgId}, so hitting the base path
     * /internal/billing/entitlements (missing both segments) should not match any
     * registered route and should return a non-200 response (typically 404).
     */
    @Test
    fun `GET entitlements without appSlug and orgId path segments returns non-200`() = billingTestApplication(
        routes = { noAuthInternalRoutes { entitlementsRoutes(controller) } },
    ) {
        val response = client.get("/internal/billing/entitlements")

        assertNotEquals(HttpStatusCode.OK, response.status)
    }

    /**
     * Task 10.3: GET entitlements with valid path and no Authorization header returns HTTP 401.
     *
     * A request to the fully-qualified route without an Authorization header should be
     * rejected by the internal route auth middleware with 401 Unauthorized.
     */
    @Test
    fun `GET entitlements with valid path and no Authorization header returns HTTP 401`() = billingTestApplication(
        routes = { noAuthInternalRoutes { entitlementsRoutes(controller) } },
    ) {
        val response = client.get("/internal/billing/entitlements/signal/org_1")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }
}
