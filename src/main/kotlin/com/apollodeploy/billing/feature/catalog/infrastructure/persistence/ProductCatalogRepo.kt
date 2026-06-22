package com.apollodeploy.billing.feature.catalog.infrastructure.persistence

import com.apollodeploy.billing.core.AppRegistry
import com.apollodeploy.billing.core.BillingCatalogItem
import com.apollodeploy.billing.feature.catalog.domain.CatalogPrice
import com.apollodeploy.billing.feature.catalog.domain.CatalogProduct
import com.apollodeploy.billing.feature.catalog.domain.ProductCatalogResponse
import com.apollodeploy.billing.feature.catalog.domain.ProductCatalogResult
import com.apollodeploy.billing.infrastructure.polar.PolarClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

class ProductCatalogRepo(
    private val appRegistry: AppRegistry,
    private val polarClient: PolarClient,
) {
    private val logger = LoggerFactory.getLogger(ProductCatalogRepo::class.java)
    private val cacheTtlMs = 5 * 60 * 1000L
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    suspend fun getCatalog(appSlug: String): ProductCatalogResult {
        val catalog = appRegistry.catalog(appSlug) ?: return ProductCatalogResult.UnknownApp(appSlug)
        val now = System.currentTimeMillis()
        cache[appSlug]?.takeIf { it.expiresAt > now }?.let {
            return ProductCatalogResult.Found(it.value)
        }

        val mutex = mutexes.computeIfAbsent(appSlug) { Mutex() }
        return mutex.withLock {
            cache[appSlug]?.takeIf { it.expiresAt > System.currentTimeMillis() }?.let {
                return@withLock ProductCatalogResult.Found(it.value)
            }

            val polarProducts =
                polarClient
                    .listProducts()
                    .value
                    ?.associateBy { it.string("id").orEmpty() }
                    ?: return@withLock ProductCatalogResult.PricingUnavailable

            val products =
                catalog.map { item ->
                    item.toResponse(polarProducts[item.polarProductId]) ?: return@withLock ProductCatalogResult.PricingUnavailable
                }
            val response =
                ProductCatalogResponse(
                    appSlug = appSlug,
                    products = products,
                )
            cache[appSlug] = CacheEntry(response, System.currentTimeMillis() + cacheTtlMs)
            ProductCatalogResult.Found(response)
        }
    }

    private fun BillingCatalogItem.toResponse(polarProduct: JsonObject?): CatalogProduct? {
        if (polarProduct == null) {
            logger.warn(
                "[billing:catalog] live Polar product unavailable appProduct={} polarProductId={}",
                slug,
                polarProductId,
            )
            return null
        }

        return CatalogProduct(
            slug = slug,
            name = polarProduct.string("name") ?: name,
            description = polarProduct.string("description") ?: description,
            kind = kind.name,
            checkoutAvailable = checkoutAvailable,
            currency = polarProduct.firstPriceCurrency() ?: currency,
            fallbackPriceCents = fallbackPriceCents,
            prices = polarProduct.prices(),
            recurringInterval = polarProduct.string("recurring_interval"),
            priceSource = "polar",
            limits = limits,
            features = features,
            metadata = metadata,
        )
    }

    private data class CacheEntry(
        val value: ProductCatalogResponse,
        val expiresAt: Long,
    )
}

private fun JsonObject?.string(key: String): String? = (this?.get(key) as? JsonPrimitive)?.contentOrNull

private fun JsonObject?.prices(): List<CatalogPrice> {
    val prices = this?.get("prices") as? JsonArray ?: return emptyList()
    return prices.mapNotNull { price ->
        val obj = price as? JsonObject ?: return@mapNotNull null
        CatalogPrice(
            amountType = obj.string("amount_type") ?: "unknown",
            currency = obj.string("price_currency"),
            amountCents = obj.int("price_amount"),
            unitAmount = obj.string("unit_amount"),
            meterId = obj.string("meter_id"),
        )
    }
}

private fun JsonObject?.firstPriceCurrency(): String? = prices().firstNotNullOfOrNull { it.currency }

private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
