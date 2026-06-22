package com.apollodeploy.billing.infrastructure.polar

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PolarWebhookVerifierTest {
    @Test
    fun verifiesStandardWebhookSignature() {
        val payload = """{"type":"customer.state_changed","data":{}}""".toByteArray()
        val webhookId = "msg_123"
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val secret = "test-webhook-secret"
        val signature = sign(payload, webhookId, timestamp, secret)

        assertTrue(
            PolarWebhookVerifier.verify(
                payload = payload,
                webhookId = webhookId,
                webhookTimestamp = timestamp,
                signatureHeader = "v1,$signature",
                secret = secret,
            ),
        )

        assertFalse(
            PolarWebhookVerifier.verify(
                payload = """{"type":"order.paid","data":{}}""".toByteArray(),
                webhookId = webhookId,
                webhookTimestamp = timestamp,
                signatureHeader = "v1,$signature",
                secret = secret,
            ),
        )
    }

    private fun sign(
        payload: ByteArray,
        webhookId: String,
        timestamp: String,
        secret: String,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val signedPayload = "$webhookId.$timestamp.".toByteArray(Charsets.UTF_8) + payload
        return Base64.getEncoder().encodeToString(mac.doFinal(signedPayload))
    }
}
