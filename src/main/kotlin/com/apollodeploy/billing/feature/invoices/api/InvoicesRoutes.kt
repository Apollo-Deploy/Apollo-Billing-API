package com.apollodeploy.billing.feature.invoices.api

import com.apollodeploy.billing.feature.common.api.BillingApiErrorResponse
import com.apollodeploy.billing.feature.invoices.domain.GenerateInvoiceResponse
import com.apollodeploy.billing.feature.invoices.domain.InvoiceDetailResponse
import com.apollodeploy.billing.feature.invoices.domain.InvoiceMeterUsageResponse
import com.apollodeploy.billing.feature.invoices.domain.PaginatedInvoicesResponse
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.route

/**
 * Invoice endpoints.
 *
 * GET  /internal/billing/invoices                       — paginated list of invoices grouped by app
 * GET  /internal/billing/invoices/{invoiceId}           — single invoice by Polar order ID
 * GET  /internal/billing/invoices/{invoiceId}/meter-usage — meter usage for one invoice
 * POST /internal/billing/invoices/{invoiceId}/invoice   — trigger PDF invoice generation via Polar
 */
fun Route.invoicesRoutes(controller: InvoicesController) {
    route("/internal/billing/invoices") {
        get("/{invoiceId}/meter-usage", {
            operationId = "getInvoiceMeterUsage"
            summary = "Get invoice meter usage"
            description = "Returns the invoice-period usage and current details for each customer meter."
            tags("Invoices")
            protected = true
            securitySchemeNames("serviceToken")
            request {
                pathParameter<String>("invoiceId") {
                    description = "Polar order ID representing the invoice."
                    required = true
                }
                queryParameter<String>("orgId") {
                    description = "Organization ID that owns the invoice."
                    required = true
                }
            }
            response {
                code(HttpStatusCode.OK) {
                    description = "Invoice meter usage returned."
                    body<InvoiceMeterUsageResponse>()
                }
                code(HttpStatusCode.BadRequest) {
                    description = "The required orgId query parameter is missing or blank."
                    body<BillingApiErrorResponse>()
                }
                code(HttpStatusCode.Unauthorized) {
                    description = "Missing, expired, or invalid internal service JWT."
                    body<BillingApiErrorResponse>()
                }
                code(HttpStatusCode.NotFound) {
                    description = "No invoice exists with the given ID."
                    body<BillingApiErrorResponse>()
                }
                code(HttpStatusCode.BadGateway) {
                    description = "Polar was unavailable or returned an unexpected failure."
                    body<BillingApiErrorResponse>()
                }
            }
        }) {
            controller.getInvoiceMeterUsage(call)
        }

        get("/{invoiceId}", {
            operationId = "getInvoice"
            summary = "Get invoice by ID"
            description =
                "Fetches a single invoice (Polar order) by its ID. Returns the table-ready invoice " +
                "fields: app, invoice number, product, amount (total incl. tax/VAT), currency, status, and creation time."
            tags("Invoices")
            protected = true
            securitySchemeNames("serviceToken")
            request {
                pathParameter<String>("invoiceId") {
                    description = "Polar order ID representing the invoice."
                    required = true
                }
                queryParameter<String>("orgId") {
                    description = "Organization ID that owns the invoice."
                    required = true
                }
            }
            response {
                code(HttpStatusCode.OK) {
                    description = "Invoice found and returned."
                    body<InvoiceDetailResponse> {
                        description = "Table-ready invoice detail."
                    }
                }
                code(HttpStatusCode.BadRequest) {
                    description = "Missing invoiceId path parameter or required orgId query parameter."
                    body<BillingApiErrorResponse>()
                }
                code(HttpStatusCode.Unauthorized) {
                    description = "Missing, expired, or invalid internal service JWT."
                    body<BillingApiErrorResponse>()
                }
                code(HttpStatusCode.NotFound) {
                    description = "No invoice exists with the given ID."
                    body<BillingApiErrorResponse>()
                }
                code(HttpStatusCode.BadGateway) {
                    description = "Polar was unavailable or returned an unexpected failure."
                    body<BillingApiErrorResponse>()
                }
            }
        }) {
            controller.getInvoice(call)
        }

        get({
            operationId = "listInvoices"
            summary = "List invoices grouped by app"
            description =
                "Returns a paginated list of invoices (Polar orders) for an organization, grouped by billing app slug."
            tags("Invoices")
            protected = true
            securitySchemeNames("serviceToken")
            request {
                queryParameter<String>("orgId") {
                    description = "Organization ID to retrieve invoices for."
                    required = true
                }
                queryParameter<Int>("page") {
                    description = "One-based page number. Defaults to 1."
                    required = false
                }
                queryParameter<Int>("limit") {
                    description = "Page size from 1 to 100. Defaults to 20."
                    required = false
                }
            }
            response {
                code(HttpStatusCode.OK) {
                    description = "Invoices grouped by app slug with pagination metadata."
                    body<PaginatedInvoicesResponse> {
                        description = "Map of app slugs to invoice items, plus pagination info."
                    }
                }
                code(HttpStatusCode.BadRequest) {
                    description = "The required `orgId` query parameter is missing or blank."
                    body<BillingApiErrorResponse>()
                }
                code(HttpStatusCode.Unauthorized) {
                    description = "Missing, expired, or invalid internal service JWT."
                    body<BillingApiErrorResponse>()
                }
                code(HttpStatusCode.BadGateway) {
                    description = "Polar was unavailable or returned an unexpected failure."
                    body<BillingApiErrorResponse>()
                }
            }
        }) {
            controller.listInvoices(call)
        }

        post("/{invoiceId}/invoice", {
            operationId = "generateInvoice"
            summary = "Generate invoice PDF for an order"
            description =
                "Triggers generation of a PDF invoice for a Polar order. Returns a pre-signed " +
                "`downloadUrl` when the PDF is ready (typically within a few seconds). " +
                "Once generated, the invoice is permanent and cannot be modified."
            tags("Invoices")
            protected = true
            securitySchemeNames("serviceToken")
            request {
                pathParameter<String>("invoiceId") {
                    description = "Polar order ID to generate the invoice for."
                    required = true
                }
                queryParameter<String>("orgId") {
                    description = "Organization ID that owns the order."
                    required = true
                }
            }
            response {
                code(HttpStatusCode.OK) {
                    description = "Invoice generated; includes a pre-signed PDF download URL."
                    body<GenerateInvoiceResponse>()
                }
                code(HttpStatusCode.Accepted) {
                    description = "Invoice generation triggered; PDF URL not ready yet — retry shortly."
                    body<GenerateInvoiceResponse>()
                }
                code(HttpStatusCode.BadRequest) {
                    description = "Missing invoiceId path parameter or orgId query parameter."
                    body<BillingApiErrorResponse>()
                }
                code(HttpStatusCode.Unauthorized) {
                    description = "Missing, expired, or invalid internal service JWT."
                    body<BillingApiErrorResponse>()
                }
                code(HttpStatusCode.NotFound) {
                    description = "No order exists with the given ID."
                    body<BillingApiErrorResponse>()
                }
                code(HttpStatusCode.BadGateway) {
                    description = "Polar was unavailable or returned an unexpected failure."
                    body<BillingApiErrorResponse>()
                }
            }
        }) {
            controller.generateInvoice(call)
        }
    }
}
