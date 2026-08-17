package com.apollodeploy.billing.feature.signal.domain

import com.apollodeploy.billing.core.UNLIMITED_SENTINEL
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.forAll
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignalPlanCatalogTest {
    // ─── 7.1 signalFindPlanByProductId returns correct plan for each of the 6 plans ─────────

    @Test
    fun `signalFindPlanByProductId returns correct plan for each of the 6 plans`() {
        for (plan in signalPlans) {
            val result = signalFindPlanByProductId(plan.polarProductId)
            assertEquals(
                plan.slug,
                result?.slug,
                "Expected slug ${plan.slug} for product ID ${plan.polarProductId}",
            )
        }
    }

    // ─── 7.2 signalFindPlanByProductId with unknown ID returns null ──────────────────────────

    @Test
    fun `signalFindPlanByProductId with unknown ID returns null`() {
        assertNull(signalFindPlanByProductId("non-existent-id"))
    }

    // ─── 7.3 signalFindPlanByProductId with empty string returns null ────────────────────────

    @Test
    fun `signalFindPlanByProductId with empty string returns null`() {
        assertNull(signalFindPlanByProductId(""))
    }

    // ─── 7.4 signalGetFreePlan returns signal-spark with price 0 ─────────────────────────────

    @Test
    fun `signalGetFreePlan returns signal-spark with price 0`() {
        val freePlan = signalGetFreePlan()
        assertEquals("signal-spark", freePlan.slug)
        assertEquals(0, freePlan.price)
    }

    // ─── 7.5 findAiCreditTopupPackBySlug with "signal-ai-credits-100" returns credits=100 ────

    @Test
    fun `findAiCreditTopupPackBySlug with signal-ai-credits-100 returns pack with credits 100`() {
        val pack = findAiCreditTopupPackBySlug("signal-ai-credits-100")
        assertNotNull(pack)
        assertEquals(100, pack.credits)
    }

    // ─── 7.6 findAiCreditTopupPackBySlug with unknown slug returns null ──────────────────────

    @Test
    fun `findAiCreditTopupPackBySlug with unknown slug returns null`() {
        assertNull(findAiCreditTopupPackBySlug("unknown-pack"))
    }

    // ─── 7.7 findAiCreditTopupPackByProductId with 500-credit pack ID returns credits=500 ────

    @Test
    fun `findAiCreditTopupPackByProductId with 500-credit pack ID returns credits 500`() {
        val pack500 = signalAiCreditTopupPacks.first { it.credits == 500 }
        val result = findAiCreditTopupPackByProductId(pack500.polarProductId)
        assertNotNull(result)
        assertEquals(500, result.credits)
    }

    // ─── 7.8 findAiCreditTopupPackByProductId with empty string returns null ────────────────

    @Test
    fun `findAiCreditTopupPackByProductId with empty string returns null`() {
        assertNull(findAiCreditTopupPackByProductId(""))
    }

    // ─── 7.9 isMultiRegionAllowedForPlan — false for spark/ignite/growth, true for pulse/scale/enterprise ──

    @Test
    fun `isMultiRegionAllowedForPlan returns false for spark ignite growth`() {
        assertFalse(isMultiRegionAllowedForPlan("signal-spark"))
        assertFalse(isMultiRegionAllowedForPlan("signal-ignite"))
        assertFalse(isMultiRegionAllowedForPlan("signal-growth"))
    }

    @Test
    fun `isMultiRegionAllowedForPlan returns true for pulse scale enterprise`() {
        assertTrue(isMultiRegionAllowedForPlan("signal-pulse"))
        assertTrue(isMultiRegionAllowedForPlan("signal-scale"))
        assertTrue(isMultiRegionAllowedForPlan("signal-enterprise"))
    }

    // ─── 7.10 isDedicatedIpEligibleForPlan — false for spark, true for ignite/growth/enterprise ──

    @Test
    fun `isDedicatedIpEligibleForPlan returns false for spark`() {
        assertFalse(isDedicatedIpEligibleForPlan("signal-spark"))
    }

    @Test
    fun `isDedicatedIpEligibleForPlan returns true for ignite growth enterprise`() {
        assertTrue(isDedicatedIpEligibleForPlan("signal-ignite"))
        assertTrue(isDedicatedIpEligibleForPlan("signal-growth"))
        assertTrue(isDedicatedIpEligibleForPlan("signal-enterprise"))
    }

    // ─── 7.11 signalPlans contains exactly 6 plans ───────────────────────────────────────────

    @Test
    fun `signalPlans contains exactly 6 plans`() {
        assertEquals(6, signalPlans.size)
    }

    // ─── 7.12 all plan slugs are distinct ────────────────────────────────────────────────────

    @Test
    fun `all plan slugs are distinct`() {
        assertEquals(6, signalPlans.map { it.slug }.toSet().size)
    }

    // ─── 7.13 all polarProductId values are distinct ─────────────────────────────────────────

    @Test
    fun `all polarProductId values are distinct`() {
        assertEquals(6, signalPlans.map { it.polarProductId }.toSet().size)
    }

    // ─── 7.14 all slugs start with "signal-" and are non-blank ──────────────────────────────

    @Test
    fun `all slugs start with signal- and are non-blank`() {
        signalPlans.forEach { plan ->
            assertTrue(plan.slug.isNotBlank(), "Slug should not be blank")
            assertTrue(plan.slug.startsWith("signal-"), "Slug '${plan.slug}' should start with 'signal-'")
        }
    }

    // ─── 7.15 signal-spark price is 0 ────────────────────────────────────────────────────────

    @Test
    fun `signal-spark price is 0`() {
        val spark = signalPlans.first { it.slug == "signal-spark" }
        assertEquals(0, spark.price)
    }

    // ─── 7.16 signal-enterprise has UNLIMITED_SENTINEL for maxProjects, maxDomains, monthlySends, aiCredits ──

    @Test
    fun `signal-enterprise has UNLIMITED_SENTINEL for maxProjects maxDomains monthlySends aiCredits`() {
        val enterprise = signalPlans.first { it.slug == "signal-enterprise" }
        assertEquals(UNLIMITED_SENTINEL, enterprise.entitlements.maxProjects)
        assertEquals(UNLIMITED_SENTINEL, enterprise.entitlements.maxDomains)
        assertEquals(UNLIMITED_SENTINEL, enterprise.entitlements.monthlySends)
        assertEquals(UNLIMITED_SENTINEL, enterprise.entitlements.aiCredits)
    }

    // ─── 7.17 paid plans in ascending price order have non-decreasing monthlySends ────────────

    @Test
    fun `paid plans in ascending price order have non-decreasing monthlySends`() {
        val paidPlans =
            signalPlans
                .filter { it.price > 0 }
                .sortedBy { it.price }
        for (i in 1 until paidPlans.size) {
            assertTrue(
                paidPlans[i].entitlements.monthlySends >= paidPlans[i - 1].entitlements.monthlySends,
                "monthlySends should be non-decreasing: " +
                    "${paidPlans[i - 1].slug}(${paidPlans[i - 1].entitlements.monthlySends}) -> " +
                    "${paidPlans[i].slug}(${paidPlans[i].entitlements.monthlySends})",
            )
        }
    }

    // ─── 7.18 all plans have dataRetentionDays > 0 ────────────────────────────────────────────

    @Test
    fun `all plans have dataRetentionDays greater than 0`() {
        signalPlans.forEach { plan ->
            assertTrue(plan.entitlements.dataRetentionDays > 0, "${plan.slug} should have dataRetentionDays > 0")
        }
    }

    // ─── 7.19 tracking availability ───────────────────────────────────────────────

    @Test
    fun `all signal plans include custom tracking domains`() {
        assertTrue(signalPlans.all { it.entitlements.customTrackingDomain })
    }

    @Test
    fun `signal-spark excludes read engagement`() {
        val spark = signalPlans.first { it.slug == "signal-spark" }
        assertFalse(spark.entitlements.readEngagement)
    }

    // ─── 7.20 AI credit pack priceUsdCents is > 0 for all three packs ────────────────────────

    @Test
    fun `AI credit pack priceUsdCents is greater than 0 for all three packs`() {
        signalAiCreditTopupPacks.forEach { pack ->
            assertTrue(pack.priceUsdCents > 0, "${pack.slug} priceUsdCents should be > 0")
        }
    }

    // ─── 7.21 500-credit pack price is approximately 5× the 100-credit pack (within ±5 cents) ─

    @Test
    fun `500-credit pack price is approximately 5 times the 100-credit pack within 5 cents`() {
        val pack100 = signalAiCreditTopupPacks.first { it.credits == 100 }
        val pack500 = signalAiCreditTopupPacks.first { it.credits == 500 }
        assertTrue(
            abs(pack500.priceUsdCents - 5 * pack100.priceUsdCents) <= 5,
            "500-credit pack price (${pack500.priceUsdCents}) should be ~5x 100-credit pack (${pack100.priceUsdCents})",
        )
    }

    // ─── 7.22 1000-credit pack price is 10× the 100-credit pack within ±10 cents ─────────────

    @Test
    fun `1000-credit pack price is approximately 10 times the 100-credit pack within 10 cents`() {
        val pack100 = signalAiCreditTopupPacks.first { it.credits == 100 }
        val pack1000 = signalAiCreditTopupPacks.first { it.credits == 1_000 }
        assertTrue(
            abs(pack1000.priceUsdCents - 10 * pack100.priceUsdCents) <= 10,
            "1000-credit pack price (${pack1000.priceUsdCents}) should be ~10x 100-credit pack (${pack100.priceUsdCents})",
        )
    }

    // ─── 7.23 AI_CREDIT_DEEPSEEK_REFERENCE_COST_USD equals expected arithmetic value ──────────

    @Test
    fun `AI_CREDIT_DEEPSEEK_REFERENCE_COST_USD equals expected arithmetic value`() {
        val expected = (5_000 / 1_000_000.0) * 0.435 + (3_000 / 1_000_000.0) * 0.87
        assertEquals(
            expected,
            AI_CREDIT_DEEPSEEK_REFERENCE_COST_USD,
            1e-10,
        )
    }

    // ─── 7.24 AI_CREDIT_RETAIL_PRICE_USD equals ceil(referenceCost × 5.0 × 100) / 100.0 ──────

    @Test
    fun `AI_CREDIT_RETAIL_PRICE_USD equals ceil of referenceCost times 5 times 100 divided by 100`() {
        val expected = ceil(AI_CREDIT_DEEPSEEK_REFERENCE_COST_USD * 5.0 * 100) / 100.0
        assertEquals(
            expected,
            AI_CREDIT_RETAIL_PRICE_USD,
            1e-10,
        )
    }

    // ─── 7.25 Property 1 — plan lookup by product ID round-trips for all 6 plans ──────────────

    // Feature: billing-comprehensive-unit-tests, Property 1: Plan lookup by product ID round-trips
    @Test
    fun `Property 1 - plan lookup by product ID round-trips for all 6 plans`() =
        runBlocking {
            // **Property 1: Plan lookup by product ID round-trips**
            // **Validates: Requirements 19.1**
            forAll(Arb.element(signalPlans)) { plan ->
                signalFindPlanByProductId(plan.polarProductId) == plan
            }
        }

    // ─── 7.26 Property 2 — AI credit pack slug lookup round-trips ─────────────────────────────

    // Feature: billing-comprehensive-unit-tests, Property 2: AI credit pack lookup by slug round-trips
    @Test
    fun `Property 2 - AI credit pack slug lookup round-trips`() =
        runBlocking {
            // **Property 2: AI credit pack lookup by slug round-trips**
            // **Validates: Requirements 19.2, 9.9**
            forAll(Arb.element(signalAiCreditTopupPacks)) { pack ->
                findAiCreditTopupPackBySlug(pack.slug) == pack
            }
        }

    // ─── 7.27 Property 3 — AI credit pack product ID lookup round-trips ───────────────────────

    // Feature: billing-comprehensive-unit-tests, Property 3: AI credit pack lookup by product ID round-trips
    @Test
    fun `Property 3 - AI credit pack product ID lookup round-trips`() =
        runBlocking {
            // **Property 3: AI credit pack lookup by product ID round-trips**
            // **Validates: Requirements 19.3, 9.10**
            forAll(Arb.element(signalAiCreditTopupPacks)) { pack ->
                findAiCreditTopupPackByProductId(pack.polarProductId) == pack
            }
        }

    // ─── 7.28 Property 4 — isMultiRegionAllowedForPlan agrees with plan entitlements ──────────

    // Feature: billing-comprehensive-unit-tests, Property 4: Multi-region predicate is consistent with plan catalog data
    @Test
    fun `Property 4 - isMultiRegionAllowedForPlan agrees with plan entitlements`() =
        runBlocking {
            // **Property 4: Multi-region predicate is consistent with plan catalog data**
            // **Validates: Requirements 19.4, 10.11**
            forAll(Arb.element(signalPlans)) { plan ->
                isMultiRegionAllowedForPlan(plan.slug) == plan.entitlements.multiRegion
            }
        }

    // ─── 7.29 Property 5 — isDedicatedIpEligibleForPlan agrees with plan entitlements ─────────

    // Feature: billing-comprehensive-unit-tests, Property 5: Dedicated IP predicate is consistent with plan catalog data
    @Test
    fun `Property 5 - isDedicatedIpEligibleForPlan agrees with plan entitlements`() =
        runBlocking {
            // **Property 5: Dedicated IP predicate is consistent with plan catalog data**
            // **Validates: Requirements 19.5, 10.12**
            forAll(Arb.element(signalPlans)) { plan ->
                isDedicatedIpEligibleForPlan(plan.slug) == plan.entitlements.dedicatedIps
            }
        }
}
