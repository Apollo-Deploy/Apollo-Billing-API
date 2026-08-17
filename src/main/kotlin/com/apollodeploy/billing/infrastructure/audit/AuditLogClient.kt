package com.apollodeploy.billing.infrastructure.audit

import com.apollodeploy.oauth.m2m.client.MachineOAuthClient
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

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
    private val m2mClient: MachineOAuthClient,
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
        try {
            val token = m2mClient.accessToken()
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
        if (platformUrl.isBlank()) {
            logger.debug("[billing:audit] not configured (PLATFORM_URL missing) — skipping")
            return false
        }
        return true
    }
}
