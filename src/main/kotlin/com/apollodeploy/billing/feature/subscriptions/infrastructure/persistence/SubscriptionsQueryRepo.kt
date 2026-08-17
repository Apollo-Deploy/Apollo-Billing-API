package com.apollodeploy.billing.feature.subscriptions.infrastructure.persistence

import com.apollodeploy.billing.feature.subscriptions.domain.SubscriptionItem
import com.apollodeploy.billing.infrastructure.persistence.DatabasePool
import com.apollodeploy.billing.infrastructure.persistence.prepareAndQuery
import java.sql.ResultSet

/**
 * Read-only repository for querying active subscriptions grouped by app.
 */
class SubscriptionsQueryRepo(
    private val db: DatabasePool,
) {
    private val subscriptionColumns =
        """
        a.slug AS app_slug,
        a.display_name AS app_name,
        s.polar_subscription_id,
        s.polar_product_id,
        s.status,
        s.quantity,
        s.cancel_at_period_end,
        s.amount_cents,
        s.currency,
        s.recurring_interval,
        c.external_ref AS org_id,
        c.email,
        s.renewal_at,
        s.ends_at,
        s.created_at,
        s.updated_at
        """.trimIndent()

    private val SQL_ACTIVE_SUBSCRIPTIONS_BY_ORG =
        """
        SELECT $subscriptionColumns
        FROM billing_subscriptions s
        JOIN billing_customers c ON c.app_id = s.app_id AND c.customer_id = s.customer_id
        JOIN platform_apps a ON a.id = s.app_id
        WHERE s.status IN ('active', 'trialing', 'past_due')
          AND c.external_ref = ?
        ORDER BY a.slug, s.created_at DESC
        """.trimIndent()

    private val SQL_ACTIVE_SUBSCRIPTION_FOR_ORG =
        """
        SELECT $subscriptionColumns
        FROM billing_subscriptions s
        JOIN billing_customers c ON c.app_id = s.app_id AND c.customer_id = s.customer_id
        JOIN platform_apps a ON a.id = s.app_id
        WHERE s.status IN ('active', 'trialing', 'past_due')
          AND c.external_ref = ?
          AND s.polar_subscription_id = ?
        LIMIT 1
        """.trimIndent()

    fun findActiveSubscriptionsGroupedByApp(orgId: String): Map<String, List<SubscriptionItem>> =
        db.withConnection { conn ->
            conn
                .prepareAndQuery(SQL_ACTIVE_SUBSCRIPTIONS_BY_ORG, listOf(orgId)) { rs ->
                    rs.getString("app_slug") to mapSubscriptionRow(rs)
                }.groupBy({ it.first }, { it.second })
        }

    fun findActiveSubscriptionForOrg(
        orgId: String,
        polarSubscriptionId: String,
    ): SubscriptionItem? =
        db.withConnection { conn ->
            conn
                .prepareAndQuery(SQL_ACTIVE_SUBSCRIPTION_FOR_ORG, listOf(orgId, polarSubscriptionId)) { rs ->
                    mapSubscriptionRow(rs)
                }.firstOrNull()
        }

    private fun mapSubscriptionRow(rs: ResultSet): SubscriptionItem =
        SubscriptionItem(
            polarSubscriptionId = rs.getString("polar_subscription_id"),
            polarProductId = rs.getString("polar_product_id"),
            appSlug = rs.getString("app_slug"),
            appName = rs.getString("app_name"),
            status = rs.getString("status"),
            quantity = rs.getInt("quantity"),
            amountCents = rs.getObject("amount_cents") as Int?,
            currency = rs.getString("currency"),
            billingInterval = rs.getString("recurring_interval"),
            cancelAtPeriodEnd = rs.getBoolean("cancel_at_period_end"),
            renewalDate = rs.getTimestamp("renewal_at")?.toInstant()?.toString(),
            endsAt = rs.getTimestamp("ends_at")?.toInstant()?.toString(),
            createdAt = rs.getTimestamp("created_at").toInstant().toString(),
            updatedAt = rs.getTimestamp("updated_at").toInstant().toString(),
            orgId = rs.getString("org_id"),
            email = rs.getString("email"),
        )
}
