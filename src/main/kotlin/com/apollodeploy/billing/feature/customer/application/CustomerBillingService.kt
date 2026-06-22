package com.apollodeploy.billing.feature.customer.application

import com.apollodeploy.billing.feature.customer.domain.CustomerBillingResult
import com.apollodeploy.billing.feature.customer.domain.ListCustomerPaymentMethodsResponse
import com.apollodeploy.billing.feature.customer.domain.UpdateCustomerBillingInfoRequest
import com.apollodeploy.billing.feature.customer.domain.UpdateCustomerBillingInfoResponse
import com.apollodeploy.billing.feature.customer.domain.hasAnyUpdate
import com.apollodeploy.billing.feature.customer.infrastructure.persistence.CustomerBillingRepo
import com.apollodeploy.billing.infrastructure.audit.AuditEvent
import com.apollodeploy.billing.infrastructure.audit.AuditLogClient
import com.apollodeploy.billing.infrastructure.audit.AuditRiskLevel
import com.apollodeploy.billing.infrastructure.audit.AuditStatus

class CustomerBillingService(
    private val customerBillingRepo: CustomerBillingRepo,
    private val auditLogClient: AuditLogClient,
) {
    suspend fun updateBillingInfo(req: UpdateCustomerBillingInfoRequest): CustomerBillingResult<UpdateCustomerBillingInfoResponse> {
        if (req.orgId.isBlank()) {
            return CustomerBillingResult.InvalidRequest("orgId is required")
        }
        if (!req.hasAnyUpdate()) {
            return CustomerBillingResult.InvalidRequest("No billing fields provided")
        }

        val result =
            customerBillingRepo.updateCustomerBillingInfo(
                orgId = req.orgId,
                email = req.email,
                billingName = req.billingName,
                billingAddress = req.billingAddress,
                taxId = req.taxId,
                defaultPaymentMethodId = req.defaultPaymentMethodId,
            )

        return if (result.value != null) {
            auditLogClient.log(
                AuditEvent(
                    module = "customer",
                    action = "billing_info_updated",
                    resourceType = "customer",
                    organizationId = req.orgId,
                    status = AuditStatus.SUCCESS,
                    metadata =
                        buildMap {
                            if (req.email != null) put("updatedEmail", "true")
                            if (req.billingName != null) put("updatedBillingName", "true")
                            if (req.billingAddress != null) put("updatedBillingAddress", "true")
                            if (req.taxId != null) put("updatedTaxId", "true")
                            if (req.defaultPaymentMethodId != null) put("updatedDefaultPaymentMethod", "true")
                        },
                ),
            )
            CustomerBillingResult.Success(UpdateCustomerBillingInfoResponse(result.value))
        } else {
            auditLogClient.log(
                AuditEvent(
                    module = "customer",
                    action = "billing_info_updated",
                    resourceType = "customer",
                    organizationId = req.orgId,
                    status = AuditStatus.FAILURE,
                    errorMessage = result.errorBody ?: "Customer update failed",
                ),
            )
            CustomerBillingResult.PolarFailure(
                fallbackCode = "billing.customer_update_failed",
                statusCode = result.statusCode,
                errorBody = result.errorBody,
            )
        }
    }

    suspend fun listPaymentMethods(
        orgId: String?,
        page: Int,
        limit: Int,
    ): CustomerBillingResult<ListCustomerPaymentMethodsResponse> {
        if (orgId.isNullOrBlank()) {
            return CustomerBillingResult.InvalidRequest("orgId query parameter is required")
        }

        val result = customerBillingRepo.listCustomerPaymentMethods(orgId, page, limit)
        return result.value?.let { CustomerBillingResult.Success(ListCustomerPaymentMethodsResponse(it)) }
            ?: CustomerBillingResult.PolarFailure(
                fallbackCode = "billing.payment_methods_unavailable",
                statusCode = result.statusCode,
                errorBody = result.errorBody,
            )
    }

    suspend fun deletePaymentMethod(
        orgId: String?,
        paymentMethodId: String?,
    ): CustomerBillingResult<Unit> {
        if (orgId.isNullOrBlank() || paymentMethodId.isNullOrBlank()) {
            return CustomerBillingResult.InvalidRequest(
                "orgId query parameter and paymentMethodId path parameter are required",
            )
        }

        val result = customerBillingRepo.deleteCustomerPaymentMethod(orgId, paymentMethodId)
        return if (result.value != null) {
            auditLogClient.log(
                AuditEvent(
                    module = "customer",
                    action = "payment_method_deleted",
                    resourceType = "payment_method",
                    resourceId = paymentMethodId,
                    organizationId = orgId,
                    status = AuditStatus.SUCCESS,
                    riskLevel = AuditRiskLevel.MEDIUM,
                ),
            )
            CustomerBillingResult.Success(Unit)
        } else {
            auditLogClient.log(
                AuditEvent(
                    module = "customer",
                    action = "payment_method_deleted",
                    resourceType = "payment_method",
                    resourceId = paymentMethodId,
                    organizationId = orgId,
                    status = AuditStatus.FAILURE,
                    errorMessage = result.errorBody ?: "Payment method deletion failed",
                    riskLevel = AuditRiskLevel.MEDIUM,
                ),
            )
            CustomerBillingResult.PolarFailure(
                fallbackCode = "billing.payment_method_delete_failed",
                statusCode = result.statusCode,
                errorBody = result.errorBody,
            )
        }
    }
}
