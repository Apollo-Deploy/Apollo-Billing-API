package com.apollodeploy.billing.feature.checkout.application

import com.apollodeploy.billing.core.BillingProduct
import com.apollodeploy.billing.core.BillingProductKind
import com.apollodeploy.billing.feature.checkout.domain.CreateCheckoutRequest
import com.apollodeploy.billing.feature.checkout.domain.CreateCheckoutResult
import com.apollodeploy.billing.feature.checkout.infrastructure.persistence.CheckoutRepo
import com.apollodeploy.billing.infrastructure.polar.model.PolarCheckoutSession
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CheckoutServiceTest {
    private val repo = mockk<CheckoutRepo>()
    private val auditLogClient = mockk<com.apollodeploy.billing.infrastructure.audit.AuditLogClient>(relaxed = true)
    private val service = CheckoutService(repo, auditLogClient)

    // 3.1 – unknown product returns UnknownProduct with matching fields
    @Test
    fun `unknown product returns UnknownProduct with matching appSlug and productSlug`() =
        runTest {
            every { repo.findProduct("signal", "unknown-slug") } returns null

            val result =
                service.createCheckout(
                    CreateCheckoutRequest(orgId = "org_1", appSlug = "signal", productSlug = "unknown-slug"),
                )

            assertIs<CreateCheckoutResult.UnknownProduct>(result)
            assertEquals("signal", result.appSlug)
            assertEquals("unknown-slug", result.productSlug)
        }

    // 3.2 – product found but null session returns Unavailable
    @Test
    fun `product found but null checkout session returns Unavailable`() =
        runTest {
            every { repo.findProduct(any(), any()) } returns
                BillingProduct(
                    appSlug = "signal",
                    slug = "signal-ignite",
                    polarProductId = "polar_123",
                    kind = BillingProductKind.SUBSCRIPTION,
                )
            coEvery {
                repo.createCheckoutSession(any(), any(), any(), any(), any(), any(), any())
            } returns null

            val result =
                service.createCheckout(
                    CreateCheckoutRequest(orgId = "org_1", appSlug = "signal", productSlug = "signal-ignite"),
                )

            assertIs<CreateCheckoutResult.Unavailable>(result)
        }

    // 3.3 – product found and session created returns Created with id, url, productKind
    @Test
    fun `product found and session created returns Created with id, url, and productKind`() =
        runTest {
            every { repo.findProduct(any(), any()) } returns
                BillingProduct(
                    appSlug = "signal",
                    slug = "signal-ignite",
                    polarProductId = "polar_123",
                    kind = BillingProductKind.SUBSCRIPTION,
                )
            coEvery {
                repo.createCheckoutSession(any(), any(), any(), any(), any(), any(), any())
            } returns
                PolarCheckoutSession(
                    id = "chk_abc",
                    url = "https://checkout.polar.sh/chk_abc",
                    expiresAt = null,
                )

            val result =
                service.createCheckout(
                    CreateCheckoutRequest(orgId = "org_1", appSlug = "signal", productSlug = "signal-ignite"),
                )

            assertIs<CreateCheckoutResult.Created>(result)
            assertEquals("chk_abc", result.response.id)
            assertEquals("https://checkout.polar.sh/chk_abc", result.response.url)
            assertEquals("subscription", result.response.productKind)
        }

    // 3.4 – caller metadata is merged and system keys win over caller values
    @Test
    fun `system metadata keys override caller-supplied values and extra caller keys are preserved`() =
        runTest {
            every { repo.findProduct(any(), any()) } returns
                BillingProduct(
                    appSlug = "signal",
                    slug = "signal-ignite",
                    polarProductId = "polar_123",
                    kind = BillingProductKind.SUBSCRIPTION,
                )

            val metadataSlot = slot<Map<String, String>>()
            coEvery {
                repo.createCheckoutSession(any(), any(), any(), any(), any(), any(), capture(metadataSlot))
            } returns
                PolarCheckoutSession(
                    id = "chk_abc",
                    url = "https://checkout.polar.sh/chk_abc",
                    expiresAt = null,
                )

            service.createCheckout(
                CreateCheckoutRequest(
                    orgId = "org_1",
                    appSlug = "signal",
                    productSlug = "signal-ignite",
                    metadata = mapOf("app_slug" to "caller-overwrite", "extra" to "keep"),
                ),
            )

            // System value wins: "app_slug" must be the product's appSlug, not the caller-supplied value
            assertEquals("signal", metadataSlot.captured["app_slug"])
            // Caller-supplied extra key is preserved
            assertEquals("keep", metadataSlot.captured["extra"])
        }
}
