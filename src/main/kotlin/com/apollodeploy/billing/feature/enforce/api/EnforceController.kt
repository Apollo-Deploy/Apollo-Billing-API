package com.apollodeploy.billing.feature.enforce.api

import com.apollodeploy.billing.feature.enforce.application.EnforceService
import com.apollodeploy.billing.feature.enforce.domain.EnforceRequest
import com.apollodeploy.billing.feature.enforce.domain.EnforceResponse
import com.apollodeploy.billing.feature.enforce.domain.EnforceResult
import com.apollodeploy.billing.infrastructure.iam.authenticatedClientId
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond

class EnforceController(
    private val enforceService: EnforceService,
) {
    suspend fun enforce(call: ApplicationCall) {
        val req = call.receive<EnforceRequest>()
        val callerClientId = call.authenticatedClientId()

        when (val result = enforceService.enforce(req, callerClientId = callerClientId)) {
            EnforceResult.Allowed -> call.respond(HttpStatusCode.OK, EnforceResponse(allowed = true))
            is EnforceResult.Rejected -> call.respond(HttpStatusCode.fromValue(result.statusCode), result.error)
        }
    }
}
