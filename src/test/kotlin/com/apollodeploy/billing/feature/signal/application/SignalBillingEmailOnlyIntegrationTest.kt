package com.apollodeploy.billing.feature.signal.application

import com.apollodeploy.billing.infrastructure.persistence.DatabasePool
import com.apollodeploy.billing.infrastructure.persistence.SubscriptionRepo
import com.apollodeploy.billing.infrastructure.polar.PolarClient
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SignalBillingEmailOnlyIntegrationTest {
    @Test
    fun `Signal entitlements resolve against the email schema`() =
        PostgreSQLContainer<Nothing>("postgres:16-alpine").use { postgres ->
            postgres.start()

            DatabasePool
                .create(
                    jdbcUrl = postgres.jdbcUrl,
                    username = postgres.username,
                    password = postgres.password,
                ).use { pool ->
                    pool.withConnection { connection ->
                        connection.createStatement().use { statement ->
                            statement.execute(
                                """
                                CREATE TABLE platform_apps (
                                    id TEXT PRIMARY KEY,
                                    slug TEXT NOT NULL
                                );
                                CREATE TABLE billing_customers (
                                    app_id TEXT NOT NULL,
                                    customer_id TEXT NOT NULL,
                                    external_ref TEXT NOT NULL
                                );
                                CREATE TABLE billing_subscriptions (
                                    app_id TEXT NOT NULL,
                                    customer_id TEXT NOT NULL,
                                    polar_product_id TEXT NOT NULL,
                                    status TEXT NOT NULL,
                                    quantity INTEGER,
                                    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
                                );
                                CREATE TABLE apikey (
                                    "referenceId" TEXT NOT NULL,
                                    "configId" TEXT NOT NULL,
                                    enabled BOOLEAN NOT NULL
                                );
                                CREATE TABLE projects (
                                    organization_id TEXT NOT NULL,
                                    status TEXT NOT NULL
                                );
                                CREATE TABLE domains (
                                    organization_id TEXT NOT NULL,
                                    status TEXT NOT NULL
                                );
                                CREATE TABLE webhook_endpoints (
                                    organization_id TEXT NOT NULL,
                                    deleted_at TIMESTAMPTZ
                                );
                                CREATE TABLE organization_usage_daily (
                                    organization_id TEXT NOT NULL,
                                    usage_date DATE NOT NULL,
                                    email_count INTEGER NOT NULL
                                );
                                INSERT INTO platform_apps (id, slug) VALUES ('signal-app', 'signal');
                                INSERT INTO organization_usage_daily (
                                    organization_id,
                                    usage_date,
                                    email_count
                                ) VALUES
                                    ('org_1', (now() AT TIME ZONE 'UTC')::date, 5),
                                    ('org_1', date_trunc('month', now() AT TIME ZONE 'UTC')::date - 1, 99);
                                """.trimIndent(),
                            )
                        }
                    }

                    val polarClient = mockk<PolarClient>()
                    coEvery { polarClient.getCustomerState("org_1") } returns null

                    val registration =
                        SignalBillingConfig(
                            db = pool,
                            platformReaderDb = pool,
                            signalDb = pool,
                            subscriptionRepo = SubscriptionRepo(pool),
                            polarClient = polarClient,
                        ).buildRegistration()

                    val result = runBlocking { registration.enforcer.resolveEntitlements("org_1") }

                    val entitlements = assertNotNull(result.getOrNull())
                    assertEquals(
                        setOf(
                            "maxProjects",
                            "maxDomains",
                            "maxWebhooks",
                            "dailySends",
                            "monthlySends",
                            "maxApiKeys",
                        ),
                        entitlements.usage.keys,
                    )
                    assertEquals(5, entitlements.usage["dailySends"])
                    assertEquals(5, entitlements.usage["monthlySends"])
                    Unit
                }
        }
}
