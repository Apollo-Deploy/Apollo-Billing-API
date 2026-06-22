package com.apollodeploy.billing.infrastructure.polar

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Apollo Billing — Polar webhook signature verifier.
 *
 * Polar webhooks use the Standard Webhooks signing format:
 *   webhook-id
 *   webhook-timestamp
 *   webhook-signature: v1,<base64-hmac>
 *
 * Security invariants:
 *   - Uses constant-time comparison to prevent timing attacks.
 *   - The raw secret MUST NEVER be logged.
 */
object PolarWebhookVerifier {
    private const val DEFAULT_TOLERANCE_SECONDS = 5 * 60L

    /**
     * Returns true if [signatureHeader] matches the Standard Webhooks HMAC-SHA256
     * signature for [payload] using [secret]. Always returns false on blank inputs.
     */
    fun verify(
        payload: ByteArray,
        webhookId: String,
        webhookTimestamp: String,
        signatureHeader: String,
        secret: String,
        toleranceSeconds: Long = DEFAULT_TOLERANCE_SECONDS,
    ): Boolean {
        if (
            secret.isBlank() ||
            webhookId.isBlank() ||
            webhookTimestamp.isBlank() ||
            signatureHeader.isBlank()
        ) {
            return false
        }
        if (!isTimestampWithinTolerance(webhookTimestamp, toleranceSeconds)) return false

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val signedPayload = "$webhookId.$webhookTimestamp.".toByteArray(Charsets.UTF_8) + payload
        val expected = Base64.getEncoder().encodeToString(mac.doFinal(signedPayload))

        return extractV1Signatures(signatureHeader).any { actual ->
            MessageDigest.isEqual(
                expected.toByteArray(Charsets.UTF_8),
                actual.toByteArray(Charsets.UTF_8),
            )
        }
    }

    private fun extractV1Signatures(signatureHeader: String): List<String> =
        signatureHeader
            .split(" ")
            .mapNotNull { part ->
                val pieces = part.trim().split(",", limit = 2)
                if (pieces.size == 2 && pieces[0] == "v1") pieces[1] else null
            }

    private fun isTimestampWithinTolerance(
        webhookTimestamp: String,
        toleranceSeconds: Long,
    ): Boolean {
        val timestampSeconds = webhookTimestamp.toLongOrNull() ?: return false
        val nowSeconds = System.currentTimeMillis() / 1000
        return kotlin.math.abs(nowSeconds - timestampSeconds) <= toleranceSeconds
    }
}
