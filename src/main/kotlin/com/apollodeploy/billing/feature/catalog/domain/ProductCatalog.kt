package com.apollodeploy.billing.feature.catalog.domain

import kotlinx.serialization.Serializable

@Serializable
data class ProductCatalogResponse(
    val appSlug: String,
    val products: List<CatalogProduct>,
)

@Serializable
data class CatalogProduct(
    val slug: String,
    val name: String,
    val description: String? = null,
    val kind: String,
    val checkoutAvailable: Boolean,
    val currency: String,
    val fallbackPriceCents: Int? = null,
    val prices: List<CatalogPrice> = emptyList(),
    val recurringInterval: String? = null,
    val priceSource: String,
    val limits: Map<String, Int> = emptyMap(),
    val features: Map<String, Boolean> = emptyMap(),
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
data class CatalogPrice(
    val amountType: String,
    val currency: String? = null,
    val amountCents: Int? = null,
    val unitAmount: String? = null,
    val meterId: String? = null,
)

sealed class ProductCatalogResult {
    data class Found(
        val response: ProductCatalogResponse,
    ) : ProductCatalogResult()

    data class UnknownApp(
        val appSlug: String,
    ) : ProductCatalogResult()

    data object PricingUnavailable : ProductCatalogResult()
}
