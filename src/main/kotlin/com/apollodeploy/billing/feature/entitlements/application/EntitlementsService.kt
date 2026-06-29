package com.apollodeploy.billing.feature.entitlements.application

import com.apollodeploy.billing.core.AppEntitlements
import com.apollodeploy.billing.core.BillingError
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

        return enforcer.resolveEntitlements(orgId).fold(
            ifLeft = { error ->
                when (error) {
                    is BillingError.NoSubscription -> EntitlementsResult.NoSubscription
                    is BillingError.ServiceUnavailable -> {
                        logger.error("[billing:entitlements] service unavailable app={} org={}: {}", appSlug, orgId, error.message)
                        EntitlementsResult.InternalError
                    }
                    else -> {
                        logger.error("[billing:entitlements] unexpected error app={} org={}: {}", appSlug, orgId, error.message)
                        EntitlementsResult.InternalError
                    }
                }
            },
            ifRight = { entitlements ->
                EntitlementsResult.Found(entitlements.toResponse())
            },
        )
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
