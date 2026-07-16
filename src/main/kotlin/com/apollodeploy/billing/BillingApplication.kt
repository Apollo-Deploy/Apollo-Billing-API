package com.apollodeploy.billing

import com.apollodeploy.billing.bootstrap.AppAssembly
import com.apollodeploy.billing.feature.catalog.api.productCatalogRoutes
import com.apollodeploy.billing.feature.checkout.api.checkoutRoutes
import com.apollodeploy.billing.feature.customer.api.customerBillingRoutes
import com.apollodeploy.billing.feature.docs.api.docsRoutes
import com.apollodeploy.billing.feature.enforce.api.enforceRoutes
import com.apollodeploy.billing.feature.entitlements.api.entitlementsRoutes
import com.apollodeploy.billing.feature.health.api.healthRoutes
import com.apollodeploy.billing.feature.invoices.api.invoicesRoutes
import com.apollodeploy.billing.feature.subscriptions.api.subscriptionsRoutes
import com.apollodeploy.billing.feature.usage.api.usageIngestRoutes
import com.apollodeploy.billing.feature.webhook.api.polarWebhookRoutes
import com.apollodeploy.billing.infrastructure.config.AppConfig
import com.apollodeploy.billing.infrastructure.iam.OAuthServiceAuthException
import com.apollodeploy.billing.infrastructure.iam.oauthInternalRoutes
import com.apollodeploy.billing.infrastructure.validation.InvalidRedirectUrlException
import com.apollodeploy.tesseract.ManifestInfo
import com.apollodeploy.tesseract.TesseractPlugin
import io.github.smiley4.ktoropenapi.OpenApi
import io.github.smiley4.ktoropenapi.config.AuthScheme
import io.github.smiley4.ktoropenapi.config.AuthType
import io.github.smiley4.ktoropenapi.config.OutputFormat
import io.github.smiley4.ktoropenapi.config.SchemaGenerator
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

fun main() {
    val isManifestMode = System.getenv("TESSERACT_GENERATE").let { it == "1" || it == "true" }
    val assembly = if (isManifestMode) AppAssembly.createForManifest() else AppAssembly.create()
    embeddedServer(Netty, port = AppConfig.billingPort, host = "0.0.0.0") {
        configure(assembly)
    }.start(wait = true)
}

/** Ktor config-file entry point — keeps the reference in application.conf valid. */
fun Application.module() {
    configure(AppAssembly.create())
}

private fun Application.configure(assembly: AppAssembly) {
    val logger = LoggerFactory.getLogger("com.apollodeploy.billing")

    installCorePlugins()
    installErrorHandling(logger)
    registerRoutes(assembly)
    install(TesseractPlugin) {
        info =
            ManifestInfo(
                title = "Apollo Billing API",
                version = "1.0.0",
                description = "Server-to-server SDK for Apollo Deploy billing, entitlement, checkout, usage, and customer billing operations.",
                baseUrl = System.getenv("TESSERACT_BASE_URL") ?: "https://billing.apollodeploy.com",
            )
        packageName = System.getenv("TESSERACT_PACKAGE_NAME") ?: "@apollo-deploy/billing-sdk"
        packageVersion = System.getenv("TESSERACT_PACKAGE_VERSION")?.takeIf { it.isNotBlank() }
        clientName = System.getenv("TESSERACT_CLIENT_NAME") ?: "ApolloBilling"
        output = System.getenv("TESSERACT_SDK_OUTPUT") ?: "./sdk"
        language = System.getenv("TESSERACT_LANGUAGE") ?: "typescript"
        sdkStyle = System.getenv("TESSERACT_SDK_STYLE") ?: "functional"
        clientType = System.getenv("TESSERACT_CLIENT_TYPE") ?: "internal"
    }

    environment.monitor.subscribe(ApplicationStopped) {
        logger.info("[billing] Shutting down")
        assembly.close()
    }
    logger.info("Apollo Billing started on port ${AppConfig.billingPort}")
}

// ── Private helpers ───────────────────────────────────────────────────────────

private fun Application.installCorePlugins() {
    install(RateLimit) {
        register(RateLimitName("webhook")) {
            rateLimiter(limit = 100, refillPeriod = kotlin.time.Duration.parse("60s"))
        }
        register(RateLimitName("internal")) {
            rateLimiter(limit = 1000, refillPeriod = kotlin.time.Duration.parse("60s"))
            requestKey { call -> call.request.local.remoteHost }
        }
        register(RateLimitName("public")) {
            rateLimiter(limit = 60, refillPeriod = kotlin.time.Duration.parse("60s"))
            requestKey { call -> call.request.local.remoteHost }
        }
    }

    install(OpenApi) {
        info {
            title = "Apollo Billing API"
            version = "1.0.0"
            description =
                "Central billing API for Apollo Deploy internal apps. It provides server-to-server checkout, " +
                "entitlement resolution, billing enforcement, usage ingestion, customer billing profile " +
                "management, and Polar webhook handling."
        }
        server {
            url = "http://localhost:3040"
            description = "Local development"
        }
        server {
            url = "https://billing.dev.apollodeploy.com"
            description = "Development deployment"
        }
        server {
            url = "https://billing.apollodeploy.com"
            description = "Production"
        }
        pathFilter = { _, url -> url.firstOrNull() != "docs" && url.isNotEmpty() }
        schemas {
            generator =
                SchemaGenerator.reflection {
                    explicitNullTypes = false
                }
        }
        tags {
            tagGenerator = { url ->
                when (url.firstOrNull()) {
                    "billing" ->
                        when (url.getOrNull(1)) {
                            "catalog" -> listOf("Catalog")
                            else -> listOf("Billing")
                        }
                    "health" -> listOf("Health")
                    "webhooks" -> listOf("Webhooks")
                    "internal" ->
                        when (url.getOrNull(2)) {
                            "enforce" -> listOf("Enforcement")
                            "entitlements" -> listOf("Entitlements")
                            "checkout" -> listOf("Checkout")
                            "customer" -> listOf("Customer Billing")
                            "usage" -> listOf("Usage")
                            "subscriptions" -> listOf("Subscriptions")
                            "invoices" -> listOf("Invoices")
                            else -> listOf("Billing")
                        }
                    else -> listOf()
                }
            }
        }
        security {
            securityScheme("serviceToken") {
                type = AuthType.HTTP
                scheme = AuthScheme.BEARER
                bearerFormat = "JWT"
                description = "Short-lived service JWT for internal backend-to-billing calls."
            }
        }
        ignoredRouteSelectorClassNames = ignoredRouteSelectorClassNames +
            "io.ktor.server.auth.AuthenticationRouteSelector"
        outputFormat = OutputFormat.JSON
    }

    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                explicitNulls = false
            },
        )
    }

    install(CallLogging) {
        level = Level.INFO
        filter { call -> call.request.local.uri != "/health" }
    }
}

private fun Application.installErrorHandling(logger: org.slf4j.Logger) {
    install(StatusPages) {
        exception<OAuthServiceAuthException> { call, cause ->
            call.respond(
                HttpStatusCode.Unauthorized,
                mapOf("code" to cause.code, "message" to cause.message),
            )
        }
        exception<InvalidRedirectUrlException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf(
                    "code" to "billing.invalid_redirect_url",
                    "message" to "Invalid ${cause.fieldName}: ${cause.reason}",
                ),
            )
        }
        exception<Throwable> { call, cause ->
            logger.error("Unhandled exception on ${call.request.local.uri}", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("code" to "internal_error", "message" to "An unexpected error occurred"),
            )
        }
    }
}

private fun Application.registerRoutes(assembly: AppAssembly) {
    routing {
        healthRoutes(assembly.healthController)
        docsRoutes()
        rateLimit(RateLimitName("public")) {
            productCatalogRoutes(assembly.productCatalogController)
        }
        oauthInternalRoutes(assembly.httpClient) {
            rateLimit(RateLimitName("internal")) {
                enforceRoutes(assembly.enforceController)
                entitlementsRoutes(assembly.entitlementsController)
                checkoutRoutes(assembly.checkoutController)
                customerBillingRoutes(assembly.customerBillingController)
                usageIngestRoutes(assembly.usageIngestController)
                subscriptionsRoutes(assembly.subscriptionsController)
                invoicesRoutes(assembly.invoicesController)
            }
        }
        rateLimit(RateLimitName("webhook")) {
            polarWebhookRoutes(assembly.polarWebhookController)
        }
    }
}
