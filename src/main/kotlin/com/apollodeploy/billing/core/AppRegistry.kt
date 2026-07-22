package com.apollodeploy.billing.core

/**
 * Apollo Billing — app registry.
 *
 * Each product (signal, deploy, etc.) registers a [BillingEnforcer] keyed by
 * its app slug, plus the Polar products that belong to that app. The registry
 * is the single source of truth for which apps/products the billing service
 * knows about.
 *
 * Populated at startup in AppAssembly.
 */
class AppRegistry(
    apps: List<BillingAppRegistration>,
) {
    private val registrations: Map<String, BillingAppRegistration> = apps.associateBy { it.slug }
    private val enforcers: Map<String, BillingEnforcer> = registrations.mapValues { it.value.enforcer }
    private val productsByPolarProductId: Map<String, BillingProduct> =
        apps.flatMap { it.products }.associateByUnique { it.polarProductId }

    init {
        require(apps.isNotEmpty()) { "At least one billing app must be registered" }
        require(registrations.size == apps.size) {
            "Duplicate billing app slugs are not allowed: ${apps.map { it.slug }}"
        }
    }

    /** Returns the [BillingEnforcer] for [appSlug], or null if unknown. */
    fun get(appSlug: String): BillingEnforcer? = enforcers[appSlug]

    /** Returns the full app registration for [appSlug], or null if unknown. */
    fun app(appSlug: String): BillingAppRegistration? = registrations[appSlug]

    /** All registered app slugs. */
    fun knownApps(): Set<String> = enforcers.keys

    /** Returns a registered product by app/product slug. */
    fun product(
        appSlug: String,
        productSlug: String,
    ): BillingProduct? = registrations[appSlug]?.products?.firstOrNull { it.slug == productSlug }

    /** Returns the product catalog for an app. */
    fun catalog(appSlug: String): List<BillingCatalogItem>? = registrations[appSlug]?.catalog

    /** Resolves the app/product owner for a Polar product ID from webhooks. */
    fun productForPolarProductId(polarProductId: String): BillingProduct? = productsByPolarProductId[polarProductId]

    /** Human-readable plan/product name from the registered app catalog. */
    fun productDisplayName(polarProductId: String): String? = catalogItemForPolarProductId(polarProductId)?.name

    /** Registered catalog item for a Polar product ID. */
    fun catalogItemForPolarProductId(polarProductId: String): BillingCatalogItem? {
        val product = productForPolarProductId(polarProductId) ?: return null
        return catalog(product.appSlug)?.firstOrNull { it.polarProductId == polarProductId }
    }

    /** Invalidates the entitlement cache for an org across all registered apps. */
    fun invalidateAll(orgId: String) = enforcers.values.forEach { it.invalidate(orgId) }
}

private fun <T, K> Iterable<T>.associateByUnique(keySelector: (T) -> K): Map<K, T> {
    val result = LinkedHashMap<K, T>()
    for (item in this) {
        val key = keySelector(item)
        require(!result.containsKey(key)) { "Duplicate billing product key: $key" }
        result[key] = item
    }
    return result
}
