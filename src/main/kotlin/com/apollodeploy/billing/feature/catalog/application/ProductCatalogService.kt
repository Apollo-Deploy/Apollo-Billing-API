package com.apollodeploy.billing.feature.catalog.application

import com.apollodeploy.billing.feature.catalog.domain.ProductCatalogResult
import com.apollodeploy.billing.feature.catalog.infrastructure.persistence.ProductCatalogRepo

class ProductCatalogService(
    private val repo: ProductCatalogRepo,
) {
    suspend fun getCatalog(appSlug: String): ProductCatalogResult = repo.getCatalog(appSlug)
}
