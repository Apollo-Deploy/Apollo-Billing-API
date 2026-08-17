package com.apollodeploy.billing.feature.invoices.api

import com.apollodeploy.billing.feature.invoices.application.InvoicesService
import com.apollodeploy.billing.feature.invoices.domain.GenerateInvoiceResponse
import com.apollodeploy.billing.feature.invoices.domain.GenerateInvoiceResult
import com.apollodeploy.billing.feature.invoices.domain.GetInvoiceMeterUsageResult
import com.apollodeploy.billing.feature.invoices.domain.GetInvoiceResult
import com.apollodeploy.billing.feature.invoices.domain.ListInvoicesResult
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

class InvoicesController(
    private val invoicesService: InvoicesService,
) {
    suspend fun generateInvoice(call: ApplicationCall) {
        val invoiceId =
            call.parameters["invoiceId"]
                ?: return call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("code" to "billing.invalid_request", "message" to "Missing invoiceId path parameter"),
                )
        val orgId =
            call.request.queryParameters["orgId"]?.takeIf { it.isNotBlank() }
                ?: return call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("code" to "billing.invalid_request", "message" to "Missing required query parameter: orgId"),
                )

        when (val result = invoicesService.generateInvoice(orgId, invoiceId)) {
            is GenerateInvoiceResult.Generated ->
                call.respond(
                    HttpStatusCode.OK,
                    GenerateInvoiceResponse(
                        message = "Invoice generated",
                        downloadUrl = result.downloadUrl,
                    ),
                )
            GenerateInvoiceResult.Pending ->
                call.respond(
                    HttpStatusCode.Accepted,
                    GenerateInvoiceResponse(
                        message = "Invoice generation in progress",
                        downloadUrl = null,
                    ),
                )
            is GenerateInvoiceResult.NotFound ->
                call.respond(
                    HttpStatusCode.NotFound,
                    mapOf("code" to "billing.invoice_not_found", "message" to "Invoice not found: $invoiceId"),
                )
            GenerateInvoiceResult.PolarUnavailable ->
                call.respond(
                    HttpStatusCode.BadGateway,
                    mapOf("code" to "billing.provider_unavailable", "message" to "Billing provider unavailable"),
                )
        }
    }

    suspend fun getInvoice(call: ApplicationCall) {
        val invoiceId =
            call.parameters["invoiceId"]
                ?: return call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("code" to "billing.invalid_request", "message" to "Missing invoiceId path parameter"),
                )
        val orgId =
            call.request.queryParameters["orgId"]?.takeIf { it.isNotBlank() }
                ?: return call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("code" to "billing.invalid_request", "message" to "Missing required query parameter: orgId"),
                )

        when (val result = invoicesService.getInvoice(orgId, invoiceId)) {
            is GetInvoiceResult.Found -> call.respond(HttpStatusCode.OK, result.response)
            is GetInvoiceResult.NotFound ->
                call.respond(
                    HttpStatusCode.NotFound,
                    mapOf("code" to "billing.invoice_not_found", "message" to "Invoice not found: ${result.invoiceId}"),
                )
            GetInvoiceResult.PolarUnavailable ->
                call.respond(
                    HttpStatusCode.BadGateway,
                    mapOf("code" to "billing.provider_unavailable", "message" to "Billing provider unavailable"),
                )
        }
    }

    suspend fun getInvoiceMeterUsage(call: ApplicationCall) {
        val invoiceId =
            call.parameters["invoiceId"]
                ?: return call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("code" to "billing.invalid_request", "message" to "Missing invoiceId path parameter"),
                )
        val orgId =
            call.request.queryParameters["orgId"]?.takeIf { it.isNotBlank() }
                ?: return call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("code" to "billing.invalid_request", "message" to "Missing required query parameter: orgId"),
                )

        when (val result = invoicesService.getInvoiceMeterUsage(orgId, invoiceId)) {
            is GetInvoiceMeterUsageResult.Found -> call.respond(HttpStatusCode.OK, result.response)
            is GetInvoiceMeterUsageResult.NotFound ->
                call.respond(
                    HttpStatusCode.NotFound,
                    mapOf("code" to "billing.invoice_not_found", "message" to "Invoice not found: ${result.invoiceId}"),
                )
            GetInvoiceMeterUsageResult.PolarUnavailable ->
                call.respond(
                    HttpStatusCode.BadGateway,
                    mapOf("code" to "billing.provider_unavailable", "message" to "Billing provider unavailable"),
                )
        }
    }

    suspend fun listInvoices(call: ApplicationCall) {
        val orgId =
            call.request.queryParameters["orgId"]?.takeIf { it.isNotBlank() }
                ?: return call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("code" to "billing.invalid_request", "message" to "Missing required query parameter: orgId"),
                )
        val page =
            call.request.queryParameters["page"]
                ?.toIntOrNull()
                ?.coerceAtLeast(1) ?: 1
        val limit =
            call.request.queryParameters["limit"]
                ?.toIntOrNull()
                ?.coerceIn(1, 100) ?: 20

        when (val result = invoicesService.listInvoices(orgId, page, limit)) {
            is ListInvoicesResult.Found -> call.respond(HttpStatusCode.OK, result.response)
            ListInvoicesResult.PolarUnavailable ->
                call.respond(
                    HttpStatusCode.BadGateway,
                    mapOf("code" to "billing.provider_unavailable", "message" to "Billing provider unavailable"),
                )
        }
    }
}
