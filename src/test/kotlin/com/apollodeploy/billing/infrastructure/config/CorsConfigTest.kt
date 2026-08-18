package com.apollodeploy.billing.infrastructure.config

import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.cors.CORSConfig
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CorsConfigTest {
    @Test
    fun `production allows only configured exact origins`() =
        testApplication {
            application {
                install(CORS) {
                    allowCredentials = true
                    allowMethod(HttpMethod.Get)
                    allowConfiguredOrigins(
                        "https://signal.apollodeploy.com,https://account.apollodeploy.com," +
                            "http://localhost:3004,http://localhost:3005",
                        isProduction = true,
                    )
                }
                routing { get("/probe") {} }
            }

            listOf(
                "https://signal.apollodeploy.com",
                "https://account.apollodeploy.com",
                "http://localhost:3004",
                "http://localhost:3005",
            ).forEach { origin ->
                val response = preflight(origin)
                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals(origin, response.headers[HttpHeaders.AccessControlAllowOrigin])
            }

            listOf(
                "https://app.apollodeploy.com",
                "http://localhost:3003",
            ).forEach { origin ->
                val rejected = preflight(origin)
                assertEquals(HttpStatusCode.Forbidden, rejected.status)
                assertNull(rejected.headers[HttpHeaders.AccessControlAllowOrigin])
            }
        }

    @Test
    fun `production rejects missing or non-origin configuration`() {
        assertFailsWith<IllegalArgumentException> {
            CORSConfig().allowConfiguredOrigins("", isProduction = true)
        }
        assertFailsWith<IllegalArgumentException> {
            parseCorsOrigins("https://*.apollodeploy.com")
        }
        assertFailsWith<IllegalArgumentException> {
            parseCorsOrigins("https://signal.apollodeploy.com/path")
        }
    }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.preflight(origin: String) =
        client.options("/probe") {
            header(HttpHeaders.Origin, origin)
            header(HttpHeaders.AccessControlRequestMethod, HttpMethod.Get.value)
        }
}
