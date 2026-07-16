package com.apollodeploy.billing.feature.subscriptions.infrastructure.persistence

import com.apollodeploy.billing.feature.subscriptions.domain.SubscriptionItem
import com.apollodeploy.billing.infrastructure.persistence.DatabasePool
import com.apollodeploy.billing.infrastructure.persistence.prepareAndQuery

/**
 * Read-only repository for querying active subscriptions grouped by app.
 */
class SubscriptionsQueryRepo(
    private val db: DatabasePool,
) {
    private val SQL_ACTIVE_SUBSCRIPTIONS_BY_ORG =
        """
        SELECT a.slug AS app_slug,
               s.polar_subscription_id,
               s.polar_product_id,
               s.status,
               s.quantity,
               c.external_ref AS org_id,
               c.email,
               s.created_at,
               s.updated_at
        FROM billing_subscriptions s
        JOIN billing_customers c ON c.app_id = s.app_id AND c.customer_id = s.customer_id
        JOIN platform_apps a ON a.id = s.app_id
        WHERE s.status IN ('active', 'trialing', 'past_due')
          AND c.external_ref = ?
        ORDER BY a.slug, s.created_at DESC
        """.trimIndent()

    /**
     * Returns active subscriptions for an org grouped by app slug.
     */
    fun findActiveSubscriptionsGroupedByApp(orgId: String): Map<String, List<SubscriptionItem>> {
        return db.withConnection { conn ->
            conn
                .prepareAndQuery(SQL_ACTIVE_SUBSCRIPTIONS_BY_ORG, listOf(orgId)) { rs ->
                    rs.getString("app_slug") to
                        SubscriptionItem(
                            polarSubscriptionId = rs.getString("polar_subscription_id"),
                            polarProductId = rs.getString("polar_product_id"),
                            orgId = rs.getString("org_id"),
                            email = rs.getString("email"),
                            status = rs.getString("status"),
                            quantity = rs.getInt("quantity"),
                            createdAt = rs.getTimestamp("created_at").toInstant().toString(),
                            updatedAt = rs.getTimestamp("updated_at").toInstant().toString(),
                        )
                }.groupBy({ it.first }, { it.second })
        }
    }
}
