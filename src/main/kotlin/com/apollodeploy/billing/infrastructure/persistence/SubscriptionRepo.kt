package com.apollodeploy.billing.infrastructure.persistence

/**
 * Apollo Billing — subscription repository.
 *
 * Owns the write side of the subscription lifecycle:
 *   - Upsert customers (org → Polar customer mapping)
 *   - Upsert subscriptions (from Polar webhook events)
 *   - Deactivate subscriptions on revocation
 *
 * Also exposes app-agnostic read helpers used by app-specific billing configs
 * to resolve plans and add-ons without repeating subscription SQL per app.
 *
 * Local meter balance management has been removed. Polar's Credits benefit
 * and Usage Meters now own credit-backed resources end-to-end:
 *   - Plan subscriptions can auto-credit a meter at each billing cycle start.
 *   - One-time top-up purchases can auto-credit the meter at purchase time.
 *   - Balance is read via PolarClient.getCustomerState() in SignalBillingConfig.
 *
 * The read side (plan resolution) lives in each app's BillingConfig.resolvePlan.
 */
class SubscriptionRepo(
    private val db: DatabasePool,
) {
    // ─── SQL ─────────────────────────────────────────────────────────────────

    private val SQL_UPSERT_CUSTOMER =
        """
        INSERT INTO billing_customers (app_id, customer_id, external_ref, email)
        SELECT a.id, ?, ?, ?
        FROM platform_apps a
        WHERE a.slug = ?
        ON CONFLICT (app_id, external_ref)
            DO UPDATE SET customer_id = EXCLUDED.customer_id,
                          email       = EXCLUDED.email
        """.trimIndent()

    private val SQL_UPSERT_SUBSCRIPTION =
        """
        INSERT INTO billing_subscriptions
            (app_id, customer_id, polar_subscription_id, polar_product_id, status, quantity, created_at, updated_at)
        SELECT a.id, c.customer_id, ?, ?, ?, ?, now(), now()
        FROM platform_apps a
        JOIN billing_customers c ON c.app_id = a.id AND c.external_ref = ?
        WHERE a.slug = ?
        ON CONFLICT (polar_subscription_id)
            DO UPDATE SET app_id           = EXCLUDED.app_id,
                          customer_id      = EXCLUDED.customer_id,
                          polar_product_id = EXCLUDED.polar_product_id,
                          status           = EXCLUDED.status,
                          quantity         = EXCLUDED.quantity,
                          updated_at       = now()
        """.trimIndent()

    private val SQL_REVOKE_SUBSCRIPTION =
        """
        UPDATE billing_subscriptions
        SET status = 'canceled', updated_at = now()
        WHERE polar_subscription_id = ?
        """.trimIndent()

    private val SQL_SUM_ACTIVE_SUBSCRIPTION_QUANTITY =
        """
        SELECT COALESCE(SUM(GREATEST(COALESCE(s.quantity, 1), 0)), 0)::int AS cnt
        FROM billing_subscriptions s
        JOIN billing_customers c ON c.app_id = s.app_id AND c.customer_id = s.customer_id
        JOIN platform_apps a ON a.id = s.app_id
        WHERE a.slug = ?
          AND c.external_ref = ?
          AND s.polar_product_id = ?
          AND s.status IN ('active', 'trialing', 'past_due')
        """.trimIndent()

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Upserts a customer record from a Polar webhook.
     *
     * @param polarCustomerId  Polar's internal customer ID
     * @param orgId            Our external_ref (organisation ID)
     * @param email            Customer email
     * @param appSlug          Which app this subscription belongs to
     */
    fun upsertCustomer(
        polarCustomerId: String,
        orgId: String,
        email: String,
        appSlug: String,
    ) {
        db.withConnection { conn ->
            conn.executeUpdate(
                SQL_UPSERT_CUSTOMER,
                listOf(polarCustomerId, orgId, email, appSlug),
            )
        }
    }

    /**
     * Upserts a subscription from a Polar webhook event.
     *
     * @param polarSubscriptionId  Polar's subscription ID (unique key)
     * @param polarProductId       Polar's product ID
     * @param orgId                Our external_ref (used to look up customer)
     * @param appSlug              App this subscription belongs to
     * @param status               Polar subscription status
     * @param quantity             Quantity (for add-ons; defaults to 1)
     */
    fun upsertSubscription(
        polarSubscriptionId: String,
        polarProductId: String,
        orgId: String,
        appSlug: String,
        status: String,
        quantity: Int = 1,
    ) {
        db.withConnection { conn ->
            conn.executeUpdate(
                SQL_UPSERT_SUBSCRIPTION,
                listOf(polarSubscriptionId, polarProductId, status, quantity, orgId, appSlug),
            )
        }
    }

    /** Marks a subscription as canceled. */
    fun revokeSubscription(polarSubscriptionId: String) {
        db.withConnection { conn ->
            conn.executeUpdate(SQL_REVOKE_SUBSCRIPTION, listOf(polarSubscriptionId))
        }
    }

    /**
     * Finds the newest active subscription product for an app/org constrained
     * to [polarProductIds]. Returns null when no matching subscription exists.
     */
    fun findLatestActiveProductId(
        appSlug: String,
        orgId: String,
        polarProductIds: List<String>,
    ): String? {
        val ids = polarProductIds.filter { it.isNotBlank() }
        if (ids.isEmpty()) return null

        val placeholders = ids.joinToString(",") { "?" }
        val sql =
            """
            SELECT s.polar_product_id AS "polarProductId"
            FROM billing_subscriptions s
            JOIN billing_customers c ON c.app_id = s.app_id AND c.customer_id = s.customer_id
            JOIN platform_apps a ON a.id = s.app_id
            WHERE a.slug = ?
              AND c.external_ref = ?
              AND s.polar_product_id IN ($placeholders)
              AND s.status IN ('active', 'trialing', 'past_due')
            ORDER BY s.created_at DESC
            LIMIT 1
            """.trimIndent()

        return db.withConnection { conn ->
            conn
                .prepareAndQuery(sql, listOf(appSlug, orgId) + ids) {
                    it.getString("polarProductId")
                }.firstOrNull()
        }
    }

    /**
     * Returns the total active quantity for one subscription product.
     */
    fun activeSubscriptionQuantity(
        appSlug: String,
        orgId: String,
        polarProductId: String,
    ): Int {
        if (polarProductId.isBlank()) return 0
        return db.withConnection { conn ->
            conn
                .prepareAndQuery(SQL_SUM_ACTIVE_SUBSCRIPTION_QUANTITY, listOf(appSlug, orgId, polarProductId)) {
                    it.getInt("cnt")
                }.firstOrNull() ?: 0
        }
    }
}
