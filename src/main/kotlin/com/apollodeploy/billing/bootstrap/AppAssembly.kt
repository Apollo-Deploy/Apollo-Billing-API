package com.apollodeploy.billing.bootstrap

import com.apollodeploy.billing.core.AppRegistry
import com.apollodeploy.billing.feature.catalog.api.ProductCatalogController
import com.apollodeploy.billing.feature.catalog.application.ProductCatalogService
import com.apollodeploy.billing.feature.catalog.infrastructure.persistence.ProductCatalogRepo
import com.apollodeploy.billing.feature.checkout.api.CheckoutController
import com.apollodeploy.billing.feature.checkout.application.CheckoutService
import com.apollodeploy.billing.feature.checkout.infrastructure.persistence.CheckoutRepo
import com.apollodeploy.billing.feature.customer.api.CustomerBillingController
import com.apollodeploy.billing.feature.customer.application.CustomerBillingService
import com.apollodeploy.billing.feature.customer.infrastructure.persistence.CustomerBillingRepo
import com.apollodeploy.billing.feature.enforce.api.EnforceController
import com.apollodeploy.billing.feature.enforce.application.EnforceService
import com.apollodeploy.billing.feature.enforce.infrastructure.persistence.EnforceRepo
import com.apollodeploy.billing.feature.entitlements.api.EntitlementsController
import com.apollodeploy.billing.feature.entitlements.application.EntitlementsService
import com.apollodeploy.billing.feature.entitlements.infrastructure.persistence.EntitlementsRepo
import com.apollodeploy.billing.feature.health.api.HealthController
import com.apollodeploy.billing.feature.health.application.HealthService
import com.apollodeploy.billing.feature.invoices.api.InvoicesController
import com.apollodeploy.billing.feature.invoices.application.InvoicesService
import com.apollodeploy.billing.feature.invoices.infrastructure.persistence.InvoicesRepo
import com.apollodeploy.billing.feature.signal.application.SignalBillingConfig
import com.apollodeploy.billing.feature.subscriptions.api.SubscriptionsController
import com.apollodeploy.billing.feature.subscriptions.application.SubscriptionsService
import com.apollodeploy.billing.feature.subscriptions.infrastructure.persistence.SubscriptionsQueryRepo
import com.apollodeploy.billing.feature.usage.api.UsageIngestController
import com.apollodeploy.billing.feature.usage.application.InboundUsageEntitlementPort
import com.apollodeploy.billing.feature.usage.application.UsageIngestService
import com.apollodeploy.billing.feature.usage.infrastructure.persistence.UsageIngestRepo
import com.apollodeploy.billing.feature.webhook.api.PolarWebhookController
import com.apollodeploy.billing.feature.webhook.application.PolarWebhookService
import com.apollodeploy.billing.feature.webhook.infrastructure.persistence.PolarWebhookRepo
import com.apollodeploy.billing.infrastructure.audit.AuditLogClient
import com.apollodeploy.billing.infrastructure.config.AppConfig
import com.apollodeploy.oauth.m2m.client.MachineOAuthClient
import com.apollodeploy.billing.infrastructure.persistence.DatabasePool
import com.apollodeploy.billing.infrastructure.persistence.SubscriptionRepo
import com.apollodeploy.billing.infrastructure.polar.PolarClient
import com.apollodeploy.billing.infrastructure.polar.PolarWebhookHandler
import com.apollodeploy.billing.infrastructure.redis.PolarStateCache
import com.apollodeploy.billing.infrastructure.redis.RedisPool
import com.apollodeploy.billing.infrastructure.webhook.WebhookDeduplicator
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import org.slf4j.LoggerFactory

private const val SIGNAL_APP = "signal"
private const val INBOUND_RECEIVING_FEATURE = "inboundReceiving"

private val HTTP_JSON =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        namingStrategy = JsonNamingStrategy.SnakeCase
    }

/**
 * Application composition root.
 *
 * Owns shared infrastructure and wires repositories, services, and controllers.
 */
class AppAssembly private constructor(
    val appRegistry: AppRegistry,
    val polarClient: PolarClient,
    val oAuthM2mClient: MachineOAuthClient,
    val httpClient: HttpClient,
    val healthController: HealthController,
    val productCatalogController: ProductCatalogController,
    val enforceController: EnforceController,
    val entitlementsController: EntitlementsController,
    val checkoutController: CheckoutController,
    val customerBillingController: CustomerBillingController,
    val usageIngestController: UsageIngestController,
    val polarWebhookController: PolarWebhookController,
    val subscriptionsController: SubscriptionsController,
    val invoicesController: InvoicesController,
    private val db: DatabasePool,
    private val platformReaderDb: DatabasePool?,
    private val signalDb: DatabasePool?,
    private val redis: RedisPool?,
) : AutoCloseable {

    companion object {
        private val logger = LoggerFactory.getLogger(AppAssembly::class.java)

        fun create(): AppAssembly =
            create(openApiExportOnly = false)

        /**
         * Creates an assembly without opening external database or Redis connections.
         */
        fun createForOpenApiExport(): AppAssembly =
            create(openApiExportOnly = true)

        private fun create(openApiExportOnly: Boolean): AppAssembly {
            logger.info(
                "Assembling Apollo Billing{}",
                if (openApiExportOnly) " for OpenAPI export" else "",
            )

            val httpClient = buildHttpClient()
            val db =
                if (openApiExportOnly) {
                    DatabasePool.createStub()
                } else {
                    DatabasePool.create()
                }

            val platformReaderDb =
                if (openApiExportOnly) {
                    db
                } else {
                    DatabasePool.createPlatformReader()
                }

            val signalDb =
                if (openApiExportOnly) {
                    null
                } else {
                    DatabasePool.createSignal()
                }

            val redis =
                if (openApiExportOnly) {
                    null
                } else {
                    RedisPool.create()
                }

            val subscriptionRepo = SubscriptionRepo(db)
            val polarClient = buildPolarClient(httpClient)
            val oAuthM2mClient = buildOAuthM2mClient(httpClient)
            val auditLogClient = buildAuditLogClient(httpClient, openApiExportOnly, oAuthM2mClient)

            val polarStateCache =
                redis?.let { PolarStateCache(polarClient, it) }

            val signalApp =
                SignalBillingConfig(
                    db = db,
                    platformReaderDb = platformReaderDb,
                    signalDb = signalDb,
                    subscriptionRepo = subscriptionRepo,
                    polarClient = polarClient,
                    polarStateCache = polarStateCache,
                ).buildRegistration()

            val appRegistry = AppRegistry(listOf(signalApp))

            val polarWebhookHandler =
                PolarWebhookHandler(
                    subscriptionRepo = subscriptionRepo,
                    appRegistry = appRegistry,
                    auditLogClient = auditLogClient,
                )

            val productCatalogRepo =
                ProductCatalogRepo(
                    appRegistry = appRegistry,
                    polarClient = polarClient,
                )

            val controllers =
                buildControllers(
                    appRegistry = appRegistry,
                    polarClient = polarClient,
                    productCatalogRepo = productCatalogRepo,
                    polarWebhookHandler = polarWebhookHandler,
                    auditLogClient = auditLogClient,
                    subscriptionRepo = subscriptionRepo,
                    redis = redis,
                    db = db,
                )

            logger.info(
                "Apollo Billing assembly complete; registered apps: {}",
                appRegistry.knownApps(),
            )

            return AppAssembly(
                appRegistry = appRegistry,
                polarClient = polarClient,
                oAuthM2mClient = oAuthM2mClient,
                httpClient = httpClient,
                healthController = controllers.health,
                productCatalogController = controllers.productCatalog,
                enforceController = controllers.enforce,
                entitlementsController = controllers.entitlements,
                checkoutController = controllers.checkout,
                customerBillingController = controllers.customerBilling,
                usageIngestController = controllers.usageIngest,
                polarWebhookController = controllers.polarWebhook,
                subscriptionsController = controllers.subscriptions,
                invoicesController = controllers.invoices,
                db = db,
                platformReaderDb =
                    if (openApiExportOnly) {
                        null
                    } else {
                        platformReaderDb
                    },
                signalDb = signalDb,
                redis = redis,
            )
        }

        private fun buildHttpClient(): HttpClient =
            HttpClient(CIO) {
                install(ContentNegotiation) {
                    json(HTTP_JSON)
                }
            }

        private fun buildPolarClient(httpClient: HttpClient): PolarClient =
            PolarClient(
                httpClient = httpClient,
                apiBaseUrl = AppConfig.polar.apiBaseUrl,
                apiKey = AppConfig.polar.apiKey,
                timeoutMs = AppConfig.polar.requestTimeoutMs,
            )

        private fun buildOAuthM2mClient(
            httpClient: HttpClient,
        ): MachineOAuthClient = MachineOAuthClient {
            val platformUrl = AppConfig.platform.url
                .takeIf(String::isNotBlank)
                ?: error("PLATFORM_URL is required")
            val audience = AppConfig.platform.audienceUrl
                .takeIf(String::isNotBlank)
                ?: error("PLATFORM_AUDIENCE_URL is required")
            require(AppConfig.platform.clientId.isNotBlank()) {
                "PLATFORM_CLIENT_ID is required"
            }
            require(AppConfig.platform.clientSecret.isNotBlank()) {
                "PLATFORM_CLIENT_SECRET is required"
            }
            tokenEndpoint("${platformUrl.trimEnd('/')}/auth/oauth2/token")
            clientId(AppConfig.platform.clientId)
            clientSecret(AppConfig.platform.clientSecret)
            audience(audience)
            httpClient(httpClient)
            clientSecretPost()
            if (platformUrl.startsWith("http://", ignoreCase = true)) allowInsecureHttp()
        }

        private fun buildAuditLogClient(
            httpClient: HttpClient,
            openApiExportOnly: Boolean,
            m2mClient: MachineOAuthClient,
        ): AuditLogClient =
            AuditLogClient(
                httpClient = httpClient,
                platformUrl = if (openApiExportOnly) "" else AppConfig.platform.url,
                m2mClient = m2mClient,
                enabled = !openApiExportOnly,
            )

        private class Controllers(
            val health: HealthController,
            val productCatalog: ProductCatalogController,
            val enforce: EnforceController,
            val entitlements: EntitlementsController,
            val checkout: CheckoutController,
            val customerBilling: CustomerBillingController,
            val usageIngest: UsageIngestController,
            val polarWebhook: PolarWebhookController,
            val subscriptions: SubscriptionsController,
            val invoices: InvoicesController,
        )

        private fun buildControllers(
            appRegistry: AppRegistry,
            polarClient: PolarClient,
            productCatalogRepo: ProductCatalogRepo,
            polarWebhookHandler: PolarWebhookHandler,
            auditLogClient: AuditLogClient,
            subscriptionRepo: SubscriptionRepo,
            redis: RedisPool?,
            db: DatabasePool,
        ): Controllers {
            val inboundUsageEntitlement =
                InboundUsageEntitlementPort { organizationId ->
                    appRegistry
                        .get(SIGNAL_APP)
                        ?.enforceFeature(
                            organizationId,
                            INBOUND_RECEIVING_FEATURE,
                        )
                        ?.fold(
                            { false },
                            { true },
                        )
                        ?: false
                }

            return Controllers(
                health =
                    HealthController(
                        HealthService(),
                    ),
                productCatalog =
                    ProductCatalogController(
                        ProductCatalogService(productCatalogRepo),
                    ),
                enforce =
                    EnforceController(
                        EnforceService(
                            repository = EnforceRepo(appRegistry),
                            auditLogClient = auditLogClient,
                        ),
                    ),
                entitlements =
                    EntitlementsController(
                        EntitlementsService(
                            EntitlementsRepo(appRegistry),
                        ),
                    ),
                checkout =
                    CheckoutController(
                        CheckoutService(
                            repository = CheckoutRepo(appRegistry, polarClient),
                            auditLogClient = auditLogClient,
                        ),
                    ),
                customerBilling =
                    CustomerBillingController(
                        CustomerBillingService(
                            repository = CustomerBillingRepo(polarClient),
                            auditLogClient = auditLogClient,
                        ),
                    ),
                usageIngest =
                    UsageIngestController(
                        UsageIngestService(
                            repository = UsageIngestRepo(polarClient, redis),
                            auditLogClient = auditLogClient,
                            inboundEntitlement = inboundUsageEntitlement,
                        ),
                    ),
                polarWebhook =
                    PolarWebhookController(
                        PolarWebhookService(
                            repository = PolarWebhookRepo(polarWebhookHandler),
                            deduplicator = WebhookDeduplicator(redis),
                        ),
                    ),
                subscriptions =
                    SubscriptionsController(
                        SubscriptionsService(
                            queryRepository = SubscriptionsQueryRepo(db),
                            subscriptionRepository = subscriptionRepo,
                            polarClient = polarClient,
                            appRegistry = appRegistry,
                        ),
                    ),
                invoices =
                    InvoicesController(
                        InvoicesService(
                            InvoicesRepo(polarClient, appRegistry),
                        ),
                    ),
            )
        }
    }

    override fun close() {
        closeSafely("Redis") {
            redis?.close()
        }
        closeSafely("Signal database") {
            signalDb?.close()
        }
        closeSafely("Platform reader database") {
            platformReaderDb?.close()
        }
        closeSafely("Platform database") {
            db.close()
        }
        closeSafely("HTTP client") {
            httpClient.close()
        }
    }

    private inline fun closeSafely(
        resourceName: String,
        close: () -> Unit,
    ) {
        try {
            close()
        } catch (cause: Exception) {
            logger.warn("Failed to close {}", resourceName, cause)
        }
    }
}