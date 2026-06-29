package com.apollodeploy.billing.feature.webhook.api

import com.apollodeploy.billing.feature.webhook.application.PolarWebhookService
import com.apollodeploy.billing.feature.webhook.domain.PolarWebhookResult
import com.apollodeploy.billing.support.billingTestApplication
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals

class PolarWebhookControllerTest {
    private val polarWebhookService = mockk<PolarWebhookService>()
    private val controller = PolarWebhookController(polarWebhookService)

    private fun signWebhook(
        payload: ByteArray,
        webhookId: String,
        timestamp: String,
        secret: String,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val signedPayload = "$webhookId.$timestamp.".toByteArray(Charsets.UTF_8) + payload
        return "v1," + Base64.getEncoder().encodeToString(mac.doFinal(signedPayload))
    }

    @Test
    fun `POST webhooks polar - Received returns HTTP 200 with received true`() =
        billingTestApplication(
            routes = { polarWebhookRoutes(controller) },
        ) {
            val body = """{"type":"subscription.created"}"""
            val webhookId = "wh_test_123"
            val timestamp = "1700000000"
            val secret = "test-webhook-secret"
            val signature = signWebhook(body.toByteArray(), webhookId, timestamp, secret)

            coEvery { polarWebhookService.receive(any(), any(), any(), any()) } returns PolarWebhookResult.Received

            val response =
                client.post("/webhooks/polar") {
                    contentType(ContentType.Application.Json)
                    header("webhook-id", webhookId)
                    header("webhook-timestamp", timestamp)
                    header("webhook-signature", signature)
                    setBody(body)
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val responseBody = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals(true, responseBody["received"]?.jsonPrimitive?.boolean)
        }

    @Test
    fun `POST webhooks polar - InvalidSignature returns HTTP 401 with error invalid_signature`() =
        billingTestApplication(
            routes = { polarWebhookRoutes(controller) },
        ) {
            val body = """{"type":"subscription.created"}"""
            val webhookId = "wh_test_123"
            val timestamp = "1700000000"
            val signature = signWebhook(body.toByteArray(), webhookId, timestamp, "some-secret")

            coEvery { polarWebhookService.receive(any(), any(), any(), any()) } returns PolarWebhookResult.InvalidSignature

            val response =
                client.post("/webhooks/polar") {
                    contentType(ContentType.Application.Json)
                    header("webhook-id", webhookId)
                    header("webhook-timestamp", timestamp)
                    header("webhook-signature", signature)
                    setBody(body)
                }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            val responseBody = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("invalid_signature", responseBody["error"]?.jsonPrimitive?.content)
        }

    @Test
    fun `POST webhooks polar - InvalidPayload returns HTTP 400 with error invalid_payload`() =
        billingTestApplication(
            routes = { polarWebhookRoutes(controller) },
        ) {
            val body = """{"type":"subscription.created"}"""
            val webhookId = "wh_test_123"
            val timestamp = "1700000000"
            val signature = signWebhook(body.toByteArray(), webhookId, timestamp, "some-secret")

            coEvery { polarWebhookService.receive(any(), any(), any(), any()) } returns PolarWebhookResult.InvalidPayload

            val response =
                client.post("/webhooks/polar") {
                    contentType(ContentType.Application.Json)
                    header("webhook-id", webhookId)
                    header("webhook-timestamp", timestamp)
                    header("webhook-signature", signature)
                    setBody(body)
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val responseBody = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("invalid_payload", responseBody["error"]?.jsonPrimitive?.content)
        }

    @Test
    fun `POST webhooks polar - HandlerError returns HTTP 500 with error handler_error`() =
        billingTestApplication(
            routes = { polarWebhookRoutes(controller) },
        ) {
            val body = """{"type":"subscription.created"}"""
            val webhookId = "wh_test_123"
            val timestamp = "1700000000"
            val signature = signWebhook(body.toByteArray(), webhookId, timestamp, "some-secret")

            coEvery { polarWebhookService.receive(any(), any(), any(), any()) } returns PolarWebhookResult.HandlerError

            val response =
                client.post("/webhooks/polar") {
                    contentType(ContentType.Application.Json)
                    header("webhook-id", webhookId)
                    header("webhook-timestamp", timestamp)
                    header("webhook-signature", signature)
                    setBody(body)
                }

            assertEquals(HttpStatusCode.InternalServerError, response.status)
            val responseBody = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("handler_error", responseBody["error"]?.jsonPrimitive?.content)
        }

    @Test
    fun `POST webhooks polar - no Authorization header required`() =
        billingTestApplication(
            routes = { polarWebhookRoutes(controller) },
        ) {
            val body = """{"type":"subscription.created"}"""
            val webhookId = "wh_test_no_auth"
            val timestamp = "1700000000"
            val signature = signWebhook(body.toByteArray(), webhookId, timestamp, "some-secret")

            coEvery { polarWebhookService.receive(any(), any(), any(), any()) } returns PolarWebhookResult.Received

            // Intentionally no Authorization header — public endpoint
            val response =
                client.post("/webhooks/polar") {
                    contentType(ContentType.Application.Json)
                    header("webhook-id", webhookId)
                    header("webhook-timestamp", timestamp)
                    header("webhook-signature", signature)
                    setBody(body)
                }

            assertEquals(HttpStatusCode.OK, response.status)
        }
}
