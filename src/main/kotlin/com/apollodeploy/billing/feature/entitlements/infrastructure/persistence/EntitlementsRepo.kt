package com.apollodeploy.billing.feature.entitlements.infrastructure.persistence

import com.apollodeploy.billing.core.AppRegistry
import com.apollodeploy.billing.core.BillingEnforcer

class EntitlementsRepo(
    private val registry: AppRegistry,
) {
    fun getEnforcer(appSlug: String): BillingEnforcer? = registry.get(appSlug)
}
