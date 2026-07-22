package com.apollodeploy.billing.feature.checkout.infrastructure.persistence

import com.apollodeploy.billing.core.AppRegistry
import com.apollodeploy.billing.core.BillingProduct
import com.apollodeploy.billing.infrastructure.polar.model.PolarCheckoutSession
import com.apollodeploy.billing.infrastructure.polar.PolarClient

class CheckoutRepo(
    private val registry: AppRegistry,
    private val polarClient: PolarClient,
) {
    fun findProduct(
        appSlug: String,
        productSlug: String,
    ): BillingProduct? = registry.product(appSlug, productSlug)

    suspend fun createCheckoutSession(
        orgId: String,
        productIds: List<String>,
        customerEmail: String?,
        customerName: String?,
        successUrl: String?,
        returnUrl: String?,
        metadata: Map<String, String>,
    ): PolarCheckoutSession? =
        polarClient.createCheckoutSession(
            orgId = orgId,
            productIds = productIds,
            customerEmail = customerEmail,
            customerName = customerName,
            successUrl = successUrl,
            returnUrl = returnUrl,
            metadata = metadata,
        )
}
