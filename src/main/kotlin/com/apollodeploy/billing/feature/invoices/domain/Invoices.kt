package com.apollodeploy.billing.feature.invoices.domain

import kotlinx.serialization.Serializable

@Serializable
data class InvoiceItem(
    val id: String,
    val appSlug: String?,
    val productName: String?,
    val invoiceNumber: String?,
    /** Final total in cents, including tax/VAT when Polar provides it. */
    val amount: Int?,
    val currency: String?,
    val status: String?,
    val createdAt: String?,
)

@Serializable
data class InvoiceMeterUsage(
    val meterId: String,
    val meterName: String,
    val unit: String?,
    val usedUnits: Long,
    val consumedUnits: Long,
    val creditedUnits: Long,
    val balance: Long,
)

@Serializable
data class InvoiceMeterUsageResponse(
    val invoiceId: String,
    val meterUsage: List<InvoiceMeterUsage>,
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
data class GenerateInvoiceResponse(
    val message: String = "Invoice generated",
    val downloadUrl: String? = null,
)

sealed class GetInvoiceResult {
    data class Found(val response: InvoiceDetailResponse) : GetInvoiceResult()
    data class NotFound(val invoiceId: String) : GetInvoiceResult()
    data object PolarUnavailable : GetInvoiceResult()
}

sealed class GetInvoiceMeterUsageResult {
    data class Found(val response: InvoiceMeterUsageResponse) : GetInvoiceMeterUsageResult()
    data class NotFound(val invoiceId: String) : GetInvoiceMeterUsageResult()
    data object PolarUnavailable : GetInvoiceMeterUsageResult()
}

sealed class ListInvoicesResult {
    data class Found(val response: PaginatedInvoicesResponse) : ListInvoicesResult()
    data object PolarUnavailable : ListInvoicesResult()
}

sealed class GenerateInvoiceResult {
    /** Invoice is ready — includes a pre-signed PDF download URL from Polar. */
    data class Generated(val downloadUrl: String) : GenerateInvoiceResult()

    /** Generation was triggered but the PDF URL is not ready yet. */
    data object Pending : GenerateInvoiceResult()

    /** No order with the given ID exists (Polar returned 404). */
    data class NotFound(val invoiceId: String) : GenerateInvoiceResult()

    /** Polar returned an error other than 404. */
    data object PolarUnavailable : GenerateInvoiceResult()
}
