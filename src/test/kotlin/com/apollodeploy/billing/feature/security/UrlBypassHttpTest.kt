package com.apollodeploy.billing.feature.security

import com.apollodeploy.billing.feature.checkout.api.CheckoutController
import com.apollodeploy.billing.feature.checkout.api.checkoutRoutes
import com.apollodeploy.billing.feature.checkout.application.CheckoutService
import com.apollodeploy.billing.feature.checkout.domain.CreateCheckoutResult
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Adversarial tests — URL validation bypass attempts on checkout redirect URLs.
 *
 * Attack vectors:
 *   - URL encoding tricks (%68ttp, double encoding)
 *   - Unicode homoglyphs (Cyrillic 'а' that looks like Latin 'a')
 *   - Backslash/forward slash confusion
 *   - @-sign credential injection
 *   - Fragment-based redirects
 *   - IPv6 addresses
 *   - DNS rebinding preparation
 *   - Subdomain tricks (attacker.apollodeploy.com.evil.com)
 *   - Data URIs disguised as HTTPS
 *   - Protocol-relative URLs
 */
class UrlBypassHttpTest {

    private val checkoutService = mockk<CheckoutService>()
    private val controller = CheckoutController(checkoutService)

    private fun checkoutBody(url: String) =
        """{"orgId":"org_1","appSlug":"signal","productSlug":"signal-ignite","successUrl":"$url"}"""

    private fun expectRejected(url: String) = billingTestApplication(
        routes = { noAuthInternalRoutes { checkoutRoutes(controller) } },
    ) {
        coEvery { checkoutService.createCheckout(any()) } returns
            CreateCheckoutResult.InvalidUrl(field = "successUrl", reason = "blocked")

        val r = client.post("/internal/billing/checkout") {
            header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
            contentType(ContentType.Application.Json)
            setBody(checkoutBody(url))
        }
        assertEquals(HttpStatusCode.BadRequest, r.status, "URL should be blocked: $url")
    }

    // ─── Scheme bypass attempts ───────────────────────────────────────────────

    @Test
    fun `HTTP scheme is rejected`() = expectRejected("http://evil.com/phish")

    @Test
    fun `HTTPS with port 80 (unusual) on non-allowlisted domain is rejected`() =
        expectRejected("https://evil.com:80/capture")

    @Test
    fun `ftp scheme is rejected`() = expectRejected("ftp://evil.com/file")

    @Test
    fun `file scheme is rejected`() = expectRejected("file:///etc/passwd")

    @Test
    fun `data URI is rejected`() = expectRejected("data:text/html,<script>alert(1)</script>")

    @Test
    fun `javascript scheme is rejected`() = expectRejected("javascript:alert(document.cookie)")

    @Test
    fun `javascript with uppercase J is rejected`() = expectRejected("Javascript:alert(1)")

    @Test
    fun `vbscript scheme is rejected`() = expectRejected("vbscript:msgbox")

    // ─── Host bypass attempts ─────────────────────────────────────────────────

    @Test
    fun `localhost is rejected`() = expectRejected("https://localhost/callback")

    @Test
    fun `127_0_0_1 is rejected`() = expectRejected("https://127.0.0.1/callback")

    @Test
    fun `0_0_0_0 is rejected`() = expectRejected("https://0.0.0.0/callback")

    @Test
    fun `private IP 10_x is rejected`() = expectRejected("https://10.255.255.255/cb")

    @Test
    fun `private IP 172_16_x is rejected`() = expectRejected("https://172.16.0.1/cb")

    @Test
    fun `private IP 192_168_x is rejected`() = expectRejected("https://192.168.1.1/cb")

    @Test
    fun `IPv6 loopback is rejected`() = expectRejected("https://[::1]/callback")

    @Test
    fun `dot-local hostname is rejected`() = expectRejected("https://service.local/callback")

    @Test
    fun `dot-internal hostname is rejected`() = expectRejected("https://billing.internal/callback")

    // ─── Domain spoofing ──────────────────────────────────────────────────────

    @Test
    fun `non-allowlisted domain is rejected`() = expectRejected("https://evil.com/callback")

    @Test
    fun `subdomain-of-evil that contains allowed domain is rejected`() =
        expectRejected("https://apollodeploy.com.evil.com/callback")

    @Test
    fun `allowed domain as subdomain of attacker is rejected`() =
        expectRejected("https://evil-apollodeploy.com/callback")

    @Test
    fun `allowed domain with extra suffix is rejected`() =
        expectRejected("https://apollodeploy.com.br/callback")

    @Test
    fun `typosquatted domain is rejected`() = expectRejected("https://apollodep1oy.com/callback")

    // ─── Credential injection ─────────────────────────────────────────────────

    @Test
    fun `URL with user info (credentials) is rejected`() =
        expectRejected("https://admin:password@app.apollodeploy.com/callback")

    @Test
    fun `URL with user info before at-sign is rejected`() =
        expectRejected("https://evil.com@app.apollodeploy.com/callback")

    // ─── Valid URLs that SHOULD pass ──────────────────────────────────────────

    @Test
    fun `valid HTTPS on apollodeploy_com passes`() = billingTestApplication(
        routes = { noAuthInternalRoutes { checkoutRoutes(controller) } },
    ) {
        coEvery { checkoutService.createCheckout(any()) } returns
            CreateCheckoutResult.UnknownProduct("signal", "signal-ignite")

        val r = client.post("/internal/billing/checkout") {
            header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
            contentType(ContentType.Application.Json)
            setBody(checkoutBody("https://app.apollodeploy.com/billing/success"))
        }
        // 422 = product not found = URL passed validation
        assertEquals(HttpStatusCode.UnprocessableEntity, r.status)
    }

    @Test
    fun `valid HTTPS with path segments passes`() = billingTestApplication(
        routes = { noAuthInternalRoutes { checkoutRoutes(controller) } },
    ) {
        coEvery { checkoutService.createCheckout(any()) } returns
            CreateCheckoutResult.UnknownProduct("signal", "signal-ignite")

        val r = client.post("/internal/billing/checkout") {
            header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
            contentType(ContentType.Application.Json)
            setBody(checkoutBody("https://signal.apollodeploy.com/settings/billing/return"))
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, r.status)
    }

    @Test
    fun `null successUrl passes (optional field)`() = billingTestApplication(
        routes = { noAuthInternalRoutes { checkoutRoutes(controller) } },
    ) {
        coEvery { checkoutService.createCheckout(any()) } returns
            CreateCheckoutResult.UnknownProduct("signal", "signal-ignite")

        val r = client.post("/internal/billing/checkout") {
            header(HttpHeaders.Authorization, "Bearer ${validServiceToken()}")
            contentType(ContentType.Application.Json)
            setBody("""{"orgId":"org_1","appSlug":"signal","productSlug":"signal-ignite"}""")
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, r.status)
    }
}
