package com.apollodeploy.billing.feature.enforce.application

import com.apollodeploy.billing.core.FeatureNotAvailableError
import com.apollodeploy.billing.core.QuotaExceededError
import com.apollodeploy.billing.core.SubscriptionNotFoundError
import com.apollodeploy.billing.feature.enforce.domain.BillingCheck
import com.apollodeploy.billing.feature.enforce.domain.BillingErrorResponse
import com.apollodeploy.billing.feature.enforce.domain.EnforceRequest
import com.apollodeploy.billing.feature.enforce.domain.EnforceResult
import com.apollodeploy.billing.feature.enforce.infrastructure.persistence.EnforceRepo
import com.apollodeploy.billing.infrastructure.audit.AuditEvent
import com.apollodeploy.billing.infrastructure.audit.AuditLogClient
import com.apollodeploy.billing.infrastructure.audit.AuditRiskLevel
import com.apollodeploy.billing.infrastructure.audit.AuditStatus
import io.ktor.http.HttpStatusCode
import org.slf4j.LoggerFactory

class EnforceService(
    private val enforceRepo: EnforceRepo,
    private val auditLogClient: AuditLogClient,
) {
    private val logger = LoggerFactory.getLogger(EnforceService::class.java)

    suspend fun enforce(req: EnforceRequest): EnforceResult {
        val enforcer =
            enforceRepo.getEnforcer(req.appSlug)
                ?: return EnforceResult.Rejected(
                    statusCode = HttpStatusCode.UnprocessableEntity.value,
                    error =
                        BillingErrorResponse(
                            code = "billing.unknown_app",
                            message = "Unknown app slug: ${req.appSlug}",
                        ),
                )

        return try {
            when (val check = req.check) {
                is BillingCheck.Quota -> enforcer.enforceQuota(req.orgId, check.resource, check.limitKey)
                is BillingCheck.Feature -> enforcer.enforceFeature(req.orgId, check.feature)
                is BillingCheck.Meter -> enforcer.enforceMeter(req.orgId, check.meterKey, check.needed)
            }
            EnforceResult.Allowed
        } catch (e: SubscriptionNotFoundError) {
            logger.debug("[billing:enforce] no subscription org={} app={} - allowing", req.orgId, req.appSlug)
            EnforceResult.Rejected(
                statusCode = HttpStatusCode.NotFound.value,
                error =
                    BillingErrorResponse(
                        code = "billing.no_subscription",
                        message = "No active subscription found",
                    ),
            )
        } catch (e: QuotaExceededError) {
            auditLogClient.log(
                AuditEvent(
                    module = "enforcement",
                    action = "quota_exceeded",
                    resourceType = "quota",
                    organizationId = req.orgId,
                    status = AuditStatus.FAILURE,
                    riskLevel = AuditRiskLevel.MEDIUM,
                    errorMessage = e.message,
                    metadata =
                        mapOf(
                            "appSlug" to req.orgId,
                            "resource" to e.resource,
                            "current" to e.current.toString(),
                            "limit" to e.limit.toString(),
                        ),
                ),
            )
            EnforceResult.Rejected(
                statusCode = HttpStatusCode.PaymentRequired.value,
                error =
                    BillingErrorResponse(
                        code = "billing.quota_exceeded",
                        message = e.message ?: "Quota exceeded",
                        resource = e.resource,
                        current = e.current,
                        limit = e.limit,
                    ),
            )
        } catch (e: FeatureNotAvailableError) {
            auditLogClient.log(
                AuditEvent(
                    module = "enforcement",
                    action = "feature_denied",
                    resourceType = "feature",
                    organizationId = req.orgId,
                    status = AuditStatus.FAILURE,
                    riskLevel = AuditRiskLevel.LOW,
                    errorMessage = e.message,
                    metadata =
                        mapOf(
                            "appSlug" to e.appSlug,
                            "feature" to e.feature,
                            "currentPlan" to e.currentPlan,
                        ),
                ),
            )
            EnforceResult.Rejected(
                statusCode = HttpStatusCode.PaymentRequired.value,
                error =
                    BillingErrorResponse(
                        code = "billing.feature_unavailable",
                        message = e.message ?: "Feature not available",
                        feature = e.feature,
                        currentPlan = e.currentPlan,
                    ),
            )
        } catch (e: Exception) {
            logger.error("[billing:enforce] unexpected error org={} app={}", req.orgId, req.appSlug, e)
            EnforceResult.Rejected(
                statusCode = HttpStatusCode.InternalServerError.value,
                error =
                    BillingErrorResponse(
                        code = "billing.internal_error",
                        message = "Internal billing error",
                    ),
            )
        }
    }
}
