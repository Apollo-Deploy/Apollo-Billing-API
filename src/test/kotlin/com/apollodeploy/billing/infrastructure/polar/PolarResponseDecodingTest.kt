package com.apollodeploy.billing.infrastructure.polar

import com.apollodeploy.billing.infrastructure.polar.model.CustomerStateMeter
import com.apollodeploy.billing.infrastructure.polar.model.CustomerStateSubscription
import com.apollodeploy.billing.infrastructure.polar.model.PolarCheckoutSession
import com.apollodeploy.billing.infrastructure.polar.model.PolarCustomerSession
import com.apollodeploy.billing.infrastructure.polar.model.PolarCustomerState
import com.apollodeploy.billing.infrastructure.polar.model.PolarInvoiceUrlResponse
import com.apollodeploy.billing.infrastructure.polar.model.PolarResponseFixtures
import com.apollodeploy.billing.infrastructure.polar.model.PolarSubscriptionPayload
import com.apollodeploy.billing.infrastructure.polar.model.PolarWebhookEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PolarResponseDecodingTest {
    @Test
    fun decodesCustomerStateResponse() {
        val state = PolarResponseFixtures.decodeResponse<PolarCustomerState>("customer_state.json")

        assertEquals("org_abc123", state.externalId)
        assertEquals(1, state.activeSubscriptions.size)
        assertEquals("prod_signal_pro", state.activeSubscriptions.first().productId)
        assertEquals(1, state.grantedBenefits.size)
        assertEquals("100", state.grantedBenefits.first().benefitMetadata["maxUploadSizeMb"])
        assertEquals(958, state.activeMeters.first().balance)
    }

    @Test
    fun decodesCheckoutSessionResponse() {
        val session = PolarResponseFixtures.decodeResponse<PolarCheckoutSession>("checkout_session.json")

        assertEquals("chk_123", session.id)
        assertEquals("https://checkout.polar.sh/chk_123", session.url)
        assertEquals("2026-07-23T12:00:00Z", session.expiresAt)
    }

    @Test
    fun decodesCustomerSessionResponse() {
        val session = PolarResponseFixtures.decodeResponse<PolarCustomerSession>("customer_session.json")

        assertEquals("cs_123", session.id)
        assertTrue(session.customerPortalUrl.contains("portal"))
        assertEquals("cus_01HQXYZ", session.customerId)
    }

    @Test
    fun decodesInvoiceUrlResponse() {
        val invoice = PolarResponseFixtures.decodeResponse<PolarInvoiceUrlResponse>("invoice_url.json")

        assertTrue(invoice.url.contains("invoices"))
    }

    @Test
    fun decodesSubscriptionCreatedWebhook() {
        val event = PolarResponseFixtures.decodeResponse<PolarWebhookEvent>("subscription_created_webhook.json")
        val payload =
            PolarResponseFixtures.decodeWebhookPayload<PolarSubscriptionPayload>(
                "subscription_created_webhook.json",
            ) { it.data }

        assertEquals("subscription.created", event.type)
        assertEquals("org_abc123", payload.customer.externalId)
        assertEquals("active", payload.status)
    }

    @Test
    fun decodesCustomerStateChangedWebhook() {
        val state =
            PolarResponseFixtures.decodeWebhookPayload<PolarCustomerState>(
                "customer_state_changed_webhook.json",
            ) { it.data }

        assertEquals("org_abc123", state.externalId)
        assertTrue(state.activeSubscriptions.isEmpty())
    }

    @Test
    fun customerStateSubscriptionMapsAllFields() {
        val subscription =
            PolarResponseFixtures
                .decodeResponse<PolarCustomerState>("customer_state.json")
                .activeSubscriptions
                .first()

        assertEquals(
            CustomerStateSubscription(
                id = "sub_01HQXYZ",
                productId = "prod_signal_pro",
                status = "active",
                amount = 2900,
                currency = "usd",
                recurringInterval = "month",
                currentPeriodEnd = "2026-08-22T00:00:00Z",
                cancelAtPeriodEnd = false,
            ),
            subscription,
        )
    }

    @Test
    fun customerStateMeterMapsAllFields() {
        val meter =
            PolarResponseFixtures
                .decodeResponse<PolarCustomerState>("customer_state.json")
                .activeMeters
                .first()

        assertEquals(
            CustomerStateMeter(
                id = "cm_01",
                meterId = "meter_automation_runs",
                consumedUnits = 42,
                creditedUnits = 1000,
                balance = 958,
            ),
            meter,
        )
    }
}
