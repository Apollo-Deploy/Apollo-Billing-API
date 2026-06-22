package com.apollodeploy.billing.feature.signal.application

import com.apollodeploy.billing.core.BillingAppRegistration
import com.apollodeploy.billing.core.BillingCatalogItem
import com.apollodeploy.billing.core.BillingConfig
import com.apollodeploy.billing.core.BillingEnforcer
import com.apollodeploy.billing.core.BillingProduct
import com.apollodeploy.billing.core.BillingProductKind
import com.apollodeploy.billing.core.PlanFeatureConfig
import com.apollodeploy.billing.core.PlanResolution
import com.apollodeploy.billing.core.toBillingProductKind
import com.apollodeploy.billing.feature.signal.domain.PlanEntitlements
import com.apollodeploy.billing.feature.signal.domain.SIGNAL_AI_CREDIT_METER_ID
import com.apollodeploy.billing.feature.signal.domain.SIGNAL_AUTOMATION_RUN_METER_ID
import com.apollodeploy.billing.feature.signal.domain.SIGNAL_EMAIL_METER_ID
import com.apollodeploy.billing.feature.signal.domain.isDedicatedIpEligibleForPlan
import com.apollodeploy.billing.feature.signal.domain.isMultiRegionAllowedForPlan
import com.apollodeploy.billing.feature.signal.domain.signalCatalogProducts
import com.apollodeploy.billing.feature.signal.domain.signalDedicatedIpAddOn
import com.apollodeploy.billing.feature.signal.domain.signalFindPlanByProductId
import com.apollodeploy.billing.feature.signal.domain.signalGetFreePlan
import com.apollodeploy.billing.feature.signal.domain.signalPlans
import com.apollodeploy.billing.infrastructure.persistence.DatabasePool
import com.apollodeploy.billing.infrastructure.persistence.SubscriptionRepo
import com.apollodeploy.billing.infrastructure.persistence.prepareAndQuery
import com.apollodeploy.billing.infrastructure.polar.PolarClient

/**
 * Apollo Billing — Signal app billing configuration.
 *
 * Owns the plan resolution and usage SQL for the Signal app.
 * Ported from apollo-signal-api infrastructure/billing/SignalBillingResolver.kt.
 *
 * resolvePlan  → queries subscriptions table (platform DB)
 * resolveUsage → queries signal's own tables (same platform DB)
 */
class SignalBillingConfig(
    private val db: DatabasePool,
    private val platformReaderDb: DatabasePool,
    private val signalDb: DatabasePool?,
    private val subscriptionRepo: SubscriptionRepo,
    private val polarClient: PolarClient,
) {
    companion object {
        const val APP_SLUG = "signal"
    }

    private val basePlanProductIds: List<String> =
        signalPlans.map { it.polarProductId }.filter { it.isNotBlank() }

    // ─── Usage resolution SQL ─────────────────────────────────────────────────

    // Queries against the Signal database (signalDb).
    // dailySends comes from organization_usage_daily so per-send entitlement
    // lookups do not count today's emails repeatedly.
    private val SQL_SIGNAL_USAGE =
        """
        WITH
          projects_count AS (
            SELECT COUNT(*)::int AS cnt FROM projects
            WHERE organization_id = ? AND status != 'deleted'
          ),
          domains_count AS (
            SELECT COUNT(*)::int AS cnt FROM domains
            WHERE organization_id = ? AND status = 'verified'
          ),
          webhooks_count AS (
            SELECT COUNT(*)::int AS cnt FROM webhook_endpoints
            WHERE organization_id = ? AND deleted_at IS NULL
          ),
          daily_sends AS (
            SELECT COALESCE((
              SELECT email_count
              FROM organization_usage_daily
              WHERE organization_id = ?
                AND usage_date = (now() AT TIME ZONE 'UTC')::date
            ), 0)::int AS cnt
          )
        SELECT
          (SELECT cnt FROM projects_count) AS "maxProjects",
          (SELECT cnt FROM domains_count)  AS "maxDomains",
          (SELECT cnt FROM webhooks_count) AS "maxWebhooks",
          (SELECT cnt FROM daily_sends)    AS "dailySends"
        """.trimIndent()
    // Note: monthlySends is now sourced from the Polar email meter balance
    // (emailSendBalance) rather than a COUNT(*) on the emails table.
    // This means enforce checks always reflect the live Polar meter state and
    // reportEmailSend() events are the single source of truth for monthly usage.

    // Queries against the platform database via billing_superuser (read-only).
    // apikey is granted SELECT to billing_superuser, not billing_app.
    private val SQL_PLATFORM_API_KEY_COUNT =
        """
        SELECT COUNT(*)::int AS cnt
        FROM apikey k
        WHERE k."referenceId" = ?
          AND k."configId" = 'signal-keys'
          AND k.enabled = TRUE
        """.trimIndent()

    // ─── Public factory ───────────────────────────────────────────────────────

    /**
     * Builds the Signal app registration wired to its enforcer and Polar products.
     */
    fun buildRegistration(): BillingAppRegistration =
        BillingAppRegistration(
            slug = APP_SLUG,
            enforcer = buildEnforcer(),
            products = billingProducts(),
            catalog = billingCatalog(),
        )

    private fun buildEnforcer(): BillingEnforcer =
        BillingEnforcer(
            BillingConfig(
                appSlug = APP_SLUG,
                resolvePlan = { orgId -> resolvePlan(orgId) },
                resolveUsage = { orgId -> resolveUsage(orgId) },
                cacheTtlMs = 5_000,
            ),
        )

    // ─── Internal resolvers ────────────────────────────────────────────────

    private fun resolvePlan(orgId: String): PlanResolution {
        val plan =
            subscriptionRepo
                .findLatestActiveProductId(APP_SLUG, orgId, basePlanProductIds)
                ?.let { signalFindPlanByProductId(it) }
                ?: signalGetFreePlan() // No active subscription → free plan (signal-spark)

        val baseConfig = plan.entitlements.toPlanFeatureConfig()
        val dedicatedIpsEnabled = isDedicatedIpEligibleForPlan(plan.slug) && hasDedicatedIpAddOn(orgId)
        return PlanResolution(
            planId = plan.slug,
            config =
                baseConfig.copy(
                    features =
                        baseConfig.features +
                            mapOf(
                                "multiRegion" to isMultiRegionAllowedForPlan(plan.slug),
                                "dedicatedIps" to dedicatedIpsEnabled,
                            ),
                ),
        )
    }

    private fun hasDedicatedIpAddOn(orgId: String): Boolean {
        val productId = signalDedicatedIpAddOn.polarProductId
        if (productId.isBlank()) return false
        return subscriptionRepo.activeSubscriptionQuantity(APP_SLUG, orgId, productId) > 0
    }

    private fun billingProducts(): List<BillingProduct> =
        buildList {
            signalPlans
                .filter { it.polarProductId.isNotBlank() }
                .forEach { plan ->
                    add(
                        BillingProduct(
                            appSlug = APP_SLUG,
                            slug = plan.slug,
                            polarProductId = plan.polarProductId,
                            kind = BillingProductKind.SUBSCRIPTION,
                        ),
                    )
                }

            signalCatalogProducts
                .filter { it.polarProductId.isNotBlank() }
                .forEach { product ->
                    add(
                        BillingProduct(
                            appSlug = APP_SLUG,
                            slug = product.slug,
                            polarProductId = product.polarProductId,
                            kind = product.kind.toBillingProductKind(),
                        ),
                    )
                }
        }

    private fun billingCatalog(): List<BillingCatalogItem> =
        buildList {
            signalPlans
                .filter { it.polarProductId.isNotBlank() }
                .forEach { plan ->
                    val config = plan.entitlements.toPlanFeatureConfig()
                    add(
                        BillingCatalogItem(
                            slug = plan.slug,
                            polarProductId = plan.polarProductId,
                            name = plan.name,
                            description = "${plan.name} Signal subscription plan.",
                            kind = com.apollodeploy.billing.core.BillingCatalogProductKind.SUBSCRIPTION,
                            fallbackPriceCents = plan.price * 100,
                            currency = plan.currency,
                            limits = config.limits,
                            features = config.features,
                            metadata = mapOf("planId" to plan.slug),
                        ),
                    )
                }

            signalCatalogProducts
                .filter { it.polarProductId.isNotBlank() }
                .forEach { product ->
                    add(
                        BillingCatalogItem(
                            slug = product.slug,
                            polarProductId = product.polarProductId,
                            name = product.name,
                            kind = product.kind,
                            fallbackPriceCents = product.price * 100,
                            currency = product.currency,
                        ),
                    )
                }
        }

    private suspend fun resolveUsage(orgId: String): Map<String, Int> {
        // Signal DB: projects, domains, webhook_endpoints, organization_usage_daily.
        val signalUsage =
            signalDb?.withConnection { conn ->
                conn
                    .prepareAndQuery(SQL_SIGNAL_USAGE, List(4) { orgId }) { rs ->
                        mapOf(
                            "maxProjects" to rs.getInt("maxProjects"),
                            "maxDomains" to rs.getInt("maxDomains"),
                            "maxWebhooks" to rs.getInt("maxWebhooks"),
                            "dailySends" to rs.getInt("dailySends"),
                        )
                    }.firstOrNull() ?: emptyMap()
            } ?: emptyMap()

        // Platform reader DB: apikey (billing_superuser — SELECT only)
        val apiKeyCount =
            platformReaderDb.withConnection { conn ->
                conn
                    .prepareAndQuery(SQL_PLATFORM_API_KEY_COUNT, listOf(orgId)) { rs ->
                        rs.getInt("cnt")
                    }.firstOrNull() ?: 0
            }

        val dbUsage = signalUsage + mapOf("maxApiKeys" to apiKeyCount)

        // Meter balances come from Polar's Usage Meters, not our DB.
        // Polar's Credits benefit automatically credits the relevant meter on
        // subscription activation and one-time pack purchases. Usage events
        // ingested via reportEmailSend / reportAutomationRun decrement the balance.
        //
        // Fail-open: if getCustomerState returns null (Polar unavailable), we omit
        // meter balances from the map. BillingEnforcer.enforceMeter treats an
        // absent key as unlimited, so customers are never blocked by a Polar outage.
        val customerState = polarClient.getCustomerState(orgId)

        fun meterBalance(meterId: String): Int? {
            if (meterId.isBlank()) return null
            return customerState
                ?.activeMeters
                ?.find { it.meterId == meterId }
                ?.balance
        }

        return dbUsage +
            buildMap {
                // monthlySends: Polar email meter — the plan's credited_units (from meter credit
                // benefits) minus consumed_units (from reportEmailSend() calls). This is the
                // authoritative source for monthly quota; the old COUNT(*) query is removed.
                // Fail-open: if Polar is unavailable this key is absent → enforceMeter passes.
                meterBalance(SIGNAL_EMAIL_METER_ID)?.let { put("monthlySends", it) }
                meterBalance(SIGNAL_AUTOMATION_RUN_METER_ID)?.let { put("automationRunBalance", it) }
                meterBalance(SIGNAL_AI_CREDIT_METER_ID)?.let { put("aiCreditBalance", it) }
            }
    }
}

// ─── PlanEntitlements → PlanFeatureConfig ─────────────────────────────────────

fun PlanEntitlements.toPlanFeatureConfig() =
    PlanFeatureConfig(
        limits =
            buildMap {
                if (maxProjects != 0) put("maxProjects", maxProjects)
                if (maxDomains != 0) put("maxDomains", maxDomains)
                if (maxWebhooks != 0) put("maxWebhooks", maxWebhooks)
                if (maxApiKeys != 0) put("maxApiKeys", maxApiKeys)
                if (dailySends != 0) put("dailySends", dailySends)
                if (monthlySends != 0) put("monthlySends", monthlySends)
                if (aiCredits != 0) put("aiCredits", aiCredits)
                put("dataRetentionDays", dataRetentionDays)
            },
        features =
            mapOf(
                "customTrackingDomain" to customTrackingDomain,
                "advancedWebhooks" to advancedWebhooks,
                "signedWebhooks" to signedWebhooks,
                "readEngagement" to readEngagement,
                "enrichedTracking" to enrichedTracking,
                "forwardingDetection" to forwardingDetection,
                "deliverabilityAdvisor" to deliverabilityAdvisor,
                "realtimeStream" to realtimeStream,
                "sendTimeOptimisation" to sendTimeOptimisation,
                "dedicatedIps" to dedicatedIps,
                "multiRegion" to multiRegion,
            ),
    )
