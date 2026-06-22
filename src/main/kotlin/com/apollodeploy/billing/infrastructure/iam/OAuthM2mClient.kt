/**
 * Apollo Billing — OAuth machine-to-machine token client.
 *
 * Obtains and caches a `client_credentials` JWT from the platform OAuth
 * endpoint (`POST {platformUrl}/auth/oauth2/token`).  The token is reused
 * until 60 s before its `expires_in`, at which point it is refreshed
 * transparently on the next call.
 *
 * The resulting JWT is sent as a `Bearer` token for all calls to
 * `/internal/...` platform endpoints (audit-log ingestion, etc.).
 *
 * Security invariants:
 *   - `clientSecret` is never logged.
 *   - Tokens are stored only in memory; never written to disk or database.
 *
 * Thread-safety: token refresh is protected by a `Mutex` so only one
 * coroutine issues a refresh at a time.
 */

package com.apollodeploy.billing.infrastructure.iam

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Refresh the token this many seconds before it actually expires. */
private const val EXPIRY_BUFFER_SECONDS: Long = 60L

/**
 * Fetches and caches a machine-to-machine OAuth2 `client_credentials` JWT.
 *
 * @param httpClient   Shared Ktor CIO [HttpClient].
 * @param platformUrl  Platform base URL, e.g. `https://api.platform.apollodeploy.com`.
 * @param clientId     OAuth client ID for this service (Billing).
 * @param clientSecret OAuth client secret for this service (Billing).
 * @param timeoutMs    Per-request HTTP timeout in milliseconds.
 */
class OAuthM2mClient(
    private val httpClient: HttpClient,
    private val platformUrl: String,
    private val clientId: String,
    private val clientSecret: String,
    private val timeoutMs: Long = 5_000L,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var tokenExpiresAt: Long = 0L  // epoch seconds

    /**
     * Returns a valid M2M bearer token, refreshing it automatically when
     * it is within [EXPIRY_BUFFER_SECONDS] of expiry.
     */
    suspend fun getToken(): String {
        // Fast path: token is still valid (no lock needed for the read)
        val now = System.currentTimeMillis() / 1_000L
        val cached = cachedToken
        if (cached != null && now < tokenExpiresAt) return cached

        // Slow path: acquire lock and refresh
        return mutex.withLock {
            // Re-check inside the lock in case another coroutine already refreshed
            val nowInner = System.currentTimeMillis() / 1_000L
            val stillValid = cachedToken
            if (stillValid != null && nowInner < tokenExpiresAt) {
                stillValid
            } else {
                refresh()
            }
        }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private suspend fun refresh(): String {
        if (platformUrl.isBlank()) {
            throw OAuthM2mException("Platform URL not configured (set PLATFORM_URL)")
        }
        if (clientId.isBlank() || clientSecret.isBlank()) {
            throw OAuthM2mException(
                "OAuth client credentials not configured (set PLATFORM_CLIENT_ID / PLATFORM_CLIENT_SECRET)",
            )
        }

        val body = buildString {
            append("grant_type=client_credentials")
            append("&client_id=").append(encode(clientId))
            append("&client_secret=").append(encode(clientSecret))
            append("&resource=").append(encode(platformUrl))
        }

        val responseText = withTimeout(timeoutMs) {
            val response = httpClient.post("$platformUrl/auth/oauth2/token") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody(body)
            }
            if (!response.status.isSuccess()) {
                val err = runCatching { response.bodyAsText() }.getOrElse { "" }
                throw OAuthM2mException(
                    "OAuth token endpoint returned ${response.status.value}: $err",
                )
            }
            response.bodyAsText()
        }

        return parseTokenResponse(responseText)
    }

    private fun parseTokenResponse(text: String): String {
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            throw OAuthM2mException("Failed to parse OAuth token response: ${e.message}")
        }

        val accessToken = root["access_token"]?.jsonPrimitive?.content
            ?: throw OAuthM2mException("OAuth token response missing 'access_token'")

        val expiresIn = root["expires_in"]?.jsonPrimitive?.longOrNull ?: 3_600L
        val now = System.currentTimeMillis() / 1_000L

        cachedToken = accessToken
        tokenExpiresAt = now + expiresIn - EXPIRY_BUFFER_SECONDS

        return accessToken
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, Charsets.UTF_8.name())
}

/** Thrown when the M2M token cannot be obtained. */
class OAuthM2mException(message: String) : RuntimeException(message)
