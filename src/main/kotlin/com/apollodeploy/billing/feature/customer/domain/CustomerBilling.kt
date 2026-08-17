package com.apollodeploy.billing.feature.customer.domain

import com.apollodeploy.billing.infrastructure.polar.model.PolarBillingAddressInput
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class UpdateCustomerBillingInfoRequest(
    val orgId: String,
    /** Optional. Scopes portal sessions to a specific user (Polar external_member_id).
     *  For team customers Polar requires a member on session create; when omitted,
     *  billing falls back to the team owner. Not needed for org-token APIs. */
    val memberId: String? = null,
    val email: String? = null,
    val billingName: String? = null,
    val billingAddress: PolarBillingAddressInput? = null,
    val taxId: String? = null,
    val defaultPaymentMethodId: String? = null,
)

/**
 * Concrete Polar customer fields returned by billing-info update / set-default.
 *
 * Fields are camelCase. polarPassthroughJson uses JsonNamingStrategy.SnakeCase
 * to decode from Polar's snake_case wire format. ApiJson (no strategy) encodes
 * these as camelCase in API responses, matching what the billing SDK expects.
 */
@Serializable
data class CustomerBillingProfile(
    val id: String = "",
    val externalId: String? = null,
    val email: String? = null,
    val name: String? = null,
    val type: String? = null,
    val billingName: String? = null,
    val billingAddress: PolarBillingAddressInput? = null,
    /** Polar returns `[value, format]` or null; we expose the value only. */
    val taxId: String? = null,
    val defaultPaymentMethodId: String? = null,
)

@Serializable
data class UpdateCustomerBillingInfoResponse(
    val customer: CustomerBillingProfile,
)

@Serializable
data class PaymentMethodCardMetadata(
    val brand: String? = null,
    val last4: String? = null,
    val expMonth: Int? = null,
    val expYear: Int? = null,
    val wallet: String? = null,
)

@Serializable
data class CustomerPaymentMethodItem(
    val id: String,
    val type: String = "card",
    val isDefault: Boolean = false,
    val customerId: String? = null,
    val processor: String? = null,
    val methodMetadata: PaymentMethodCardMetadata? = null,
)

@Serializable
data class PaymentMethodsPagination(
    val totalCount: Int = 0,
    val maxPage: Int = 0,
)

/**
 * Polar paginated payment-method list (`items` + `pagination`).
 * Each item includes `is_default` for the customer's default payment method.
 */
@Serializable
data class CustomerPaymentMethodsPage(
    val items: List<CustomerPaymentMethodItem> = emptyList(),
    val pagination: PaymentMethodsPagination = PaymentMethodsPagination(),
)

@Serializable
data class ListCustomerPaymentMethodsResponse(
    val paymentMethods: CustomerPaymentMethodsPage,
)

@Serializable
data class OpenBillingPortalRequest(
    val orgId: String,
    /** Optional. Scopes the portal session to a specific user (Polar external_member_id).
     *  Required by Polar for team customers unless billing falls back to the team owner. */
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
    val customer: CustomerBillingProfile,
)

@Serializable
data class CreateCustomerSessionRequest(
    val orgId: String,
    /** Optional. Scopes the session to a specific org member (Polar external_member_id).
     *  For team customers Polar requires a member; when omitted, billing falls back to the owner. */
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

private val polarPassthroughJson =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
        namingStrategy = JsonNamingStrategy.SnakeCase
    }

internal fun JsonObject.toCustomerBillingProfile(): CustomerBillingProfile =
    runCatching {
        // Polar returns tax_id as [value, format] array or a plain string.
        // Decode first, then override taxId with the extracted value.
        val taxId =
            when (val tax = this["tax_id"]) {
                is JsonArray -> tax.firstOrNull()?.jsonPrimitive?.contentOrNull
                is JsonPrimitive -> tax.contentOrNull
                else -> null
            }
        polarPassthroughJson.decodeFromJsonElement<CustomerBillingProfile>(this).copy(taxId = taxId)
    }.getOrDefault(CustomerBillingProfile())

internal fun JsonObject.toCustomerPaymentMethodsPage(): CustomerPaymentMethodsPage =
    runCatching {
        polarPassthroughJson.decodeFromJsonElement<CustomerPaymentMethodsPage>(this)
    }.getOrDefault(CustomerPaymentMethodsPage())
