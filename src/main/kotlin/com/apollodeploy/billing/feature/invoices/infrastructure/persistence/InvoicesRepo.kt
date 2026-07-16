package com.apollodeploy.billing.feature.invoices.infrastructure.persistence

import com.apollodeploy.billing.core.AppRegistry
import com.apollodeploy.billing.feature.invoices.domain.GenerateInvoiceResult
import com.apollodeploy.billing.feature.invoices.domain.InvoiceItem
import com.apollodeploy.billing.infrastructure.polar.PolarCallResult
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
        val result = polarClient.generateOrderInvoice(orgId, invoiceId)
        return when {
            result.value != null -> GenerateInvoiceResult.Accepted
            result.statusCode == 404 -> GenerateInvoiceResult.NotFound(invoiceId)
            else -> GenerateInvoiceResult.PolarUnavailable
        }
    }

    suspend fun getInvoice(invoiceId: String): PolarCallResult<InvoiceItem> {
        val result = polarClient.getOrder(invoiceId)
        if (result.value == null) {
            return PolarCallResult.failure(result.statusCode, result.errorBody ?: "Order not found")
        }
        return PolarCallResult.success(mapOrderToInvoice(result.value), result.statusCode ?: 200)
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

        val appSlug = productId?.let { pid ->
            appRegistry.productForPolarProductId(pid)?.appSlug
        }

        val customer = order["customer"] as? JsonObject
        val orgId = customer?.get("external_id")?.jsonPrimitive?.contentOrNull
            ?: order["customer_id"]?.jsonPrimitive?.contentOrNull

        return InvoiceItem(
            id = order["id"]?.jsonPrimitive?.contentOrNull ?: "",
            appSlug = appSlug,
            productId = productId,
            productName = productName,
            orgId = orgId,
            email = customer?.get("email")?.jsonPrimitive?.contentOrNull,
            amount = order["amount"]?.jsonPrimitive?.intOrNull,
            currency = order["currency"]?.jsonPrimitive?.contentOrNull,
            status = order["status"]?.jsonPrimitive?.contentOrNull,
            createdAt = order["created_at"]?.jsonPrimitive?.contentOrNull,
            raw = order,
        )
    }
}
