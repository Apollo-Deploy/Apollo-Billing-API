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
            polarProductId = "1d87f163-7a19-46ef-bcc9-fcdecc590328",
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
            polarProductId = "e2e08cb7-d6c6-4714-80ee-90d06f2f947f",
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
            polarProductId = "d35f8fee-f8d8-40ef-b59c-f15c8302eef2",
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
            polarProductId = "a68ea3fe-920d-4761-a974-12dd02b80be1",
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
            polarProductId = "fab7157b-b78d-4964-b842-6f3c56b890b9",
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
            polarProductId = "b9f30f10-8571-40c8-a313-d9b7e9a32668",
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

const val SIGNAL_EMAIL_METER_ID = "1a739828-a8e6-44a5-9f30-1b465d7f6e17"
const val SIGNAL_AUTOMATION_RUN_METER_ID = "77d9835c-2210-4698-a9b8-a6b2375765c5"
const val SIGNAL_AI_CREDIT_METER_ID = "cf687228-6399-46bd-9a5e-339ae167c965"

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
            polarProductId = "8ebded7d-2d39-4e8a-ac55-7c510cd548e3",
        ),
        AiCreditTopupPack(
            slug = "signal-ai-credits-500",
            credits = 500,
            priceUsdCents = aiCreditPackPriceCents(500),
            polarProductId = "8f1b5d77-4f3e-4451-aaf4-e7072ab3ea76",
        ),
        AiCreditTopupPack(
            slug = "signal-ai-credits-1000",
            credits = 1_000,
            priceUsdCents = aiCreditPackPriceCents(1_000),
            polarProductId = "7177f687-ecd1-4342-8e0e-2bc1df790575",
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
        polarProductId = "a6b2a5b0-3dcb-4c84-a546-4b77b118e42b",
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
        polarProductId = "90b85fb4-3ef4-4107-acb0-07853b602811",
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
        polarProductId = "63401623-8f42-4d18-9165-3f8dcb522af6",
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
            polarProductId = "fb5e018e-1647-4de1-8de4-3684cab4fecf",
            name = "Automation Small Pack",
            price = 10,
            currency = "usd",
            kind = BillingCatalogProductKind.CREDIT_PACK,
        ),
        SignalCatalogProduct(
            slug = "signal-automation-medium-pack",
            polarProductId = "e2924b7f-d52b-4eeb-aa4e-010f0303f723",
            name = "Automation Medium Pack",
            price = 30,
            currency = "usd",
            kind = BillingCatalogProductKind.CREDIT_PACK,
        ),
        SignalCatalogProduct(
            slug = "signal-automation-growth-pack",
            polarProductId = "1f3349f5-6f76-42df-ac22-1b5f6a52b840",
            name = "Automation Growth Pack",
            price = 55,
            currency = "usd",
            kind = BillingCatalogProductKind.CREDIT_PACK,
        ),
        SignalCatalogProduct(
            slug = "signal-automation-scale-pack",
            polarProductId = "7b6908e0-96d9-41c3-a5ee-5cf9313819c5",
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
