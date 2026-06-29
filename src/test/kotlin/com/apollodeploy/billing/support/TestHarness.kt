package com.apollodeploy.billing.support

import com.apollodeploy.billing.infrastructure.iam.OAuthServiceAuthException
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

fun billingTestApplication(
    routes: Routing.() -> Unit,
    block: suspend ApplicationTestBuilder.() -> Unit,
) {
    testApplication {
        application {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                    explicitNulls = false
                })
            }
            install(StatusPages) {
                exception<OAuthServiceAuthException> { call, cause ->
                    call.respond(HttpStatusCode.Unauthorized, mapOf("code" to cause.code, "message" to cause.message))
                }
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
 * No-op auth wrapper for unit tests.
 *
 * Production routes use [oauthInternalRoutes] which verifies EdDSA JWTs via
 * the platform's JWKS. In unit tests there is no platform, so we install a
 * minimal auth guard that accepts the test token and rejects all others.
 *
 * This means controller unit tests correctly get 401 for missing/wrong tokens
 * without needing a real OAuth server.
 */
fun Routing.noAuthInternalRoutes(build: Routing.() -> Unit) {
    route("") {
        // Minimal auth: require the test bearer token
        install(io.ktor.server.application.createRouteScopedPlugin("TestAuth") {
            onCall { call ->
                val authHeader = call.request.header("Authorization") ?: ""
                val token = authHeader.removePrefix("Bearer ").trim()
                if (token != validServiceToken()) {
                    throw OAuthServiceAuthException(message = "Missing or invalid service token")
                }
            }
        })
        build()
    }
}

/**
 * Dummy bearer token for tests that exercise routes mounted under
 * [noAuthInternalRoutes]. The test auth guard accepts only this exact value.
 */
fun validServiceToken(): String = "test-bearer-token-auth-bypassed-in-unit-tests"
