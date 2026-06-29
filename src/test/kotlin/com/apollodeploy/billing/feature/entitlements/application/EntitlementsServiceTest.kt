package com.apollodeploy.billing.feature.entitlements.application

import com.apollodeploy.billing.core.AppEntitlements
import com.apollodeploy.billing.core.BillingEnforcer
import com.apollodeploy.billing.core.PlanFeatureConfig
import com.apollodeploy.billing.core.SubscriptionNotFoundError
import com.apollodeploy.billing.core.UNLIMITED_SENTINEL
import com.apollodeploy.billing.feature.entitlements.domain.EntitlementsResult
import com.apollodeploy.billing.feature.entitlements.infrastructure.persistence.EntitlementsRepo
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class EntitlementsServiceTest {

    private val repo = mockk<EntitlementsRepo>()
    private val enforcer = mockk<BillingEnforcer>()
    private val service = EntitlementsService(repo)

    // ─── 2.1 ─────────────────────────────────────────────────────────────────

    @Test
    fun `null enforcer returns UnknownApp with matching appSlug`() = runBlocking {
        every { repo.getEnforcer("unknown") } returns null

        val result = service.getEntitlements(appSlug = "unknown", orgId = "org_1")

        val unknownApp = assertIs<EntitlementsResult.UnknownApp>(result)
        assertEquals("unknown", unknownApp.appSlug)
    }

    // ─── 2.2 ─────────────────────────────────────────────────────────────────

    @Test
    fun `SubscriptionNotFoundError returns NoSubscription`() = runBlocking {
        every { repo.getEnforcer("signal") } returns enforcer
        coEvery { enforcer.resolveEntitlements(any()) } returns arrow.core.Either.Left(com.apollodeploy.billing.core.BillingError.NoSubscription("org_1", "signal"))

        val result = service.getEntitlements(appSlug = "signal", orgId = "org_1")

        assertIs<EntitlementsResult.NoSubscription>(result)
    }

    // ─── 2.3 ─────────────────────────────────────────────────────────────────

    @Test
    fun `unexpected exception returns InternalError`() = runBlocking {
        every { repo.getEnforcer("signal") } returns enforcer
        coEvery { enforcer.resolveEntitlements(any()) } returns arrow.core.Either.Left(com.apollodeploy.billing.core.BillingError.ServiceUnavailable("signal-db", "db down"))

        val result = service.getEntitlements(appSlug = "signal", orgId = "org_1")

        assertIs<EntitlementsResult.InternalError>(result)
    }

    // ─── 2.4 ─────────────────────────────────────────────────────────────────

    @Test
    fun `happy path returns Found with all response fields populated`() = runBlocking {
        val limits = PlanFeatureConfig(
            features = mapOf("analytics" to true),
            limits = mapOf("monthlySends" to 1000),
        )
        val usage = mapOf("monthlySends" to 200)
        val remaining = mapOf("monthlySends" to 800)

        val appEntitlements = AppEntitlements(
            appSlug = "signal",
            orgId = "org_1",
            planId = "signal-spark",
            limits = limits,
            usage = usage,
            remaining = remaining,
        )

        every { repo.getEnforcer("signal") } returns enforcer
        coEvery { enforcer.resolveEntitlements("org_1") } returns arrow.core.Either.Right(appEntitlements)

        val result = service.getEntitlements(appSlug = "signal", orgId = "org_1")

        val found = assertIs<EntitlementsResult.Found>(result)
        assertEquals("signal", found.response.appSlug)
        assertEquals("org_1", found.response.orgId)
        assertEquals("signal-spark", found.response.planId)
        assertEquals(limits.limits, found.response.limits)
        assertEquals(limits.features, found.response.features)
        assertEquals(usage, found.response.usage)
        assertEquals(remaining.mapValues { (_, v) -> v.toString() }, found.response.remaining)
    }

    // ─── 2.5 ─────────────────────────────────────────────────────────────────

    @Test
    fun `remaining values with UNLIMITED_SENTINEL are mapped to toString()`() = runBlocking {
        val limits = PlanFeatureConfig(
            limits = mapOf("monthlySends" to 500),
        )
        val appEntitlements = AppEntitlements(
            appSlug = "signal",
            orgId = "org_1",
            planId = "signal-spark",
            limits = limits,
            usage = emptyMap(),
            remaining = mapOf("monthlySends" to UNLIMITED_SENTINEL),
        )

        every { repo.getEnforcer("signal") } returns enforcer
        coEvery { enforcer.resolveEntitlements("org_1") } returns arrow.core.Either.Right(appEntitlements)

        val result = service.getEntitlements(appSlug = "signal", orgId = "org_1")

        val found = assertIs<EntitlementsResult.Found>(result)
        assertEquals("-1", found.response.remaining["monthlySends"])
    }
}
