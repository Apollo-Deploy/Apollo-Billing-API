package com.apollodeploy.billing.feature.usage.application

import com.apollodeploy.billing.feature.usage.domain.UsageIngestRequest
import com.apollodeploy.billing.feature.usage.domain.UsageIngestResponse
import com.apollodeploy.billing.feature.usage.infrastructure.persistence.UsageIngestRepo
import com.apollodeploy.billing.infrastructure.audit.AuditEvent
import com.apollodeploy.billing.infrastructure.audit.AuditLogClient
import com.apollodeploy.billing.infrastructure.audit.AuditStatus

class UsageIngestService(
    private val usageIngestRepo: UsageIngestRepo,
    private val auditLogClient: AuditLogClient,
) {
    suspend fun ingest(req: UsageIngestRequest): UsageIngestResponse {
        val accepted =
            usageIngestRepo.ingestUsageEvent(
                orgId = req.orgId,
                eventKey = req.eventKey,
                quantity = req.quantity,
                metadata = req.metadata,
            )

        auditLogClient.log(
            AuditEvent(
                module = "usage",
                action = "ingested",
                resourceType = "usage_event",
                organizationId = req.orgId,
                status = if (accepted) AuditStatus.SUCCESS else AuditStatus.FAILURE,
                errorMessage = if (!accepted) "Polar usage ingest unavailable" else null,
                metadata =
                    buildMap {
                        put("eventKey", req.eventKey)
                        put("quantity", req.quantity.toString())
                        if (!accepted) put("reason", "polar_unavailable")
                    },
            ),
        )

        return if (accepted) {
            UsageIngestResponse(accepted = true)
        } else {
            UsageIngestResponse(accepted = false, reason = "polar_unavailable")
        }
    }
}
