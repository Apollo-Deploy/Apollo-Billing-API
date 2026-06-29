package com.apollodeploy.billing.feature.signal.domain

import com.apollodeploy.billing.core.BillingCatalogProductKind

/*
 * Apollo Billing — Signal SMS add-on catalog.
 *
 * SMS is a paid add-on subscription that stacks on top of the base Signal plan.
 * Spark (free) has NO SMS access. Customers must subscribe to both their base
 * email plan AND the SMS add-on to use SMS features.
 *
 * Overage model:
 *   - Default: hard-blocked when monthly allowance exhausted (402 sms.segments_exhausted)
 *   - Opt-in: customer enables overage via project settings → billed at per-plan rate
 */

// ─── Polar Meter IDs ─────────────────────────────────────────────────────────

/** Polar meter for SMS segments sent. Event: signal.sms.segment_sent */
const val SIGNAL_SMS_SEGMENT_METER_ID = "a57305e9-81b8-4624-aff0-5d8b957d434e"

/** Polar meter for MMS messages sent. Event: signal.mms.message_sent */
const val SIGNAL_MMS_MESSAGE_METER_ID = "124b831b-4340-4e67-a38f-449794058777"

// ─── SMS Plan Entitlements ────────────────────────────────────────────────────

/**
 * Feature entitlements per SMS add-on tier.
 *
 * Feature matrix:
 * | Feature               | Lite  | Starter | Growth | Business | Scale | Enterprise |
 * |-----------------------|-------|---------|--------|----------|-------|------------|
 * | smsEnabled            | true  | true    | true   | true     | true  | true       |
 * | mmsEnabled            | false | false   | true   | true     | true  | true       |
 * | smsNumberPooling      | false | false   | false  | true     | true  | true       |
 * | smsShortCodes         | false | false   | false  | false    | true  | true       |
 * | smsAbTesting          | false | false   | true   | true     | true  | true       |
 * | smsSendTimeOptimization| false | false   | true   | true     | true  | true       |
 * | smsCarrierReporting   | false | false   | true   | true     | true  | true       |
 * | smsCostAnalytics      | true  | true    | true   | true     | true  | true       |
 * | smsConversationThreads| false | true    | true   | true     | true  | true       |
 * | smsKeywordRouting     | false | true    | true   | true     | true  | true       |
 * | smsAiAutoReply        | false | false   | false  | true     | true  | true       |
 * | smsRcs                | false | false   | false  | true     | true  | true       |
 */
data class SmsEntitlements(
    val smsEnabled: Boolean,
    val mmsEnabled: Boolean,
    val smsNumberPooling: Boolean,
    val smsShortCodes: Boolean,
    val smsAbTesting: Boolean,
    val smsSendTimeOptimization: Boolean,
    val smsCarrierReporting: Boolean,
    val smsCostAnalytics: Boolean,
    val smsConversationThreads: Boolean,
    val smsKeywordRouting: Boolean,
    val smsAiAutoReply: Boolean,
    val smsRcs: Boolean,
)

fun SmsEntitlements.toFeatureMap(): Map<String, Boolean> =
    mapOf(
        "smsEnabled" to smsEnabled,
        "mmsEnabled" to mmsEnabled,
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

/** No SMS add-on — all features disabled. */
val SMS_NO_ADDON_ENTITLEMENTS =
    SmsEntitlements(
        smsEnabled = false,
        mmsEnabled = false,
        smsNumberPooling = false,
        smsShortCodes = false,
        smsAbTesting = false,
        smsSendTimeOptimization = false,
        smsCarrierReporting = false,
        smsCostAnalytics = false,
        smsConversationThreads = false,
        smsKeywordRouting = false,
        smsAiAutoReply = false,
        smsRcs = false,
    )

// ─── SMS Add-On Plans ─────────────────────────────────────────────────────────

data class SignalSmsPlan(
    val slug: String,
    val polarProductId: String,
    val name: String,
    val priceUsdCents: Int,
    val currency: String = "usd",
    val includedSegments: Int,
    /** Overage rate in USD cents per segment (e.g. 1.5 = $0.015). 0 = custom/negotiated. */
    val overageRateCentsPerSegment: Double,
    val entitlements: SmsEntitlements,
)

val signalSmsPlans: List<SignalSmsPlan> =
    listOf(
        SignalSmsPlan(
            slug = "signal-sms-lite",
            polarProductId = "6b39a434-5d32-480a-97c3-5563b3aa338c",
            name = "SMS Lite",
            priceUsdCents = 1000,
            includedSegments = 500,
            overageRateCentsPerSegment = 1.5, // $0.015
            entitlements =
                SmsEntitlements(
                    smsEnabled = true,
                    mmsEnabled = false,
                    smsNumberPooling = false,
                    smsShortCodes = false,
                    smsAbTesting = false,
                    smsSendTimeOptimization = false,
                    smsCarrierReporting = false,
                    smsCostAnalytics = true,
                    smsConversationThreads = false,
                    smsKeywordRouting = false,
                    smsAiAutoReply = false,
                    smsRcs = false,
                ),
        ),
        SignalSmsPlan(
            slug = "signal-sms-starter",
            polarProductId = "d7968bb0-8d29-4633-8a19-f5a32505c2a8",
            name = "SMS Starter",
            priceUsdCents = 2000,
            includedSegments = 1_500,
            overageRateCentsPerSegment = 1.4, // $0.014
            entitlements =
                SmsEntitlements(
                    smsEnabled = true,
                    mmsEnabled = false,
                    smsNumberPooling = false,
                    smsShortCodes = false,
                    smsAbTesting = false,
                    smsSendTimeOptimization = false,
                    smsCarrierReporting = false,
                    smsCostAnalytics = true,
                    smsConversationThreads = true,
                    smsKeywordRouting = true,
                    smsAiAutoReply = false,
                    smsRcs = false,
                ),
        ),
        SignalSmsPlan(
            slug = "signal-sms-growth",
            polarProductId = "7625c966-c478-432f-8251-6ed7c5028d2d",
            name = "SMS Growth",
            priceUsdCents = 4500,
            includedSegments = 4_000,
            overageRateCentsPerSegment = 1.3, // $0.013
            entitlements =
                SmsEntitlements(
                    smsEnabled = true,
                    mmsEnabled = true,
                    smsNumberPooling = false,
                    smsShortCodes = false,
                    smsAbTesting = true,
                    smsSendTimeOptimization = true,
                    smsCarrierReporting = true,
                    smsCostAnalytics = true,
                    smsConversationThreads = true,
                    smsKeywordRouting = true,
                    smsAiAutoReply = false,
                    smsRcs = false,
                ),
        ),
        SignalSmsPlan(
            slug = "signal-sms-business",
            polarProductId = "17da7101-8c70-4c09-b6ea-a1275f66b90a",
            name = "SMS Business",
            priceUsdCents = 9500,
            includedSegments = 9_000,
            overageRateCentsPerSegment = 1.2, // $0.012
            entitlements =
                SmsEntitlements(
                    smsEnabled = true,
                    mmsEnabled = true,
                    smsNumberPooling = true,
                    smsShortCodes = false,
                    smsAbTesting = true,
                    smsSendTimeOptimization = true,
                    smsCarrierReporting = true,
                    smsCostAnalytics = true,
                    smsConversationThreads = true,
                    smsKeywordRouting = true,
                    smsAiAutoReply = true,
                    smsRcs = true,
                ),
        ),
        SignalSmsPlan(
            slug = "signal-sms-scale",
            polarProductId = "ae426dc8-62ad-427f-8ff0-15d30c2cc7c4",
            name = "SMS Scale",
            priceUsdCents = 19500,
            includedSegments = 18_000,
            overageRateCentsPerSegment = 1.1, // $0.011
            entitlements =
                SmsEntitlements(
                    smsEnabled = true,
                    mmsEnabled = true,
                    smsNumberPooling = true,
                    smsShortCodes = true,
                    smsAbTesting = true,
                    smsSendTimeOptimization = true,
                    smsCarrierReporting = true,
                    smsCostAnalytics = true,
                    smsConversationThreads = true,
                    smsKeywordRouting = true,
                    smsAiAutoReply = true,
                    smsRcs = true,
                ),
        ),
        SignalSmsPlan(
            slug = "signal-sms-enterprise",
            polarProductId = "a37a6b09-3910-4c71-96ab-b1e9d33efd3b",
            name = "SMS Enterprise",
            priceUsdCents = 0, // Custom/negotiated
            includedSegments = 0, // Contract-specific
            overageRateCentsPerSegment = 1.0, // $0.010
            entitlements =
                SmsEntitlements(
                    smsEnabled = true,
                    mmsEnabled = true,
                    smsNumberPooling = true,
                    smsShortCodes = true,
                    smsAbTesting = true,
                    smsSendTimeOptimization = true,
                    smsCarrierReporting = true,
                    smsCostAnalytics = true,
                    smsConversationThreads = true,
                    smsKeywordRouting = true,
                    smsAiAutoReply = true,
                    smsRcs = true,
                ),
        ),
    )

fun signalFindSmsPlanByProductId(polarProductId: String): SignalSmsPlan? = signalSmsPlans.find { it.polarProductId.isNotEmpty() && it.polarProductId == polarProductId }

fun signalFindSmsPlanBySlug(slug: String): SignalSmsPlan? = signalSmsPlans.find { it.slug == slug }

// ─── MMS Add-On ───────────────────────────────────────────────────────────────

/** MMS capability add-on — stacks on any SMS plan. Growth+ includes MMS free. */
val signalMmsAddOn =
    SignalCatalogProduct(
        slug = "signal-mms-addon",
        polarProductId = "10048308-dd1c-408b-948f-5a823de1f8a5",
        name = "MMS Enabled",
        price = 8,
        currency = "usd",
        kind = BillingCatalogProductKind.SUBSCRIPTION_ADD_ON,
    )

// ─── Premium SMS Add-Ons ──────────────────────────────────────────────────────

/** Number Pooling add-on (recurring). */
val signalSmsNumberPoolingAddOn =
    SignalCatalogProduct(
        slug = "signal-sms-number-pooling",
        polarProductId = "9e03f0d3-79fb-46e7-b197-a99cdead2e9c",
        name = "Number Pooling",
        price = 15,
        currency = "usd",
        kind = BillingCatalogProductKind.SUBSCRIPTION_ADD_ON,
    )

/** Short Code (Random) add-on (recurring). */
val signalSmsShortCodeRandomAddOn =
    SignalCatalogProduct(
        slug = "signal-sms-short-code-random",
        polarProductId = "ae546687-8df4-470b-ad06-0e7c25befcc0",
        name = "Short Code (Random)",
        price = 1100,
        currency = "usd",
        kind = BillingCatalogProductKind.SUBSCRIPTION_ADD_ON,
    )

/** Short Code (Vanity) add-on (recurring). */
val signalSmsShortCodeVanityAddOn =
    SignalCatalogProduct(
        slug = "signal-sms-short-code-vanity",
        polarProductId = "a0799572-6e68-449a-98a4-94dbc329a12d",
        name = "Short Code (Vanity)",
        price = 1600,
        currency = "usd",
        kind = BillingCatalogProductKind.SUBSCRIPTION_ADD_ON,
    )

/** Short Code Setup fee (one-time). */
val signalSmsShortCodeSetup =
    SignalCatalogProduct(
        slug = "signal-sms-short-code-setup",
        polarProductId = "172306c2-fdc7-4c26-b2f0-d50d8559bfba",
        name = "Short Code Setup",
        price = 650,
        currency = "usd",
        kind = BillingCatalogProductKind.ONE_TIME_PURCHASE,
    )

/** Short Code MMS Setup fee (one-time). */
val signalSmsShortCodeMmsSetup =
    SignalCatalogProduct(
        slug = "signal-sms-short-code-mms-setup",
        polarProductId = "124c85c9-0123-4a95-a40b-00c17f140fbc",
        name = "Short Code MMS Setup",
        price = 500,
        currency = "usd",
        kind = BillingCatalogProductKind.ONE_TIME_PURCHASE,
    )

// ─── SMS Segment Top-Up Packs ─────────────────────────────────────────────────

data class SmsSegmentPack(
    val slug: String,
    val segments: Int,
    val priceUsdCents: Int,
    val polarProductId: String = "",
)

val signalSmsSegmentPacks: List<SmsSegmentPack> =
    listOf(
        SmsSegmentPack(
            slug = "signal-sms-segments-1k",
            segments = 1_000,
            priceUsdCents = 1300,
            polarProductId = "bf0c5e22-5cf7-48b5-8bb8-32b04b5e5cae",
        ),
        SmsSegmentPack(
            slug = "signal-sms-segments-5k",
            segments = 5_000,
            priceUsdCents = 6000,
            polarProductId = "25f39a76-cd9a-4822-9b82-a2656562b470",
        ),
        SmsSegmentPack(
            slug = "signal-sms-segments-25k",
            segments = 25_000,
            priceUsdCents = 27500,
            polarProductId = "c6bab06a-b103-4a52-9219-ca2e6d500e44",
        ),
        SmsSegmentPack(
            slug = "signal-sms-segments-100k",
            segments = 100_000,
            priceUsdCents = 100000,
            polarProductId = "abbdd1db-57d2-4f89-af78-c2eb2af7c0e4",
        ),
    )

fun findSmsSegmentPackBySlug(slug: String): SmsSegmentPack? = signalSmsSegmentPacks.find { it.slug == slug }

fun findSmsSegmentPackByProductId(polarProductId: String): SmsSegmentPack? = signalSmsSegmentPacks.find { it.polarProductId.isNotEmpty() && it.polarProductId == polarProductId }

// ─── Aggregate catalog products for SMS ───────────────────────────────────────

/** All SMS add-on subscription products (the 6 tiered plans). */
val signalSmsSubscriptionAddOns: List<SignalCatalogProduct> =
    signalSmsPlans.map { plan ->
        SignalCatalogProduct(
            slug = plan.slug,
            polarProductId = plan.polarProductId,
            name = plan.name,
            price = plan.priceUsdCents / 100,
            currency = plan.currency,
            kind = BillingCatalogProductKind.SUBSCRIPTION_ADD_ON,
        )
    }

/** All SMS recurring premium add-ons (MMS, number pooling, short codes). */
val signalSmsPremiumRecurringAddOns: List<SignalCatalogProduct> =
    listOf(
        signalMmsAddOn,
        signalSmsNumberPoolingAddOn,
        signalSmsShortCodeRandomAddOn,
        signalSmsShortCodeVanityAddOn,
    )

/** All SMS one-time products (setup fees + segment packs). */
val signalSmsOneTimeProducts: List<SignalCatalogProduct> =
    listOf(signalSmsShortCodeSetup, signalSmsShortCodeMmsSetup) +
        signalSmsSegmentPacks.map { pack ->
            SignalCatalogProduct(
                slug = pack.slug,
                polarProductId = pack.polarProductId,
                name = "SMS Segments ${pack.segments / 1000}k",
                price = pack.priceUsdCents / 100,
                currency = "usd",
                kind = BillingCatalogProductKind.CREDIT_PACK,
            )
        }

/** All SMS-related catalog products (for inclusion in signalCatalogProducts). */
val signalSmsCatalogProducts: List<SignalCatalogProduct> =
    signalSmsSubscriptionAddOns + signalSmsPremiumRecurringAddOns + signalSmsOneTimeProducts

// ─── Plan gating helpers ──────────────────────────────────────────────────────

/** Plans that do NOT support SMS add-ons (free tier only). */
private val SMS_INELIGIBLE_BASE_PLANS = setOf("signal-spark")

/** Returns true if the base plan supports subscribing to an SMS add-on. */
fun isSmsSupportedForBasePlan(basePlanSlug: String): Boolean = basePlanSlug !in SMS_INELIGIBLE_BASE_PLANS

/** The SMS plans that include MMS natively (Growth+). MMS add-on not needed. */
private val SMS_PLANS_WITH_INCLUDED_MMS =
    setOf(
        "signal-sms-growth",
        "signal-sms-business",
        "signal-sms-scale",
        "signal-sms-enterprise",
    )

/** Returns true if the SMS plan includes MMS without needing the separate MMS add-on. */
fun isSmsPlanIncludesMms(smsPlanSlug: String): Boolean = smsPlanSlug in SMS_PLANS_WITH_INCLUDED_MMS

// ─── SMS Error Codes ──────────────────────────────────────────────────────────

object SmsErrorCodes {
    /** Org is on Spark (free) — SMS requires paid base plan. */
    const val PLAN_REQUIRED = "sms.plan_required"

    /** No active SMS add-on subscription. */
    const val ADDON_REQUIRED = "sms.addon_required"

    /** MMS attempted without MMS add-on (and SMS plan < Growth). */
    const val MMS_ADDON_REQUIRED = "sms.mms_addon_required"

    /** Monthly segment meter depleted and overage not enabled. */
    const val SEGMENTS_EXHAUSTED = "sms.segments_exhausted"

    /** Feature requires a higher SMS tier. */
    const val FEATURE_NOT_AVAILABLE = "sms.feature_not_available"
}

// ─── Event Keys ───────────────────────────────────────────────────────────────

object SmsEventKeys {
    /** SMS segment delivered to carrier. */
    const val SEGMENT_SENT = "signal.sms.segment_sent"

    /** MMS message delivered. */
    const val MMS_MESSAGE_SENT = "signal.mms.message_sent"

    /** Active sending number (monthly reconciliation). */
    const val NUMBER_ACTIVE = "signal.sms.number_active"
}
