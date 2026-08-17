package com.apollodeploy.billing.feature.signal.application

import com.apollodeploy.billing.core.UNLIMITED_SENTINEL
import com.apollodeploy.billing.feature.signal.domain.PlanEntitlements
import com.apollodeploy.billing.feature.signal.domain.signalPlans
import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.forAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SignalBillingConfigTest {
    // A baseline PlanEntitlements with all int fields = 1 and dataRetentionDays = 30,
    // used as a starting point for limit-exclusion tests.
    private val baseEntitlements =
        PlanEntitlements(
            maxProjects = 1,
            maxDomains = 1,
            maxWebhooks = 1,
            maxApiKeys = 1,
            dailySends = 1,
            monthlySends = 1,
            aiCredits = 1,
            dataRetentionDays = 30,
            customTrackingDomain = false,
            advancedWebhooks = false,
            signedWebhooks = false,
            readEngagement = false,
            enrichedTracking = false,
            forwardingDetection = false,
            deliverabilityAdvisor = false,
            realtimeStream = false,
            sendTimeOptimisation = false,
            dedicatedIps = false,
            multiRegion = false,
        )

    // ─── 8.1 ─────────────────────────────────────────────────────────────────

    @Test
    fun `maxProjects = 0 is excluded from limits`() {
        val entitlements = baseEntitlements.copy(maxProjects = 0)
        val config = entitlements.toPlanFeatureConfig()

        assertFalse("maxProjects" in config.limits, "maxProjects should be excluded when value is 0")
    }

    // ─── 8.2 ─────────────────────────────────────────────────────────────────

    @Test
    fun `maxProjects = 5 is included in limits with value 5`() {
        val entitlements = baseEntitlements.copy(maxProjects = 5)
        val config = entitlements.toPlanFeatureConfig()

        assertEquals(5, config.limits["maxProjects"])
    }

    // ─── 8.3 ─────────────────────────────────────────────────────────────────

    @Test
    fun `maxProjects = UNLIMITED_SENTINEL is included in limits with value -1`() {
        val entitlements = baseEntitlements.copy(maxProjects = UNLIMITED_SENTINEL)
        val config = entitlements.toPlanFeatureConfig()

        assertEquals(UNLIMITED_SENTINEL, config.limits["maxProjects"])
    }

    // ─── 8.4 ─────────────────────────────────────────────────────────────────

    @Test
    fun `dataRetentionDays is always present in limits regardless of value`() {
        // Case 1: dataRetentionDays = 0 — the zero-exclusion rule does NOT apply here
        val configWithZero = baseEntitlements.copy(dataRetentionDays = 0).toPlanFeatureConfig()
        assertTrue("dataRetentionDays" in configWithZero.limits, "dataRetentionDays should be present even when 0")

        // Case 2: dataRetentionDays = 30
        val configWithThirty = baseEntitlements.copy(dataRetentionDays = 30).toPlanFeatureConfig()
        assertTrue("dataRetentionDays" in configWithThirty.limits, "dataRetentionDays should be present when 30")
    }

    // ─── 8.5 ─────────────────────────────────────────────────────────────────

    @Test
    fun `features map contains exactly 12 keys`() {
        val config = baseEntitlements.toPlanFeatureConfig()

        val expectedKeys =
            setOf(
                "customTrackingDomain",
                "advancedWebhooks",
                "signedWebhooks",
                "readEngagement",
                "enrichedTracking",
                "forwardingDetection",
                "deliverabilityAdvisor",
                "realtimeStream",
                "sendTimeOptimisation",
                "dedicatedIps",
                "multiRegion",
                "inboundReceiving",
            )
        assertEquals(expectedKeys, config.features.keys)
    }

    // ─── 8.6 ─────────────────────────────────────────────────────────────────

    @Test
    fun `signal-spark produces dedicatedIps = false and signal-enterprise produces dedicatedIps = true`() {
        val spark = signalPlans.first { it.slug == "signal-spark" }
        val enterprise = signalPlans.first { it.slug == "signal-enterprise" }

        val sparkConfig = spark.entitlements.toPlanFeatureConfig()
        val enterpriseConfig = enterprise.entitlements.toPlanFeatureConfig()

        assertNotNull(sparkConfig.features["dedicatedIps"])
        assertFalse(sparkConfig.features["dedicatedIps"]!!, "signal-spark should have dedicatedIps = false")

        assertNotNull(enterpriseConfig.features["dedicatedIps"])
        assertTrue(enterpriseConfig.features["dedicatedIps"]!!, "signal-enterprise should have dedicatedIps = true")
    }

    // ─── 8.7 — Property 8 ────────────────────────────────────────────────────
    // Feature: billing-comprehensive-unit-tests, Property 8: toPlanFeatureConfig features map always has exactly 12 keys
    //
    // **Property 8: `toPlanFeatureConfig` features map always has exactly 12 keys**
    // **Validates: Requirements 22.1, 8.5**

    @Test
    fun `property - features map always has exactly 12 keys for all 6 plans (Property 8)`() =
        runBlocking {
            val expectedKeys =
                setOf(
                    "customTrackingDomain",
                    "advancedWebhooks",
                    "signedWebhooks",
                    "readEngagement",
                    "enrichedTracking",
                    "forwardingDetection",
                    "deliverabilityAdvisor",
                    "realtimeStream",
                    "sendTimeOptimisation",
                    "dedicatedIps",
                    "multiRegion",
                    "inboundReceiving",
                )

            forAll(Arb.element(signalPlans)) { plan ->
                plan.entitlements
                    .toPlanFeatureConfig()
                    .features.keys == expectedKeys
            }

            Unit
        }

    // ─── 8.8 — Property 9 ────────────────────────────────────────────────────
    // Feature: billing-comprehensive-unit-tests, Property 9: toPlanFeatureConfig limits never contain zero values
    //
    // **Property 9: `toPlanFeatureConfig` limits never contain zero values**
    // **Validates: Requirements 22.2, 8.7**

    @Test
    fun `property - limits never contain zero values for all 6 plans (Property 9)`() =
        runBlocking {
            forAll(Arb.element(signalPlans)) { plan ->
                plan.entitlements
                    .toPlanFeatureConfig()
                    .limits.values
                    .none { it == 0 }
            }

            Unit
        }

    // ─── 8.9 — Property 10 ───────────────────────────────────────────────────
    // Feature: billing-comprehensive-unit-tests, Property 10: toPlanFeatureConfig limits always include "dataRetentionDays"
    //
    // **Property 10: `toPlanFeatureConfig` limits always include `"dataRetentionDays"`**
    // **Validates: Requirements 22.3, 8.4**

    @Test
    fun `property - limits always include dataRetentionDays for all 6 plans (Property 10)`() =
        runBlocking {
            forAll(Arb.element(signalPlans)) { plan ->
                "dataRetentionDays" in plan.entitlements.toPlanFeatureConfig().limits
            }

            Unit
        }
}
