package com.apollodeploy.billing.feature.usage.application

import com.apollodeploy.billing.feature.signal.domain.SIGNAL_EMAIL_RECEIVED_EVENT_KEY
import com.apollodeploy.billing.feature.usage.domain.UsageIngestRequest
import com.apollodeploy.billing.feature.usage.domain.UsageIngestResponse
import com.apollodeploy.billing.feature.usage.infrastructure.persistence.UsageIngestRepo
import com.apollodeploy.billing.infrastructure.audit.AuditEvent
import com.apollodeploy.billing.infrastructure.audit.AuditLogClient
import com.apollodeploy.billing.infrastructure.audit.AuditStatus

class UsageIngestService(
    private val usageIngestRepo: UsageIngestRepo,
    private val auditLogClient: AuditLogClient,
    private val inboundUsageEntitlement: InboundUsageEntitlementPort = InboundUsageEntitlementPort { true },
) {
    companion object {
        /** Maximum quantity per single usage event. Prevents abuse via extreme values. */
        private const val MAX_QUANTITY = 10_000
    }

    suspend fun ingest(req: UsageIngestRequest): UsageIngestResponse {
        // Validate quantity — must be positive and within bounds
        if (req.quantity < 1) {
            return UsageIngestResponse(accepted = false, reason = "quantity must be >= 1")
        }
        if (req.quantity > MAX_QUANTITY) {
            return UsageIngestResponse(accepted = false, reason = "quantity exceeds maximum ($MAX_QUANTITY)")
        }
        if (req.orgId.isBlank()) {
            return UsageIngestResponse(accepted = false, reason = "orgId is required")
        }
        if (req.eventKey.isBlank()) {
            return UsageIngestResponse(accepted = false, reason = "eventKey is required")
        }
        val inboundReceivingAllowed =
            req.eventKey != SIGNAL_EMAIL_RECEIVED_EVENT_KEY ||
                inboundUsageEntitlement.isInboundReceivingAllowed(req.orgId)
        if (!inboundReceivingAllowed) {
            return UsageIngestResponse(accepted = false, reason = "inbound_receiving_not_entitled")
        }

        val accepted =
            usageIngestRepo.ingestUsageEvent(
                orgId = req.orgId,
                eventKey = req.eventKey,
                quantity = req.quantity,
                idempotencyKey = req.idempotencyKey ?: req.metadata["messageId"]?.toString()?.trim('"'),
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
