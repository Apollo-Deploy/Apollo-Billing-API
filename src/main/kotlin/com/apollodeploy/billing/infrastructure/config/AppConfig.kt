package com.apollodeploy.billing.infrastructure.config

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

object AppConfig {
    private val root = ConfigFactory.load().getConfig("apollo-billing")

    val environment: String = root.getString("environment")
    val port: Int = root.getInt("port")
    val requestBodyLimitBytes: Long = root.getLong("request-body-limit-bytes")
    val metricsEnabled: Boolean = root.getBoolean("metrics-enabled")
    val corsOrigins: Set<String> = root.getStringSet("cors-origins")

    val polar: PolarConfig = root.getConfig("polar").toPolarConfig()
    val platform: PlatformConfig = root.getConfig("platform").toPlatformConfig()
    val iam: IamConfig = root.getConfig("iam").toIamConfig(platform.url)
    val redis: RedisConfig = root.getConfig("redis").toRedisConfig()

    private val databaseProvider =
        System.getenv("DB_PROVIDER")
            ?.takeIf(String::isNotBlank)
            ?: root.getString("db-provider")

    val platformDatabase: DatabaseConfig =
        root.getConfig("platform-db").toDatabaseConfig(databaseProvider)

    val platformReaderDatabase: DatabasePoolConfig =
        root.getConfig("platform-reader-db").toDatabasePoolConfig()

    val signalDatabase: DatabaseConfig =
        root.getConfig("signal-db").toDatabaseConfig(databaseProvider)

    val signal: SignalConfig =
        root.getConfig("signal").toSignalConfig()
}

data class PolarConfig(
    val webhookSecret: String,
    val apiKey: String,
    val apiBaseUrl: String,
    val requestTimeoutMs: Long,
)

data class PlatformConfig(
    val url: String,
    val clientId: String,
    val clientSecret: String,
)

data class IamConfig(
    val jwksUrl: String,
    val allowedIssuers: Set<String>,
    val validAudiences: Set<String>,
    val serviceClientIds: Set<String>,
    val requestTimeoutMs: Long,
)

data class DatabaseConfig(
    val host: String,
    val port: Int,
    val name: String,
    val user: String,
    val password: String,
    val sslMode: String,
    val pool: DatabasePoolConfig,
)

data class DatabasePoolConfig(
    val maxSize: Int,
    val idleTimeoutMs: Long,
    val connectionTimeoutMs: Long,
    val statementTimeoutMs: Long,
)

data class SignalConfig(
    val emailReceivedMeterId: String,
)

data class RedisConfig(
    val host: String,
    val port: Int,
    val password: String,
    val database: Int,
)

private fun Config.toPolarConfig() =
    PolarConfig(
        webhookSecret = getString("webhook-secret"),
        apiKey = getString("api-key"),
        apiBaseUrl = getString("api-base-url"),
        requestTimeoutMs = getLong("request-timeout-ms"),
    )

private fun Config.toPlatformConfig() =
    PlatformConfig(
        url = getString("url"),
        clientId = getString("client-id"),
        clientSecret = getString("client-secret"),
    )

private fun Config.toIamConfig(platformUrl: String): IamConfig {
    val normalizedPlatformUrl = platformUrl.trimEnd('/')

    return IamConfig(
        jwksUrl = getString("auth-jwks-url")
            .ifBlank {
                normalizedPlatformUrl
                    .takeIf(String::isNotBlank)
                    ?.let { "$it/auth/jwks" }
                    .orEmpty()
            },
        allowedIssuers = getStringSet("issuer-url")
            .ifEmpty { normalizedPlatformUrl.toFallbackSet() },
        validAudiences = getStringSet("valid-audiences")
            .ifEmpty { normalizedPlatformUrl.toFallbackSet() },
        serviceClientIds = getStringSet("service-client-ids"),
        requestTimeoutMs = getLong("request-timeout-ms"),
    )
}

private fun Config.toDatabaseConfig(provider: String): DatabaseConfig =
    DatabaseConfig(
        host = getString("host"),
        port = getInt("port"),
        name = getString("name"),
        user = getString("user"),
        password = getString("password"),
        sslMode = resolveSslMode(
            configuredMode = getString("sslmode"),
            provider = provider,
        ),
        pool = toDatabasePoolConfig(),
    )

private fun Config.toDatabasePoolConfig() =
    DatabasePoolConfig(
        maxSize = getInt("pool-max-size"),
        idleTimeoutMs = getLong("idle-timeout-ms"),
        connectionTimeoutMs = getLong("connection-timeout-ms"),
        statementTimeoutMs = getLong("statement-timeout-ms"),
    )

private fun Config.toSignalConfig() =
    SignalConfig(
        emailReceivedMeterId = getString("email-received-meter-id"),
    )

private fun Config.toRedisConfig() =
    RedisConfig(
        host = getString("host"),
        port = getInt("port"),
        password = getString("password"),
        database = getInt("db"),
    )

private fun Config.getStringSet(path: String): Set<String> =
    getString(path)
        .splitToSequence(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toSet()

private fun String.toFallbackSet(): Set<String> =
    takeIf(String::isNotBlank)?.let(::setOf).orEmpty()

private fun resolveSslMode(
    configuredMode: String,
    provider: String,
): String {
    val sslMode = configuredMode.ifBlank { "disable" }

    return if (sslMode.equals("disable", ignoreCase = true)) {
        "disable"
    } else {
        "require"
    }
}