package com.apollodeploy.billing.feature.checkout.application

import com.apollodeploy.billing.feature.checkout.domain.CreateCheckoutRequest
import com.apollodeploy.billing.feature.checkout.domain.CreateCheckoutResponse
import com.apollodeploy.billing.feature.checkout.domain.CreateCheckoutResult
import com.apollodeploy.billing.feature.checkout.infrastructure.persistence.CheckoutRepo
import com.apollodeploy.billing.infrastructure.audit.AuditEvent
import com.apollodeploy.billing.infrastructure.audit.AuditLogClient
import com.apollodeploy.billing.infrastructure.audit.AuditStatus
import com.apollodeploy.billing.infrastructure.validation.UrlValidator

class CheckoutService(
    private val repository: CheckoutRepo,
    private val auditLogClient: AuditLogClient,
) {
    suspend fun createCheckout(req: CreateCheckoutRequest): CreateCheckoutResult {
        // Validate redirect URLs to prevent open-redirect attacks
        val successUrlError = UrlValidator.validateRedirectUrl(req.successUrl)
        if (successUrlError != null) {
            auditLogClient.log(
                AuditEvent(
                    module = "checkout",
                    action = "create_failed",
                    resourceType = "checkout_session",
                    organizationId = req.orgId,
                    status = AuditStatus.FAILURE,
                    errorMessage = "Invalid successUrl: $successUrlError",
                    metadata = mapOf("appSlug" to req.appSlug, "productSlug" to req.productSlug),
                ),
            )
            return CreateCheckoutResult.InvalidUrl(
                field = "successUrl",
                reason = successUrlError,
            )
        }
        val returnUrlError = UrlValidator.validateRedirectUrl(req.returnUrl)
        if (returnUrlError != null) {
            auditLogClient.log(
                AuditEvent(
                    module = "checkout",
                    action = "create_failed",
                    resourceType = "checkout_session",
                    organizationId = req.orgId,
                    status = AuditStatus.FAILURE,
                    errorMessage = "Invalid returnUrl: $returnUrlError",
                    metadata = mapOf("appSlug" to req.appSlug, "productSlug" to req.productSlug),
                ),
            )
            return CreateCheckoutResult.InvalidUrl(
                field = "returnUrl",
                reason = returnUrlError,
            )
        }

        val product =
            repository.findProduct(req.appSlug, req.productSlug)
                ?: run {
                    auditLogClient.log(
                        AuditEvent(
                            module = "checkout",
                            action = "create_failed",
                            resourceType = "checkout_session",
                            organizationId = req.orgId,
                            status = AuditStatus.FAILURE,
                            errorMessage = "Unknown product: ${req.appSlug}/${req.productSlug}",
                            metadata =
                                mapOf(
                                    "appSlug" to req.appSlug,
                                    "productSlug" to req.productSlug,
                                ),
                        ),
                    )
                    return CreateCheckoutResult.UnknownProduct(
                        appSlug = req.appSlug,
                        productSlug = req.productSlug,
                    )
                }

        val session =
            repository.createCheckoutSession(
                orgId = req.orgId,
                productIds = listOf(product.polarProductId),
                customerEmail = req.customerEmail,
                customerName = req.customerName,
                successUrl = req.successUrl,
                returnUrl = req.returnUrl,
                metadata =
                    req.metadata +
                        mapOf(
                            "app_slug" to product.appSlug,
                            "product_slug" to product.slug,
                            "product_kind" to product.kind.name.lowercase(),
                        ),
            ) ?: run {
                auditLogClient.log(
                    AuditEvent(
                        module = "checkout",
                        action = "create_failed",
                        resourceType = "checkout_session",
                        organizationId = req.orgId,
                        status = AuditStatus.FAILURE,
                        errorMessage = "Polar checkout session unavailable",
                        metadata =
                            mapOf(
                                "appSlug" to product.appSlug,
                                "productSlug" to product.slug,
                                "productKind" to product.kind.name.lowercase(),
                            ),
                    ),
                )
                return CreateCheckoutResult.Unavailable
            }

        auditLogClient.log(
            AuditEvent(
                module = "checkout",
                action = "created",
                resourceType = "checkout_session",
                resourceId = session.id,
                organizationId = req.orgId,
                status = AuditStatus.SUCCESS,
                metadata =
                    mapOf(
                        "appSlug" to product.appSlug,
                        "productSlug" to product.slug,
                        "productKind" to product.kind.name.lowercase(),
                    ),
            ),
        )

        return CreateCheckoutResult.Created(
            CreateCheckoutResponse(
                id = session.id,
                url = session.url,
                expiresAt = session.expiresAt,
                productKind = product.kind.name.lowercase(),
            ),
        )
    }
}
