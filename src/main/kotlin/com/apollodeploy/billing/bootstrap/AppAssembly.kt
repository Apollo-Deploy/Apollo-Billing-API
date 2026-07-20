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
import com.apollodeploy.billing.feature.subscriptions.api.SubscriptionsController
import com.apollodeploy.billing.feature.subscriptions.application.SubscriptionsService
import com.apollodeploy.billing.feature.subscriptions.infrastructure.persistence.SubscriptionsQueryRepo
import com.apollodeploy.billing.feature.signal.application.SignalBillingConfig
import com.apollodeploy.billing.feature.usage.api.UsageIngestController
import com.apollodeploy.billing.feature.usage.application.UsageIngestService
import com.apollodeploy.billing.feature.usage.application.InboundUsageEntitlementPort
import com.apollodeploy.billing.feature.usage.infrastructure.persistence.UsageIngestRepo
import com.apollodeploy.billing.feature.webhook.api.PolarWebhookController
import com.apollodeploy.billing.feature.webhook.application.PolarWebhookService
import com.apollodeploy.billing.feature.webhook.infrastructure.persistence.PolarWebhookRepo
import com.apollodeploy.billing.infrastructure.audit.AuditLogClient
import com.apollodeploy.billing.infrastructure.config.AppConfig
import com.apollodeploy.billing.infrastructure.iam.OAuthM2mClient
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Apollo Billing — application assembly root.
 *
 * Wires all infrastructure and domain objects together.
 * Single place to add a new app: create its BillingConfig and register it.
 */
class AppAssembly private constructor(
    val appRegistry: AppRegistry,
    val polarClient: PolarClient,
    val oAuthM2mClient: OAuthM2mClient,
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
    private val db: DatabasePool?,
    private val platformReaderDb: DatabasePool?,
    private val signalDb: DatabasePool?,
    private val redis: RedisPool?,
) : AutoCloseable {
    companion object {
        private val logger = LoggerFactory.getLogger(AppAssembly::class.java)

        fun create(): AppAssembly {
            logger.info("[billing] Assembling application")

            val db = DatabasePool.create()
            val platformReaderDb = DatabasePool.createPlatformReader()
            val signalDb = DatabasePool.createSignal()
            val httpClient = buildHttpClient()
            val redis = RedisPool.create()

            val subscriptionRepo = SubscriptionRepo(db)
            val polarClient = buildPolarClient(httpClient)
            val polarStateCache = PolarStateCache(polarClient, redis)
            val oAuthM2mClient = buildOAuthM2mClient(httpClient)
            val auditLogClient = buildAuditLogClient(httpClient)

            val signalApp =
                SignalBillingConfig(
                    db,
                    platformReaderDb,
                    signalDb,
                    subscriptionRepo,
                    polarClient,
                    polarStateCache,
                ).buildRegistration()

            val appRegistry = AppRegistry(listOf(signalApp))

            val polarWebhookHandler =
                PolarWebhookHandler(
                    subscriptionRepo = subscriptionRepo,
                    appRegistry = appRegistry,
                    auditLogClient = auditLogClient,
                )

            val productCatalogRepo = ProductCatalogRepo(appRegistry, polarClient)
            runBlocking {
                appRegistry.knownApps().forEach { appSlug ->
                    productCatalogRepo.getCatalog(appSlug)
                }
            }

            val controllers = buildControllers(appRegistry, polarClient, productCatalogRepo, polarWebhookHandler, auditLogClient, redis, db)

            logger.info("[billing] Assembly complete — registered apps: {}", appRegistry.knownApps())

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
                platformReaderDb = platformReaderDb,
                signalDb = signalDb,
                redis = redis,
            )
        }

        /**
         * Manifest-only assembly for SDK generation (TESSERACT_GENERATE=1).
         */
        fun createForManifest(): AppAssembly {
            logger.info("[billing] Assembling application (manifest-only mode — no DB connections)")

            val httpClient = buildHttpClient()
            val polarClient = buildPolarClient(httpClient)

            val stubDb = DatabasePool.createStub()
            val subscriptionRepo = SubscriptionRepo(stubDb)

            val signalApp =
                SignalBillingConfig(
                    db = stubDb,
                    platformReaderDb = stubDb,
                    signalDb = null,
                    subscriptionRepo = subscriptionRepo,
                    polarClient = polarClient,
                ).buildRegistration()

            val appRegistry = AppRegistry(listOf(signalApp))

            val auditLogClient =
                AuditLogClient(
                    httpClient = httpClient,
                    platformUrl = "",
                    clientId = "",
                    clientSecret = "",
                    enabled = false,
                )

            val polarWebhookHandler =
                PolarWebhookHandler(
                    subscriptionRepo = subscriptionRepo,
                    appRegistry = appRegistry,
                    auditLogClient = auditLogClient,
                )

            val productCatalogRepo = ProductCatalogRepo(appRegistry, polarClient)
            val controllers = buildControllers(appRegistry, polarClient, productCatalogRepo, polarWebhookHandler, auditLogClient, db = stubDb)

            logger.info("[billing] Manifest assembly complete — registered apps: {}", appRegistry.knownApps())

            val stubOAuthM2mClient =
                OAuthM2mClient(
                    httpClient = httpClient,
                    platformUrl = "",
                    clientId = "",
                    clientSecret = "",
                )

            return AppAssembly(
                appRegistry = appRegistry,
                polarClient = polarClient,
                oAuthM2mClient = stubOAuthM2mClient,
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
                db = null,
                platformReaderDb = null,
                signalDb = null,
                redis = null,
            )
        }

        // ── Shared builder helpers ────────────────────────────────────────────

        private fun buildHttpClient(): HttpClient =
            HttpClient(CIO) {
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            explicitNulls = false
                        },
                    )
                }
            }

        private fun buildPolarClient(httpClient: HttpClient): PolarClient =
            PolarClient(
                httpClient = httpClient,
                apiBaseUrl = AppConfig.polarApiBaseUrl,
                apiKey = AppConfig.polarApiKey,
                timeoutMs = AppConfig.polarRequestTimeoutMs,
            )

        private fun buildOAuthM2mClient(httpClient: HttpClient): OAuthM2mClient =
            OAuthM2mClient(
                httpClient = httpClient,
                platformUrl = AppConfig.platformUrl,
                clientId = AppConfig.platformClientId,
                clientSecret = AppConfig.platformClientSecret,
                timeoutMs = AppConfig.iamRequestTimeoutMs,
            )

        private fun buildAuditLogClient(httpClient: HttpClient): AuditLogClient =
            AuditLogClient(
                httpClient = httpClient,
                platformUrl = AppConfig.platformUrl,
                clientId = AppConfig.platformClientId,
                clientSecret = AppConfig.platformClientSecret,
            )

        private data class Controllers(
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
            redis: RedisPool? = null,
            db: DatabasePool? = null,
        ): Controllers =
            Controllers(
                health = HealthController(HealthService()),
                productCatalog = ProductCatalogController(ProductCatalogService(productCatalogRepo)),
                enforce = EnforceController(EnforceService(EnforceRepo(appRegistry), auditLogClient)),
                entitlements = EntitlementsController(EntitlementsService(EntitlementsRepo(appRegistry))),
                checkout = CheckoutController(CheckoutService(CheckoutRepo(appRegistry, polarClient), auditLogClient)),
                customerBilling = CustomerBillingController(CustomerBillingService(CustomerBillingRepo(polarClient), auditLogClient)),
                usageIngest = UsageIngestController(
                    UsageIngestService(
                        UsageIngestRepo(polarClient, redis),
                        auditLogClient,
                        InboundUsageEntitlementPort { orgId ->
                            appRegistry.get("signal")
                                ?.enforceFeature(orgId, "inboundReceiving")
                                ?.fold({ false }, { true })
                                ?: false
                        },
                    ),
                ),
                polarWebhook = PolarWebhookController(PolarWebhookService(PolarWebhookRepo(polarWebhookHandler), WebhookDeduplicator(redis))),
                subscriptions = SubscriptionsController(
                    SubscriptionsService(SubscriptionsQueryRepo(db ?: DatabasePool.createStub())),
                ),
                invoices = InvoicesController(
                    InvoicesService(InvoicesRepo(polarClient, appRegistry)),
                ),
            )
    }

    override fun close() {
        runCatching { httpClient.close() }
        runCatching { redis?.close() }
        runCatching { signalDb?.close() }
        runCatching { platformReaderDb?.close() }
        runCatching { db?.close() }
    }
}
