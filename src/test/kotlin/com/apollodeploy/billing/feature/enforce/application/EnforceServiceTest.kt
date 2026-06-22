package com.apollodeploy.billing.feature.enforce.application

import com.apollodeploy.billing.core.BillingEnforcer
import com.apollodeploy.billing.core.FeatureNotAvailableError
import com.apollodeploy.billing.core.QuotaExceededError
import com.apollodeploy.billing.core.SubscriptionNotFoundError
import com.apollodeploy.billing.feature.enforce.domain.BillingCheck
import com.apollodeploy.billing.feature.enforce.domain.EnforceRequest
import com.apollodeploy.billing.feature.enforce.domain.EnforceResult
import com.apollodeploy.billing.feature.enforce.infrastructure.persistence.EnforceRepo
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.forAll
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class EnforceServiceTest {

    private val repo = mockk<EnforceRepo>()
    private val enforcer = mockk<BillingEnforcer>()
    private val auditLogClient = mockk<com.apollodeploy.billing.infrastructure.audit.AuditLogClient>(relaxed = true)
    private val service = EnforceService(repo, auditLogClient)

    // ─── 1.1 ─────────────────────────────────────────────────────────────────

    @Test
    fun `unknown app slug returns Rejected 422`() = runBlocking {
        every { repo.getEnforcer("unknown") } returns null

        val result = service.enforce(
            EnforceRequest(
                orgId = "org_1",
                appSlug = "unknown",
                check = BillingCheck.Feature("deployments"),
            ),
        )

        assertIs<EnforceResult.Rejected>(result)
        assertEquals(422, result.statusCode)
        assertEquals("billing.unknown_app", result.error.code)
        assertTrue(result.error.message.contains("unknown"))
    }

    // ─── 1.2 ─────────────────────────────────────────────────────────────────

    @Test
    fun `SubscriptionNotFoundError returns Rejected 404`() = runBlocking {
        every { repo.getEnforcer("signal") } returns enforcer
        coEvery { enforcer.enforceFeature(any(), any()) } throws SubscriptionNotFoundError(
            orgId = "org_1",
            appSlug = "signal",
        )

        val result = service.enforce(
            EnforceRequest(
                orgId = "org_1",
                appSlug = "signal",
                check = BillingCheck.Feature("deployments"),
            ),
        )

        assertIs<EnforceResult.Rejected>(result)
        assertEquals(404, result.statusCode)
        assertEquals("billing.no_subscription", result.error.code)
    }

    // ─── 1.3 ─────────────────────────────────────────────────────────────────

    @Test
    fun `QuotaExceededError returns Rejected 402 with resource, current, limit fields`() = runBlocking {
        every { repo.getEnforcer("signal") } returns enforcer
        coEvery { enforcer.enforceQuota(any(), any(), any()) } throws QuotaExceededError(
            resource = "projects",
            current = 5,
            limit = 5,
            appSlug = "signal",
        )

        val result = service.enforce(
            EnforceRequest(
                orgId = "org_1",
                appSlug = "signal",
                check = BillingCheck.Quota(resource = "projects", limitKey = "maxProjects"),
            ),
        )

        assertIs<EnforceResult.Rejected>(result)
        assertEquals(402, result.statusCode)
        assertEquals("billing.quota_exceeded", result.error.code)
        assertEquals("projects", result.error.resource)
        assertEquals(5, result.error.current)
        assertEquals(5, result.error.limit)
    }

    // ─── 1.4 ─────────────────────────────────────────────────────────────────

    @Test
    fun `FeatureNotAvailableError returns Rejected 402 with feature and currentPlan fields`() = runBlocking {
        every { repo.getEnforcer("signal") } returns enforcer
        coEvery { enforcer.enforceFeature(any(), any()) } throws FeatureNotAvailableError(
            feature = "multiRegion",
            currentPlan = "signal-spark",
            appSlug = "signal",
        )

        val result = service.enforce(
            EnforceRequest(
                orgId = "org_1",
                appSlug = "signal",
                check = BillingCheck.Feature("multiRegion"),
            ),
        )

        assertIs<EnforceResult.Rejected>(result)
        assertEquals(402, result.statusCode)
        assertEquals("billing.feature_unavailable", result.error.code)
        assertEquals("multiRegion", result.error.feature)
        assertEquals("signal-spark", result.error.currentPlan)
    }

    // ─── 1.5 ─────────────────────────────────────────────────────────────────

    @Test
    fun `unexpected RuntimeException returns Rejected 500`() = runBlocking {
        every { repo.getEnforcer("signal") } returns enforcer
        coEvery { enforcer.enforceFeature(any(), any()) } throws RuntimeException("boom")

        val result = service.enforce(
            EnforceRequest(
                orgId = "org_1",
                appSlug = "signal",
                check = BillingCheck.Feature("deployments"),
            ),
        )

        assertIs<EnforceResult.Rejected>(result)
        assertEquals(500, result.statusCode)
        assertEquals("billing.internal_error", result.error.code)
    }

    // ─── 1.6 ─────────────────────────────────────────────────────────────────

    @Test
    fun `successful check returns Allowed`() = runBlocking {
        every { repo.getEnforcer("signal") } returns enforcer
        coEvery { enforcer.enforceFeature(any(), any()) } just Runs

        val result = service.enforce(
            EnforceRequest(
                orgId = "org_1",
                appSlug = "signal",
                check = BillingCheck.Feature("deployments"),
            ),
        )

        assertIs<EnforceResult.Allowed>(result)
    }

    // ─── 1.7 ─────────────────────────────────────────────────────────────────

    @Test
    fun `BillingCheck_Quota delegates resource and limitKey to enforceQuota`() = runBlocking {
        every { repo.getEnforcer("signal") } returns enforcer
        coEvery { enforcer.enforceQuota(any(), any(), any()) } just Runs

        service.enforce(
            EnforceRequest(
                orgId = "org_1",
                appSlug = "signal",
                check = BillingCheck.Quota(resource = "projects", limitKey = "maxProjects"),
            ),
        )

        coVerify { enforcer.enforceQuota("org_1", "projects", "maxProjects") }
    }

    // ─── 1.8 ─────────────────────────────────────────────────────────────────

    @Test
    fun `BillingCheck_Feature delegates feature to enforceFeature`() = runBlocking {
        every { repo.getEnforcer("signal") } returns enforcer
        coEvery { enforcer.enforceFeature(any(), any()) } just Runs

        service.enforce(
            EnforceRequest(
                orgId = "org_1",
                appSlug = "signal",
                check = BillingCheck.Feature(feature = "multiRegion"),
            ),
        )

        coVerify { enforcer.enforceFeature("org_1", "multiRegion") }
    }

    // ─── 1.9 ─────────────────────────────────────────────────────────────────

    @Test
    fun `BillingCheck_Meter delegates meterKey and needed to enforceMeter`() = runBlocking {
        every { repo.getEnforcer("signal") } returns enforcer
        coEvery { enforcer.enforceMeter(any(), any(), any()) } just Runs

        service.enforce(
            EnforceRequest(
                orgId = "org_1",
                appSlug = "signal",
                check = BillingCheck.Meter(meterKey = "automationRunBalance", needed = 5),
            ),
        )

        coVerify { enforcer.enforceMeter("org_1", "automationRunBalance", 5) }
    }

    // ─── 1.10 ─────────────────────────────────────────────────────────────────
    // Feature: billing-comprehensive-unit-tests, Property 6: EnforceService exception-to-result mapping is total
    //
    // **Property 6: EnforceService exception-to-result mapping is total**
    // **Validates: Requirements 20.1, 20.2, 20.3, 1.10**

    @Test
    fun `property - exception-to-result mapping is total (Property 6)`() = runBlocking {
        val exceptions: List<Exception> = listOf(
            SubscriptionNotFoundError("org_1", "signal"),
            QuotaExceededError("projects", 1, 1, "signal"),
            FeatureNotAvailableError("multiRegion", "signal-spark", "signal"),
            RuntimeException("random"),
        )

        forAll(Arb.element(exceptions)) { ex ->
            every { repo.getEnforcer("signal") } returns enforcer
            coEvery { enforcer.enforceFeature(any(), any()) } throws ex

            val result = service.enforce(
                EnforceRequest(
                    orgId = "org_1",
                    appSlug = "signal",
                    check = BillingCheck.Feature("deployments"),
                ),
            )

            result is EnforceResult.Rejected &&
                result.statusCode in setOf(402, 404, 422, 500) &&
                result.error.code.startsWith("billing.")
        }

        Unit
    }
}
