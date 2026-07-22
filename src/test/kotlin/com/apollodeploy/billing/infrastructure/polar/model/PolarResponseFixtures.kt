package com.apollodeploy.billing.infrastructure.polar.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Loads Polar API response fixtures from `src/test/resources/polar/responses/`
 * and decodes them against the production wire models in this package.
 */
object PolarResponseFixtures {
    val json =
        Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

    inline fun <reified T> decodeResponse(fileName: String): T {
        val resourcePath = "/polar/responses/$fileName"
        val text =
            checkNotNull(javaClass.getResourceAsStream(resourcePath)) {
                "Missing Polar response fixture: $resourcePath"
            }.bufferedReader().readText()
        return json.decodeFromString(text)
    }

    inline fun <reified T> decodeWebhookPayload(
        webhookFileName: String,
        crossinline extract: (PolarWebhookEvent) -> kotlinx.serialization.json.JsonElement,
    ): T {
        val event = decodeResponse<PolarWebhookEvent>(webhookFileName)
        return json.decodeFromJsonElement(extract(event))
    }
}
