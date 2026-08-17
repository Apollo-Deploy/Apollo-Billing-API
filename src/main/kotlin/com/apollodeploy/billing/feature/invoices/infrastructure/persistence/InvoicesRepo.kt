package com.apollodeploy.billing.feature.invoices.infrastructure.persistence

import kotlinx.coroutines.delay
import com.apollodeploy.billing.core.AppRegistry
import com.apollodeploy.billing.feature.invoices.domain.GenerateInvoiceResult
import com.apollodeploy.billing.feature.invoices.domain.InvoiceItem
import com.apollodeploy.billing.feature.invoices.domain.InvoiceMeterUsage
import com.apollodeploy.billing.infrastructure.polar.model.PolarCallResult
import com.apollodeploy.billing.infrastructure.polar.PolarClient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Repository that fetches orders/invoices from Polar and maps them
 * to our domain model using the app registry for product→app resolution.
 */
class InvoicesRepo(
    private val polarClient: PolarClient,
    private val appRegistry: AppRegistry,
) {
    suspend fun generateInvoice(
        orgId: String,
        invoiceId: String,
    ): GenerateInvoiceResult {
        val ownership = getOrderForOrg(orgId, invoiceId)
        when {
            ownership.statusCode == 404 -> return GenerateInvoiceResult.NotFound(invoiceId)
            ownership.value == null -> return GenerateInvoiceResult.PolarUnavailable
        }

        val result = polarClient.generateOrderInvoice(orgId, invoiceId)
        when {
            result.statusCode == 404 -> return GenerateInvoiceResult.NotFound(invoiceId)
            result.value == null -> return GenerateInvoiceResult.PolarUnavailable
        }

        repeat(INVOICE_URL_POLL_ATTEMPTS) { attempt ->
            if (attempt > 0) delay(INVOICE_URL_POLL_DELAY_MS)
            val urlResult = polarClient.getOrderInvoiceUrl(invoiceId)
            when {
                urlResult.value != null ->
                    return GenerateInvoiceResult.Generated(urlResult.value)
                urlResult.statusCode == 404 -> Unit
                else -> return GenerateInvoiceResult.PolarUnavailable
            }
        }

        return GenerateInvoiceResult.Pending
    }

    private companion object {
        const val INVOICE_URL_POLL_ATTEMPTS = 10
        const val INVOICE_URL_POLL_DELAY_MS = 500L
    }

    suspend fun getInvoice(
        orgId: String,
        invoiceId: String,
    ): PolarCallResult<InvoiceItem> {
        val result = getOrderForOrg(orgId, invoiceId)
        val order = result.value
            ?: return PolarCallResult.failure(result.statusCode, result.errorBody ?: "Order not found")
        return PolarCallResult.success(mapOrderToInvoice(order), result.statusCode ?: 200)
    }

    suspend fun getInvoiceMeterUsage(
        orgId: String,
        invoiceId: String,
    ): PolarCallResult<List<InvoiceMeterUsage>> {
        val result = getOrderForOrg(orgId, invoiceId)
        val order = result.value
            ?: return PolarCallResult.failure(result.statusCode, result.errorBody ?: "Order not found")

        val subscription = order["subscription"]?.jsonObject
            ?: return PolarCallResult.success(emptyList(), result.statusCode ?: 200)
        val periodStart = subscription["current_period_start"]?.jsonPrimitive?.contentOrNull
            ?: return PolarCallResult.success(emptyList(), result.statusCode ?: 200)
        val periodEnd = subscription["current_period_end"]?.jsonPrimitive?.contentOrNull
            ?: return PolarCallResult.success(emptyList(), result.statusCode ?: 200)

        val usage = polarClient.getInvoiceMeterUsage(orgId, periodStart, periodEnd)
        return usage.value
            ?.map {
                InvoiceMeterUsage(
                    meterId = it.meterId,
                    meterName = it.meterName,
                    unit = it.unit,
                    usedUnits = it.usedUnits,
                    consumedUnits = it.consumedUnits,
                    creditedUnits = it.creditedUnits,
                    balance = it.balance,
                )
            }
            ?.let { PolarCallResult.success(it, usage.statusCode ?: 200) }
            ?: PolarCallResult.failure(usage.statusCode, usage.errorBody ?: "Meter usage unavailable")
    }

    private suspend fun getOrderForOrg(
        orgId: String,
        invoiceId: String,
    ): PolarCallResult<JsonObject> {
        val result = polarClient.getOrder(invoiceId)
        val order = result.value
            ?: return PolarCallResult.failure(result.statusCode, result.errorBody ?: "Order not found")
        val orderOrgId =
            order["customer"]?.jsonObject?.get("external_id")?.jsonPrimitive?.contentOrNull
        if (orderOrgId != orgId) {
            return PolarCallResult.failure(404, "Order not found")
        }
        return PolarCallResult.success(order, result.statusCode ?: 200)
    }

    suspend fun listInvoices(
        orgId: String,
        page: Int = 1,
        limit: Int = 20,
    ): PolarCallResult<List<InvoiceItem>> {
        val result = polarClient.listOrders(orgId, page, limit)
        if (result.value == null) {
            return PolarCallResult.failure(result.statusCode, result.errorBody ?: "Orders unavailable")
        }

        val items =
            result.value["items"]?.jsonArray
                ?.mapNotNull { it as? JsonObject }
                ?.map { mapOrderToInvoice(it) }
                ?: emptyList()

        return PolarCallResult.success(items, result.statusCode ?: 200)
    }

    private fun mapOrderToInvoice(order: JsonObject): InvoiceItem {
        val productId = order["product_id"]?.jsonPrimitive?.contentOrNull
            ?: order["product"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
        val productName = order["product"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull

        val appSlug =
            order["product"]?.jsonObject
                ?.get("metadata")?.jsonObject
                ?.get("apollo_app")?.jsonPrimitive?.contentOrNull
                ?: productId?.let { pid -> appRegistry.productForPolarProductId(pid)?.appSlug }
        return InvoiceItem(
            id = order["id"]?.jsonPrimitive?.contentOrNull ?: "",
            appSlug = appSlug,
            productName = productName,
            invoiceNumber = order["invoice_number"]?.jsonPrimitive?.contentOrNull,
            amount = orderTotalAmount(order),
            currency = order["currency"]?.jsonPrimitive?.contentOrNull,
            status = order["status"]?.jsonPrimitive?.contentOrNull,
            createdAt = order["created_at"]?.jsonPrimitive?.contentOrNull,
        )
    }

    /** Polar `amount` is pre-tax; use `total_amount` or net + tax for the final total. */
    private fun orderTotalAmount(order: JsonObject): Int? {
        order["total_amount"]?.jsonPrimitive?.intOrNull?.let { return it }
        val net = order["net_amount"]?.jsonPrimitive?.intOrNull
        val tax = order["tax_amount"]?.jsonPrimitive?.intOrNull
        if (net != null && tax != null) return net + tax
        return null
    }
}
