package com.apollodeploy.billing.feature.customer.api

import com.apollodeploy.billing.feature.common.api.BillingApiErrorResponse
import com.apollodeploy.billing.feature.customer.domain.ListCustomerPaymentMethodsResponse
import com.apollodeploy.billing.feature.customer.domain.UpdateCustomerBillingInfoRequest
import com.apollodeploy.billing.feature.customer.domain.UpdateCustomerBillingInfoResponse
import com.apollodeploy.tesseract.sdk
import com.apollodeploy.tesseract.sdkDomain
import io.github.smiley4.ktoropenapi.delete
import io.github.smiley4.ktoropenapi.get
import io.github.smiley4.ktoropenapi.patch
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.route

/**
 * Internal customer billing profile APIs.
 *
 * These routes are server-to-server only. Product backends must authorize the
 * user against the org before calling them.
 */
fun Route.customerBillingRoutes(controller: CustomerBillingController) {
    sdkDomain("/internal/billing/customer", "billingCustomer", stability = "internal")

    route("/internal/billing/customer") {
        patch("/billing-info", {
            operationId = "updateCustomerBillingInfo"
            summary = "Update customer billing profile"
            description =
                "Updates customer billing details in Polar for an organization, including email, billing name, " +
                "billing address, tax ID, and default payment method."
            tags("Customer Billing")
            protected = true
            securitySchemeNames("serviceToken")
            request {
                body<UpdateCustomerBillingInfoRequest> {
                    description = "The organization and one or more billing profile fields to update in Polar."
                    required = true
                }
            }
            response {
                code(HttpStatusCode.OK) {
                    description = "Customer billing profile updated successfully."
                    body<UpdateCustomerBillingInfoResponse> {
                        description = "The updated Polar customer object."
                    }
                }
                code(HttpStatusCode.BadRequest) {
                    description = "The request is missing `orgId` or does not contain any fields to update."
                    body<BillingApiErrorResponse>()
                }
                code(HttpStatusCode.Unauthorized) {
                    description = "Missing, expired, or invalid internal service JWT."
                    body<BillingApiErrorResponse>()
                }
                code(HttpStatusCode.NotFound) {
                    description = "Polar could not find the customer mapped to the organization."
                    body<BillingApiErrorResponse>()
                }
                code(HttpStatusCode.UnprocessableEntity) {
                    description = "Polar rejected the billing profile payload."
                    body<BillingApiErrorResponse>()
                }
                code(HttpStatusCode.BadGateway) {
                    description = "Polar was unavailable or returned an unexpected failure."
                    body<BillingApiErrorResponse>()
                }
            }
        }) {
            controller.updateBillingInfo(call)
        }.sdk {
            operationId = "updateCustomerBillingInfo"
            methodName = "updateCustomerBillingInfo"
            internal = true
            requestBody<UpdateCustomerBillingInfoRequest>()
            response<UpdateCustomerBillingInfoResponse>()
        }

        get("/payment-methods", {
            operationId = "listCustomerPaymentMethods"
            summary = "List customer payment methods"
            description =
                "Lists the saved Polar payment methods for the customer mapped to an organization. " +
                "Use this to render payment method management UI from an internal app backend."
            tags("Customer Billing")
            protected = true
            securitySchemeNames("serviceToken")
            request {
                queryParameter<String>("orgId") {
                    description =
                        "Internal organization identifier whose Polar customer payment methods should be listed."
                    required = true
                }
                queryParameter<Int>("page") {
                    description = "One-based page number. Defaults to 1."
                    required = false
                }
                queryParameter<Int>("limit") {
                    description = "Page size from 1 to 100. Defaults to 10."
                    required = false
                }
            }
            response {
                code(HttpStatusCode.OK) {
                    description = "Payment methods returned successfully."
                    body<ListCustomerPaymentMethodsResponse> {
                        description = "Polar paginated payment method response."
                    }
                }
                code(HttpStatusCode.BadRequest) {
                    description = "The required `orgId` query parameter is missing."
                    body<BillingApiErrorResponse>()
                }
                code(HttpStatusCode.Unauthorized) {
                    description = "Missing, expired, or invalid internal service JWT."
                    body<BillingApiErrorResponse>()
                }
                code(HttpStatusCode.NotFound) {
                    description = "Polar could not find the customer mapped to the organization."
                    body<BillingApiErrorResponse>()
                }
                code(HttpStatusCode.BadGateway) {
                    description = "Polar was unavailable or returned an unexpected failure."
                    body<BillingApiErrorResponse>()
                }
            }
        }) {
            controller.listPaymentMethods(call)
        }.sdk {
            operationId = "listCustomerPaymentMethods"
            methodName = "listCustomerPaymentMethods"
            internal = true
            queryParam("orgId", required = true, description = "Internal organization identifier.")
            queryParam("page", type = "integer", description = "One-based page number.")
            queryParam("limit", type = "integer", description = "Page size from 1 to 100.")
            response<ListCustomerPaymentMethodsResponse>()
        }

        delete("/payment-methods/{paymentMethodId}", {
            operationId = "deleteCustomerPaymentMethod"
            summary = "Delete customer payment method"
            description =
                "Deletes a saved Polar payment method from the customer mapped to an organization. " +
                "The calling app must authorize the user before invoking this server-to-server endpoint."
            tags("Customer Billing")
            protected = true
            securitySchemeNames("serviceToken")
            request {
                pathParameter<String>("paymentMethodId") {
                    description = "Polar customer payment method identifier to delete."
                    required = true
                }
                queryParameter<String>("orgId") {
                    description = "Internal organization identifier mapped to the Polar customer."
                    required = true
                }
            }
            response {
                code(HttpStatusCode.NoContent) {
                    description = "Payment method deleted successfully. No response body is returned."
                }
                code(HttpStatusCode.BadRequest) {
                    description = "The required `orgId` query parameter or `paymentMethodId` path parameter is missing."
                    body<BillingApiErrorResponse>()
                }
                code(HttpStatusCode.Unauthorized) {
                    description = "Missing, expired, or invalid internal service JWT."
                    body<BillingApiErrorResponse>()
                }
                code(HttpStatusCode.NotFound) {
                    description = "Polar could not find the customer or payment method."
                    body<BillingApiErrorResponse>()
                }
                code(HttpStatusCode.BadGateway) {
                    description = "Polar was unavailable or returned an unexpected failure."
                    body<BillingApiErrorResponse>()
                }
            }
        }) {
            controller.deletePaymentMethod(call)
        }.sdk {
            operationId = "deleteCustomerPaymentMethod"
            methodName = "deleteCustomerPaymentMethod"
            internal = true
            queryParam("orgId", required = true, description = "Internal organization identifier.")
            responseStatus = 204
        }
    }
}
