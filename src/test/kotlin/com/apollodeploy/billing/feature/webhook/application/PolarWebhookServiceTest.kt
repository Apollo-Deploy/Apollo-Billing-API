package com.apollodeploy.billing.feature.webhook.application

import com.apollodeploy.billing.feature.webhook.domain.PolarWebhookResult
import com.apollodeploy.billing.feature.webhook.infrastructure.persistence.PolarWebhookRepo
import com.apollodeploy.billing.infrastructure.polar.PolarWebhookVerifier
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.Runs
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs

class PolarWebhookServiceTest {

    private val repo = mockk<PolarWebhookRepo>()
    private val service = PolarWebhookService(repo)

    private val id = "wh_test"
    private val ts = "1234567890"
    private val sig = "v1,dummy"
    private val validJson = """{"type":"subscription.created","data":{}}""".toByteArray()

    @BeforeTest
    fun setup() {
        mockkObject(PolarWebhookVerifier)
    }

    @AfterTest
    fun teardown() {
        unmockkObject(PolarWebhookVerifier)
    }

    @Test
    fun `invalid signature returns InvalidSignature without calling repo`() = runBlocking {
        every { PolarWebhookVerifier.verify(any(), any(), any(), any(), any()) } returns false

        val result = service.receive(rawBody = validJson, webhookId = id, webhookTimestamp = ts, signature = sig)

        assertIs<PolarWebhookResult.InvalidSignature>(result)
        coVerify(exactly = 0) { repo.handle(any()) }
    }

    @Test
    fun `valid signature but malformed JSON returns InvalidPayload without calling repo`() = runBlocking {
        every { PolarWebhookVerifier.verify(any(), any(), any(), any(), any()) } returns true

        val result = service.receive(rawBody = "not-json".toByteArray(), webhookId = id, webhookTimestamp = ts, signature = sig)

        assertIs<PolarWebhookResult.InvalidPayload>(result)
        coVerify(exactly = 0) { repo.handle(any()) }
    }

    @Test
    fun `valid signature, valid JSON, repo throws, returns HandlerError`() {
        runBlocking {
            every { PolarWebhookVerifier.verify(any(), any(), any(), any(), any()) } returns true
            coEvery { repo.handle(any()) } throws RuntimeException("handler failed")

            val result = service.receive(rawBody = validJson, webhookId = id, webhookTimestamp = ts, signature = sig)

            assertIs<PolarWebhookResult.HandlerError>(result)
        }
    }

    @Test
    fun `valid signature, valid JSON, repo completes, returns Received`() {
        runBlocking {
            every { PolarWebhookVerifier.verify(any(), any(), any(), any(), any()) } returns true
            coEvery { repo.handle(any()) } just Runs

            val result = service.receive(rawBody = validJson, webhookId = id, webhookTimestamp = ts, signature = sig)

            assertIs<PolarWebhookResult.Received>(result)
        }
    }
}
