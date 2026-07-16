package com.apollodeploy.billing.feature.customer.api

import com.apollodeploy.billing.feature.customer.application.CustomerBillingService
import com.apollodeploy.billing.feature.customer.domain.CustomerBillingResult
import com.apollodeploy.billing.feature.customer.domain.OpenBillingPortalRequest
import com.apollodeploy.billing.feature.customer.domain.ProvisionCustomerRequest
import com.apollodeploy.billing.feature.customer.domain.UpdateCustomerBillingInfoRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond

class CustomerBillingController(
    private val customerBillingService: CustomerBillingService,
) {
    suspend fun updateBillingInfo(call: ApplicationCall) {
        val req = call.receive<UpdateCustomerBillingInfoRequest>()
        call.respondCustomerResult(customerBillingService.updateBillingInfo(req))
    }

    suspend fun listPaymentMethods(call: ApplicationCall) {
        val orgId = call.request.queryParameters["orgId"]
        val memberId = call.request.queryParameters["memberId"]
        val page =
            call.request.queryParameters["page"]
                ?.toIntOrNull()
                ?.coerceAtLeast(1) ?: 1
        val limit =
            call.request.queryParameters["limit"]
                ?.toIntOrNull()
                ?.coerceIn(1, 100) ?: 10

        call.respondCustomerResult(customerBillingService.listPaymentMethods(orgId, page, limit, memberId))
    }

    suspend fun deletePaymentMethod(call: ApplicationCall) {
        val orgId = call.request.queryParameters["orgId"]
        val memberId = call.request.queryParameters["memberId"]
        val paymentMethodId = call.parameters["paymentMethodId"]

        when (val result = customerBillingService.deletePaymentMethod(orgId, paymentMethodId, memberId)) {
            is CustomerBillingResult.Success -> call.respond(HttpStatusCode.NoContent)
            is CustomerBillingResult.InvalidRequest ->
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("code" to "billing.invalid_request", "message" to result.message),
                )
            is CustomerBillingResult.PolarFailure -> call.respondPolarFailure(result)
        }
    }

    suspend fun openBillingPortal(call: ApplicationCall) {
        val req = call.receive<OpenBillingPortalRequest>()
        call.respondCustomerResult(customerBillingService.openBillingPortal(req))
    }

    suspend fun provisionCustomer(call: ApplicationCall) {
        val req = call.receive<ProvisionCustomerRequest>()
        when (val result = customerBillingService.provisionCustomer(req)) {
            is CustomerBillingResult.Success -> call.respond(HttpStatusCode.Created, result.value as Any)
            is CustomerBillingResult.InvalidRequest ->
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("code" to "billing.invalid_request", "message" to result.message),
                )
            is CustomerBillingResult.PolarFailure -> call.respondPolarFailure(result)
        }
    }
}

private suspend fun ApplicationCall.respondCustomerResult(result: CustomerBillingResult<*>) {
    when (result) {
        is CustomerBillingResult.Success -> respond(HttpStatusCode.OK, result.value as Any)
        is CustomerBillingResult.InvalidRequest ->
            respond(
                HttpStatusCode.BadRequest,
                mapOf("code" to "billing.invalid_request", "message" to result.message),
            )
        is CustomerBillingResult.PolarFailure -> respondPolarFailure(result)
    }
}

private suspend fun ApplicationCall.respondPolarFailure(result: CustomerBillingResult.PolarFailure) {
    val status =
        when (result.statusCode) {
            400 -> HttpStatusCode.BadRequest
            404 -> HttpStatusCode.NotFound
            409 -> HttpStatusCode.Conflict
            422 -> HttpStatusCode.UnprocessableEntity
            else -> HttpStatusCode.BadGateway
        }
    respond(
        status,
        mapOf(
            "code" to result.fallbackCode,
            "message" to "Billing provider request failed",
            "status" to (result.statusCode?.toString() ?: "unavailable"),
        ),
    )
}
