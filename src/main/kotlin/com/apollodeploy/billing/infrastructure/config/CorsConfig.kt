package com.apollodeploy.billing.infrastructure.config

import io.ktor.server.plugins.cors.CORSConfig
import java.net.URI

internal data class CorsOrigin(
    val scheme: String,
    val host: String,
)

internal fun CORSConfig.allowConfiguredOrigins(
    configuredOrigins: String,
    isProduction: Boolean,
) {
    val origins = parseCorsOrigins(configuredOrigins)
    require(origins.isNotEmpty() || !isProduction) {
        "CORS_ORIGINS must contain at least one exact origin in production"
    }

    if (origins.isEmpty()) {
        anyHost()
        return
    }

    origins.forEach { origin ->
        allowHost(origin.host, schemes = listOf(origin.scheme))
    }
}

internal fun parseCorsOrigins(configuredOrigins: String): List<CorsOrigin> =
    configuredOrigins
        .split(',')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .map(::parseCorsOrigin)
        .distinct()

private fun parseCorsOrigin(value: String): CorsOrigin {
    val uri =
        runCatching { URI(value) }
            .getOrElse { throw IllegalArgumentException("Invalid CORS origin: $value", it) }
    val scheme = uri.scheme?.lowercase()
    require(scheme == "http" || scheme == "https") { "Invalid CORS origin scheme: $value" }
    require(uri.host != null && uri.userInfo == null) { "Invalid CORS origin host: $value" }
    require(uri.path.isNullOrEmpty() && uri.query == null && uri.fragment == null) {
        "CORS origin must not include a path, query, or fragment: $value"
    }

    val host = if (uri.port == -1) uri.host else "${uri.host}:${uri.port}"
    return CorsOrigin(scheme = scheme, host = host.lowercase())
}
