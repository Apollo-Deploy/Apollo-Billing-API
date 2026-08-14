package com.apollodeploy.billing.feature.usage.application

import com.apollodeploy.billing.feature.usage.domain.UsageIngestRequest
import com.apollodeploy.billing.feature.usage.infrastructure.persistence.UsageIngestRepo
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UsageIngestServiceTest {
    private val repo = mockk<UsageIngestRepo>()
    private val auditLogClient = mockk<com.apollodeploy.billing.infrastructure.audit.AuditLogClient>(relaxed = true)
    private val service = UsageIngestService(repo, auditLogClient)

    // ─── 5.1 ─────────────────────────────────────────────────────────────────

    @Test
    fun `repo returns true - response is accepted true with null reason`() =
        runBlocking {
            coEvery { repo.ingestUsageEvent(any(), any(), any(), any(), any()) } returns true

            val result =
                service.ingest(
                    UsageIngestRequest(orgId = "org_1", eventKey = "signal.automation.run", quantity = 1),
                )

            assertEquals(true, result.accepted)
            assertNull(result.reason)
        }

    // ─── 5.2 ─────────────────────────────────────────────────────────────────

    @Test
    fun `repo returns false - response is accepted false with reason polar_unavailable`() =
        runBlocking {
            coEvery { repo.ingestUsageEvent(any(), any(), any(), any(), any()) } returns false

            val result =
                service.ingest(
                    UsageIngestRequest(orgId = "org_1", eventKey = "signal.automation.run", quantity = 1),
                )

            assertEquals(false, result.accepted)
            assertEquals("polar_unavailable", result.reason)
        }

    // ─── 5.3 ─────────────────────────────────────────────────────────────────

    @Test
    fun `all request fields are forwarded unchanged to repo`() =
        runBlocking {
            coEvery { repo.ingestUsageEvent(any(), any(), any(), any(), any()) } returns true

            service.ingest(
                UsageIngestRequest(
                    orgId = "org_1",
                    eventKey = "email.sent",
                    quantity = 42,
                    metadata = mapOf("source" to "test"),
                ),
            )

            coVerify {
                repo.ingestUsageEvent(
                    "org_1",
                    "email.sent",
                    42,
                    null,
                    mapOf("source" to "test"),
                )
            }
        }

    @Test
    fun `inbound usage is rejected before persistence when inbound receiving is not entitled`() = runBlocking {
        val deniedService = UsageIngestService(
            repo,
            auditLogClient,
            InboundUsageEntitlementPort { false },
        )

        val result = deniedService.ingest(
            UsageIngestRequest(orgId = "org_1", eventKey = "signal.email.received", quantity = 1),
        )

        assertEquals(false, result.accepted)
        assertEquals("inbound_receiving_not_entitled", result.reason)
        coVerify(exactly = 0) { repo.ingestUsageEvent(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `entitled inbound usage uses the message id as its idempotency key`() = runBlocking {
        val inboundService = UsageIngestService(
            repo,
            auditLogClient,
            InboundUsageEntitlementPort { true },
        )
        coEvery { repo.ingestUsageEvent(any(), any(), any(), any(), any()) } returns true

        val result = inboundService.ingest(
            UsageIngestRequest(
                orgId = "org_1",
                eventKey = "signal.email.received",
                quantity = 1,
                metadata = mapOf("messageId" to "inmsg_1"),
            ),
        )

        assertEquals(true, result.accepted)
        coVerify {
            repo.ingestUsageEvent(
                "org_1",
                "signal.email.received",
                1,
                "inmsg_1",
                any(),
            )
        }
    }
}
