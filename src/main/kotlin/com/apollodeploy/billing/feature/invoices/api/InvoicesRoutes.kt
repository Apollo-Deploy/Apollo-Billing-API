package com.apollodeploy.billing.feature.invoices.api

import com.apollodeploy.billing.feature.common.api.BillingApiErrorResponse
import com.apollodeploy.billing.feature.invoices.domain.GenerateInvoiceAcceptedResponse
import com.apollodeploy.billing.feature.invoices.domain.InvoiceDetailResponse
import com.apollodeploy.billing.feature.invoices.domain.PaginatedInvoicesResponse
import com.apollodeploy.tesseract.sdk
import com.apollodeploy.tesseract.sdkDomain
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
 * POST /internal/billing/invoices/{invoiceId}/invoice   — trigger PDF invoice generation via Polar
 */
fun Route.invoicesRoutes(controller: InvoicesController) {
    sdkDomain("/internal/billing/invoices", "billingInvoices", stability = "internal")

    route("/internal/billing/invoices") {
        get("/{invoiceId}", {
            operationId = "getInvoice"
            summary = "Get invoice by ID"
            description =
                "Fetches a single invoice (Polar order) by its ID. Returns the full invoice detail " +
                "including the resolved app slug, product information, amount, and status."
            tags("Invoices")
            protected = true
            securitySchemeNames("serviceToken")
            request {
                pathParameter<String>("invoiceId") {
                    description = "Polar order ID representing the invoice."
                    required = true
                }
            }
            response {
                code(HttpStatusCode.OK) {
                    description = "Invoice found and returned."
                    body<InvoiceDetailResponse> {
                        description = "Full invoice detail."
                    }
                }
                code(HttpStatusCode.BadRequest) {
                    description = "Missing invoiceId path parameter."
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
        }.sdk {
            operationId = "getInvoice"
            methodName = "getInvoice"
            internal = true
            response<InvoiceDetailResponse>()
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
        }.sdk {
            operationId = "listInvoices"
            methodName = "listInvoices"
            internal = true
            queryParam("orgId", required = true, description = "Organization ID to retrieve invoices for.")
            queryParam("page", type = "integer", description = "One-based page number.")
            queryParam("limit", type = "integer", description = "Page size from 1 to 100.")
            response<PaginatedInvoicesResponse>()
        }

        post("/{invoiceId}/invoice", {
            operationId = "generateInvoice"
            summary = "Generate invoice PDF for an order"
            description =
                "Triggers generation of a PDF invoice for a Polar order via the Customer Portal API. " +
                "Once generated, the invoice is permanent and cannot be modified. " +
                "Ensure billing details (name and address) are correct before calling this endpoint."
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
                code(HttpStatusCode.Accepted) {
                    description = "Invoice generation has been triggered successfully."
                    body<GenerateInvoiceAcceptedResponse>()
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
        }.sdk {
            operationId = "generateInvoice"
            methodName = "generateInvoice"
            internal = true
            queryParam("orgId", required = true, description = "Organization ID that owns the order.")
            response<GenerateInvoiceAcceptedResponse>()
        }
    }
}
