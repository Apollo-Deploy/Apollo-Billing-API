package com.apollodeploy.billing.feature.enforce.application

import arrow.core.Either
import com.apollodeploy.billing.core.BillingError
import com.apollodeploy.billing.core.httpStatus
import com.apollodeploy.billing.feature.enforce.domain.BillingCheck
import com.apollodeploy.billing.feature.enforce.domain.BillingErrorResponse
import com.apollodeploy.billing.feature.enforce.domain.EnforceRequest
import com.apollodeploy.billing.feature.enforce.domain.EnforceResult
import com.apollodeploy.billing.feature.enforce.infrastructure.persistence.EnforceRepo
import com.apollodeploy.billing.infrastructure.audit.AuditEvent
import com.apollodeploy.billing.infrastructure.audit.AuditLogClient
import com.apollodeploy.billing.infrastructure.audit.AuditRiskLevel
import com.apollodeploy.billing.infrastructure.audit.AuditStatus
import org.slf4j.LoggerFactory

class EnforceService(
    private val repository: EnforceRepo,
    private val auditLogClient: AuditLogClient,
) {
    private val logger = LoggerFactory.getLogger(EnforceService::class.java)

    suspend fun enforce(
        req: EnforceRequest,
        callerClientId: String? = null,
    ): EnforceResult {
        val enforcer =
            repository.getEnforcer(req.appSlug)
                ?: return EnforceResult.Rejected(
                    statusCode = 422,
                    error =
                        BillingErrorResponse(
                            code = "billing.unknown_app",
                            message = "Unknown app slug: ${req.appSlug}",
                        ),
                )

        val result: Either<BillingError, Unit> =
            when (val check = req.check) {
                is BillingCheck.Quota -> enforcer.enforceQuota(req.orgId, check.resource, check.limitKey)
                is BillingCheck.Feature -> enforcer.enforceFeature(req.orgId, check.feature)
                is BillingCheck.Meter -> enforcer.enforceMeter(req.orgId, check.meterKey, check.needed)
            }

        return result.fold(
            ifLeft = { error ->
                logBillingError(error, req, callerClientId)
                EnforceResult.Rejected(
                    statusCode = error.httpStatus(),
                    error = error.toErrorResponse(),
                )
            },
            ifRight = { EnforceResult.Allowed },
        )
    }

    private fun logBillingError(
        error: BillingError,
        req: EnforceRequest,
        callerClientId: String?,
    ) {
        val (action, riskLevel) =
            when (error) {
                is BillingError.QuotaExceeded -> "quota_exceeded" to AuditRiskLevel.MEDIUM
                is BillingError.MeterExhausted -> "meter_exhausted" to AuditRiskLevel.MEDIUM
                is BillingError.FeatureNotAvailable -> "feature_denied" to AuditRiskLevel.LOW
                is BillingError.NoSubscription -> {
                    logger.debug("[billing:enforce] no subscription org={} app={}", req.orgId, req.appSlug)
                    return // Don't audit log no-subscription (common for free users)
                }
                is BillingError.ServiceUnavailable -> {
                    logger.error("[billing:enforce] service unavailable org={} app={}: {}", req.orgId, req.appSlug, error.message)
                    return
                }
                else -> "enforcement_failed" to AuditRiskLevel.LOW
            }

        auditLogClient.log(
            AuditEvent(
                module = "enforcement",
                action = action,
                resourceType = "billing_check",
                organizationId = req.orgId,
                userId = callerClientId,
                status = AuditStatus.FAILURE,
                riskLevel = riskLevel,
                errorMessage = error.message,
                metadata =
                    buildMap {
                        put("appSlug", req.appSlug)
                        put("errorCode", error.code)
                        callerClientId?.let { put("callerClientId", it) }
                    },
            ),
        )
    }
}

private fun BillingError.toErrorResponse(): BillingErrorResponse =
    when (this) {
        is BillingError.QuotaExceeded ->
            BillingErrorResponse(
                code = code,
                message = message,
                resource = resource,
                current = current,
                limit = limit,
            )
        is BillingError.MeterExhausted ->
            BillingErrorResponse(
                code = code,
                message = message,
                resource = meterKey,
                current = balance,
                limit = needed,
            )
        is BillingError.FeatureNotAvailable ->
            BillingErrorResponse(
                code = code,
                message = message,
                feature = feature,
                currentPlan = currentPlan,
            )
        is BillingError.NoSubscription -> BillingErrorResponse(code = code, message = message)
        is BillingError.UnknownApp -> BillingErrorResponse(code = code, message = message)
        is BillingError.ServiceUnavailable -> BillingErrorResponse(code = code, message = message)
        is BillingError.InvalidInput -> BillingErrorResponse(code = code, message = message)
    }
