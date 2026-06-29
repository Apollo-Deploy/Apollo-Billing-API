package com.apollodeploy.billing.feature.signal.application

import com.apollodeploy.billing.feature.signal.domain.SIGNAL_MMS_MESSAGE_METER_ID
import com.apollodeploy.billing.feature.signal.domain.SIGNAL_SMS_SEGMENT_METER_ID
import com.apollodeploy.billing.feature.signal.domain.SignalSmsPlan
import com.apollodeploy.billing.feature.signal.domain.SmsErrorCodes
import com.apollodeploy.billing.feature.signal.domain.SmsEventKeys
import com.apollodeploy.billing.feature.signal.domain.isSmsPlanIncludesMms
import com.apollodeploy.billing.feature.signal.domain.isSmsSupportedForBasePlan
import com.apollodeploy.billing.feature.signal.domain.signalFindSmsPlanByProductId
import com.apollodeploy.billing.feature.signal.domain.signalMmsAddOn
import com.apollodeploy.billing.feature.signal.domain.signalSmsPlans
import com.apollodeploy.billing.infrastructure.persistence.SubscriptionRepo
import com.apollodeploy.billing.infrastructure.polar.PolarClient
import com.apollodeploy.billing.infrastructure.redis.PolarStateCache
import kotlinx.serialization.json.JsonPrimitive

/**
 * Apollo Billing — Signal SMS enforcement and usage reporting.
 *
 * This class implements the SMS-specific billing checks described in the
 * SMS Billing specification. SMS is a paid add-on subscription that stacks
 * on top of the base Signal plan (Ignite+).
 *
 * Enforcement flow:
 * 1. requirePaidPlan → checks base plan is Ignite or higher
 * 2. requireSmsAddon → checks active SMS add-on subscription exists
 * 3. requireSmsSegments → checks meter balance (with overage opt-in logic)
 *
 * Overage model:
 * - Default: sends are hard-blocked when allowance exhausted (402)
 * - Opt-in: if smsOverageEnabled = true on project, sends continue and bill overage
 *
 * Security:
 * - Redis-backed Polar state cache — meters enforced even during Polar outage
 * - Only fails open when BOTH Polar AND Redis are simultaneously unavailable
 */
class SignalSmsBilling(
    private val subscriptionRepo: SubscriptionRepo,
    private val polarClient: PolarClient,
    private val projectSettingsProvider: SmsProjectSettingsProvider,
    private val polarStateCache: PolarStateCache? = null,
) {
    companion object {
        const val APP_SLUG = "signal"
    }

    // ─── SMS Plan Gating ──────────────────────────────────────────────────

    /**
     * Throws 402 if org is on Spark (free). SMS requires a paid base plan.
     */
    suspend fun requirePaidPlan(
        orgId: String,
        basePlanSlug: String,
        onBlocked: (SmsBlockedError) -> Nothing,
    ) {
        if (!isSmsSupportedForBasePlan(basePlanSlug)) {
            onBlocked(
                SmsBlockedError(
                    code = SmsErrorCodes.PLAN_REQUIRED,
                    message = "SMS requires a paid Signal plan (Ignite or higher).",
                    orgId = orgId,
                ),
            )
        }
    }

    /**
     * Throws 402 if org does not have an active SMS add-on subscription.
     * Returns the resolved SMS plan for further entitlement checks.
     */
    suspend fun requireSmsAddon(
        orgId: String,
        onBlocked: (SmsBlockedError) -> Nothing,
    ): SignalSmsPlan {
        val smsProductIds = signalSmsPlans.map { it.polarProductId }.filter { it.isNotBlank() }
        if (smsProductIds.isEmpty()) {
            // No product IDs configured — fail open during development
            return signalSmsPlans.first()
        }

        val activeProductId =
            subscriptionRepo.findLatestActiveProductId(
                APP_SLUG,
                orgId,
                smsProductIds,
            )

        if (activeProductId == null) {
            onBlocked(
                SmsBlockedError(
                    code = SmsErrorCodes.ADDON_REQUIRED,
                    message = "An active SMS add-on subscription is required.",
                    orgId = orgId,
                ),
            )
        }

        return signalFindSmsPlanByProductId(activeProductId!!)
            ?: signalSmsPlans.first() // Fallback — shouldn't happen
    }

    /**
     * Throws 402 if org does not have the MMS add-on.
     * Growth+ SMS plans include MMS free; lower tiers need the separate add-on.
     */
    suspend fun requireMmsAddon(
        orgId: String,
        smsPlanSlug: String,
        onBlocked: (SmsBlockedError) -> Nothing,
    ) {
        // Growth+ SMS plans include MMS natively
        if (isSmsPlanIncludesMms(smsPlanSlug)) return

        // Check for the separate MMS add-on subscription
        val mmsProductId = signalMmsAddOn.polarProductId
        if (mmsProductId.isBlank()) return // Not configured — fail open

        val hasAddOn = subscriptionRepo.activeSubscriptionQuantity(APP_SLUG, orgId, mmsProductId) > 0
        if (!hasAddOn) {
            onBlocked(
                SmsBlockedError(
                    code = SmsErrorCodes.MMS_ADDON_REQUIRED,
                    message = "MMS requires the MMS add-on or an SMS Growth+ plan.",
                    orgId = orgId,
                ),
            )
        }
    }

    // ─── SMS Meter Enforcement ────────────────────────────────────────────

    /**
     * Check SMS segment meter balance.
     * If segments exhausted:
     *   - If smsOverageEnabled = true on the project → allow (meter continues, billed later)
     *   - If smsOverageEnabled = false (default) → throw 402 sms.segments_exhausted
     *
     * Redis fallback: if Polar is down, PolarStateCache returns last-known-good from Redis.
     * Only if BOTH Polar AND Redis are down does `balance` become null (fail-open as last resort).
     */
    suspend fun requireSmsSegments(
        orgId: String,
        projectId: String,
        needed: Int = 1,
        onBlocked: (SmsBlockedError) -> Nothing,
    ) {
        if (SIGNAL_SMS_SEGMENT_METER_ID.isBlank()) return // Not configured — fail open

        val balance = getMeterBalance(orgId, SIGNAL_SMS_SEGMENT_METER_ID)
        if (balance == null) return // Both Polar AND Redis unavailable — last-resort fail-open

        if (balance < needed) {
            val overageEnabled = projectSettingsProvider.getSmsOverageEnabled(projectId)
            if (!overageEnabled) {
                onBlocked(
                    SmsBlockedError(
                        code = SmsErrorCodes.SEGMENTS_EXHAUSTED,
                        message =
                            "Monthly SMS segment allowance exhausted. " +
                                "Enable overage, upgrade your SMS plan, or purchase a segment top-up pack.",
                        orgId = orgId,
                        remaining = balance,
                        needed = needed,
                    ),
                )
            }
            // Overage enabled — allow the send, usage will be billed as overage
        }
    }

    /**
     * Check MMS message meter balance (same Redis fallback logic).
     */
    suspend fun requireMmsMessages(
        orgId: String,
        projectId: String,
        needed: Int = 1,
        onBlocked: (SmsBlockedError) -> Nothing,
    ) {
        if (SIGNAL_MMS_MESSAGE_METER_ID.isBlank()) return // Not configured — fail open

        val balance = getMeterBalance(orgId, SIGNAL_MMS_MESSAGE_METER_ID)
        if (balance == null) return // Both Polar AND Redis unavailable — last-resort fail-open

        if (balance < needed) {
            val overageEnabled = projectSettingsProvider.getSmsOverageEnabled(projectId)
            if (!overageEnabled) {
                onBlocked(
                    SmsBlockedError(
                        code = SmsErrorCodes.SEGMENTS_EXHAUSTED,
                        message =
                            "Monthly MMS message allowance exhausted. " +
                                "Enable overage or purchase additional capacity.",
                        orgId = orgId,
                        remaining = balance,
                        needed = needed,
                    ),
                )
            }
        }
    }

    // ─── SMS Feature Gating ───────────────────────────────────────────────

    suspend fun requireSmsFeature(
        orgId: String,
        smsPlan: SignalSmsPlan,
        feature: String,
        onBlocked: (SmsBlockedError) -> Nothing,
    ) {
        val features =
            smsPlan.entitlements.run {
                mapOf(
                    "smsNumberPooling" to smsNumberPooling,
                    "smsShortCodes" to smsShortCodes,
                    "smsAbTesting" to smsAbTesting,
                    "smsSendTimeOptimization" to smsSendTimeOptimization,
                    "smsCarrierReporting" to smsCarrierReporting,
                    "smsCostAnalytics" to smsCostAnalytics,
                    "smsConversationThreads" to smsConversationThreads,
                    "smsKeywordRouting" to smsKeywordRouting,
                    "smsAiAutoReply" to smsAiAutoReply,
                    "smsRcs" to smsRcs,
                )
            }

        if (features[feature] != true) {
            onBlocked(
                SmsBlockedError(
                    code = SmsErrorCodes.FEATURE_NOT_AVAILABLE,
                    message = "Feature \"$feature\" requires a higher SMS tier.",
                    orgId = orgId,
                ),
            )
        }
    }

    suspend fun requireSmsNumberPooling(
        orgId: String,
        smsPlan: SignalSmsPlan,
        onBlocked: (SmsBlockedError) -> Nothing,
    ) = requireSmsFeature(orgId, smsPlan, "smsNumberPooling", onBlocked)

    suspend fun requireSmsShortCodes(
        orgId: String,
        smsPlan: SignalSmsPlan,
        onBlocked: (SmsBlockedError) -> Nothing,
    ) = requireSmsFeature(orgId, smsPlan, "smsShortCodes", onBlocked)

    suspend fun requireSmsAbTesting(
        orgId: String,
        smsPlan: SignalSmsPlan,
        onBlocked: (SmsBlockedError) -> Nothing,
    ) = requireSmsFeature(orgId, smsPlan, "smsAbTesting", onBlocked)

    suspend fun requireSmsSendTimeOptimization(
        orgId: String,
        smsPlan: SignalSmsPlan,
        onBlocked: (SmsBlockedError) -> Nothing,
    ) = requireSmsFeature(orgId, smsPlan, "smsSendTimeOptimization", onBlocked)

    // ─── SMS Usage Reporting ──────────────────────────────────────────────

    /**
     * Report SMS segment(s) sent. Fire-and-forget after successful Bandwidth send.
     */
    suspend fun reportSmsSend(
        orgId: String,
        segmentCount: Int,
        requestId: String? = null,
    ) {
        polarClient.ingestUsageEvent(
            orgId = orgId,
            eventName = SmsEventKeys.SEGMENT_SENT,
            quantity = segmentCount,
            metadata =
                buildMap {
                    requestId?.let { put("requestId", JsonPrimitive(it)) }
                },
        )
    }

    /**
     * Report MMS message sent. Fire-and-forget after successful MMS send.
     */
    suspend fun reportMmsSend(
        orgId: String,
        requestId: String? = null,
    ) {
        polarClient.ingestUsageEvent(
            orgId = orgId,
            eventName = SmsEventKeys.MMS_MESSAGE_SENT,
            quantity = 1,
            metadata =
                buildMap {
                    requestId?.let { put("requestId", JsonPrimitive(it)) }
                },
        )
    }

    // ─── Internal helpers ─────────────────────────────────────────────────

    private suspend fun getMeterBalance(
        orgId: String,
        meterId: String,
    ): Int? {
        // Use PolarStateCache (Redis fallback) if available, otherwise direct Polar call
        val customerState =
            polarStateCache?.getCustomerState(orgId)
                ?: polarClient.getCustomerState(orgId)
                ?: return null
        return customerState.activeMeters
            .find { it.meterId == meterId }
            ?.balance
    }
}

// ─── Supporting types ─────────────────────────────────────────────────────────

/**
 * Error returned when an SMS billing check fails.
 */
data class SmsBlockedError(
    val code: String,
    val message: String,
    val orgId: String,
    val remaining: Int? = null,
    val needed: Int? = null,
)

/**
 * Abstraction for reading project-level SMS overage settings.
 * Implemented by the Signal service's project repository.
 */
interface SmsProjectSettingsProvider {
    /**
     * Reads the smsOverageEnabled flag from project settings.
     * Returns false (hard-block) by default.
     */
    suspend fun getSmsOverageEnabled(projectId: String): Boolean
}
