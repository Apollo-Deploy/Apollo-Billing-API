package com.apollodeploy.billing.feature.usage.api

import com.apollodeploy.billing.feature.usage.application.UsageIngestService
import com.apollodeploy.billing.feature.usage.domain.UsageIngestRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond

class UsageIngestController(
    private val usageIngestService: UsageIngestService,
) {
    suspend fun ingest(call: ApplicationCall) {
        val req = call.receive<UsageIngestRequest>()
        val response = usageIngestService.ingest(req)
        val status = if (response.accepted) HttpStatusCode.OK else HttpStatusCode.Accepted
        call.respond(status, response)
    }
}
