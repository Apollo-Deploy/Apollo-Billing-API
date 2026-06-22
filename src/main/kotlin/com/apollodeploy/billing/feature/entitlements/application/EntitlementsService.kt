package com.apollodeploy.billing.feature.entitlements.application

import com.apollodeploy.billing.core.AppEntitlements
import com.apollodeploy.billing.core.SubscriptionNotFoundError
import com.apollodeploy.billing.feature.entitlements.domain.EntitlementsResponse
import com.apollodeploy.billing.feature.entitlements.domain.EntitlementsResult
import com.apollodeploy.billing.feature.entitlements.infrastructure.persistence.EntitlementsRepo
import org.slf4j.LoggerFactory

class EntitlementsService(
    private val entitlementsRepo: EntitlementsRepo,
) {
    private val logger = LoggerFactory.getLogger(EntitlementsService::class.java)

    suspend fun getEntitlements(
        appSlug: String,
        orgId: String,
    ): EntitlementsResult {
        val enforcer =
            entitlementsRepo.getEnforcer(appSlug)
                ?: return EntitlementsResult.UnknownApp(appSlug)

        return try {
            EntitlementsResult.Found(enforcer.resolveEntitlements(orgId).toResponse())
        } catch (e: SubscriptionNotFoundError) {
            EntitlementsResult.NoSubscription
        } catch (e: Exception) {
            logger.error("[billing:entitlements] error app={} org={}", appSlug, orgId, e)
            EntitlementsResult.InternalError
        }
    }
}

private fun AppEntitlements.toResponse() =
    EntitlementsResponse(
        appSlug = appSlug,
        orgId = orgId,
        planId = planId,
        limits = limits.limits,
        features = limits.features,
        usage = usage,
        remaining = remaining.mapValues { (_, v) -> v.toString() },
    )
