package com.apollodeploy.billing.infrastructure.polar

import com.apollodeploy.billing.core.AppRegistry
import com.apollodeploy.billing.core.BillingProductKind
import com.apollodeploy.billing.infrastructure.audit.AuditEvent
import com.apollodeploy.billing.infrastructure.audit.AuditLogClient
import com.apollodeploy.billing.infrastructure.audit.AuditRiskLevel
import com.apollodeploy.billing.infrastructure.audit.AuditStatus
import com.apollodeploy.billing.infrastructure.persistence.SubscriptionRepo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import org.slf4j.LoggerFactory

/**
 * Apollo Billing — Polar webhook event handler.
 *
 * Processes Polar lifecycle events and keeps the billing DB in sync.
 *
 * Primary event (recommended by Polar docs):
 *   customer.state_changed
 *     → Full customer state snapshot: subscriptions + benefit grants + meter balances.
 *     → Replaces the need to handle individual subscription events.
 *     → Docs: https://polar.sh/docs/integrate/customer-state
 *
 * Fallback events (individual, kept for belt-and-suspenders):
 *   subscription.created / .updated / .active  → upsert customer + subscription
 *   subscription.revoked / .canceled           → mark subscription canceled
 *
 * One-time purchases:
 *   order.paid / order.created → upsert customer + invalidate app cache.
 *   Actual grants should live in Polar Benefits/Meters; this service reads
 *   the resulting customer state instead of tracking credits locally.
 *
 * All public methods are idempotent — safe to re-deliver.
 */
class PolarWebhookHandler(
    private val subscriptionRepo: SubscriptionRepo,
    private val appRegistry: AppRegistry,
    private val auditLogClient: AuditLogClient,
) {
    private val logger = LoggerFactory.getLogger(PolarWebhookHandler::class.java)

    private val json =
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

    fun handle(event: PolarWebhookEvent) {
        logger.info("[billing:polar] event type={}", event.type)
        when (event.type) {
            // Primary: full customer state snapshot
            "customer.state_changed" -> handleCustomerStateChanged(event)

            // Fallback: individual subscription events (belt-and-suspenders)
            "subscription.created",
            "subscription.updated",
            "subscription.active",
            -> handleSubscriptionUpsert(event)

            "subscription.revoked",
            "subscription.canceled",
            -> handleSubscriptionRevoke(event)

            "order.paid",
            "order.created",
            -> handleOrder(event)

            else -> logger.debug("[billing:polar] ignoring unknown event type={}", event.type)
        }
    }

    // ─── customer.state_changed ───────────────────────────────────────────────

    /**
     * Handles the canonical Polar state-changed event.
     *
     * The payload is a CustomerState object containing the full current state
     * for a customer: active subscriptions, granted benefits, and meter balances.
     * We upsert every active subscription and invalidate the enforcer cache so
     * the next enforcement call picks up fresh data from Polar.
     *
     * Benefit grants and meter balances are NOT stored in our DB — they are read
     * live from the Polar Customer State API on enforcement (cached for 5 s).
     */
    private fun handleCustomerStateChanged(event: PolarWebhookEvent) {
        val state =
            try {
                json.decodeFromJsonElement<PolarCustomerState>(event.data)
            } catch (e: Exception) {
                logger.error("[billing:polar] failed to parse customer.state_changed payload", e)
                return
            }

        val orgId = state.externalId
        if (orgId.isNullOrBlank()) {
            logger.warn("[billing:polar] customer.state_changed has no external_id — skipping")
            return
        }

        for (sub in state.activeSubscriptions) {
            val product = appRegistry.productForPolarProductId(sub.productId)
            if (product == null) {
                logger.warn("[billing:polar] unknown product_id={} in customer.state_changed", sub.productId)
                continue
            }
            if (product.kind != BillingProductKind.SUBSCRIPTION) {
                logger.warn(
                    "[billing:polar] non-subscription product_id={} appeared in active_subscriptions",
                    sub.productId,
                )
                continue
            }
            subscriptionRepo.upsertCustomer(
                polarCustomerId = state.id,
                orgId = orgId,
                email = state.email,
                appSlug = product.appSlug,
            )
            subscriptionRepo.upsertSubscription(
                polarSubscriptionId = sub.id,
                polarProductId = sub.productId,
                orgId = orgId,
                appSlug = product.appSlug,
                status = sub.status,
                quantity = 1,
            )
            appRegistry.get(product.appSlug)?.invalidate(orgId)

            auditLogClient.log(
                AuditEvent(
                    module = "subscription",
                    action = "synced",
                    resourceType = "subscription",
                    resourceId = sub.id,
                    organizationId = orgId,
                    status = AuditStatus.SUCCESS,
                    metadata =
                        mapOf(
                            "appSlug" to product.appSlug,
                            "productId" to sub.productId,
                            "subscriptionStatus" to sub.status,
                            "trigger" to "customer.state_changed",
                        ),
                ),
            )
        }

        logger.info(
            "[billing:polar] customer.state_changed org={} subscriptions={} benefits={} meters={}",
            orgId,
            state.activeSubscriptions.size,
            state.grantedBenefits.size,
            state.activeMeters.size,
        )
    }

    // ─── Subscription upsert (fallback) ───────────────────────────────────────

    private fun handleSubscriptionUpsert(event: PolarWebhookEvent) {
        val payload =
            try {
                json.decodeFromJsonElement<PolarSubscriptionPayload>(event.data)
            } catch (e: Exception) {
                logger.error("[billing:polar] Failed to parse subscription payload for type={}", event.type, e)
                return
            }

        val orgId = payload.customer.externalId
        if (orgId.isNullOrBlank()) {
            logger.warn("[billing:polar] subscription {} has no external_id (orgId) — skipping", payload.id)
            return
        }

        val product = appRegistry.productForPolarProductId(payload.productId)
        if (product == null) {
            logger.warn("[billing:polar] unknown product_id={} in subscription={}", payload.productId, payload.id)
            return
        }
        if (product.kind != BillingProductKind.SUBSCRIPTION) {
            logger.warn(
                "[billing:polar] non-subscription product_id={} in subscription={}",
                payload.productId,
                payload.id,
            )
            return
        }

        subscriptionRepo.upsertCustomer(
            polarCustomerId = payload.customerId,
            orgId = orgId,
            email = payload.customer.email,
            appSlug = product.appSlug,
        )
        subscriptionRepo.upsertSubscription(
            polarSubscriptionId = payload.id,
            polarProductId = payload.productId,
            orgId = orgId,
            appSlug = product.appSlug,
            status = payload.status,
            quantity = payload.quantity ?: 1,
        )

        // Invalidate entitlement cache so the next enforce call gets fresh data
        appRegistry.get(product.appSlug)?.invalidate(orgId)

        auditLogClient.log(
            AuditEvent(
                module = "subscription",
                action = if (event.type == "subscription.created") "created" else "updated",
                resourceType = "subscription",
                resourceId = payload.id,
                organizationId = orgId,
                status = AuditStatus.SUCCESS,
                metadata =
                    mapOf(
                        "appSlug" to product.appSlug,
                        "productId" to payload.productId,
                        "subscriptionStatus" to payload.status,
                        "trigger" to event.type,
                    ),
            ),
        )

        logger.info(
            "[billing:polar] upserted subscription={} org={} app={} status={}",
            payload.id,
            orgId,
            product.appSlug,
            payload.status,
        )
    }

    // ─── Subscription revoke ──────────────────────────────────────────────────

    private fun handleSubscriptionRevoke(event: PolarWebhookEvent) {
        val payload =
            try {
                json.decodeFromJsonElement<PolarSubscriptionPayload>(event.data)
            } catch (e: Exception) {
                logger.error("[billing:polar] Failed to parse subscription payload for type={}", event.type, e)
                return
            }

        subscriptionRepo.revokeSubscription(payload.id)

        val orgId = payload.customer.externalId
        val product = appRegistry.productForPolarProductId(payload.productId)
        if (orgId != null && product != null) {
            appRegistry.get(product.appSlug)?.invalidate(orgId)
        }

        auditLogClient.log(
            AuditEvent(
                module = "subscription",
                action = "canceled",
                resourceType = "subscription",
                resourceId = payload.id,
                organizationId = orgId,
                status = AuditStatus.SUCCESS,
                riskLevel = AuditRiskLevel.MEDIUM,
                metadata =
                    buildMap {
                        put("trigger", event.type)
                        product?.let {
                            put("appSlug", it.appSlug)
                            put("productId", payload.productId)
                        }
                    },
            ),
        )

        logger.info("[billing:polar] revoked subscription={}", payload.id)
    }

    // ─── Order events for one-time products ───────────────────────────────────

    /**
     * Orders are used for one-time purchases and can also appear around
     * subscription payments. We only persist customer ownership and invalidate
     * cached entitlements; Polar Benefits/Meters own the actual grants.
     */
    private fun handleOrder(event: PolarWebhookEvent) {
        val payload =
            try {
                json.decodeFromJsonElement<PolarOrderPayload>(event.data)
            } catch (e: Exception) {
                logger.error("[billing:polar] Failed to parse order payload for type={}", event.type, e)
                return
            }

        val orgId = payload.customer.externalId
        if (orgId.isNullOrBlank()) {
            logger.warn("[billing:polar] order {} has no external_id (orgId) — skipping", payload.id)
            return
        }

        val product = appRegistry.productForPolarProductId(payload.productId)
        if (product == null) {
            logger.warn("[billing:polar] unknown product_id={} in order={}", payload.productId, payload.id)
            return
        }

        subscriptionRepo.upsertCustomer(
            polarCustomerId = payload.customerId,
            orgId = orgId,
            email = payload.customer.email,
            appSlug = product.appSlug,
        )
        appRegistry.get(product.appSlug)?.invalidate(orgId)

        auditLogClient.log(
            AuditEvent(
                module = "order",
                action = if (event.type == "order.paid") "paid" else "created",
                resourceType = "order",
                resourceId = payload.id,
                organizationId = orgId,
                status = AuditStatus.SUCCESS,
                metadata =
                    mapOf(
                        "appSlug" to product.appSlug,
                        "productSlug" to product.slug,
                        "productKind" to product.kind.name.lowercase(),
                        "trigger" to event.type,
                    ),
            ),
        )

        logger.info(
            "[billing:polar] handled order={} org={} app={} product={} kind={} event={}",
            payload.id,
            orgId,
            product.appSlug,
            product.slug,
            product.kind,
            event.type,
        )
    }
}
