package com.apollodeploy.billing.infrastructure.validation

import java.net.URI

/**
 * Apollo Billing — URL validator for checkout redirect URLs.
 *
 * Validates successUrl and returnUrl to prevent open-redirect attacks
 * through the Polar checkout flow. Only HTTPS URLs on allowed domains
 * (or localhost for dev) are permitted.
 *
 * Security:
 *   - Blocks non-HTTPS schemes (except http://localhost for dev)
 *   - Blocks IPs, internal hostnames, and known-dangerous patterns
 *   - Allows only domains in the configured allowlist (or any domain if allowlist is empty)
 *   - Rejects URLs with credentials (user:pass@host)
 */
object UrlValidator {
    // Default domains that are always allowed for checkout redirects.
    // Extend via CHECKOUT_REDIRECT_ALLOWED_DOMAINS env var.
    private val DEFAULT_ALLOWED_DOMAINS =
        setOf(
            "apollodeploy.com",
            "www.apollodeploy.com",
            "app.apollodeploy.com",
            "signal.apollodeploy.com",
            "billing.apollodeploy.com",
            "billing.dev.apollodeploy.com",
        )

    // Patterns that indicate potentially dangerous URLs
    private val BLOCKED_HOST_PATTERNS =
        listOf(
            Regex("""^\d+\.\d+\.\d+\.\d+$"""),          // IPv4 addresses
            Regex("""^\[.*]$"""),                          // IPv6 addresses
            Regex("""^localhost$""", RegexOption.IGNORE_CASE),
            Regex("""^127\.0\.0\.\d+$"""),
            Regex("""^0\.0\.0\.0$"""),
            Regex("""^10\.\d+\.\d+\.\d+$"""),             // Private RFC1918
            Regex("""^172\.(1[6-9]|2\d|3[01])\.\d+\.\d+$"""),
            Regex("""^192\.168\.\d+\.\d+$"""),
            Regex("""\.local$""", RegexOption.IGNORE_CASE),
            Regex("""\.internal$""", RegexOption.IGNORE_CASE),
        )

    private val configuredAllowedDomains: Set<String> by lazy {
        val envDomains =
            System.getenv("CHECKOUT_REDIRECT_ALLOWED_DOMAINS")
                ?.split(",")
                ?.map { it.trim().lowercase() }
                ?.filter { it.isNotBlank() }
                ?.toSet()
                ?: emptySet()
        DEFAULT_ALLOWED_DOMAINS + envDomains
    }

    /**
     * Validate a redirect URL for checkout flows.
     * Returns null if valid, or an error message if invalid.
     */
    fun validateRedirectUrl(url: String?): String? {
        if (url == null) return null // null is allowed (optional field)
        if (url.isBlank()) return "URL cannot be blank"

        val uri =
            try {
                URI(url)
            } catch (e: Exception) {
                return "Malformed URL: ${e.message}"
            }

        // Must have a scheme
        val scheme = uri.scheme?.lowercase() ?: return "URL must have a scheme (https://)"

        // Only HTTPS allowed (no http, javascript:, data:, file:, etc.)
        if (scheme != "https") {
            return "Only HTTPS URLs are allowed for checkout redirects"
        }

        // Must have a host
        val host = uri.host?.lowercase() ?: return "URL must have a valid host"

        // No credentials in URL
        if (uri.userInfo != null) {
            return "URLs with credentials are not allowed"
        }

        // Block dangerous host patterns
        for (pattern in BLOCKED_HOST_PATTERNS) {
            if (pattern.containsMatchIn(host)) {
                return "URL host is not allowed: $host"
            }
        }

        // If allowlist is configured, validate against it
        if (configuredAllowedDomains.isNotEmpty()) {
            val isAllowed = configuredAllowedDomains.any { allowed ->
                host == allowed || host.endsWith(".$allowed")
            }
            if (!isAllowed) {
                return "URL domain not in allowed list: $host"
            }
        }

        return null // Valid
    }

    /**
     * Convenience: validate and return the URL if valid, or throw.
     */
    fun requireValidRedirectUrl(url: String?, fieldName: String): String? {
        val error = validateRedirectUrl(url) ?: return url
        throw InvalidRedirectUrlException(fieldName, error)
    }
}

class InvalidRedirectUrlException(
    val fieldName: String,
    val reason: String,
) : RuntimeException("Invalid $fieldName: $reason")
