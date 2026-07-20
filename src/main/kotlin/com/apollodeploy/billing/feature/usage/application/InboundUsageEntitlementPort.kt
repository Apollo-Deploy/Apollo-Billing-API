package com.apollodeploy.billing.feature.usage.application

/** Fail-closed entitlement check used before inbound-email usage reaches Polar. */
fun interface InboundUsageEntitlementPort {
    suspend fun isInboundReceivingAllowed(orgId: String): Boolean
}
