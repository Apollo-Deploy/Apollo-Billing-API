package com.apollodeploy.billing.infrastructure.validation

import java.net.URI
import java.net.URISyntaxException
import java.util.LinkedHashSet

private const val ALLOWED_DOMAINS_ENV =
    "CHECKOUT_REDIRECT_ALLOWED_DOMAINS"

private const val HTTPS_SCHEME = "https"

private const val BLANK_URL_ERROR =
    "URL cannot be blank"

private const val MALFORMED_URL_ERROR =
    "Malformed URL"

private const val HTTPS_REQUIRED_ERROR =
    "Only HTTPS URLs are allowed for checkout redirects"

private const val INVALID_HOST_ERROR =
    "URL must have a valid host"

private const val CREDENTIALS_ERROR =
    "URLs with credentials are not allowed"

/**
 * Validates checkout redirect URLs against an explicit host allowlist.
 *
 * Environment entries support:
 *
 * - `app.example.com` for an exact host
 * - `*.example.com` for subdomains only
 */
object UrlValidator {
    private val defaultAllowedHosts =
        arrayOf(
            "apollodeploy.com",
            "www.apollodeploy.com",
            "app.apollodeploy.com",
            "account.apollodeploy.com",
            "auth.apollodeploy.com",
            "signal.apollodeploy.com",
            "billing.apollodeploy.com",
            "billing.dev.apollodeploy.com",
            "apollodeploy.local",
        )

    /**
     * Wildcard rules are stored with a leading dot:
     *
     * `*.example.com` becomes `.example.com`.
     */
    private val allowedHosts: Array<String> =
        loadAllowedHosts()

    /**
     * Returns null when valid, otherwise an error message.
     */
    fun validateRedirectUrl(url: String?): String? {
        if (url == null) {
            return null
        }

        if (url.isBlank()) {
            return BLANK_URL_ERROR
        }

        val uri =
            try {
                URI(url)
            } catch (_: URISyntaxException) {
                return MALFORMED_URL_ERROR
            }

        if (!uri.scheme.equals(HTTPS_SCHEME, ignoreCase = true)) {
            return HTTPS_REQUIRED_ERROR
        }

        if (uri.rawUserInfo != null) {
            return CREDENTIALS_ERROR
        }

        val host =
            uri.host
                ?.lowercase()
                ?.removeSuffix(".")
                ?: return INVALID_HOST_ERROR

        if (!isAllowedHost(host)) {
            return "URL domain not in allowed list: $host"
        }

        return null
    }

    fun requireValidRedirectUrl(
        url: String?,
        fieldName: String,
    ): String? {
        val error =
            validateRedirectUrl(url)
                ?: return url

        throw InvalidRedirectUrlException(
            fieldName = fieldName,
            reason = error,
        )
    }

    private fun isAllowedHost(host: String): Boolean {
        for (rule in allowedHosts) {
            if (rule[0] == '.') {
                // A wildcard matches subdomains, but not the root domain.
                if (host.length > rule.length && host.endsWith(rule)) {
                    return true
                }
            } else if (host == rule) {
                return true
            }
        }

        return false
    }

    private fun loadAllowedHosts(): Array<String> {
        val hosts =
            LinkedHashSet<String>(
                defaultAllowedHosts.size + 4,
            )

        hosts.addAll(defaultAllowedHosts)

        val configured =
            System.getenv(ALLOWED_DOMAINS_ENV)

        if (!configured.isNullOrBlank()) {
            for (value in configured.split(',')) {
                normalizeRule(value)?.let(hosts::add)
            }
        }

        return hosts.toTypedArray()
    }

    private fun normalizeRule(value: String): String? {
        val rule =
            value
                .trim()
                .lowercase()
                .removeSuffix(".")

        if (rule.isEmpty()) {
            return null
        }

        return if (rule.startsWith("*.")) {
            rule.removePrefix("*")
        } else {
            rule
        }
    }
}

/**
 * Expected validation failure.
 *
 * Stack traces are disabled because malformed user input is not an
 * application fault and may occur frequently on public endpoints.
 */
class InvalidRedirectUrlException(
    val fieldName: String,
    val reason: String,
) : RuntimeException("Invalid $fieldName: $reason") {
    override fun fillInStackTrace(): Throwable = this
}