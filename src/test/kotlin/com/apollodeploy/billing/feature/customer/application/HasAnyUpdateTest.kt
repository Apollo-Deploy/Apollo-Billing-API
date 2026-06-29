package com.apollodeploy.billing.feature.customer.application

import com.apollodeploy.billing.feature.customer.domain.UpdateCustomerBillingInfoRequest
import com.apollodeploy.billing.feature.customer.domain.hasAnyUpdate
import com.apollodeploy.billing.infrastructure.polar.PolarBillingAddressInput
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.filter
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.string
import io.kotest.property.forAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HasAnyUpdateTest {
    // ─── 14.1 ─────────────────────────────────────────────────────────────────
    // All five optional fields null → hasAnyUpdate() returns false

    @Test
    fun `14_1 all five optional fields null returns false`() {
        val request = UpdateCustomerBillingInfoRequest(orgId = "org_1")
        assertFalse(request.hasAnyUpdate())
    }

    // ─── 14.2 ─────────────────────────────────────────────────────────────────
    // Only email non-null → hasAnyUpdate() returns true

    @Test
    fun `14_2 only email non-null returns true`() {
        val request = UpdateCustomerBillingInfoRequest(orgId = "org_1", email = "a@b.com")
        assertTrue(request.hasAnyUpdate())
    }

    // ─── 14.3 ─────────────────────────────────────────────────────────────────
    // Only billingName non-null → hasAnyUpdate() returns true

    @Test
    fun `14_3 only billingName non-null returns true`() {
        val request = UpdateCustomerBillingInfoRequest(orgId = "org_1", billingName = "Acme")
        assertTrue(request.hasAnyUpdate())
    }

    // ─── 14.4 ─────────────────────────────────────────────────────────────────
    // Only billingAddress non-null → hasAnyUpdate() returns true

    @Test
    fun `14_4 only billingAddress non-null returns true`() {
        val address = PolarBillingAddressInput(country = "US")
        val request = UpdateCustomerBillingInfoRequest(orgId = "org_1", billingAddress = address)
        assertTrue(request.hasAnyUpdate())
    }

    // ─── 14.5 ─────────────────────────────────────────────────────────────────
    // Only taxId non-null → hasAnyUpdate() returns true

    @Test
    fun `14_5 only taxId non-null returns true`() {
        val request = UpdateCustomerBillingInfoRequest(orgId = "org_1", taxId = "VAT123")
        assertTrue(request.hasAnyUpdate())
    }

    // ─── 14.6 ─────────────────────────────────────────────────────────────────
    // Only defaultPaymentMethodId non-null → hasAnyUpdate() returns true

    @Test
    fun `14_6 only defaultPaymentMethodId non-null returns true`() {
        val request = UpdateCustomerBillingInfoRequest(orgId = "org_1", defaultPaymentMethodId = "pm_abc")
        assertTrue(request.hasAnyUpdate())
    }

    // ─── 14.7 ─────────────────────────────────────────────────────────────────
    // Feature: billing-comprehensive-unit-tests, Property 7: hasAnyUpdate returns true for any non-empty optional field combination
    //
    // **Property 7: `hasAnyUpdate` returns true for any non-empty optional field combination**
    // **Validates: Requirements 21.1, 7.3**

    @Test
    fun `14_7 property - any non-empty subset of optional fields returns true (Property 7)`() =
        runBlocking {
            val nonNullString = Arb.string(minSize = 1, maxSize = 50)

            // Four string-typed optional fields as nullable arbs
            val emailArb = nonNullString.orNull(nullProbability = 0.5)
            val billingNameArb = nonNullString.orNull(nullProbability = 0.5)
            val taxIdArb = nonNullString.orNull(nullProbability = 0.5)
            val pmIdArb = nonNullString.orNull(nullProbability = 0.5)

            // Generate tuples (email, billingName, taxId, pmId) where at least one is non-null
            // billingAddress is always null to simplify generation — the other 4 axes fully cover
            // the "at least one non-null" requirement as stated in the task spec
            val arbTuple =
                Arb
                    .bind(emailArb, billingNameArb, taxIdArb, pmIdArb) { e, b, t, p ->
                        Quadruple(e, b, t, p)
                    }.filter { (e, b, t, p) -> e != null || b != null || t != null || p != null }

            forAll(arbTuple) { (email, billingName, taxId, pmId) ->
                val request =
                    UpdateCustomerBillingInfoRequest(
                        orgId = "o",
                        email = email,
                        billingName = billingName,
                        taxId = taxId,
                        defaultPaymentMethodId = pmId,
                    )
                request.hasAnyUpdate()
            }

            Unit
        }
}

/** Simple data holder for four nullable values used in the property test generator. */
private data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
)

/** Destructuring operator for Quadruple. */
private operator fun <A, B, C, D> Quadruple<A, B, C, D>.component1() = first

private operator fun <A, B, C, D> Quadruple<A, B, C, D>.component2() = second

private operator fun <A, B, C, D> Quadruple<A, B, C, D>.component3() = third

private operator fun <A, B, C, D> Quadruple<A, B, C, D>.component4() = fourth
