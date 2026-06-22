package com.apollodeploy.billing.infrastructure.audit

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Apollo Billing — audit log event.
 *
 * Maps to the platform's internal audit-log ingestion API field reference.
 *
 * POST /internal/apps/<slug>/audit-logs
 * POST /internal/apps/<slug>/audit-logs/batch
 */
@Serializable
data class AuditEvent(
    /** e.g. "subscription", "checkout", "enforcement", "webhook", "customer" */
    val module: String,
    /** e.g. "created", "canceled", "failed", "denied", "updated" */
    val action: String,
    /** e.g. "subscription", "checkout_session", "payment_method" */
    @SerialName("resourceType") val resourceType: String,
    /** "success" | "failure" | "partial" */
    val status: AuditStatus,
    /** Organisation that the event belongs to. Defaults to "platform-admin" if omitted by platform. */
    @SerialName("organizationId") val organizationId: String? = null,
    /** User or service that triggered the action (jwt sub / service issuer). */
    @SerialName("userId") val userId: String? = null,
    /** ID of the affected resource (subscription ID, checkout ID, etc.). */
    @SerialName("resourceId") val resourceId: String? = null,
    /** Human-readable error message on failure. */
    @SerialName("errorMessage") val errorMessage: String? = null,
    /** Optional risk classification; auto-inferred by platform if omitted. */
    @SerialName("riskLevel") val riskLevel: AuditRiskLevel? = null,
    /** Source IP address when available. */
    @SerialName("ipAddress") val ipAddress: String? = null,
    /** Arbitrary contextual key-value pairs. */
    val metadata: Map<String, String>? = null,
)

@Serializable
enum class AuditStatus {
    @SerialName("success") SUCCESS,
    @SerialName("failure") FAILURE,
    @SerialName("partial") PARTIAL,
}

@Serializable
enum class AuditRiskLevel {
    @SerialName("low") LOW,
    @SerialName("medium") MEDIUM,
    @SerialName("high") HIGH,
    @SerialName("critical") CRITICAL,
}

@Serializable
internal data class AuditBatchRequest(
    val events: List<AuditEvent>,
)
