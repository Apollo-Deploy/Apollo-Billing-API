package com.apollodeploy.billing.feature.customer.domain

import com.apollodeploy.billing.infrastructure.polar.PolarBillingAddressInput
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class UpdateCustomerBillingInfoRequest(
    val orgId: String,
    /** Optional. Only needed if you want the Polar portal session scoped to a specific user
     *  rather than the org's owner. For flat org-level billing this can be omitted. */
    val memberId: String? = null,
    val email: String? = null,
    val billingName: String? = null,
    val billingAddress: PolarBillingAddressInput? = null,
    val taxId: String? = null,
    val defaultPaymentMethodId: String? = null,
)

@Serializable
data class UpdateCustomerBillingInfoResponse(
    val customer: JsonObject,
)

@Serializable
data class ListCustomerPaymentMethodsResponse(
    val paymentMethods: JsonObject,
)

@Serializable
data class OpenBillingPortalRequest(
    val orgId: String,
    /** Optional. Scopes the portal session to a specific user so Polar attributes
     *  portal activity to them. For flat org-level billing this can be omitted. */
    val memberId: String? = null,
    val returnUrl: String? = null,
)

@Serializable
data class OpenBillingPortalResponse(
    val portalUrl: String,
    val sessionToken: String,
    val expiresAt: String,
)

@Serializable
data class ProvisionCustomerRequest(
    val orgId: String,
    val name: String,
    /** Email address of the org owner. Used as the Polar owner member's email.
     *  Polar sends billing notifications here if no separate billingEmail is provided. */
    val ownerEmail: String,
    /** Optional. Sets the owner member's external_id in Polar so portal sessions can later
     *  be attributed to this user via memberId. Safe to omit for flat org-level billing. */
    val ownerMemberId: String? = null,
    val ownerName: String? = null,
    /** Optional. Billing contact email shown on invoices. Defaults to ownerEmail if omitted. */
    val billingEmail: String? = null,
)

@Serializable
data class ProvisionCustomerResponse(
    val polarCustomerId: String,
    val externalId: String,
    val name: String?,
    val type: String,
)

@Serializable
data class SetDefaultPaymentMethodResponse(
    val customer: JsonObject,
)

@Serializable
data class CreateCustomerSessionRequest(
    val orgId: String,
    /** Optional. Scopes the session to a specific org member for portal activity attribution.
     *  For flat org-level billing this can be omitted. */
    val memberId: String? = null,
    /** Optional. Polar will surface a back button pointing to this URL inside the Customer Portal. */
    val returnUrl: String? = null,
)

@Serializable
data class CreateCustomerSessionResponse(
    /** Short-lived bearer token for Customer Portal API calls. */
    val sessionToken: String,
    /** Pre-built portal URL that can be used to redirect the user directly into the portal. */
    val customerPortalUrl: String,
    /** ISO-8601 timestamp when the session expires (typically 30 minutes). */
    val expiresAt: String,
    /** Polar-internal customer session ID. */
    val sessionId: String,
)

sealed class CustomerBillingResult<out T> {
    data class Success<T>(
        val value: T,
    ) : CustomerBillingResult<T>()

    data class InvalidRequest(
        val message: String,
    ) : CustomerBillingResult<Nothing>()

    data class PolarFailure(
        val fallbackCode: String,
        val statusCode: Int?,
        val errorBody: String?,
    ) : CustomerBillingResult<Nothing>()
}

fun UpdateCustomerBillingInfoRequest.hasAnyUpdate(): Boolean =
    email != null ||
        billingName != null ||
        billingAddress != null ||
        taxId != null ||
        defaultPaymentMethodId != null
