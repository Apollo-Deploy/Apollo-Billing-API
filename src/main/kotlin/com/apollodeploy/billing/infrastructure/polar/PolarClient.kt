package com.apollodeploy.billing.infrastructure.polar

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.net.URLEncoder

/**
 * Apollo Billing — Polar API client.
 *
 * Handles outbound calls to the Polar API:
 *   - Usage event ingestion for meters
 *   - Customer state lookup
 *   - Checkout session creation
 *   - Customer billing profile and payment method operations
 *
 * Security invariant: the API key is never logged.
 */
class PolarClient(
    private val httpClient: HttpClient,
    private val apiBaseUrl: String,
    private val apiKey: String,
    private val timeoutMs: Long,
) {
    private val logger = LoggerFactory.getLogger(PolarClient::class.java)
    private val baseUrl = apiBaseUrl.trimEnd('/')

    /**
     * Reports a usage event to Polar for metered billing.
     *
     * @param orgId          The organisation's external ID (Polar customer external_id)
     * @param eventName      Polar meter event name (e.g. "signal.automation.run")
     * @param quantity       Usage quantity to report. Included as metadata for meter aggregation.
     * @param metadata       Additional Polar event metadata for filtering/aggregation.
     * @return true on success, false on failure (non-fatal — caller logs and continues)
     */
    suspend fun ingestUsageEvent(
        orgId: String,
        eventName: String,
        quantity: Int = 1,
        metadata: Map<String, JsonElement> = emptyMap(),
    ): Boolean {
        if (apiKey.isBlank()) {
            logger.warn("[billing:polar] POLAR_API_KEY is not configured — skipping usage ingest for org={}", orgId)
            return false
        }

        val eventMetadata =
            buildMap {
                put("quantity", JsonPrimitive(quantity))
                put("units", JsonPrimitive(quantity))
                putAll(metadata)
            }
        val body =
            PolarEventsIngestRequest(
                events =
                    listOf(
                        PolarUsageEvent(
                            name = eventName,
                            externalCustomerId = orgId,
                            metadata = eventMetadata,
                        ),
                    ),
            )

        return try {
            withTimeout(timeoutMs) {
                val response =
                    httpClient.post("$baseUrl/v1/events/ingest") {
                        contentType(ContentType.Application.Json)
                        bearerAuth(apiKey)
                        setBody(body)
                    }
                if (!response.status.isSuccess()) {
                    val responseBody = runCatching { response.bodyAsText() }.getOrElse { "" }
                    logger.warn(
                        "[billing:polar] usage ingest failed org={} event={} status={} body={}",
                        orgId,
                        eventName,
                        response.status.value,
                        responseBody,
                    )
                    return@withTimeout false
                }
                true
            }
        } catch (e: Exception) {
            logger.warn("[billing:polar] usage ingest exception org={} event={}", orgId, eventName, e)
            false
        }
    }

    /**
     * Fetches the full customer state for an org from Polar.
     *
     * Returns null on any failure (network error, 404, timeout) — callers
     * must treat null as "Polar unavailable" and apply their own fallback.
     *
     * The response includes:
     *   - active_subscriptions  → subscription status / product mapping
     *   - granted_benefits      → feature flag grants with metadata
     *   - active_meters         → usage meter balances (automation runs, credit packs, etc.)
     *
     * Docs: https://polar.sh/docs/integrate/customer-state
     */
    suspend fun getCustomerState(orgId: String): PolarCustomerState? {
        if (apiKey.isBlank()) {
            logger.warn("[billing:polar] POLAR_API_KEY not configured — cannot get customer state org={}", orgId)
            return null
        }
        return try {
            withTimeout(timeoutMs) {
                val response =
                    httpClient.get("$baseUrl/v1/customers/external/$orgId/state") {
                        bearerAuth(apiKey)
                    }
                if (!response.status.isSuccess()) {
                    logger.warn(
                        "[billing:polar] getCustomerState failed org={} status={}",
                        orgId,
                        response.status.value,
                    )
                    return@withTimeout null
                }
                response.body<PolarCustomerState>()
            }
        } catch (e: Exception) {
            logger.warn("[billing:polar] getCustomerState exception org={}: {}", orgId, e.message)
            null
        }
    }

    /**
     * Creates a Polar checkout session for a registered product.
     *
     * Context7 Polar docs show POST /v1/checkouts/ with products plus
     * external_customer_id so the resulting customer/order reconcile back to
     * our org ID and appear in webhooks/customer state.
     */
    suspend fun createCheckoutSession(
        orgId: String,
        productIds: List<String>,
        customerEmail: String? = null,
        customerName: String? = null,
        successUrl: String? = null,
        returnUrl: String? = null,
        metadata: Map<String, String> = emptyMap(),
    ): PolarCheckoutSession? {
        if (apiKey.isBlank()) {
            logger.warn("[billing:polar] POLAR_API_KEY is not configured — cannot create checkout org={}", orgId)
            return null
        }
        if (productIds.isEmpty()) {
            logger.warn("[billing:polar] checkout requested without products org={}", orgId)
            return null
        }

        val body =
            PolarCreateCheckoutSessionRequest(
                externalCustomerId = orgId,
                products = productIds,
                customerEmail = customerEmail,
                customerName = customerName,
                successUrl = successUrl,
                returnUrl = returnUrl,
                metadata = metadata,
            )

        return try {
            withTimeout(timeoutMs) {
                val response =
                    httpClient.post("$baseUrl/v1/checkouts/") {
                        contentType(ContentType.Application.Json)
                        bearerAuth(apiKey)
                        setBody(body)
                    }
                if (!response.status.isSuccess()) {
                    val responseBody = runCatching { response.bodyAsText() }.getOrElse { "" }
                    logger.warn(
                        "[billing:polar] checkout creation failed org={} status={} body={}",
                        orgId,
                        response.status.value,
                        responseBody,
                    )
                    return@withTimeout null
                }
                response.body<PolarCheckoutSession>()
            }
        } catch (e: Exception) {
            logger.warn("[billing:polar] checkout creation exception org={}: {}", orgId, e.message)
            null
        }
    }

    suspend fun getProduct(productId: String): PolarCallResult<JsonObject> {
        if (apiKey.isBlank()) {
            logger.warn("[billing:polar] POLAR_API_KEY is not configured — cannot get product id={}", productId)
            return PolarCallResult.failure(null, "POLAR_API_KEY is not configured")
        }

        return try {
            withTimeout(timeoutMs) {
                val response =
                    httpClient.get("$baseUrl/v1/products/${productId.pathSegment()}") {
                        bearerAuth(apiKey)
                    }
                if (!response.status.isSuccess()) {
                    return@withTimeout response.toPolarFailure("get product", productId)
                }
                PolarCallResult.success(response.body<JsonObject>(), response.status.value)
            }
        } catch (e: Exception) {
            logger.warn("[billing:polar] get product exception id={}: {}", productId, e.message)
            PolarCallResult.failure(null, e.message ?: "Polar request failed")
        }
    }

    suspend fun listProducts(limit: Int = 100): PolarCallResult<List<JsonObject>> {
        if (apiKey.isBlank()) {
            logger.warn("[billing:polar] POLAR_API_KEY is not configured — cannot list products")
            return PolarCallResult.failure(null, "POLAR_API_KEY is not configured")
        }

        return try {
            withTimeout(timeoutMs) {
                val response =
                    httpClient.get("$baseUrl/v1/products/") {
                        bearerAuth(apiKey)
                        parameter("limit", limit)
                    }
                if (!response.status.isSuccess()) {
                    return@withTimeout response.toPolarFailure("list products", "products")
                }
                val body = response.body<JsonObject>()
                val products =
                    (body["items"] as? JsonArray)
                        ?.mapNotNull { it as? JsonObject }
                        ?: emptyList()
                PolarCallResult.success(products, response.status.value)
            }
        } catch (e: Exception) {
            logger.warn("[billing:polar] list products exception: {}", e.message)
            PolarCallResult.failure(null, e.message ?: "Polar request failed")
        }
    }

    /**
     * Updates customer billing data.
     *
     * Polar splits this across two APIs:
     *   - `PATCH /v1/customers/external/{external_id}` for email.
     *   - `PATCH /v1/customer-portal/customers/me` for billing name, address,
     *     tax ID, and default payment method. Customer Portal calls require a
     *     short-lived customer session token created server-side.
     */
    suspend fun updateCustomerBillingInfo(
        orgId: String,
        email: String? = null,
        billingName: String? = null,
        billingAddress: PolarBillingAddressInput? = null,
        taxId: String? = null,
        defaultPaymentMethodId: String? = null,
        externalMemberId: String? = null,
    ): PolarCallResult<JsonObject> {
        if (apiKey.isBlank()) {
            logger.warn("[billing:polar] POLAR_API_KEY is not configured — cannot update customer billing info org={}", orgId)
            return PolarCallResult.failure(null, "POLAR_API_KEY is not configured")
        }

        val portalUpdateRequired =
            billingName != null || billingAddress != null || taxId != null || defaultPaymentMethodId != null
        var latestCustomer: JsonObject? = null

        // Email update goes through the admin API (no session needed).
        // Do it first so a failure here aborts before touching portal fields,
        // avoiding a partial-write where portal fields succeed but email fails.
        if (email != null) {
            val emailUpdate = updateCustomerByExternalId(orgId = orgId, email = email)
            if (emailUpdate.value == null) return emailUpdate
            latestCustomer = emailUpdate.value
        }

        if (portalUpdateRequired) {
            val sessionResult = createCustomerSession(orgId, externalMemberId = externalMemberId)
            val session =
                sessionResult.value
                    ?: return PolarCallResult.failure(
                        sessionResult.statusCode,
                        sessionResult.errorBody ?: "Unable to create customer session",
                    )

            val portalUpdate =
                updateCustomerPortalProfile(
                    customerSessionToken = session.token,
                    billingName = billingName,
                    billingAddress = billingAddress,
                    taxId = taxId,
                    defaultPaymentMethodId = defaultPaymentMethodId,
                )
            if (portalUpdate.value == null) return portalUpdate
            latestCustomer = portalUpdate.value
        }

        return latestCustomer?.let { PolarCallResult.success(it, 200) }
            ?: PolarCallResult.failure(400, "No billing fields provided")
    }

    suspend fun listCustomerPaymentMethods(
        orgId: String,
        page: Int = 1,
        limit: Int = 10,
        externalMemberId: String? = null,
    ): PolarCallResult<JsonObject> {
        if (apiKey.isBlank()) {
            logger.warn("[billing:polar] POLAR_API_KEY is not configured — cannot list payment methods org={}", orgId)
            return PolarCallResult.failure(null, "POLAR_API_KEY is not configured")
        }

        val sessionResult = createCustomerSession(orgId, externalMemberId = externalMemberId)
        val session =
            sessionResult.value
                ?: return PolarCallResult.failure(
                    sessionResult.statusCode,
                    sessionResult.errorBody ?: "Unable to create customer session",
                )

        return try {
            withTimeout(timeoutMs) {
                val response =
                    httpClient.get("$baseUrl/v1/customer-portal/customers/me/payment-methods") {
                        bearerAuth(session.token)
                        parameter("page", page)
                        parameter("limit", limit)
                    }
                if (!response.status.isSuccess()) {
                    return@withTimeout response.toPolarFailure("list payment methods", orgId)
                }
                PolarCallResult.success(response.body<JsonObject>(), response.status.value)
            }
        } catch (e: Exception) {
            logger.warn("[billing:polar] list payment methods exception org={}: {}", orgId, e.message)
            PolarCallResult.failure(null, e.message ?: "Polar request failed")
        }
    }

    suspend fun deleteCustomerPaymentMethod(
        orgId: String,
        paymentMethodId: String,
        externalMemberId: String? = null,
    ): PolarCallResult<Unit> {
        if (apiKey.isBlank()) {
            logger.warn("[billing:polar] POLAR_API_KEY is not configured — cannot delete payment method org={}", orgId)
            return PolarCallResult.failure(null, "POLAR_API_KEY is not configured")
        }

        val sessionResult = createCustomerSession(orgId, externalMemberId = externalMemberId)
        val session =
            sessionResult.value
                ?: return PolarCallResult.failure(
                    sessionResult.statusCode,
                    sessionResult.errorBody ?: "Unable to create customer session",
                )

        return try {
            withTimeout(timeoutMs) {
                val response =
                    httpClient.delete(
                        "$baseUrl/v1/customer-portal/customers/me/payment-methods/${paymentMethodId.pathSegment()}",
                    ) {
                        bearerAuth(session.token)
                    }
                if (!response.status.isSuccess()) {
                    return@withTimeout response.toPolarFailure("delete payment method", orgId)
                }
                PolarCallResult.success(Unit, response.status.value)
            }
        } catch (e: Exception) {
            logger.warn("[billing:polar] delete payment method exception org={}: {}", orgId, e.message)
            PolarCallResult.failure(null, e.message ?: "Polar request failed")
        }
    }

    /**
     * Creates a new team customer in Polar for an organization.
     *
     * Uses `POST /v1/customers/` with `type: "team"`.
     * The `external_id` is set to `orgId` so all subsequent calls
     * can reference the customer by the Apollo org ID.
     *
     * An owner member is created automatically by Polar from `ownerEmail`/`ownerName`
     * if not provided explicitly. Pass `ownerMemberId` to set the owner's external_id
     * so portal sessions can be scoped back to them via `external_member_id`.
     */
    suspend fun createTeamCustomer(
        orgId: String,
        name: String,
        ownerEmail: String,
        ownerMemberId: String? = null,
        ownerName: String? = null,
        billingEmail: String? = null,
    ): PolarCallResult<JsonObject> {
        if (apiKey.isBlank()) {
            logger.warn("[billing:polar] POLAR_API_KEY is not configured — cannot create customer org={}", orgId)
            return PolarCallResult.failure(null, "POLAR_API_KEY is not configured")
        }

        val body = PolarCreateTeamCustomerRequest(
            externalId = orgId,
            name = name,
            email = billingEmail,
            owner = PolarMemberOwnerCreate(
                email = ownerEmail,
                name = ownerName,
                externalId = ownerMemberId,
            ),
        )

        return try {
            withTimeout(timeoutMs) {
                val response =
                    httpClient.post("$baseUrl/v1/customers/") {
                        contentType(ContentType.Application.Json)
                        bearerAuth(apiKey)
                        setBody(body)
                    }
                if (!response.status.isSuccess()) {
                    return@withTimeout response.toPolarFailure("create team customer", orgId)
                }
                PolarCallResult.success(response.body<JsonObject>(), response.status.value)
            }
        } catch (e: Exception) {
            logger.warn("[billing:polar] create team customer exception org={}: {}", orgId, e.message)
            PolarCallResult.failure(null, e.message ?: "Polar request failed")
        }
    }

    suspend fun createCustomerSession(
        orgId: String,
        returnUrl: String? = null,
        externalMemberId: String? = null,
    ): PolarCallResult<PolarCustomerSession> {
        if (apiKey.isBlank()) {
            logger.warn("[billing:polar] POLAR_API_KEY is not configured — cannot create customer session org={}", orgId)
            return PolarCallResult.failure(null, "POLAR_API_KEY is not configured")
        }
        return try {
            withTimeout(timeoutMs) {
                val response =
                    httpClient.post("$baseUrl/v1/customer-sessions/") {
                        contentType(ContentType.Application.Json)
                        bearerAuth(apiKey)
                        setBody(
                            PolarCreateCustomerSessionRequest(
                                externalCustomerId = orgId,
                                returnUrl = returnUrl,
                                externalMemberId = externalMemberId,
                            ),
                        )
                    }
                if (!response.status.isSuccess()) {
                    return@withTimeout response.toPolarFailure("create customer session", orgId)
                }
                PolarCallResult.success(response.body<PolarCustomerSession>(), response.status.value)
            }
        } catch (e: Exception) {
            logger.warn("[billing:polar] create customer session exception org={}: {}", orgId, e.message)
            PolarCallResult.failure(null, e.message ?: "Polar request failed")
        }
    }

    private suspend fun updateCustomerByExternalId(
        orgId: String,
        email: String,
    ): PolarCallResult<JsonObject> =
        try {
            withTimeout(timeoutMs) {
                val response =
                    httpClient.patch("$baseUrl/v1/customers/external/${orgId.pathSegment()}") {
                        contentType(ContentType.Application.Json)
                        bearerAuth(apiKey)
                        setBody(PolarCustomerExternalUpdateRequest(email = email))
                    }
                if (!response.status.isSuccess()) {
                    return@withTimeout response.toPolarFailure("update customer by external ID", orgId)
                }
                PolarCallResult.success(response.body<JsonObject>(), response.status.value)
            }
        } catch (e: Exception) {
            logger.warn("[billing:polar] update customer by external ID exception org={}: {}", orgId, e.message)
            PolarCallResult.failure(null, e.message ?: "Polar request failed")
        }

    private suspend fun updateCustomerPortalProfile(
        customerSessionToken: String,
        billingName: String?,
        billingAddress: PolarBillingAddressInput?,
        taxId: String?,
        defaultPaymentMethodId: String?,
    ): PolarCallResult<JsonObject> =
        try {
            withTimeout(timeoutMs) {
                val response =
                    httpClient.patch("$baseUrl/v1/customer-portal/customers/me") {
                        contentType(ContentType.Application.Json)
                        bearerAuth(customerSessionToken)
                        setBody(
                            PolarCustomerPortalUpdateRequest(
                                billingName = billingName,
                                billingAddress = billingAddress,
                                taxId = taxId,
                                defaultPaymentMethodId = defaultPaymentMethodId,
                            ),
                        )
                    }
                if (!response.status.isSuccess()) {
                    return@withTimeout response.toPolarFailure("update customer portal profile", "customer-session")
                }
                PolarCallResult.success(response.body<JsonObject>(), response.status.value)
            }
        } catch (e: Exception) {
            logger.warn("[billing:polar] update customer portal profile exception: {}", e.message)
            PolarCallResult.failure(null, e.message ?: "Polar request failed")
        }

    /**
     * Triggers invoice generation for an order via the Customer Portal API.
     *
     * Uses `POST /v1/customer-portal/orders/{id}/invoice` with a short-lived
     * customer session token scoped to the org. Once generated, the invoice
     * is permanent and cannot be modified.
     *
     * Docs: https://polar.sh/docs/api-reference/customer-portal/orders/post-invoice
     */
    suspend fun generateOrderInvoice(
        orgId: String,
        orderId: String,
        externalMemberId: String? = null,
    ): PolarCallResult<Unit> {
        if (apiKey.isBlank()) {
            logger.warn("[billing:polar] POLAR_API_KEY is not configured — cannot generate invoice org={} order={}", orgId, orderId)
            return PolarCallResult.failure(null, "POLAR_API_KEY is not configured")
        }

        val sessionResult = createCustomerSession(orgId, externalMemberId = externalMemberId)
        val session =
            sessionResult.value
                ?: return PolarCallResult.failure(
                    sessionResult.statusCode,
                    sessionResult.errorBody ?: "Unable to create customer session",
                )

        return try {
            withTimeout(timeoutMs) {
                val response =
                    httpClient.post("$baseUrl/v1/customer-portal/orders/${orderId.pathSegment()}/invoice") {
                        bearerAuth(session.token)
                    }
                if (!response.status.isSuccess()) {
                    return@withTimeout response.toPolarFailure("generate order invoice", orderId)
                }
                PolarCallResult.success(Unit, response.status.value)
            }
        } catch (e: Exception) {
            logger.warn("[billing:polar] generate order invoice exception org={} order={}: {}", orgId, orderId, e.message)
            PolarCallResult.failure(null, e.message ?: "Polar request failed")
        }
    }

    /**
     * Fetches a single order (invoice) from Polar by ID.
     *
     * Polar orders represent completed checkout transactions and serve as
     * the invoice record for both subscription and one-time purchases.
     */
    suspend fun getOrder(orderId: String): PolarCallResult<JsonObject> {
        if (apiKey.isBlank()) {
            logger.warn("[billing:polar] POLAR_API_KEY is not configured — cannot get order id={}", orderId)
            return PolarCallResult.failure(null, "POLAR_API_KEY is not configured")
        }

        return try {
            withTimeout(timeoutMs) {
                val response =
                    httpClient.get("$baseUrl/v1/orders/${orderId.pathSegment()}") {
                        bearerAuth(apiKey)
                    }
                if (!response.status.isSuccess()) {
                    return@withTimeout response.toPolarFailure("get order", orderId)
                }
                PolarCallResult.success(response.body<JsonObject>(), response.status.value)
            }
        } catch (e: Exception) {
            logger.warn("[billing:polar] get order exception id={}: {}", orderId, e.message)
            PolarCallResult.failure(null, e.message ?: "Polar request failed")
        }
    }

    /**
     * Lists orders (invoices) for a customer from Polar.
     *
     * Uses the Customer Portal API (`GET /v1/customer-portal/orders/`) with a
     * short-lived customer session token, so orders are scoped to the customer
     * automatically without requiring a Polar-internal customer UUID.
     */
    suspend fun listOrders(
        orgId: String,
        page: Int = 1,
        limit: Int = 20,
        externalMemberId: String? = null,
    ): PolarCallResult<JsonObject> {
        if (apiKey.isBlank()) {
            logger.warn("[billing:polar] POLAR_API_KEY is not configured — cannot list orders")
            return PolarCallResult.failure(null, "POLAR_API_KEY is not configured")
        }

        val sessionResult = createCustomerSession(orgId, externalMemberId = externalMemberId)
        val session =
            sessionResult.value
                ?: return PolarCallResult.failure(
                    sessionResult.statusCode,
                    sessionResult.errorBody ?: "Unable to create customer session",
                )

        return try {
            withTimeout(timeoutMs) {
                val response =
                    httpClient.get("$baseUrl/v1/customer-portal/orders/") {
                        bearerAuth(session.token)
                        parameter("page", page)
                        parameter("limit", limit)
                    }
                if (!response.status.isSuccess()) {
                    return@withTimeout response.toPolarFailure("list orders", orgId)
                }
                PolarCallResult.success(response.body<JsonObject>(), response.status.value)
            }
        } catch (e: Exception) {
            logger.warn("[billing:polar] list orders exception: {}", e.message)
            PolarCallResult.failure(null, e.message ?: "Polar request failed")
        }
    }

    private suspend fun <T> io.ktor.client.statement.HttpResponse.toPolarFailure(
        operation: String,
        subject: String,
    ): PolarCallResult<T> {
        val responseBody = runCatching { bodyAsText() }.getOrElse { "" }
        logger.warn(
            "[billing:polar] {} failed subject={} status={} body={}",
            operation,
            subject,
            status.value,
            responseBody,
        )
        return PolarCallResult.failure(status.value, responseBody)
    }
}

data class PolarCallResult<out T>(
    val value: T? = null,
    val statusCode: Int? = null,
    val errorBody: String? = null,
) {
    companion object {
        fun <T> success(
            value: T,
            statusCode: Int,
        ): PolarCallResult<T> = PolarCallResult(value = value, statusCode = statusCode)

        fun <T> failure(
            statusCode: Int?,
            errorBody: String,
        ): PolarCallResult<T> = PolarCallResult(statusCode = statusCode, errorBody = errorBody)
    }
}

@Serializable
private data class PolarEventsIngestRequest(
    val events: List<PolarUsageEvent>,
)

@Serializable
private data class PolarUsageEvent(
    val name: String,
    @SerialName("external_customer_id") val externalCustomerId: String,
    val metadata: Map<String, JsonElement> = emptyMap(),
)

@Serializable
private data class PolarCreateCheckoutSessionRequest(
    @SerialName("external_customer_id") val externalCustomerId: String,
    val products: List<String>,
    @SerialName("customer_email") val customerEmail: String? = null,
    @SerialName("customer_name") val customerName: String? = null,
    @SerialName("success_url") val successUrl: String? = null,
    @SerialName("return_url") val returnUrl: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
data class PolarCheckoutSession(
    val id: String,
    val url: String,
    @SerialName("expires_at") val expiresAt: String? = null,
)

@Serializable
private data class PolarCreateCustomerSessionRequest(
    @SerialName("external_customer_id") val externalCustomerId: String,
    @SerialName("return_url") val returnUrl: String? = null,
    @SerialName("external_member_id") val externalMemberId: String? = null,
)

@Serializable
data class PolarCustomerSession(
    val id: String,
    val token: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("customer_portal_url") val customerPortalUrl: String,
    @SerialName("customer_id") val customerId: String,
)

@Serializable
data class PolarBillingAddressInput(
    val line1: String? = null,
    val line2: String? = null,
    @SerialName("postal_code") val postalCode: String? = null,
    val city: String? = null,
    val state: String? = null,
    val country: String? = null,
)

@Serializable
private data class PolarCustomerExternalUpdateRequest(
    val email: String,
)

@Serializable
private data class PolarCustomerPortalUpdateRequest(
    @SerialName("billing_name") val billingName: String? = null,
    @SerialName("billing_address") val billingAddress: PolarBillingAddressInput? = null,
    @SerialName("tax_id") val taxId: String? = null,
    @SerialName("default_payment_method_id") val defaultPaymentMethodId: String? = null,
)

@Serializable
private data class PolarCreateTeamCustomerRequest(
    @SerialName("type") val type: String = "team",
    @SerialName("external_id") val externalId: String,
    @SerialName("name") val name: String,
    @SerialName("email") val email: String? = null,
    @SerialName("owner") val owner: PolarMemberOwnerCreate? = null,
)

@Serializable
private data class PolarMemberOwnerCreate(
    @SerialName("email") val email: String,
    @SerialName("name") val name: String? = null,
    @SerialName("external_id") val externalId: String? = null,
)

private fun String.pathSegment(): String = URLEncoder.encode(this, Charsets.UTF_8).replace("+", "%20")
