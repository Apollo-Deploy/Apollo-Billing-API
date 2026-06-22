package com.apollodeploy.billing.core

/**
 * Shared billing app/product registration.
 *
 * New internal apps should expose one [BillingAppRegistration] with their
 * enforcer plus every Polar product ID that can appear in checkouts/webhooks.
 */
data class BillingAppRegistration(
    val slug: String,
    val enforcer: BillingEnforcer,
    val products: List<BillingProduct> = emptyList(),
    val catalog: List<BillingCatalogItem> = emptyList(),
) {
    init {
        require(slug.isNotBlank()) { "Billing app slug cannot be blank" }
        require(products.all { it.appSlug == slug }) {
            "All products for app $slug must use the same appSlug"
        }
    }
}

data class BillingCatalogItem(
    val slug: String,
    val polarProductId: String,
    val name: String,
    val description: String? = null,
    val kind: BillingCatalogProductKind,
    val currency: String = "usd",
    val fallbackPriceCents: Int? = null,
    val checkoutAvailable: Boolean = true,
    val limits: Map<String, Int> = emptyMap(),
    val features: Map<String, Boolean> = emptyMap(),
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(slug.isNotBlank()) { "Billing catalog item slug cannot be blank" }
        require(name.isNotBlank()) { "Billing catalog item name cannot be blank" }
    }
}

data class BillingProduct(
    val appSlug: String,
    val slug: String,
    val polarProductId: String,
    val kind: BillingProductKind,
) {
    init {
        require(appSlug.isNotBlank()) { "Billing product appSlug cannot be blank" }
        require(slug.isNotBlank()) { "Billing product slug cannot be blank" }
        require(polarProductId.isNotBlank()) { "Billing product Polar product ID cannot be blank" }
    }
}

enum class BillingProductKind {
    SUBSCRIPTION,
    ONE_TIME_PURCHASE,
}

/**
 * Product-model kind used inside app catalogs.
 *
 * This is intentionally richer than [BillingProductKind]. Runtime Polar
 * registration only needs to know whether webhooks/checkouts are subscription
 * or one-time flows, while app catalogs need to distinguish plans, add-ons,
 * usage-based products, credit packs, and permanent purchases.
 */
enum class BillingCatalogProductKind {
    SUBSCRIPTION,
    SUBSCRIPTION_ADD_ON,
    USAGE_BASED_SUBSCRIPTION,
    ONE_TIME_PURCHASE,
    CREDIT_PACK,
    PERMANENT_PURCHASE,
}

fun BillingCatalogProductKind.toBillingProductKind(): BillingProductKind =
    when (this) {
        BillingCatalogProductKind.SUBSCRIPTION -> BillingProductKind.SUBSCRIPTION
        BillingCatalogProductKind.SUBSCRIPTION_ADD_ON -> BillingProductKind.SUBSCRIPTION
        BillingCatalogProductKind.USAGE_BASED_SUBSCRIPTION -> BillingProductKind.SUBSCRIPTION
        BillingCatalogProductKind.ONE_TIME_PURCHASE -> BillingProductKind.ONE_TIME_PURCHASE
        BillingCatalogProductKind.CREDIT_PACK -> BillingProductKind.ONE_TIME_PURCHASE
        BillingCatalogProductKind.PERMANENT_PURCHASE -> BillingProductKind.ONE_TIME_PURCHASE
    }
