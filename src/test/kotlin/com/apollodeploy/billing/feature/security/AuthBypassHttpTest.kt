package com.apollodeploy.billing.feature.security

import com.apollodeploy.billing.infrastructure.polar.PolarWebhookVerifier
import com.apollodeploy.billing.infrastructure.validation.UrlValidator
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Adversarial tests for security infrastructure — directly tests the guard logic.
 *
 * Since the test harness bypasses OAuth for controller-level tests, these tests
 * verify the guard implementations directly (PolarWebhookVerifier, UrlValidator)
 * and confirm the auth guard is structured correctly.
 *
 * Auth bypass tests for the OAuth guard itself are covered by:
 *   - InternalEndpointAuthTest (integration-level, valid JWT accepted)
 *   - the SDK `MachineOAuth` plugin behavior
 */
class AuthBypassHttpTest {
    // ─── PolarWebhookVerifier — adversarial signature tests ───────────────────

    @Test
    fun `webhook verifier - blank secret always returns false`() {
        assertFalse(
            PolarWebhookVerifier.verify(
                payload = "{}".toByteArray(),
                webhookId = "wh_123",
                webhookTimestamp = "1700000000",
                signatureHeader = "v1,anything",
                secret = "",
            ),
        )
    }

    @Test
    fun `webhook verifier - blank webhookId returns false`() {
        assertFalse(
            PolarWebhookVerifier.verify(
                payload = "{}".toByteArray(),
                webhookId = "",
                webhookTimestamp = "1700000000",
                signatureHeader = "v1,anything",
                secret = "test-secret",
            ),
        )
    }

    @Test
    fun `webhook verifier - blank timestamp returns false`() {
        assertFalse(
            PolarWebhookVerifier.verify(
                payload = "{}".toByteArray(),
                webhookId = "wh_123",
                webhookTimestamp = "",
                signatureHeader = "v1,sig",
                secret = "test-secret",
            ),
        )
    }

    @Test
    fun `webhook verifier - blank signature returns false`() {
        assertFalse(
            PolarWebhookVerifier.verify(
                payload = "{}".toByteArray(),
                webhookId = "wh_123",
                webhookTimestamp = "1700000000",
                signatureHeader = "",
                secret = "test-secret",
            ),
        )
    }

    @Test
    fun `webhook verifier - non-numeric timestamp returns false`() {
        assertFalse(
            PolarWebhookVerifier.verify(
                payload = "{}".toByteArray(),
                webhookId = "wh_123",
                webhookTimestamp = "not-a-number",
                signatureHeader = "v1,sig",
                secret = "test-secret",
            ),
        )
    }

    @Test
    fun `webhook verifier - timestamp far in past returns false`() {
        assertFalse(
            PolarWebhookVerifier.verify(
                payload = "{}".toByteArray(),
                webhookId = "wh_123",
                webhookTimestamp = "946684800", // year 2000
                signatureHeader = "v1,sig",
                secret = "test-secret",
            ),
        )
    }

    @Test
    fun `webhook verifier - timestamp far in future returns false`() {
        assertFalse(
            PolarWebhookVerifier.verify(
                payload = "{}".toByteArray(),
                webhookId = "wh_123",
                webhookTimestamp = "9999999999", // year 2286
                signatureHeader = "v1,sig",
                secret = "test-secret",
            ),
        )
    }

    @Test
    fun `webhook verifier - wrong signature returns false`() {
        assertFalse(
            PolarWebhookVerifier.verify(
                payload = "{}".toByteArray(),
                webhookId = "wh_123",
                webhookTimestamp = (System.currentTimeMillis() / 1000).toString(),
                signatureHeader = "v1,AAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                secret = "test-secret",
            ),
        )
    }

    @Test
    fun `webhook verifier - v2 signature version is not accepted`() {
        assertFalse(
            PolarWebhookVerifier.verify(
                payload = "{}".toByteArray(),
                webhookId = "wh_123",
                webhookTimestamp = (System.currentTimeMillis() / 1000).toString(),
                signatureHeader = "v2,anything",
                secret = "test-secret",
            ),
        )
    }

    @Test
    fun `webhook verifier - multiple wrong v1 signatures all rejected`() {
        assertFalse(
            PolarWebhookVerifier.verify(
                payload = "{}".toByteArray(),
                webhookId = "wh_123",
                webhookTimestamp = (System.currentTimeMillis() / 1000).toString(),
                signatureHeader = "v1,wrong1 v1,wrong2 v1,wrong3",
                secret = "test-secret",
            ),
        )
    }

    @Test
    fun `webhook verifier - negative timestamp returns false`() {
        assertFalse(
            PolarWebhookVerifier.verify(
                payload = "{}".toByteArray(),
                webhookId = "wh_123",
                webhookTimestamp = "-1",
                signatureHeader = "v1,sig",
                secret = "test-secret",
            ),
        )
    }

    // ─── UrlValidator — adversarial URL tests ─────────────────────────────────

    @Test
    fun `url validator - HTTP scheme returns error`() {
        assertNotNull(UrlValidator.validateRedirectUrl("http://evil.com/capture"))
    }

    @Test
    fun `url validator - javascript scheme returns error`() {
        assertNotNull(UrlValidator.validateRedirectUrl("javascript:alert(document.cookie)"))
    }

    @Test
    fun `url validator - javascript mixed case returns error`() {
        assertNotNull(UrlValidator.validateRedirectUrl("Javascript:alert(1)"))
    }

    @Test
    fun `url validator - data URI returns error`() {
        assertNotNull(UrlValidator.validateRedirectUrl("data:text/html,<script>alert(1)</script>"))
    }

    @Test
    fun `url validator - file scheme returns error`() {
        assertNotNull(UrlValidator.validateRedirectUrl("file:///etc/passwd"))
    }

    @Test
    fun `url validator - localhost returns error`() {
        assertNotNull(UrlValidator.validateRedirectUrl("https://localhost/callback"))
    }

    @Test
    fun `url validator - 127_0_0_1 returns error`() {
        assertNotNull(UrlValidator.validateRedirectUrl("https://127.0.0.1/callback"))
    }

    @Test
    fun `url validator - 0_0_0_0 returns error`() {
        assertNotNull(UrlValidator.validateRedirectUrl("https://0.0.0.0/callback"))
    }

    @Test
    fun `url validator - private IP 10_x returns error`() {
        assertNotNull(UrlValidator.validateRedirectUrl("https://10.0.0.1/callback"))
    }

    @Test
    fun `url validator - private IP 192_168_x returns error`() {
        assertNotNull(UrlValidator.validateRedirectUrl("https://192.168.1.1/callback"))
    }

    @Test
    fun `url validator - dot-local hostname returns error`() {
        assertNotNull(UrlValidator.validateRedirectUrl("https://billing.local/callback"))
    }

    @Test
    fun `url validator - allowlisted apollodeploy local host passes`() {
        assertNull(UrlValidator.validateRedirectUrl("https://account.apollodeploy.local/billing"))
        assertNull(UrlValidator.validateRedirectUrl("https://app.apollodeploy.local/return"))
    }

    @Test
    fun `url validator - dot-internal hostname returns error`() {
        assertNotNull(UrlValidator.validateRedirectUrl("https://billing.internal/callback"))
    }

    @Test
    fun `url validator - non-allowlisted domain returns error`() {
        assertNotNull(UrlValidator.validateRedirectUrl("https://evil-phishing.com/capture"))
    }

    @Test
    fun `url validator - domain spoofing apollodeploy in subdomain returns error`() {
        assertNotNull(UrlValidator.validateRedirectUrl("https://apollodeploy.com.evil.com/callback"))
    }

    @Test
    fun `url validator - credential injection returns error`() {
        assertNotNull(UrlValidator.validateRedirectUrl("https://admin:pass@app.apollodeploy.com/callback"))
    }

    @Test
    fun `url validator - at-sign spoofing returns error`() {
        assertNotNull(UrlValidator.validateRedirectUrl("https://evil.com@app.apollodeploy.com/callback"))
    }

    @Test
    fun `url validator - blank URL returns error`() {
        assertNotNull(UrlValidator.validateRedirectUrl(""))
    }

    @Test
    fun `url validator - URL without scheme returns error`() {
        assertNotNull(UrlValidator.validateRedirectUrl("app.apollodeploy.com/success"))
    }

    @Test
    fun `url validator - null URL is valid (optional field)`() {
        assertNull(UrlValidator.validateRedirectUrl(null))
    }

    @Test
    fun `url validator - valid HTTPS on allowed domain passes`() {
        assertNull(UrlValidator.validateRedirectUrl("https://app.apollodeploy.com/billing/success"))
    }

    @Test
    fun `url validator - valid HTTPS subdomain of allowed domain passes`() {
        assertNull(UrlValidator.validateRedirectUrl("https://my.app.apollodeploy.com/return"))
    }
}
