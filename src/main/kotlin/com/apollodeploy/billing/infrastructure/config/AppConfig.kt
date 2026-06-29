package com.apollodeploy.billing.infrastructure.config

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

object AppConfig {
    private val config: Config = ConfigFactory.load()

    val environment: String = config.getString("apollo-billing.environment")
    val billingPort: Int = config.getInt("apollo-billing.port")

    // Polar
    val polarWebhookSecret: String = config.getString("apollo-billing.polar.webhook-secret")
    val polarApiKey: String = config.getString("apollo-billing.polar.api-key")
    val polarApiBaseUrl: String = config.getString("apollo-billing.polar.api-base-url")
    val polarRequestTimeoutMs: Long = config.getLong("apollo-billing.polar.request-timeout-ms")

    // Platform DB -- billing_app role
    val platformDbHost: String = config.getString("apollo-billing.platform-db.host")
    val platformDbPort: Int = config.getInt("apollo-billing.platform-db.port")
    val platformDbName: String = config.getString("apollo-billing.platform-db.name")
    val platformDbUser: String = config.getString("apollo-billing.platform-db.user")
    val platformDbPassword: String = config.getString("apollo-billing.platform-db.password")
    val platformDbPoolMaxSize: Int = config.getInt("apollo-billing.platform-db.pool-max-size")
    val platformDbIdleTimeoutMs: Long = config.getLong("apollo-billing.platform-db.idle-timeout-ms")
    val platformDbConnectionTimeoutMs: Long = config.getLong("apollo-billing.platform-db.connection-timeout-ms")
    val platformDbStatementTimeoutMs: Long = config.getLong("apollo-billing.platform-db.statement-timeout-ms")

    // Platform reader DB -- billing_superuser role on the platform database
    val platformReaderDbUser: String = config.getString("apollo-billing.platform-reader-db.user")
    val platformReaderDbPassword: String = config.getString("apollo-billing.platform-reader-db.password")
    val platformReaderDbPoolMaxSize: Int = config.getInt("apollo-billing.platform-reader-db.pool-max-size")
    val platformReaderDbIdleTimeoutMs: Long = config.getLong("apollo-billing.platform-reader-db.idle-timeout-ms")
    val platformReaderDbConnectionTimeoutMs: Long = config.getLong("apollo-billing.platform-reader-db.connection-timeout-ms")
    val platformReaderDbStatementTimeoutMs: Long = config.getLong("apollo-billing.platform-reader-db.statement-timeout-ms")

    // Platform OAuth2 -- client_credentials for audit-log ingestion and outbound calls
    val platformUrl: String = config.getString("apollo-billing.platform.url")
    val platformClientId: String = config.getString("apollo-billing.platform.client-id")
    val platformClientSecret: String = config.getString("apollo-billing.platform.client-secret")

    // IAM / OAuth M2M inbound -- verify tokens sent by callers of /internal/* endpoints.
    // The platform issues EdDSA-signed JWTs via /auth/oauth2/token (client_credentials).
    // Billing verifies them locally via JWKS (no shared secret needed).

    /** JWKS URL for token verification. Defaults to {platformUrl}/auth/jwks. */
    val iamJwksUrl: String =
        config.getString("apollo-billing.iam.jwks-url")
            .ifBlank { if (platformUrl.isNotBlank()) "${platformUrl.trimEnd('/')}/auth/jwks" else "" }

    /** Expected iss claim in incoming tokens. Defaults to platformUrl. */
    val iamAllowedIssuers: Set<String> =
        config.getString("apollo-billing.iam.issuer-url")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
            .ifEmpty { if (platformUrl.isNotBlank()) setOf(platformUrl) else emptySet() }

    /** Expected aud claim(s) in incoming tokens. Defaults to platformUrl. */
    val iamValidAudiences: Set<String> =
        config.getString("apollo-billing.iam.valid-audiences")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
            .ifEmpty { if (platformUrl.isNotBlank()) setOf(platformUrl) else emptySet() }

    /**
     * Comma-separated OAuth client_id values that are allowed to call /internal/ endpoints.
     * Fails closed when empty. Set to the client_id of each first-party service registered
     * on the platform (e.g. signal's client_id).
     */
    val iamServiceClientIds: Set<String> =
        config.getString("apollo-billing.iam.service-client-ids")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()

    /** Per-request timeout for outbound OAuth / JWKS HTTP calls (milliseconds). */
    val iamRequestTimeoutMs: Long = config.getLong("apollo-billing.iam.request-timeout-ms")

    // Signal DB -- billing_superuser role
    val signalDbHost: String = config.getString("apollo-billing.signal-db.host")
    val signalDbPort: Int = config.getInt("apollo-billing.signal-db.port")
    val signalDbName: String = config.getString("apollo-billing.signal-db.name")
    val signalDbUser: String = config.getString("apollo-billing.signal-db.user")
    val signalDbPassword: String = config.getString("apollo-billing.signal-db.password")
    val signalDbPoolMaxSize: Int = config.getInt("apollo-billing.signal-db.pool-max-size")
    val signalDbIdleTimeoutMs: Long = config.getLong("apollo-billing.signal-db.idle-timeout-ms")
    val signalDbConnectionTimeoutMs: Long = config.getLong("apollo-billing.signal-db.connection-timeout-ms")
    val signalDbStatementTimeoutMs: Long = config.getLong("apollo-billing.signal-db.statement-timeout-ms")

    // Redis
    val redisHost: String = config.getString("apollo-billing.redis.host")
    val redisPort: Int = config.getInt("apollo-billing.redis.port")
    val redisPassword: String = config.getString("apollo-billing.redis.password")
}
