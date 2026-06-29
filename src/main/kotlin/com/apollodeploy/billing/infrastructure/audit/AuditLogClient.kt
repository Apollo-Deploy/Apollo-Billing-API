package com.apollodeploy.billing.infrastructure.audit

import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Apollo Billing — platform audit-log client.
 *
 * Sends billing events to the platform's internal audit-log ingestion endpoint:
 *   POST <platformUrl>/internal/apps/billing/audit-logs/batch
 *
 * Authentication uses the OAuth2 client_credentials grant. The token is cached
 * and refreshed automatically before expiry.
 *
 * All calls are fire-and-forget — failures are logged as WARN and never surface
 * to the caller. The audit log must never break a billing operation.
 */
class AuditLogClient(
    private val httpClient: HttpClient,
    private val platformUrl: String,
    private val clientId: String,
    private val clientSecret: String,
    private val appSlug: String = "billing",
    private val enabled: Boolean = true,
) {
    private val logger = LoggerFactory.getLogger(AuditLogClient::class.java)

    // Fire-and-forget coroutine scope with a SupervisorJob so individual
    // audit log failures never cancel sibling coroutines.
    private val scope = CoroutineScope(SupervisorJob())

    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    // ─── Token cache ──────────────────────────────────────────────────────────

    private data class CachedToken(
        val accessToken: String,
        val expiresAtMs: Long,
    )

    private val cachedToken = AtomicReference<CachedToken?>(null)
    private val tokenRefreshLock = AtomicLong(0L) // 0 = available

    /** Returns a valid bearer token, refreshing it if within 60 s of expiry. */
    private suspend fun getToken(): String? {
        val now = System.currentTimeMillis()
        val current = cachedToken.get()
        if (current != null && current.expiresAtMs - now > 60_000) return current.accessToken

        return try {
            val response =
                httpClient.submitForm(
                    url = "$platformUrl/auth/oauth2/token",
                    formParameters =
                        parameters {
                            append("grant_type", "client_credentials")
                            append("client_id", clientId)
                            append("client_secret", clientSecret)
                            append("resource", platformUrl)
                        },
                )

            if (!response.status.isSuccess()) {
                logger.warn(
                    "[billing:audit] token request failed status={} body={}",
                    response.status.value,
                    runCatching { response.bodyAsText() }.getOrElse { "" },
                )
                return null
            }

            val body = json.decodeFromString<JsonObject>(response.bodyAsText())
            val accessToken = body["access_token"]?.jsonPrimitive?.content ?: return null
            val expiresIn = body["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L

            val newToken = CachedToken(accessToken, now + expiresIn * 1000L)
            cachedToken.set(newToken)
            accessToken
        } catch (e: Exception) {
            logger.warn("[billing:audit] token request exception: {}", e.message)
            null
        }
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    /** Asynchronously logs a single audit event. Never throws. */
    fun log(event: AuditEvent) {
        if (!isConfigured()) return
        scope.launch { sendBatch(listOf(event)) }
    }

    /** Asynchronously logs a batch of audit events. Never throws. */
    fun logBatch(events: List<AuditEvent>) {
        if (!isConfigured() || events.isEmpty()) return
        scope.launch { sendBatch(events) }
    }

    // ─── Internal send ────────────────────────────────────────────────────────

    private suspend fun sendBatch(events: List<AuditEvent>) {
        val token = getToken() ?: return

        try {
            val url = "$platformUrl/internal/apps/$appSlug/audit-logs/batch"
            val response =
                httpClient.post(url) {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(AuditBatchRequest(events)))
                }

            if (!response.status.isSuccess()) {
                logger.warn(
                    "[billing:audit] batch ingest failed status={} events={} body={}",
                    response.status.value,
                    events.size,
                    runCatching { response.bodyAsText() }.getOrElse { "" },
                )
            } else {
                logger.debug("[billing:audit] sent {} event(s) → {}", events.size, response.status.value)
            }
        } catch (e: Exception) {
            logger.warn("[billing:audit] batch send exception events={}: {}", events.size, e.message)
        }
    }

    private fun isConfigured(): Boolean {
        if (!enabled) return false
        if (platformUrl.isBlank() || clientId.isBlank() || clientSecret.isBlank()) {
            logger.debug("[billing:audit] not configured (PLATFORM_URL / PLATFORM_CLIENT_ID / PLATFORM_CLIENT_SECRET missing) — skipping")
            return false
        }
        return true
    }
}
