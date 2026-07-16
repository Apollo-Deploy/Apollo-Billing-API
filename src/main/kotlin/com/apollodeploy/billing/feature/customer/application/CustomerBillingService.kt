package com.apollodeploy.billing.feature.customer.application

import com.apollodeploy.billing.feature.customer.domain.CustomerBillingResult
import com.apollodeploy.billing.feature.customer.domain.ListCustomerPaymentMethodsResponse
import com.apollodeploy.billing.feature.customer.domain.OpenBillingPortalRequest
import com.apollodeploy.billing.feature.customer.domain.OpenBillingPortalResponse
import com.apollodeploy.billing.feature.customer.domain.ProvisionCustomerRequest
import com.apollodeploy.billing.feature.customer.domain.ProvisionCustomerResponse
import com.apollodeploy.billing.feature.customer.domain.UpdateCustomerBillingInfoRequest
import com.apollodeploy.billing.feature.customer.domain.UpdateCustomerBillingInfoResponse
import com.apollodeploy.billing.feature.customer.domain.hasAnyUpdate
import com.apollodeploy.billing.feature.customer.infrastructure.persistence.CustomerBillingRepo
import com.apollodeploy.billing.infrastructure.audit.AuditEvent
import com.apollodeploy.billing.infrastructure.audit.AuditLogClient
import com.apollodeploy.billing.infrastructure.audit.AuditRiskLevel
import com.apollodeploy.billing.infrastructure.audit.AuditStatus
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

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
                externalMemberId = req.memberId,
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
        memberId: String? = null,
    ): CustomerBillingResult<ListCustomerPaymentMethodsResponse> {
        if (orgId.isNullOrBlank()) {
            return CustomerBillingResult.InvalidRequest("orgId query parameter is required")
        }

        val result = customerBillingRepo.listCustomerPaymentMethods(orgId, page, limit, memberId)
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
        memberId: String? = null,
    ): CustomerBillingResult<Unit> {
        if (orgId.isNullOrBlank() || paymentMethodId.isNullOrBlank()) {
            return CustomerBillingResult.InvalidRequest(
                "orgId query parameter and paymentMethodId path parameter are required",
            )
        }

        val result = customerBillingRepo.deleteCustomerPaymentMethod(orgId, paymentMethodId, memberId)
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

    suspend fun openBillingPortal(req: OpenBillingPortalRequest): CustomerBillingResult<OpenBillingPortalResponse> {
        if (req.orgId.isBlank()) {
            return CustomerBillingResult.InvalidRequest("orgId is required")
        }

        val result = customerBillingRepo.createCustomerPortalSession(req.orgId, req.returnUrl, req.memberId)
        return if (result.value != null) {
            auditLogClient.log(
                AuditEvent(
                    module = "customer",
                    action = "billing_portal_opened",
                    resourceType = "customer",
                    organizationId = req.orgId,
                    status = AuditStatus.SUCCESS,
                ),
            )
            CustomerBillingResult.Success(
                OpenBillingPortalResponse(
                    portalUrl = result.value.customerPortalUrl,
                    sessionToken = result.value.token,
                    expiresAt = result.value.expiresAt,
                ),
            )
        } else {
            auditLogClient.log(
                AuditEvent(
                    module = "customer",
                    action = "billing_portal_opened",
                    resourceType = "customer",
                    organizationId = req.orgId,
                    status = AuditStatus.FAILURE,
                    errorMessage = result.errorBody ?: "Customer portal session creation failed",
                ),
            )
            CustomerBillingResult.PolarFailure(
                fallbackCode = "billing.portal_session_failed",
                statusCode = result.statusCode,
                errorBody = result.errorBody,
            )
        }
    }

    suspend fun provisionCustomer(req: ProvisionCustomerRequest): CustomerBillingResult<ProvisionCustomerResponse> {
        if (req.orgId.isBlank()) return CustomerBillingResult.InvalidRequest("orgId is required")
        if (req.name.isBlank()) return CustomerBillingResult.InvalidRequest("name is required")
        if (req.ownerEmail.isBlank()) return CustomerBillingResult.InvalidRequest("ownerEmail is required")

        val result = customerBillingRepo.provisionCustomer(
            orgId = req.orgId,
            name = req.name,
            ownerEmail = req.ownerEmail,
            ownerMemberId = req.ownerMemberId,
            ownerName = req.ownerName,
            billingEmail = req.billingEmail,
        )

        return if (result.value != null) {
            auditLogClient.log(
                AuditEvent(
                    module = "customer",
                    action = "customer_provisioned",
                    resourceType = "customer",
                    resourceId = result.value["id"]?.jsonPrimitive?.contentOrNull,
                    organizationId = req.orgId,
                    status = AuditStatus.SUCCESS,
                ),
            )
            CustomerBillingResult.Success(
                ProvisionCustomerResponse(
                    polarCustomerId = result.value["id"]?.jsonPrimitive?.contentOrNull ?: "",
                    externalId = result.value["external_id"]?.jsonPrimitive?.contentOrNull ?: req.orgId,
                    name = result.value["name"]?.jsonPrimitive?.contentOrNull,
                    type = result.value["type"]?.jsonPrimitive?.contentOrNull ?: "team",
                ),
            )
        } else {
            auditLogClient.log(
                AuditEvent(
                    module = "customer",
                    action = "customer_provisioned",
                    resourceType = "customer",
                    organizationId = req.orgId,
                    status = AuditStatus.FAILURE,
                    errorMessage = result.errorBody ?: "Customer provisioning failed",
                ),
            )
            CustomerBillingResult.PolarFailure(
                fallbackCode = "billing.customer_provision_failed",
                statusCode = result.statusCode,
                errorBody = result.errorBody,
            )
        }
    }
}
