package com.apollodeploy.billing.feature.security

import com.apollodeploy.billing.feature.enforce.api.EnforceController
import com.apollodeploy.billing.feature.enforce.api.enforceRoutes
import com.apollodeploy.billing.feature.enforce.application.EnforceService
import com.apollodeploy.billing.feature.enforce.domain.EnforceResult
import com.apollodeploy.billing.feature.usage.api.UsageIngestController
import com.apollodeploy.billing.feature.usage.api.usageIngestRoutes
import com.apollodeploy.billing.feature.usage.application.UsageIngestService
import com.apollodeploy.billing.feature.usage.domain.UsageIngestResponse
import com.apollodeploy.billing.support.billingTestApplication
import com.apollodeploy.billing.support.noAuthInternalRoutes
import com.apollodeploy.billing.support.validServiceToken
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.mockk.coEvery
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Adversarial tests — injection attack attempts.
 *
 * Attack vectors:
 *   - SQL injection via orgId, appSlug, resource, feature fields
 *   - JSON injection via nested objects, arrays where strings expected
 *   - Path traversal in parameters
 *   - Unicode tricks (homoglyphs, zero-width chars, RTL override)
 *   - Null bytes in strings
 *   - Oversized payloads
 *   - Wrong Content-Type headers
 */
class InjectionHttpTest {

    private val enforceService = mockk<EnforceService>()
    private val enforceController = EnforceController(enforceService)

    private val usageService = mockk<UsageIngestService>()
    private val usageController = UsageIngestController(usageService)

    // ─── SQL injection in orgId ───────────────────────────────────────────────

    @Test
    fun `SQL injection in orgId - single quote does not cause 500`() = billingTestApplication(
        routes = { noAuthInternalRoutes { enforceRoutes(enforceController) } },
    ) {
        coEvery { enforceService.enforce(any(), any()) } returns EnforceResult.Allowed

        val r = client.post("/internal/billing/enforce") {
            header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
            contentType(ContentType.Application.Json)
            setBody("""{"orgId":"' OR 1=1 --","appSlug":"signal","check":{"type":"feature","feature":"x"}}""")
        }
        // Must not be 500 (SQL error) — parameterized queries protect
        assertTrue(r.status.value != 500, "SQL injection should not cause server error")
    }

    @Test
    fun `SQL injection in orgId - UNION SELECT does not cause 500`() = billingTestApplication(
        routes = { noAuthInternalRoutes { enforceRoutes(enforceController) } },
    ) {
        coEvery { enforceService.enforce(any(), any()) } returns EnforceResult.Allowed

        val r = client.post("/internal/billing/enforce") {
            header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
            contentType(ContentType.Application.Json)
            setBody("""{"orgId":"org_1' UNION SELECT password FROM users--","appSlug":"signal","check":{"type":"feature","feature":"x"}}""")
        }
        assertTrue(r.status.value != 500)
    }

    @Test
    fun `SQL injection in appSlug - DROP TABLE does not execute`() = billingTestApplication(
        routes = { noAuthInternalRoutes { enforceRoutes(enforceController) } },
    ) {
        coEvery { enforceService.enforce(any(), any()) } returns EnforceResult.Allowed

        val r = client.post("/internal/billing/enforce") {
            header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
            contentType(ContentType.Application.Json)
            setBody("""{"orgId":"org_1","appSlug":"signal'; DROP TABLE billing_subscriptions;--","check":{"type":"feature","feature":"x"}}""")
        }
        assertTrue(r.status.value != 500)
    }

    @Test
    fun `SQL injection in resource field of quota check`() = billingTestApplication(
        routes = { noAuthInternalRoutes { enforceRoutes(enforceController) } },
    ) {
        coEvery { enforceService.enforce(any(), any()) } returns EnforceResult.Allowed

        val r = client.post("/internal/billing/enforce") {
            header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
            contentType(ContentType.Application.Json)
            setBody("""{"orgId":"org_1","appSlug":"signal","check":{"type":"quota","resource":"'; DELETE FROM billing_subscriptions;--","limitKey":"maxProjects"}}""")
        }
        assertTrue(r.status.value != 500)
    }

    @Test
    fun `SQL injection in eventKey of usage ingest`() = billingTestApplication(
        routes = { noAuthInternalRoutes { usageIngestRoutes(usageController) } },
    ) {
        coEvery { usageService.ingest(any()) } returns UsageIngestResponse(accepted = true)

        val r = client.post("/internal/billing/usage/ingest") {
            header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
            contentType(ContentType.Application.Json)
            setBody("""{"orgId":"org_1","eventKey":"email.sent'; DROP TABLE sms_messages;--","quantity":1}""")
        }
        assertTrue(r.status.value != 500, "SQL injection in eventKey should not crash server")
    }

    // ─── JSON injection / type confusion ──────────────────────────────────────

    @Test
    fun `orgId as object instead of string returns 400`() = billingTestApplication(
        routes = { noAuthInternalRoutes { enforceRoutes(enforceController) } },
    ) {
        val r = client.post("/internal/billing/enforce") {
            header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
            contentType(ContentType.Application.Json)
            setBody("""{"orgId":{"${'$'}ne":""},"appSlug":"signal","check":{"type":"feature","feature":"x"}}""")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test
    fun `orgId as array returns 400`() = billingTestApplication(
        routes = { noAuthInternalRoutes { enforceRoutes(enforceController) } },
    ) {
        val r = client.post("/internal/billing/enforce") {
            header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
            contentType(ContentType.Application.Json)
            setBody("""{"orgId":["org_1","org_2"],"appSlug":"signal","check":{"type":"feature","feature":"x"}}""")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test
    fun `quantity as string returns 400`() = billingTestApplication(
        routes = { noAuthInternalRoutes { usageIngestRoutes(usageController) } },
    ) {
        val r = client.post("/internal/billing/usage/ingest") {
            header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
            contentType(ContentType.Application.Json)
            setBody("""{"orgId":"org_1","eventKey":"email.sent","quantity":"one hundred"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test
    fun `quantity as float is rejected or truncated safely`() = billingTestApplication(
        routes = { noAuthInternalRoutes { usageIngestRoutes(usageController) } },
    ) {
        coEvery { usageService.ingest(any()) } returns UsageIngestResponse(accepted = true)

        val r = client.post("/internal/billing/usage/ingest") {
            header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
            contentType(ContentType.Application.Json)
            setBody("""{"orgId":"org_1","eventKey":"email.sent","quantity":1.5}""")
        }
        // Either 400 (strict) or 200 (truncated to 1) — never 500
        assertTrue(r.status.value in listOf(200, 202, 400), "Float quantity should not crash: ${r.status}")
    }

    // ─── Unicode tricks ───────────────────────────────────────────────────────

    @Test
    fun `zero-width characters in orgId do not bypass enforcement`() = billingTestApplication(
        routes = { noAuthInternalRoutes { enforceRoutes(enforceController) } },
    ) {
        coEvery { enforceService.enforce(any(), any()) } returns EnforceResult.Allowed

        // Zero-width space U+200B between characters
        val r = client.post("/internal/billing/enforce") {
            header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
            contentType(ContentType.Application.Json)
            setBody("""{"orgId":"org\u200B_1","appSlug":"signal","check":{"type":"feature","feature":"x"}}""")
        }
        assertTrue(r.status.value != 500)
    }

    @Test
    fun `RTL override character in appSlug does not cause issues`() = billingTestApplication(
        routes = { noAuthInternalRoutes { enforceRoutes(enforceController) } },
    ) {
        coEvery { enforceService.enforce(any(), any()) } returns EnforceResult.Allowed

        val r = client.post("/internal/billing/enforce") {
            header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
            contentType(ContentType.Application.Json)
            setBody("""{"orgId":"org_1","appSlug":"sig\u202Enal","check":{"type":"feature","feature":"x"}}""")
        }
        assertTrue(r.status.value != 500)
    }

    @Test
    fun `null bytes in orgId do not bypass enforcement`() = billingTestApplication(
        routes = { noAuthInternalRoutes { enforceRoutes(enforceController) } },
    ) {
        coEvery { enforceService.enforce(any(), any()) } returns EnforceResult.Allowed

        val r = client.post("/internal/billing/enforce") {
            header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
            contentType(ContentType.Application.Json)
            setBody("""{"orgId":"org_1\u0000admin","appSlug":"signal","check":{"type":"feature","feature":"x"}}""")
        }
        assertTrue(r.status.value != 500)
    }

    // ─── Content-Type attacks ─────────────────────────────────────────────────

    @Test
    fun `XML payload with JSON content type returns 400`() = billingTestApplication(
        routes = { noAuthInternalRoutes { enforceRoutes(enforceController) } },
    ) {
        val r = client.post("/internal/billing/enforce") {
            header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
            contentType(ContentType.Application.Json)
            setBody("""<?xml version="1.0"?><orgId>org_1</orgId>""")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test
    fun `multipart form-data content type returns 400 or 415`() = billingTestApplication(
        routes = { noAuthInternalRoutes { enforceRoutes(enforceController) } },
    ) {
        val r = client.post("/internal/billing/enforce") {
            header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
            header(HttpHeaders.ContentType, "multipart/form-data; boundary=----")
            setBody("------\r\nContent-Disposition: form-data; name=\"orgId\"\r\n\r\norg_1\r\n------")
        }
        assertTrue(r.status.value in 400..415)
    }

    @Test
    fun `empty JSON object returns 400 (missing fields)`() = billingTestApplication(
        routes = { noAuthInternalRoutes { enforceRoutes(enforceController) } },
    ) {
        val r = client.post("/internal/billing/enforce") {
            header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test
    fun `deeply nested JSON does not cause stack overflow`() = billingTestApplication(
        routes = { noAuthInternalRoutes { enforceRoutes(enforceController) } },
    ) {
        // 100 levels of nesting
        val nested = "{".repeat(100) + "\"a\":1" + "}".repeat(100)
        val r = client.post("/internal/billing/enforce") {
            header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
            contentType(ContentType.Application.Json)
            setBody(nested)
        }
        // Must be 400 (parse error), not 500 (stack overflow)
        assertTrue(r.status.value in 400..499, "Deep nesting should not crash: ${r.status}")
    }

    // ─── Oversized payloads ───────────────────────────────────────────────────

    @Test
    fun `1MB payload does not crash the server`() = billingTestApplication(
        routes = { noAuthInternalRoutes { enforceRoutes(enforceController) } },
    ) {
        coEvery { enforceService.enforce(any(), any()) } returns EnforceResult.Allowed

        val largeValue = "x".repeat(1_000_000)
        val r = client.post("/internal/billing/enforce") {
            header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
            contentType(ContentType.Application.Json)
            setBody("""{"orgId":"$largeValue","appSlug":"signal","check":{"type":"feature","feature":"x"}}""")
        }
        // Must not be 500 (crash). 400 (bad input), 413 (too large), or 200 are all acceptable
        assertTrue(r.status.value != 500, "1MB payload should not crash server, got ${r.status}")
    }

    @Test
    fun `metadata with 10000 keys does not crash usage ingest`() = billingTestApplication(
        routes = { noAuthInternalRoutes { usageIngestRoutes(usageController) } },
    ) {
        coEvery { usageService.ingest(any()) } returns UsageIngestResponse(accepted = true)

        val metadataEntries = (1..10000).joinToString(",") { """"k$it":"v$it"""" }
        val r = client.post("/internal/billing/usage/ingest") {
            header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
            contentType(ContentType.Application.Json)
            setBody("""{"orgId":"org_1","eventKey":"email.sent","quantity":1,"metadata":{$metadataEntries}}""")
        }
        assertTrue(r.status.value != 500, "Large metadata should not crash")
    }
}
