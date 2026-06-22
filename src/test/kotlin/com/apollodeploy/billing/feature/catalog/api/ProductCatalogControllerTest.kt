package com.apollodeploy.billing.feature.catalog.api

import com.apollodeploy.billing.feature.catalog.application.ProductCatalogService
import com.apollodeploy.billing.feature.catalog.domain.ProductCatalogResponse
import com.apollodeploy.billing.feature.catalog.domain.ProductCatalogResult
import com.apollodeploy.billing.support.billingTestApplication
import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.az
import io.kotest.property.arbitrary.string
import io.kotest.property.forAll
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ProductCatalogControllerTest {

    private val service = mockk<ProductCatalogService>()
    private val controller = ProductCatalogController(service)

    // -----------------------------------------------------------------
    // Example-based unit tests
    // -----------------------------------------------------------------

    @Test
    fun `getCatalog Found returns HTTP 200 with appSlug and products array`() = billingTestApplication(
        routes = { productCatalogRoutes(controller) },
    ) {
        coEvery { service.getCatalog("signal") } returns
            ProductCatalogResult.Found(ProductCatalogResponse("signal", emptyList()))

        val response = client.get("/billing/catalog/signal")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("signal", body["appSlug"]?.jsonPrimitive?.content)
        assertIs<JsonArray>(body["products"])
    }

    @Test
    fun `getCatalog UnknownApp returns HTTP 404 with billing_unknown_app code`() = billingTestApplication(
        routes = { productCatalogRoutes(controller) },
    ) {
        coEvery { service.getCatalog("unknown-app") } returns
            ProductCatalogResult.UnknownApp("unknown-app")

        val response = client.get("/billing/catalog/unknown-app")

        assertEquals(HttpStatusCode.NotFound, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("billing.unknown_app", body["code"]?.jsonPrimitive?.content)
    }

    @Test
    fun `getCatalog PricingUnavailable returns HTTP 502 with billing_catalog_pricing_unavailable code`() =
        billingTestApplication(
            routes = { productCatalogRoutes(controller) },
        ) {
            coEvery { service.getCatalog("signal") } returns ProductCatalogResult.PricingUnavailable

            val response = client.get("/billing/catalog/signal")

            assertEquals(HttpStatusCode.BadGateway, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("billing.catalog_pricing_unavailable", body["code"]?.jsonPrimitive?.content)
        }

    // -----------------------------------------------------------------
    // Property 2: appSlug round-trip
    // Validates: Requirements 3.1
    // -----------------------------------------------------------------

    @Test
    fun `Property 2 - appSlug is preserved in Found response for any non-blank appSlug`() = runBlocking {
        // Use URL-safe alphanumeric strings to avoid URLDecodeException when used as path segments
        val nonBlankString = Arb.string(minSize = 1, maxSize = 50, codepoints = Codepoint.az())

        forAll(nonBlankString) { appSlug ->
            var result = false
            billingTestApplication(
                routes = { productCatalogRoutes(controller) },
            ) {
                coEvery { service.getCatalog(appSlug) } returns
                    ProductCatalogResult.Found(ProductCatalogResponse(appSlug, emptyList()))

                val response = client.get("/billing/catalog/$appSlug")
                if (response.status == HttpStatusCode.OK) {
                    val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                    result = body["appSlug"]?.jsonPrimitive?.content == appSlug
                }
            }
            result
        }
        Unit
    }

    // -----------------------------------------------------------------
    // Property 3: UnknownApp always produces 404 with billing.unknown_app
    // Validates: Requirements 3.2
    // -----------------------------------------------------------------

    @Test
    fun `Property 3 - UnknownApp always returns HTTP 404 with billing_unknown_app for any non-blank appSlug`() =
        runBlocking {
            // Use URL-safe alphanumeric strings to avoid URLDecodeException when used as path segments
            val nonBlankString = Arb.string(minSize = 1, maxSize = 50, codepoints = Codepoint.az())

            forAll(nonBlankString) { appSlug ->
                var result = false
                billingTestApplication(
                    routes = { productCatalogRoutes(controller) },
                ) {
                    coEvery { service.getCatalog(appSlug) } returns
                        ProductCatalogResult.UnknownApp(appSlug)

                    val response = client.get("/billing/catalog/$appSlug")
                    if (response.status == HttpStatusCode.NotFound) {
                        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                        result = body["code"]?.jsonPrimitive?.content == "billing.unknown_app"
                    }
                }
                result
            }
            Unit
        }
}
