package com.apollodeploy.billing.feature.invoices.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class InvoiceItem(
    val id: String,
    val appSlug: String?,
    val productId: String?,
    val productName: String?,
    val orgId: String?,
    val email: String?,
    val amount: Int?,
    val currency: String?,
    val status: String?,
    val createdAt: String?,
    val raw: JsonObject? = null,
)

@Serializable
data class InvoiceDetailResponse(
    val invoice: InvoiceItem,
)

@Serializable
data class PaginatedInvoicesResponse(
    val apps: Map<String, List<InvoiceItem>>,
    val totalCount: Int,
    val page: Int,
    val limit: Int,
)

@Serializable
data class GenerateInvoiceAcceptedResponse(
    val message: String = "Invoice generation triggered",
)

sealed class GetInvoiceResult {
    data class Found(val response: InvoiceDetailResponse) : GetInvoiceResult()
    data class NotFound(val invoiceId: String) : GetInvoiceResult()
    data object PolarUnavailable : GetInvoiceResult()
}

sealed class ListInvoicesResult {
    data class Found(val response: PaginatedInvoicesResponse) : ListInvoicesResult()
    data object PolarUnavailable : ListInvoicesResult()
}

sealed class GenerateInvoiceResult {
    /** Invoice generation triggered successfully. */
    data object Accepted : GenerateInvoiceResult()

    /** No order with the given ID exists (Polar returned 404). */
    data class NotFound(val invoiceId: String) : GenerateInvoiceResult()

    /** Polar returned an error other than 404. */
    data object PolarUnavailable : GenerateInvoiceResult()
}
