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
    ): PolarCallResult<JsonObject> {
        if (apiKey.isBlank()) {
            logger.warn("[billing:polar] POLAR_API_KEY is not configured — cannot update customer billing info org={}", orgId)
            return PolarCallResult.failure(null, "POLAR_API_KEY is not configured")
        }

        val portalUpdateRequired =
            billingName != null || billingAddress != null || taxId != null || defaultPaymentMethodId != null
        var latestCustomer: JsonObject? = null

        if (portalUpdateRequired) {
            val sessionResult = createCustomerSession(orgId)
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

        if (email != null) {
            val emailUpdate =
                updateCustomerByExternalId(
                    orgId = orgId,
                    email = email,
                )
            if (emailUpdate.value == null) return emailUpdate
            latestCustomer = emailUpdate.value
        }

        return latestCustomer?.let { PolarCallResult.success(it, 200) }
            ?: PolarCallResult.failure(400, "No billing fields provided")
    }

    suspend fun listCustomerPaymentMethods(
        orgId: String,
        page: Int = 1,
        limit: Int = 10,
    ): PolarCallResult<JsonObject> {
        if (apiKey.isBlank()) {
            logger.warn("[billing:polar] POLAR_API_KEY is not configured — cannot list payment methods org={}", orgId)
            return PolarCallResult.failure(null, "POLAR_API_KEY is not configured")
        }

        val sessionResult = createCustomerSession(orgId)
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
    ): PolarCallResult<Unit> {
        if (apiKey.isBlank()) {
            logger.warn("[billing:polar] POLAR_API_KEY is not configured — cannot delete payment method org={}", orgId)
            return PolarCallResult.failure(null, "POLAR_API_KEY is not configured")
        }

        val sessionResult = createCustomerSession(orgId)
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

    private suspend fun createCustomerSession(orgId: String): PolarCallResult<PolarCustomerSession> =
        try {
            withTimeout(timeoutMs) {
                val response =
                    httpClient.post("$baseUrl/v1/customer-sessions/") {
                        contentType(ContentType.Application.Json)
                        bearerAuth(apiKey)
                        setBody(PolarCreateCustomerSessionRequest(externalCustomerId = orgId))
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

private fun String.pathSegment(): String = URLEncoder.encode(this, Charsets.UTF_8).replace("+", "%20")
