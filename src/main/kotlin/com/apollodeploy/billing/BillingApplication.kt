package com.apollodeploy.billing

import com.apollodeploy.billing.bootstrap.AppAssembly
import com.apollodeploy.billing.feature.catalog.api.productCatalogRoutes
import com.apollodeploy.billing.feature.checkout.api.checkoutRoutes
import com.apollodeploy.billing.feature.customer.api.customerBillingRoutes
import com.apollodeploy.billing.feature.docs.api.docsRoutes
import com.apollodeploy.billing.feature.docs.api.generateOpenApiSpec
import com.apollodeploy.billing.feature.enforce.api.enforceRoutes
import com.apollodeploy.billing.feature.entitlements.api.entitlementsRoutes
import com.apollodeploy.billing.feature.health.api.healthRoutes
import com.apollodeploy.billing.feature.invoices.api.invoicesRoutes
import com.apollodeploy.billing.feature.subscriptions.api.subscriptionsRoutes
import com.apollodeploy.billing.feature.usage.api.usageIngestRoutes
import com.apollodeploy.billing.feature.webhook.api.polarWebhookRoutes
import com.apollodeploy.billing.infrastructure.config.AppConfig
import com.apollodeploy.billing.infrastructure.validation.InvalidRedirectUrlException
import com.apollodeploy.oauth.m2m.ktor.MachineOAuth
import com.apollodeploy.oauth.m2m.ktor.machineAuthenticated
import io.github.smiley4.ktoropenapi.OpenApi
import io.github.smiley4.ktoropenapi.config.AuthScheme
import io.github.smiley4.ktoropenapi.config.AuthType
import io.github.smiley4.ktoropenapi.config.OutputFormat
import io.github.smiley4.ktoropenapi.config.SchemaGenerator
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.ServerReady
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.nio.file.Path
import kotlin.time.Duration.Companion.minutes

private const val API_TITLE = "Apollo Billing API"
private const val API_VERSION = "1.0.0"
private const val SERVER_HOST = "0.0.0.0"
private const val HEALTH_PATH = "/health"
private const val DEFAULT_BASE_URL = "https://api.billing.apollodeploy.com"

private const val INTERNAL_ERROR_CODE = "internal_error"
private const val INVALID_REDIRECT_CODE = "billing.invalid_redirect_url"

private const val AUTH_ROUTE_SELECTOR =
    "io.ktor.server.auth.AuthenticationRouteSelector"

private val logger = LoggerFactory.getLogger("com.apollodeploy.billing")

private val WebhookRateLimit = RateLimitName("webhook")
private val InternalRateLimit = RateLimitName("internal")
private val PublicRateLimit = RateLimitName("public")

private val RateLimitWindow = 1.minutes

private val ApiJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

private val EmptyTags = emptyList<String>()
private val BillingTags = listOf("Billing")
private val CatalogTags = listOf("Catalog")
private val HealthTags = listOf("Health")
private val WebhookTags = listOf("Webhooks")
private val EnforcementTags = listOf("Enforcement")
private val EntitlementTags = listOf("Entitlements")
private val CheckoutTags = listOf("Checkout")
private val CustomerBillingTags = listOf("Customer Billing")
private val UsageTags = listOf("Usage")
private val SubscriptionTags = listOf("Subscriptions")
private val InvoiceTags = listOf("Invoices")

@Serializable
private data class ErrorResponse(
    val code: String,
    val message: String?,
)

fun main() {
    val openApiOutputPath = System.getenv("OPENAPI_EXPORT_PATH")?.takeIf { it.isNotBlank() }
    val assembly =
        if (openApiOutputPath != null) {
            AppAssembly.createForOpenApiExport()
        } else {
            AppAssembly.create()
        }

    embeddedServer(
        factory = Netty,
        host = SERVER_HOST,
        port = AppConfig.port,
    ) {
        configure(assembly)

        if (openApiOutputPath != null) {
            val application = this
            monitor.subscribe(ServerReady) {
                val path = Path.of(openApiOutputPath)
                path.parent?.let {
                    java.nio.file.Files
                        .createDirectories(it)
                }
                java.nio.file.Files.writeString(
                    path,
                    generateOpenApiSpec(application),
                )
                logger.info("OpenAPI spec exported to {}", path)
                engine.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
            }
        }
    }.start(wait = true)
}

/**
 * Entry point used when Ktor loads the application from application.conf.
 */
fun Application.module() {
    configure(AppAssembly.create())
}

private fun Application.configure(assembly: AppAssembly) {
    installCorePlugins()
    installMachineOAuth(assembly)
    installOpenApi()
    installErrorHandling()
    registerRoutes(assembly)

    monitor.subscribe(ApplicationStopped) {
        logger.info("Apollo Billing shutting down")
        assembly.close()
    }

    logger.info(
        "Apollo Billing started on port {}",
        AppConfig.port,
    )
}

private fun Application.installCorePlugins() {
    install(CORS) {
        allowCredentials = true
        allowHost(
            AppConfig.corsAllowedDomain,
            schemes = if (AppConfig.environment == "production") listOf("https") else listOf("http", "https"),
        )
        allowHost(
            "*.${AppConfig.corsAllowedDomain}",
            schemes = if (AppConfig.environment == "production") listOf("https") else listOf("http", "https"),
        )
        listOf(
            HttpMethod.Get,
            HttpMethod.Post,
            HttpMethod.Put,
            HttpMethod.Delete,
            HttpMethod.Patch,
            HttpMethod.Options,
        ).forEach(::allowMethod)
        listOf(
            HttpHeaders.ContentType,
            HttpHeaders.Authorization,
            HttpHeaders.CacheControl,
            "X-Idempotency-Key",
            "X-CSRF-Token",
        ).forEach(::allowHeader)
        exposeHeader("X-Request-Id")
        maxAgeInSeconds = 86_400
    }

    install(ContentNegotiation) {
        json(ApiJson)
    }

    install(CallLogging) {
        level = Level.INFO
        filter { call ->
            call.request.path() != HEALTH_PATH
        }
    }

    install(RateLimit) {
        register(WebhookRateLimit) {
            rateLimiter(
                limit = 100,
                refillPeriod = RateLimitWindow,
            )
        }

        register(InternalRateLimit) {
            rateLimiter(
                limit = 1_000,
                refillPeriod = RateLimitWindow,
            )

            requestKey { call ->
                call.request.local.remoteHost
            }
        }

        register(PublicRateLimit) {
            rateLimiter(
                limit = 60,
                refillPeriod = RateLimitWindow,
            )

            requestKey { call ->
                call.request.local.remoteHost
            }
        }
    }
}

private fun Application.installMachineOAuth(assembly: AppAssembly) {
    val issuer =
        AppConfig.iam.allowedIssuers
            .firstOrNull()
            ?: error("IAM issuer URL is not configured")
    val allowedAudiences =
        AppConfig.iam.validAudiences
            .takeIf(Set<String>::isNotEmpty)
            ?: error("IAM valid audiences are not configured")
    val jwksUrl =
        AppConfig.iam.jwksUrl
            .takeIf(String::isNotBlank)
            ?: error("IAM JWKS URL is not configured")
    val serviceClientIds =
        AppConfig.iam.serviceClientIds
            .takeIf(Set<String>::isNotEmpty)
            ?: error("IAM service client IDs are not configured")
    install(MachineOAuth) {
        issuer(issuer)
        audience(allowedAudiences.first())
        audiences(*allowedAudiences.toTypedArray())
        jwks { url = jwksUrl }
        httpClient = assembly.httpClient
        algorithms("EdDSA")
        validate { principal ->
            principal.clientId.value in serviceClientIds
        }
        if (issuer.startsWith("http://", ignoreCase = true) ||
            jwksUrl.startsWith("http://", ignoreCase = true)
        ) {
            allowInsecureHttp()
        }
    }
}

private fun Application.installOpenApi() {
    install(OpenApi) {
        info {
            title = API_TITLE
            version = API_VERSION
            description =
                "Central billing API for Apollo Deploy internal applications. " +
                "Provides checkout, entitlement resolution, billing enforcement, " +
                "usage ingestion, customer billing management, and Polar webhook handling."
        }

        server {
            url = "http://localhost:3040"
            description = "Local development"
        }

        server {
            url = "https://api.billing.apollodeploy.local"
            description = "Development"
        }

        server {
            url = DEFAULT_BASE_URL
            description = "Production"
        }

        pathFilter = { _, path ->
            path.isNotEmpty() && path[0] != "docs"
        }

        schemas {
            generator =
                SchemaGenerator.reflection {
                    explicitNullTypes = false
                }
        }

        tags {
            tagGenerator = ::openApiTags
        }

        security {
            securityScheme("serviceToken") {
                type = AuthType.HTTP
                scheme = AuthScheme.BEARER
                bearerFormat = "JWT"
                description =
                    "Short-lived service JWT for internal backend-to-billing calls."
            }
        }

        ignoredRouteSelectorClassNames +=
            listOf(
                AUTH_ROUTE_SELECTOR,
                "io.ktor.server.plugins.ratelimit.RateLimitRouteSelector",
            )
        outputFormat = OutputFormat.JSON
    }
}

private fun openApiTags(path: List<String>): List<String> =
    when (path.firstOrNull()) {
        "health" -> HealthTags
        "webhooks" -> WebhookTags

        "billing" ->
            when (path.getOrNull(1)) {
                "catalog" -> CatalogTags
                else -> BillingTags
            }

        "internal" ->
            when (path.getOrNull(2)) {
                "enforce" -> EnforcementTags
                "entitlements" -> EntitlementTags
                "checkout" -> CheckoutTags
                "customer" -> CustomerBillingTags
                "usage" -> UsageTags
                "subscriptions" -> SubscriptionTags
                "invoices" -> InvoiceTags
                else -> BillingTags
            }

        else -> EmptyTags
    }

private fun Application.installErrorHandling() {
    install(StatusPages) {
        exception<UnsupportedMediaTypeException> { call, _ ->
            call.respond(
                status = HttpStatusCode.UnsupportedMediaType,
                message =
                    ErrorResponse(
                        code = "billing.unsupported_media_type",
                        message = "Request content type is not supported",
                    ),
            )
        }

        exception<ContentTransformationException> { call, _ ->
            call.respond(
                status = HttpStatusCode.BadRequest,
                message =
                    ErrorResponse(
                        code = "billing.invalid_request",
                        message = "Invalid or unsupported request body",
                    ),
            )
        }

        exception<InvalidRedirectUrlException> { call, cause ->
            call.respond(
                status = HttpStatusCode.BadRequest,
                message =
                    ErrorResponse(
                        code = INVALID_REDIRECT_CODE,
                        message = "Invalid ${cause.fieldName}: ${cause.reason}",
                    ),
            )
        }

        exception<Throwable> { call, cause ->
            logger.error(
                "Unhandled exception on {}",
                call.request.path(),
                cause,
            )

            call.respond(
                status = HttpStatusCode.InternalServerError,
                message =
                    ErrorResponse(
                        code = INTERNAL_ERROR_CODE,
                        message = "An unexpected error occurred",
                    ),
            )
        }
    }
}

private fun Application.registerRoutes(assembly: AppAssembly) {
    routing {
        healthRoutes(assembly.healthController)
        docsRoutes()

        rateLimit(PublicRateLimit) {
            productCatalogRoutes(
                assembly.productCatalogController,
            )
        }

        machineAuthenticated {
            rateLimit(InternalRateLimit) {
                enforceRoutes(assembly.enforceController)
                entitlementsRoutes(assembly.entitlementsController)
                checkoutRoutes(assembly.checkoutController)
                customerBillingRoutes(assembly.customerBillingController)
                usageIngestRoutes(assembly.usageIngestController)
                subscriptionsRoutes(assembly.subscriptionsController)
                invoicesRoutes(assembly.invoicesController)
            }
        }

        rateLimit(WebhookRateLimit) {
            polarWebhookRoutes(
                assembly.polarWebhookController,
            )
        }
    }
}
