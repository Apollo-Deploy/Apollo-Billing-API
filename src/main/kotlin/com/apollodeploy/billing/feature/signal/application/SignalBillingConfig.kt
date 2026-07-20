package com.apollodeploy.billing.feature.signal.application

import com.apollodeploy.billing.core.BillingAppRegistration
import com.apollodeploy.billing.core.BillingCatalogItem
import com.apollodeploy.billing.core.BillingConfig
import com.apollodeploy.billing.core.BillingEnforcer
import com.apollodeploy.billing.core.BillingProduct
import com.apollodeploy.billing.core.BillingProductKind
import com.apollodeploy.billing.core.PlanAndUsageResolution
import com.apollodeploy.billing.core.PlanFeatureConfig
import com.apollodeploy.billing.core.PlanResolution
import com.apollodeploy.billing.core.SignalDbUnavailableError
import com.apollodeploy.billing.core.toBillingProductKind
import com.apollodeploy.billing.feature.signal.domain.PlanEntitlements
import com.apollodeploy.billing.feature.signal.domain.SIGNAL_AI_CREDIT_METER_ID
import com.apollodeploy.billing.feature.signal.domain.SIGNAL_AUTOMATION_RUN_METER_ID
import com.apollodeploy.billing.feature.signal.domain.SIGNAL_EMAIL_METER_ID
import com.apollodeploy.billing.feature.signal.domain.SIGNAL_MMS_MESSAGE_METER_ID
import com.apollodeploy.billing.feature.signal.domain.SIGNAL_SMS_SEGMENT_METER_ID
import com.apollodeploy.billing.feature.signal.domain.SMS_NO_ADDON_ENTITLEMENTS
import com.apollodeploy.billing.feature.signal.domain.isDedicatedIpEligibleForPlan
import com.apollodeploy.billing.feature.signal.domain.isMultiRegionAllowedForPlan
import com.apollodeploy.billing.feature.signal.domain.signalCatalogProducts
import com.apollodeploy.billing.feature.signal.domain.signalDedicatedIpAddOn
import com.apollodeploy.billing.feature.signal.domain.signalFindPlanByProductId
import com.apollodeploy.billing.feature.signal.domain.signalFindSmsPlanByProductId
import com.apollodeploy.billing.feature.signal.domain.signalGetFreePlan
import com.apollodeploy.billing.feature.signal.domain.signalPlans
import com.apollodeploy.billing.feature.signal.domain.signalSmsPlans
import com.apollodeploy.billing.feature.signal.domain.toFeatureMap
import com.apollodeploy.billing.infrastructure.config.AppConfig
import com.apollodeploy.billing.infrastructure.persistence.DatabasePool
import com.apollodeploy.billing.infrastructure.persistence.SubscriptionRepo
import com.apollodeploy.billing.infrastructure.persistence.prepareAndQuery
import com.apollodeploy.billing.infrastructure.polar.PolarClient

/**
 * Apollo Billing — Signal app billing configuration.
 *
 * Owns the plan resolution and usage SQL for the Signal app.
 *
 * Database efficiency: resolves ALL billing state in exactly 2 DB round-trips:
 *   1. Platform DB (billing_superuser): subscriptions + API key count (single CTE query)
 *   2. Signal DB (billing_superuser): projects, domains, webhooks, daily sends
 *
 * Meter balances come from Polar (via Redis-cached PolarStateCache) — no DB call.
 */
class SignalBillingConfig(
    private val db: DatabasePool,
    private val platformReaderDb: DatabasePool,
    private val signalDb: DatabasePool?,
    private val subscriptionRepo: SubscriptionRepo,
    private val polarClient: PolarClient,
    private val polarStateCache: com.apollodeploy.billing.infrastructure.redis.PolarStateCache? = null,
) {
    companion object {
        const val APP_SLUG = "signal"
    }

    private val basePlanProductIds: List<String> =
        signalPlans.map { it.polarProductId }.filter { it.isNotBlank() }

    private val smsProductIds: List<String> =
        signalSmsPlans.map { it.polarProductId }.filter { it.isNotBlank() }

    private val dedicatedIpProductId: String =
        signalDedicatedIpAddOn.polarProductId

    // ─── Consolidated SQL ─────────────────────────────────────────────────────

    /**
     * Single platform DB query that resolves ALL subscription state + API key count.
     *
     * Returns in one round-trip:
     *   - Active base plan product ID (newest)
     *   - Active SMS add-on product ID (newest)
     *   - Dedicated IP add-on quantity (sum of active)
     *   - API key count
     *
     * Uses billing_superuser role (read-only) since it needs access to both
     * billing_subscriptions AND apikey tables.
     */
    private fun buildPlatformQuery(): String {
        val basePlanPlaceholders = basePlanProductIds.joinToString(",") { "?" }
        val smsPlaceholders = smsProductIds.joinToString(",") { "?" }

        return """
            WITH base_plan AS (
                SELECT s.polar_product_id
                FROM billing_subscriptions s
                JOIN billing_customers c ON c.app_id = s.app_id AND c.customer_id = s.customer_id
                JOIN platform_apps a ON a.id = s.app_id
                WHERE a.slug = ?
                  AND c.external_ref = ?
                  AND s.polar_product_id IN ($basePlanPlaceholders)
                  AND s.status IN ('active', 'trialing', 'past_due')
                ORDER BY s.created_at DESC
                LIMIT 1
            ),
            sms_plan AS (
                SELECT s.polar_product_id
                FROM billing_subscriptions s
                JOIN billing_customers c ON c.app_id = s.app_id AND c.customer_id = s.customer_id
                JOIN platform_apps a ON a.id = s.app_id
                WHERE a.slug = ?
                  AND c.external_ref = ?
                  AND s.polar_product_id IN ($smsPlaceholders)
                  AND s.status IN ('active', 'trialing', 'past_due')
                ORDER BY s.created_at DESC
                LIMIT 1
            ),
            dedicated_ip AS (
                SELECT COALESCE(SUM(GREATEST(COALESCE(s.quantity, 1), 0)), 0)::int AS cnt
                FROM billing_subscriptions s
                JOIN billing_customers c ON c.app_id = s.app_id AND c.customer_id = s.customer_id
                JOIN platform_apps a ON a.id = s.app_id
                WHERE a.slug = ?
                  AND c.external_ref = ?
                  AND s.polar_product_id = ?
                  AND s.status IN ('active', 'trialing', 'past_due')
            ),
            api_keys AS (
                SELECT COUNT(*)::int AS cnt
                FROM apikey k
                WHERE k."referenceId" = ?
                  AND k."configId" = 'signal-keys'
                  AND k.enabled = TRUE
            )
            SELECT
                (SELECT polar_product_id FROM base_plan) AS "basePlanProductId",
                (SELECT polar_product_id FROM sms_plan) AS "smsPlanProductId",
                (SELECT cnt FROM dedicated_ip) AS "dedicatedIpQty",
                (SELECT cnt FROM api_keys) AS "apiKeyCount"
            """.trimIndent()
    }

    /**
     * Signal DB query — resolves all usage counters in a single round-trip.
     *
     * Includes:
     *   - projects (non-deleted)
     *   - domains (verified)
     *   - webhooks (active)
     *   - daily email sends (from pre-aggregated usage table)
     *   - active SMS senders (phone numbers with status 'active')
     *   - daily SMS segments sent (for observability; enforcement uses Polar meters)
     */
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
          ),
          sms_senders_count AS (
            SELECT COUNT(*)::int AS cnt FROM sms_senders
            WHERE organization_id = ? AND status = 'active'
          ),
          daily_sms_segments AS (
            SELECT COALESCE(SUM(segment_count), 0)::int AS cnt
            FROM sms_messages
            WHERE organization_id = ?
              AND created_at >= (now() AT TIME ZONE 'UTC')::date
              AND status NOT IN ('queued', 'scheduled')
          )
        SELECT
          (SELECT cnt FROM projects_count)    AS "maxProjects",
          (SELECT cnt FROM domains_count)     AS "maxDomains",
          (SELECT cnt FROM webhooks_count)    AS "maxWebhooks",
          (SELECT cnt FROM daily_sends)       AS "dailySends",
          (SELECT cnt FROM sms_senders_count) AS "smsSenders",
          (SELECT cnt FROM daily_sms_segments) AS "dailySmsSegments"
        """.trimIndent()

    // ─── Platform state data class ────────────────────────────────────────────

    private data class PlatformBillingState(
        val basePlanProductId: String?,
        val smsPlanProductId: String?,
        val dedicatedIpQuantity: Int,
        val apiKeyCount: Int,
    )

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
                resolvePlanAndUsage = { orgId -> resolvePlanAndUsage(orgId) },
                cacheTtlMs = 5_000,
            ),
        )

    // ─── Internal resolvers ────────────────────────────────────────────────

    /**
     * Fetches ALL platform billing state in a single DB round-trip.
     * Uses billing_superuser role (read-only access to both billing_* and apikey).
     */
    private fun fetchPlatformState(orgId: String): PlatformBillingState {
        val sql = buildPlatformQuery()

        // Build params: appSlug, orgId for each CTE + dedicated IP product ID + orgId for apikey
        val params =
            buildList {
                // base_plan CTE: app_slug, org_id, ...product_ids
                add(APP_SLUG)
                add(orgId)
                addAll(basePlanProductIds)
                // sms_plan CTE: app_slug, org_id, ...sms_product_ids
                add(APP_SLUG)
                add(orgId)
                addAll(smsProductIds)
                // dedicated_ip CTE: app_slug, org_id, product_id
                add(APP_SLUG)
                add(orgId)
                add(dedicatedIpProductId)
                // api_keys CTE: org_id
                add(orgId)
            }

        return platformReaderDb.withConnection { conn ->
            conn
                .prepareAndQuery(sql, params) { rs ->
                    PlatformBillingState(
                        basePlanProductId = rs.getString("basePlanProductId"),
                        smsPlanProductId = rs.getString("smsPlanProductId"),
                        dedicatedIpQuantity = rs.getInt("dedicatedIpQty"),
                        apiKeyCount = rs.getInt("apiKeyCount"),
                    )
                }.firstOrNull() ?: PlatformBillingState(null, null, 0, 0)
        }
    }

    /**
     * Combined plan + usage resolution.
     *
     * Total DB calls: 1 platform query + 1 signal query = 2 round-trips.
     * (Previously this was 3 subscription queries + 1 apikey query + 1 signal query = 5.)
     */
    private suspend fun resolvePlanAndUsage(orgId: String): PlanAndUsageResolution {
        // ── 1 query: Platform DB (subscriptions + API keys) ──────────────────
        val state = fetchPlatformState(orgId)

        val plan =
            state.basePlanProductId
                ?.let { signalFindPlanByProductId(it) }
                ?: signalGetFreePlan()

        val baseConfig = plan.entitlements.toPlanFeatureConfig()
        val dedicatedIpsEnabled = isDedicatedIpEligibleForPlan(plan.slug) && state.dedicatedIpQuantity > 0

        val smsFeatures =
            if (state.smsPlanProductId != null) {
                signalFindSmsPlanByProductId(state.smsPlanProductId)
                    ?.entitlements
                    ?.toFeatureMap()
                    ?: SMS_NO_ADDON_ENTITLEMENTS.toFeatureMap()
            } else {
                SMS_NO_ADDON_ENTITLEMENTS.toFeatureMap()
            }

        val planResolution =
            PlanResolution(
                planId = plan.slug,
                config =
                    baseConfig.copy(
                        features =
                            baseConfig.features +
                                mapOf(
                                    "multiRegion" to isMultiRegionAllowedForPlan(plan.slug),
                                    "dedicatedIps" to dedicatedIpsEnabled,
                                ) +
                                smsFeatures,
                    ),
            )

        // ── 1 query: Signal DB (projects, domains, webhooks, daily sends, SMS senders) ────
        val signalUsage =
            if (signalDb != null) {
                signalDb.withConnection { conn ->
                    conn
                        .prepareAndQuery(SQL_SIGNAL_USAGE, List(6) { orgId }) { rs ->
                            mapOf(
                                "maxProjects" to rs.getInt("maxProjects"),
                                "maxDomains" to rs.getInt("maxDomains"),
                                "maxWebhooks" to rs.getInt("maxWebhooks"),
                                "dailySends" to rs.getInt("dailySends"),
                                "smsSenders" to rs.getInt("smsSenders"),
                                "dailySmsSegments" to rs.getInt("dailySmsSegments"),
                            )
                        }.firstOrNull() ?: emptyMap()
                }
            } else {
                throw SignalDbUnavailableError(orgId)
            }

        // API key count came from the platform query — include it in usage
        val dbUsage = signalUsage + mapOf("maxApiKeys" to state.apiKeyCount)

        // ── Polar meter balances (via Redis-cached state, no DB) ─────────────
        val customerState =
            polarStateCache?.getCustomerState(orgId)
                ?: polarClient.getCustomerState(orgId)

        fun meterBalance(meterId: String): Int? {
            if (meterId.isBlank()) return null
            return customerState?.activeMeters?.find { it.meterId == meterId }?.balance
        }

        val usage =
            dbUsage +
                buildMap {
                    meterBalance(SIGNAL_EMAIL_METER_ID)?.let { put("monthlySends", it) }
                    meterBalance(AppConfig.signalEmailReceivedMeterId)?.let {
                        put("inboundReceivedBalance", it)
                    }
                    meterBalance(SIGNAL_AUTOMATION_RUN_METER_ID)?.let { put("automationRunBalance", it) }
                    meterBalance(SIGNAL_AI_CREDIT_METER_ID)?.let { put("aiCreditBalance", it) }
                    meterBalance(SIGNAL_SMS_SEGMENT_METER_ID)?.let { put("smsSegmentBalance", it) }
                    meterBalance(SIGNAL_MMS_MESSAGE_METER_ID)?.let { put("mmsMessageBalance", it) }
                }

        return PlanAndUsageResolution(plan = planResolution, usage = usage)
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
                "inboundReceiving" to inboundReceiving,
            ),
    )
