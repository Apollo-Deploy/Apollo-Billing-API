package com.apollodeploy.billing.feature.enforce.application

import arrow.core.Either
import com.apollodeploy.billing.core.BillingEnforcer
import com.apollodeploy.billing.core.BillingError
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
import io.mockk.mockk
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
            EnforceRequest(orgId = "org_1", appSlug = "unknown", check = BillingCheck.Feature("deployments")),
        )

        assertIs<EnforceResult.Rejected>(result)
        assertEquals(422, result.statusCode)
        assertEquals("billing.unknown_app", result.error.code)
        assertTrue(result.error.message.contains("unknown"))
    }

    // ─── 1.2 — NoSubscription via Either ─────────────────────────────────────

    @Test
    fun `NoSubscription error returns Rejected 404`() = runBlocking {
        every { repo.getEnforcer("signal") } returns enforcer
        coEvery { enforcer.enforceFeature(any(), any()) } returns Either.Left(
            BillingError.NoSubscription(orgId = "org_1", appSlug = "signal"),
        )

        val result = service.enforce(
            EnforceRequest(orgId = "org_1", appSlug = "signal", check = BillingCheck.Feature("deployments")),
        )

        assertIs<EnforceResult.Rejected>(result)
        assertEquals(404, result.statusCode)
        assertEquals("billing.no_subscription", result.error.code)
    }

    // ─── 1.3 — QuotaExceeded via Either ──────────────────────────────────────

    @Test
    fun `QuotaExceeded returns Rejected 402 with resource, current, limit fields`() = runBlocking {
        every { repo.getEnforcer("signal") } returns enforcer
        coEvery { enforcer.enforceQuota(any(), any(), any()) } returns Either.Left(
            BillingError.QuotaExceeded(resource = "projects", current = 5, limit = 5, appSlug = "signal"),
        )

        val result = service.enforce(
            EnforceRequest(orgId = "org_1", appSlug = "signal", check = BillingCheck.Quota(resource = "projects", limitKey = "maxProjects")),
        )

        assertIs<EnforceResult.Rejected>(result)
        assertEquals(402, result.statusCode)
        assertEquals("billing.quota_exceeded", result.error.code)
        assertEquals("projects", result.error.resource)
        assertEquals(5, result.error.current)
        assertEquals(5, result.error.limit)
    }

    // ─── 1.4 — FeatureNotAvailable via Either ────────────────────────────────

    @Test
    fun `FeatureNotAvailable returns Rejected 402 with feature and currentPlan fields`() = runBlocking {
        every { repo.getEnforcer("signal") } returns enforcer
        coEvery { enforcer.enforceFeature(any(), any()) } returns Either.Left(
            BillingError.FeatureNotAvailable(feature = "multiRegion", currentPlan = "signal-spark", appSlug = "signal"),
        )

        val result = service.enforce(
            EnforceRequest(orgId = "org_1", appSlug = "signal", check = BillingCheck.Feature("multiRegion")),
        )

        assertIs<EnforceResult.Rejected>(result)
        assertEquals(402, result.statusCode)
        assertEquals("billing.feature_unavailable", result.error.code)
        assertEquals("multiRegion", result.error.feature)
        assertEquals("signal-spark", result.error.currentPlan)
    }

    // ─── 1.5 — MeterExhausted via Either ─────────────────────────────────────

    @Test
    fun `MeterExhausted returns Rejected 402`() = runBlocking {
        every { repo.getEnforcer("signal") } returns enforcer
        coEvery { enforcer.enforceMeter(any(), any(), any()) } returns Either.Left(
            BillingError.MeterExhausted(meterKey = "automationRunBalance", balance = 0, needed = 1, appSlug = "signal"),
        )

        val result = service.enforce(
            EnforceRequest(orgId = "org_1", appSlug = "signal", check = BillingCheck.Meter(meterKey = "automationRunBalance", needed = 1)),
        )

        assertIs<EnforceResult.Rejected>(result)
        assertEquals(402, result.statusCode)
        assertEquals("billing.meter_exhausted", result.error.code)
    }

    // ─── 1.6 — ServiceUnavailable (Signal DB down) → 503 ─────────────────────

    @Test
    fun `ServiceUnavailable returns Rejected 503`() = runBlocking {
        every { repo.getEnforcer("signal") } returns enforcer
        coEvery { enforcer.enforceFeature(any(), any()) } returns Either.Left(
            BillingError.ServiceUnavailable(service = "signal-db", reason = "connection refused"),
        )

        val result = service.enforce(
            EnforceRequest(orgId = "org_1", appSlug = "signal", check = BillingCheck.Feature("deployments")),
        )

        assertIs<EnforceResult.Rejected>(result)
        assertEquals(503, result.statusCode)
        assertEquals("billing.service_unavailable", result.error.code)
    }

    // ─── 1.7 — successful check ───────────────────────────────────────────────

    @Test
    fun `successful check returns Allowed`() = runBlocking {
        every { repo.getEnforcer("signal") } returns enforcer
        coEvery { enforcer.enforceFeature(any(), any()) } returns Either.Right(Unit)

        val result = service.enforce(
            EnforceRequest(orgId = "org_1", appSlug = "signal", check = BillingCheck.Feature("deployments")),
        )

        assertIs<EnforceResult.Allowed>(result)
    }

    // ─── 1.8 — delegation tests ───────────────────────────────────────────────

    @Test
    fun `BillingCheck_Quota delegates resource and limitKey to enforceQuota`() = runBlocking {
        every { repo.getEnforcer("signal") } returns enforcer
        coEvery { enforcer.enforceQuota(any(), any(), any()) } returns Either.Right(Unit)

        service.enforce(
            EnforceRequest(orgId = "org_1", appSlug = "signal", check = BillingCheck.Quota(resource = "projects", limitKey = "maxProjects")),
        )

        coVerify { enforcer.enforceQuota("org_1", "projects", "maxProjects") }
    }

    @Test
    fun `BillingCheck_Feature delegates feature to enforceFeature`() = runBlocking {
        every { repo.getEnforcer("signal") } returns enforcer
        coEvery { enforcer.enforceFeature(any(), any()) } returns Either.Right(Unit)

        service.enforce(
            EnforceRequest(orgId = "org_1", appSlug = "signal", check = BillingCheck.Feature(feature = "multiRegion")),
        )

        coVerify { enforcer.enforceFeature("org_1", "multiRegion") }
    }

    @Test
    fun `BillingCheck_Meter delegates meterKey and needed to enforceMeter`() = runBlocking {
        every { repo.getEnforcer("signal") } returns enforcer
        coEvery { enforcer.enforceMeter(any(), any(), any()) } returns Either.Right(Unit)

        service.enforce(
            EnforceRequest(orgId = "org_1", appSlug = "signal", check = BillingCheck.Meter(meterKey = "automationRunBalance", needed = 5)),
        )

        coVerify { enforcer.enforceMeter("org_1", "automationRunBalance", 5) }
    }

    // ─── 1.10 — Property: result mapping is total ─────────────────────────────

    @Test
    fun `property - BillingError-to-result mapping is total (Property 6)`() = runBlocking {
        val errors: List<BillingError> = listOf(
            BillingError.NoSubscription("org_1", "signal"),
            BillingError.QuotaExceeded("projects", 1, 1, "signal"),
            BillingError.FeatureNotAvailable("multiRegion", "signal-spark", "signal"),
            BillingError.MeterExhausted("automationRunBalance", 0, 1, "signal"),
            BillingError.ServiceUnavailable("signal-db"),
            BillingError.UnknownApp("signal"),
        )

        forAll(Arb.element(errors)) { error ->
            every { repo.getEnforcer("signal") } returns enforcer
            coEvery { enforcer.enforceFeature(any(), any()) } returns Either.Left(error)

            val result = service.enforce(
                EnforceRequest(orgId = "org_1", appSlug = "signal", check = BillingCheck.Feature("deployments")),
            )

            result is EnforceResult.Rejected &&
                result.statusCode in setOf(402, 404, 422, 500, 503) &&
                result.error.code.startsWith("billing.")
        }

        Unit
    }
}
