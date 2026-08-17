package com.apollodeploy.billing.feature.signal.domain

import com.apollodeploy.billing.core.BillingCatalogProductKind
import com.apollodeploy.billing.core.UNLIMITED_SENTINEL

/**
 * Apollo Billing — Signal plan catalog.
 * Ported from apollo-signal-api core/billing/PlanCatalog.kt.
 */

data class PlanEntitlements(
    val maxProjects: Int,
    val maxDomains: Int,
    val maxWebhooks: Int,
    val maxApiKeys: Int,
    val dailySends: Int,
    val monthlySends: Int,
    val aiCredits: Int,
    val dataRetentionDays: Int,
    val customTrackingDomain: Boolean,
    val advancedWebhooks: Boolean,
    val signedWebhooks: Boolean,
    val readEngagement: Boolean,
    val enrichedTracking: Boolean,
    val forwardingDetection: Boolean,
    val deliverabilityAdvisor: Boolean,
    val realtimeStream: Boolean,
    val sendTimeOptimisation: Boolean,
    val dedicatedIps: Boolean,
    val multiRegion: Boolean,
    val inboundReceiving: Boolean = false,
)

data class SignalPlan(
    val slug: String,
    val polarProductId: String,
    val name: String,
    val price: Int,
    val currency: String,
    val entitlements: PlanEntitlements,
)

data class SignalCatalogProduct(
    val slug: String,
    val polarProductId: String,
    val name: String,
    val price: Int,
    val currency: String,
    val kind: BillingCatalogProductKind,
)

private val PAID_FEATURES =
    PlanEntitlements(
        maxProjects = 0,
        maxDomains = 0,
        maxWebhooks = 0,
        maxApiKeys = 0,
        dailySends = 0,
        monthlySends = 0,
        aiCredits = 0,
        dataRetentionDays = 0,
        customTrackingDomain = true,
        advancedWebhooks = true,
        signedWebhooks = true,
        readEngagement = true,
        enrichedTracking = true,
        forwardingDetection = true,
        deliverabilityAdvisor = true,
        realtimeStream = true,
        sendTimeOptimisation = true,
        dedicatedIps = false,
        multiRegion = false,
        inboundReceiving = true,
    )

val signalPlans: List<SignalPlan> =
    listOf(
        SignalPlan(
            slug = "signal-spark",
            polarProductId = "5c1a055d-ca8d-4aba-a86a-e26dd07ee169",
            name = "Spark",
            price = 0,
            currency = "usd",
            entitlements =
                PlanEntitlements(
                    maxProjects = 1,
                    maxDomains = 1,
                    maxWebhooks = 1,
                    maxApiKeys = 3,
                    dailySends = 100,
                    monthlySends = 3_000,
                    aiCredits = 5,
                    dataRetentionDays = 30,
                    customTrackingDomain = true,
                    advancedWebhooks = true,
                    signedWebhooks = true,
                    readEngagement = false,
                    enrichedTracking = false,
                    forwardingDetection = false,
                    deliverabilityAdvisor = false,
                    realtimeStream = false,
                    sendTimeOptimisation = false,
                    dedicatedIps = false,
                    multiRegion = false,
                    inboundReceiving = false,
                ),
        ),
        SignalPlan(
            slug = "signal-ignite",
            polarProductId = "d0428f94-479b-4d7c-82df-0c103fdbcd5a",
            name = "Ignite",
            price = 15,
            currency = "usd",
            entitlements =
                PAID_FEATURES.copy(
                    maxProjects = 5,
                    maxDomains = 10,
                    maxWebhooks = 10,
                    maxApiKeys = 10,
                    dailySends = UNLIMITED_SENTINEL,
                    monthlySends = 50_000,
                    aiCredits = 20,
                    dataRetentionDays = 90,
                ),
        ),
        SignalPlan(
            slug = "signal-growth",
            polarProductId = "8e7ddadc-fefa-4be9-a114-b3645887cd94",
            name = "Growth",
            price = 35,
            currency = "usd",
            entitlements =
                PAID_FEATURES.copy(
                    maxProjects = 10,
                    maxDomains = 25,
                    maxWebhooks = 25,
                    maxApiKeys = 25,
                    dailySends = UNLIMITED_SENTINEL,
                    monthlySends = 150_000,
                    aiCredits = 50,
                    dataRetentionDays = 90,
                ),
        ),
        SignalPlan(
            slug = "signal-pulse",
            polarProductId = "4985c797-eeb1-4dbc-ab1f-3f84085bf17d",
            name = "Pulse",
            price = 65,
            currency = "usd",
            entitlements =
                PAID_FEATURES.copy(
                    maxProjects = 25,
                    maxDomains = 100,
                    maxWebhooks = 50,
                    maxApiKeys = 50,
                    dailySends = UNLIMITED_SENTINEL,
                    monthlySends = 300_000,
                    aiCredits = 100,
                    dataRetentionDays = 90,
                    multiRegion = true,
                ),
        ),
        SignalPlan(
            slug = "signal-scale",
            polarProductId = "acc82e9b-b3b2-44dc-8d5f-fe557495431e",
            name = "Scale",
            price = 180,
            currency = "usd",
            entitlements =
                PAID_FEATURES.copy(
                    maxProjects = 100,
                    maxDomains = 250,
                    maxWebhooks = 100,
                    maxApiKeys = 100,
                    dailySends = UNLIMITED_SENTINEL,
                    monthlySends = 1_000_000,
                    aiCredits = 250,
                    dataRetentionDays = 180,
                    multiRegion = true,
                ),
        ),
        SignalPlan(
            slug = "signal-enterprise",
            polarProductId = "97594229-5d62-4792-87c1-2db3edcb2870",
            name = "Enterprise",
            price = 0,
            currency = "usd",
            entitlements =
                PlanEntitlements(
                    maxProjects = UNLIMITED_SENTINEL,
                    maxDomains = UNLIMITED_SENTINEL,
                    maxWebhooks = UNLIMITED_SENTINEL,
                    maxApiKeys = UNLIMITED_SENTINEL,
                    dailySends = UNLIMITED_SENTINEL,
                    monthlySends = UNLIMITED_SENTINEL,
                    aiCredits = UNLIMITED_SENTINEL,
                    dataRetentionDays = 365,
                    customTrackingDomain = true,
                    advancedWebhooks = true,
                    signedWebhooks = true,
                    readEngagement = true,
                    enrichedTracking = true,
                    forwardingDetection = true,
                    deliverabilityAdvisor = true,
                    realtimeStream = true,
                    sendTimeOptimisation = true,
                    dedicatedIps = true,
                    multiRegion = true,
                    inboundReceiving = true,
                ),
        ),
    )

fun signalFindPlanByProductId(polarProductId: String): SignalPlan? = signalPlans.find { it.polarProductId.isNotEmpty() && it.polarProductId == polarProductId }

fun signalGetFreePlan(): SignalPlan = signalPlans.first()

private val MULTI_REGION_DISABLED_PLANS =
    setOf(
        "signal-spark",
        "signal-ignite",
        "signal-growth",
    )

private val DEDICATED_IP_INELIGIBLE_PLANS = setOf("signal-spark", "free")

fun isMultiRegionAllowedForPlan(planSlug: String): Boolean = planSlug !in MULTI_REGION_DISABLED_PLANS

fun isDedicatedIpEligibleForPlan(planSlug: String): Boolean = planSlug !in DEDICATED_IP_INELIGIBLE_PLANS

// ─── Polar meters and catalog products ────────────────────────────────────────

const val SIGNAL_EMAIL_METER_ID = "7925de58-f234-46b2-bc05-97dabd8894a7"
const val SIGNAL_AUTOMATION_RUN_METER_ID = "b28806bb-56e0-4c95-8f78-433140388632"
const val SIGNAL_AI_CREDIT_METER_ID = "73419326-4df0-454f-b734-0c9eef1c492f"

/** Polar usage event name for accepted inbound email. */
const val SIGNAL_EMAIL_RECEIVED_EVENT_KEY = "signal.email.received"

const val DEDICATED_IP_ADDON_PRICE_USD = 30

// ─── AI credit pricing ───────────────────────────────────────────────────────

private const val AI_CREDIT_DEEPSEEK_INPUT_USD_PER_M = 0.435
private const val AI_CREDIT_DEEPSEEK_OUTPUT_USD_PER_M = 0.87
private const val AI_CREDIT_REFERENCE_INPUT_TOKENS = 5_000
private const val AI_CREDIT_REFERENCE_OUTPUT_TOKENS = 3_000

val AI_CREDIT_DEEPSEEK_REFERENCE_COST_USD: Double =
    (AI_CREDIT_REFERENCE_INPUT_TOKENS / 1_000_000.0) * AI_CREDIT_DEEPSEEK_INPUT_USD_PER_M +
        (AI_CREDIT_REFERENCE_OUTPUT_TOKENS / 1_000_000.0) * AI_CREDIT_DEEPSEEK_OUTPUT_USD_PER_M

const val AI_CREDIT_MARKUP_RATE = 4.0

val AI_CREDIT_RETAIL_PRICE_USD: Double =
    kotlin.math.ceil(AI_CREDIT_DEEPSEEK_REFERENCE_COST_USD * (1.0 + AI_CREDIT_MARKUP_RATE) * 100) / 100.0

data class AiCreditTopupPack(
    val slug: String,
    val credits: Int,
    val priceUsdCents: Int,
    val polarProductId: String = "",
)

private fun aiCreditPackPriceCents(credits: Int): Int = kotlin.math.round(AI_CREDIT_RETAIL_PRICE_USD * credits * 100).toInt()

val signalAiCreditTopupPacks: List<AiCreditTopupPack> =
    listOf(
        AiCreditTopupPack(
            slug = "signal-ai-credits-100",
            credits = 100,
            priceUsdCents = aiCreditPackPriceCents(100),
            polarProductId = "34ffa465-6426-4fdd-a3af-7cc748f14306",
        ),
        AiCreditTopupPack(
            slug = "signal-ai-credits-500",
            credits = 500,
            priceUsdCents = aiCreditPackPriceCents(500),
            polarProductId = "78921976-e01c-48b2-baf1-02f2fa18ff35",
        ),
        AiCreditTopupPack(
            slug = "signal-ai-credits-1000",
            credits = 1_000,
            priceUsdCents = aiCreditPackPriceCents(1_000),
            polarProductId = "ea0bf43d-7984-4d86-9526-7867e8557dc6",
        ),
    )

fun findAiCreditTopupPackBySlug(slug: String): AiCreditTopupPack? = signalAiCreditTopupPacks.find { it.slug == slug }

fun findAiCreditTopupPackByProductId(polarProductId: String): AiCreditTopupPack? = signalAiCreditTopupPacks.find { it.polarProductId.isNotEmpty() && it.polarProductId == polarProductId }

/**
 * Monthly subscription add-on attached alongside a customer's base Signal plan.
 */
val signalDedicatedIpAddOn =
    SignalCatalogProduct(
        slug = "signal-dedicated-ip-addon",
        polarProductId = "bd3abb23-826f-4559-a126-58ab27c905ad",
        name = "Dedicated IP",
        price = DEDICATED_IP_ADDON_PRICE_USD,
        currency = "usd",
        kind = BillingCatalogProductKind.SUBSCRIPTION_ADD_ON,
    )

/**
 * Usage-based subscription product for emails without committing to a paid plan.
 */
val signalEmailPayg =
    SignalCatalogProduct(
        slug = "signal-email-payg",
        polarProductId = "65bfe1fb-3a49-4914-8652-ec9722b780d2",
        name = "Email PAYG",
        price = 0,
        currency = "usd",
        kind = BillingCatalogProductKind.USAGE_BASED_SUBSCRIPTION,
    )

/**
 * Usage-based subscription product for automation runs beyond prepaid packs.
 */
val signalAutomationPayg =
    SignalCatalogProduct(
        slug = "signal-automation-payg",
        polarProductId = "b2e51ede-9970-453a-b587-89e571ff99c3",
        name = "Automation PAYG",
        price = 0,
        currency = "usd",
        kind = BillingCatalogProductKind.USAGE_BASED_SUBSCRIPTION,
    )

/**
 * One-time Polar products that grant automation-run credits through Polar benefits.
 */
val signalAutomationRunPacks: List<SignalCatalogProduct> =
    listOf(
        SignalCatalogProduct(
            slug = "signal-automation-small-pack",
            polarProductId = "02b8a108-37b6-463e-b446-ed09f8f24547",
            name = "Automation Small Pack",
            price = 10,
            currency = "usd",
            kind = BillingCatalogProductKind.CREDIT_PACK,
        ),
        SignalCatalogProduct(
            slug = "signal-automation-medium-pack",
            polarProductId = "c9e4bbb4-c591-4b5c-a1e4-28a6b1e67e25",
            name = "Automation Medium Pack",
            price = 30,
            currency = "usd",
            kind = BillingCatalogProductKind.CREDIT_PACK,
        ),
        SignalCatalogProduct(
            slug = "signal-automation-growth-pack",
            polarProductId = "4d861b42-de0f-445a-b037-8c0e0a76e69f",
            name = "Automation Growth Pack",
            price = 55,
            currency = "usd",
            kind = BillingCatalogProductKind.CREDIT_PACK,
        ),
        SignalCatalogProduct(
            slug = "signal-automation-scale-pack",
            polarProductId = "6e86fd52-c75a-41ef-aefc-69214d1f21e4",
            name = "Automation Scale Pack",
            price = 200,
            currency = "usd",
            kind = BillingCatalogProductKind.CREDIT_PACK,
        ),
    )

/**
 * One-time Polar products that grant AI credits through Polar benefits.
 */
val signalAiCreditPacks: List<SignalCatalogProduct> =
    signalAiCreditTopupPacks.map { pack ->
        SignalCatalogProduct(
            slug = pack.slug,
            polarProductId = pack.polarProductId,
            name = "AI Credits ${pack.credits}",
            price = pack.priceUsdCents / 100,
            currency = "usd",
            kind = BillingCatalogProductKind.CREDIT_PACK,
        )
    }

val signalSubscriptionAddOns: List<SignalCatalogProduct> = listOf(signalDedicatedIpAddOn)

val signalUsageBasedSubscriptions: List<SignalCatalogProduct> =
    listOf(
        signalEmailPayg,
        signalAutomationPayg,
    )

val signalOneTimeProducts: List<SignalCatalogProduct> = signalAutomationRunPacks + signalAiCreditPacks

val signalCatalogProducts: List<SignalCatalogProduct> =
    signalSubscriptionAddOns + signalUsageBasedSubscriptions + signalOneTimeProducts
