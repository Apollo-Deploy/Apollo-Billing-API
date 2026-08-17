package com.apollodeploy.billing.feature.invoices.application

import com.apollodeploy.billing.feature.invoices.domain.GenerateInvoiceResult
import com.apollodeploy.billing.feature.invoices.domain.GetInvoiceMeterUsageResult
import com.apollodeploy.billing.feature.invoices.domain.GetInvoiceResult
import com.apollodeploy.billing.feature.invoices.domain.InvoiceDetailResponse
import com.apollodeploy.billing.feature.invoices.domain.InvoiceMeterUsageResponse
import com.apollodeploy.billing.feature.invoices.domain.ListInvoicesResult
import com.apollodeploy.billing.feature.invoices.domain.PaginatedInvoicesResponse
import com.apollodeploy.billing.feature.invoices.infrastructure.persistence.InvoicesRepo
import org.slf4j.LoggerFactory

class InvoicesService(
    private val invoicesRepo: InvoicesRepo,
) {
    private val logger = LoggerFactory.getLogger(InvoicesService::class.java)

    suspend fun generateInvoice(
        orgId: String,
        invoiceId: String,
    ): GenerateInvoiceResult {
        val result = invoicesRepo.generateInvoice(orgId, invoiceId)
        if (result == GenerateInvoiceResult.PolarUnavailable) {
            logger.error(
                "[billing:invoices] failed to generate invoice id={} org={}",
                invoiceId,
                orgId,
            )
        }
        return result
    }

    suspend fun getInvoice(
        orgId: String,
        invoiceId: String,
    ): GetInvoiceResult {
        val result = invoicesRepo.getInvoice(orgId, invoiceId)
        if (result.value == null) {
            return if (result.statusCode == 404) {
                GetInvoiceResult.NotFound(invoiceId)
            } else {
                logger.error(
                    "[billing:invoices] failed to fetch invoice id={} org={} status={} body={}",
                    invoiceId,
                    orgId,
                    result.statusCode,
                    result.errorBody,
                )
                GetInvoiceResult.PolarUnavailable
            }
        }
        return GetInvoiceResult.Found(InvoiceDetailResponse(invoice = result.value))
    }

    suspend fun getInvoiceMeterUsage(
        orgId: String,
        invoiceId: String,
    ): GetInvoiceMeterUsageResult {
        val result = invoicesRepo.getInvoiceMeterUsage(orgId, invoiceId)
        if (result.value == null) {
            return if (result.statusCode == 404) {
                GetInvoiceMeterUsageResult.NotFound(invoiceId)
            } else {
                logger.error(
                    "[billing:invoices] failed to fetch meter usage id={} org={} status={}",
                    invoiceId,
                    orgId,
                    result.statusCode,
                )
                GetInvoiceMeterUsageResult.PolarUnavailable
            }
        }
        return GetInvoiceMeterUsageResult.Found(
            InvoiceMeterUsageResponse(invoiceId = invoiceId, meterUsage = result.value),
        )
    }

    suspend fun listInvoices(
        orgId: String,
        page: Int = 1,
        limit: Int = 20,
    ): ListInvoicesResult {
        val result = invoicesRepo.listInvoices(orgId, page, limit)
        if (result.value == null) {
            logger.error(
                "[billing:invoices] failed to list invoices status={} body={}",
                result.statusCode,
                result.errorBody,
            )
            return ListInvoicesResult.PolarUnavailable
        }

        val grouped = result.value.groupBy { it.appSlug ?: "unknown" }
        val totalCount = result.value.size

        return ListInvoicesResult.Found(
            PaginatedInvoicesResponse(
                apps = grouped,
                totalCount = totalCount,
                page = page,
                limit = limit,
            ),
        )
    }
}
