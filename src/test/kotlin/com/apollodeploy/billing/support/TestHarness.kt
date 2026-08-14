package com.apollodeploy.billing.support

import com.apollodeploy.oauth.m2m.jwt.rsaSigningKey
import com.apollodeploy.oauth.m2m.ktor.MachineOAuth
import com.apollodeploy.oauth.m2m.ktor.machineAuthenticated
import com.apollodeploy.oauth.m2m.testing.TestMachineToken
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.Routing
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

private val testSigningKey = rsaSigningKey("test")

private fun createTestMachineToken(
    clientId: String = "test-client",
    issuer: String = "https://test.identity",
    audience: String = "test-api",
): TestMachineToken =
    TestMachineToken.create {
        this.clientId = clientId
        this.issuer = issuer
        this.audience = audience
        scopes = setOf("internal")
        signingKey(testSigningKey)
    }

private val testMachineToken = createTestMachineToken()

fun billingTestApplication(
    routes: Routing.() -> Unit,
    block: suspend ApplicationTestBuilder.() -> Unit,
) {
    testApplication {
        application {
            install(MachineOAuth) {
                issuer("https://test.identity")
                audience("test-api")
                algorithms("RS256")
                verificationKeys { provider(listOf(testMachineToken.verificationKey)) }
                validate { principal -> principal.clientId.value == "test-client" }
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
            install(StatusPages) {
                exception<BadRequestException> { call, _ ->
                    call.respond(HttpStatusCode.BadRequest, mapOf("code" to "billing.invalid_request", "message" to "Invalid or missing request body fields"))
                }
                exception<SerializationException> { call, _ ->
                    call.respond(HttpStatusCode.BadRequest, mapOf("code" to "billing.invalid_request", "message" to "Invalid or missing request body fields"))
                }
                exception<Throwable> { call, _ ->
                    call.respond(HttpStatusCode.InternalServerError, mapOf("code" to "internal_error"))
                }
            }
            routing { routes() }
        }
        block()
    }
}

/**
 * Mounts the same SDK-authenticated route wrapper used by production.
 * The test application installs MachineOAuth with a static RSA key and signed token.
 */
fun Routing.machineAuthenticatedRoutes(build: Route.() -> Unit) {
    machineAuthenticated(build)
}

fun validServiceToken(): String = testMachineToken.value

fun serviceToken(
    clientId: String = "test-client",
    issuer: String = "https://test.identity",
    audience: String = "test-api",
): String = createTestMachineToken(clientId, issuer, audience).value
