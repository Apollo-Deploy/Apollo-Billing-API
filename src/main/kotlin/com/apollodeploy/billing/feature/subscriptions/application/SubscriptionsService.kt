package com.apollodeploy.billing.feature.subscriptions.application

import com.apollodeploy.billing.feature.subscriptions.domain.ActiveSubscriptionsResponse
import com.apollodeploy.billing.feature.subscriptions.domain.ActiveSubscriptionsResult
import com.apollodeploy.billing.feature.subscriptions.infrastructure.persistence.SubscriptionsQueryRepo
import org.slf4j.LoggerFactory

class SubscriptionsService(
    private val subscriptionsQueryRepo: SubscriptionsQueryRepo,
) {
    private val logger = LoggerFactory.getLogger(SubscriptionsService::class.java)

    fun getActiveSubscriptions(orgId: String): ActiveSubscriptionsResult {
        return try {
            val grouped = subscriptionsQueryRepo.findActiveSubscriptionsGroupedByApp(orgId)
            val totalCount = grouped.values.sumOf { it.size }
            ActiveSubscriptionsResult.Found(
                ActiveSubscriptionsResponse(
                    apps = grouped,
                    totalCount = totalCount,
                ),
            )
        } catch (e: Exception) {
            logger.error("[billing:subscriptions] failed to fetch active subscriptions org={}", orgId, e)
            ActiveSubscriptionsResult.InternalError
        }
    }
}
