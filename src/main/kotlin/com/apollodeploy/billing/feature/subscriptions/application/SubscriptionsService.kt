package com.apollodeploy.billing.feature.subscriptions.application

import com.apollodeploy.billing.core.AppRegistry
import com.apollodeploy.billing.feature.subscriptions.domain.ActiveSubscriptionsResponse
import com.apollodeploy.billing.feature.subscriptions.domain.ActiveSubscriptionsResult
import com.apollodeploy.billing.feature.subscriptions.domain.CancelSubscriptionResponse
import com.apollodeploy.billing.feature.subscriptions.domain.CancelSubscriptionResult
import com.apollodeploy.billing.feature.subscriptions.domain.SubscriptionItem
import com.apollodeploy.billing.feature.subscriptions.infrastructure.persistence.SubscriptionsQueryRepo
import com.apollodeploy.billing.infrastructure.persistence.SubscriptionRepo
import com.apollodeploy.billing.infrastructure.polar.PolarClient
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

class SubscriptionsService(
    private val queryRepository: SubscriptionsQueryRepo,
    private val subscriptionRepository: SubscriptionRepo,
    private val polarClient: PolarClient,
    private val appRegistry: AppRegistry,
) {
    private val logger = LoggerFactory.getLogger(SubscriptionsService::class.java)

    fun getActiveSubscriptions(orgId: String): ActiveSubscriptionsResult {
        return try {
            val grouped =
                queryRepository.findActiveSubscriptionsGroupedByApp(orgId)
                    .mapValues { (_, items) ->
                        items.map(::enrichForClient)
                    }
            ActiveSubscriptionsResult.Found(
                ActiveSubscriptionsResponse(
                    apps = grouped,
                    totalCount = grouped.values.sumOf { it.size },
                ),
            )
        } catch (e: Exception) {
            logger.error("[billing:subscriptions] failed to fetch active subscriptions org={}", orgId, e)
            ActiveSubscriptionsResult.InternalError
        }
    }

    suspend fun cancelSubscriptionAtPeriodEnd(
        orgId: String,
        polarSubscriptionId: String,
    ): CancelSubscriptionResult {
        if (queryRepository.findActiveSubscriptionForOrg(orgId, polarSubscriptionId) == null) {
            return CancelSubscriptionResult.NotFound(polarSubscriptionId)
        }

        val result = polarClient.cancelSubscriptionAtPeriodEnd(polarSubscriptionId)
        val body = result.value
            ?: return when (result.statusCode) {
                404 -> CancelSubscriptionResult.NotFound(polarSubscriptionId)
                else -> CancelSubscriptionResult.PolarUnavailable
            }

        val endsAt = body["current_period_end"]?.jsonPrimitive?.contentOrNull
        subscriptionRepository.markCancelAtPeriodEnd(polarSubscriptionId, endsAt)

        return CancelSubscriptionResult.Canceled(
            CancelSubscriptionResponse(
                polarSubscriptionId = polarSubscriptionId,
                status = body["status"]?.jsonPrimitive?.contentOrNull ?: "active",
                cancelAtPeriodEnd = true,
                endsAt = endsAt,
            ),
        )
    }

    private fun enrichForClient(item: SubscriptionItem): SubscriptionItem {
        val catalog = appRegistry.catalogItemForPolarProductId(item.polarProductId)
        return item.copy(
            productSlug = catalog?.slug,
            planName = catalog?.name ?: item.planName,
            productKind = catalog?.kind?.name,
            amountCents = item.amountCents ?: catalog?.fallbackPriceCents,
            currency = item.currency ?: catalog?.currency,
            billingInterval = item.billingInterval ?: catalog?.metadata?.get("billingInterval"),
        )
    }
}
