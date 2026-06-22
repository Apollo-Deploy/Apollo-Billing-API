package com.apollodeploy.billing.feature.enforce.infrastructure.persistence

import com.apollodeploy.billing.core.AppRegistry
import com.apollodeploy.billing.core.BillingEnforcer

class EnforceRepo(
    private val registry: AppRegistry,
) {
    fun getEnforcer(appSlug: String): BillingEnforcer? = registry.get(appSlug)
}
