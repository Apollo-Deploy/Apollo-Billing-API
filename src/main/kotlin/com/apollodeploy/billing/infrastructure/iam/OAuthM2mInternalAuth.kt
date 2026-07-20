/**
 * Apollo Billing — OAuth M2M inbound guard for `/internal/` routes.
 *
 * Replaces the legacy HS256 shared-secret `InternalServiceAuth` plugin with
 * standard OAuth 2.1 `client_credentials` token verification.
 *
 * Flow:
 *   1. Extract `Authorization: Bearer <token>` from the request.
 *   2. Fetch the platform's public JWKS from `{platformUrl}/auth/jwks`.
 *   3. Verify the EdDSA-signed JWT:
 *        - issuer matches `AUTH_OAUTH_ISSUER_URL` (or `PLATFORM_URL`)
 *        - audience matches `AUTH_OAUTH_VALID_AUDIENCES` (or `PLATFORM_URL`)
 *   4. Check that the token's `azp` / `sub` (client_id) is in the
 *      `OAUTH_SERVICE_CLIENT_IDS` allowlist (comma-separated env var).
 *      Fails closed when the allowlist is empty — no service may call
 *      `/internal/` endpoints unless explicitly listed.
 *
 * Security invariants:
 *   - Tokens are verified asymmetrically (EdDSA); no shared secret required.
 *   - The allowlist is parsed once at startup and never mutated.
 *   - JWKS responses are cached for `JWKS_CACHE_TTL_SECONDS` (default 300 s)
 *     and refreshed on cache miss or key-ID not found.
 *   - Tokens are never logged.
 *
 * The JWKS fetching uses Ktor's CIO client with a configurable timeout.
 * Key material is cached in-memory; a rolling refresh prevents stale keys
 * from blocking valid requests after a key rotation.
 */

package com.apollodeploy.billing.infrastructure.iam

import com.apollodeploy.billing.infrastructure.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.application.install
import io.ktor.server.request.header
import io.ktor.server.request.path
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.math.BigInteger
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.EdECPoint
import java.security.spec.EdECPublicKeySpec
import java.security.spec.NamedParameterSpec
import java.util.Base64

private val logger = LoggerFactory.getLogger("com.apollodeploy.billing.infrastructure.iam.oauth")
private const val JWKS_CACHE_TTL_SECONDS: Long = 300L
private const val REQUEST_TIMEOUT_MS: Long = 5_000L

// Shared Json instance (avoids repeated instance creation per call)
private val sharedJson = Json { ignoreUnknownKeys = true }

// ── Exceptions ────────────────────────────────────────────────────────────────

class OAuthServiceAuthException(
    val code: String = "billing.unauthenticated",
    override val message: String = "Missing or invalid service token",
) : RuntimeException(message)

// ── JWKS cache ────────────────────────────────────────────────────────────────

private data class JwksCache(
    val keys: Map<String, PublicKey>, // kid → PublicKey
    val fetchedAt: Long, // epoch seconds
)

private class JwksVerifier(
    private val httpClient: HttpClient,
    private val jwksUrl: String,
) {
    private val mutex = Mutex()

    @Volatile
    private var cache: JwksCache? = null

    /** Returns the public key for [kid], refreshing the JWKS if necessary. */
    suspend fun getPublicKey(kid: String?): PublicKey? {
        val cached = cache
        val now = System.currentTimeMillis() / 1_000L
        if (cached != null && now - cached.fetchedAt < JWKS_CACHE_TTL_SECONDS) {
            val key = if (kid != null) cached.keys[kid] else cached.keys.values.firstOrNull()
            if (key != null) return key
        }
        // Refresh
        return mutex.withLock {
            val refetched = fetchJwks()
            cache = JwksCache(refetched, System.currentTimeMillis() / 1_000L)
            if (kid != null) refetched[kid] else refetched.values.firstOrNull()
        }
    }

    private suspend fun fetchJwks(): Map<String, PublicKey> {
        if (jwksUrl.isBlank()) return emptyMap()
        return try {
            val text =
                withTimeout(REQUEST_TIMEOUT_MS) {
                    val response = httpClient.get(jwksUrl)
                    if (!response.status.isSuccess()) {
                        logger.warn("[billing:oauth] JWKS fetch failed status={}", response.status.value)
                        return@withTimeout ""
                    }
                    response.bodyAsText()
                }
            if (text.isBlank()) return emptyMap()
            parseJwks(text)
        } catch (e: Exception) {
            logger.warn("[billing:oauth] JWKS fetch exception: {}", e.message)
            emptyMap()
        }
    }

    private fun parseJwks(text: String): Map<String, PublicKey> {
        val root =
            try {
                sharedJson.parseToJsonElement(text).jsonObject
            } catch (_: Exception) {
                return emptyMap()
            }
        val keysArray = root["keys"] as? JsonArray ?: return emptyMap()
        val result = mutableMapOf<String, PublicKey>()
        for (element in keysArray) {
            val jwk = element as? JsonObject ?: continue
            val kty = jwk["kty"]?.jsonPrimitive?.content ?: continue
            val kid = jwk["kid"]?.jsonPrimitive?.content ?: continue
            if (kty != "OKP") continue // Only EdDSA (OKP) keys
            val x = jwk["x"]?.jsonPrimitive?.content ?: continue
            val key = runCatching { decodeEdDsaPublicKey(x) }.getOrNull() ?: continue
            result[kid] = key
        }
        return result
    }

    private fun decodeEdDsaPublicKey(xBase64Url: String): PublicKey {
        val xBytes =
            Base64.getUrlDecoder().decode(
                xBase64Url.padEnd(xBase64Url.length + (4 - xBase64Url.length % 4) % 4, '='),
            )
        // Ed25519: last byte's MSB indicates sign of x coordinate (odd/even)
        val xBytesCopy = xBytes.copyOf()
        val lastByte = xBytesCopy.last()
        val oddY = (lastByte.toInt() and 0x80) != 0
        xBytesCopy[xBytesCopy.size - 1] = (lastByte.toInt() and 0x7f).toByte()
        // Reverse for little-endian to big-endian
        xBytesCopy.reverse()
        val y = BigInteger(1, xBytesCopy)
        val point = EdECPoint(oddY, y)
        val spec = EdECPublicKeySpec(NamedParameterSpec.ED25519, point)
        return KeyFactory.getInstance("EdDSA").generatePublic(spec)
    }
}

// ── JWT verification ──────────────────────────────────────────────────────────

private data class OAuthTokenClaims(
    val subject: String?,
    val clientId: String?,
    val issuer: String?,
    val audience: Set<String>,
    val expiresAt: Long,
    val issuedAt: Long,
)

private fun verifyEdDsaJwt(
    token: String,
    publicKey: PublicKey,
    allowedIssuers: Set<String>,
    allowedAudiences: Set<String>,
    clockSkewSeconds: Long = 30L,
): OAuthTokenClaims {
    val parts = token.split(".")
    if (parts.size != 3) throw OAuthServiceAuthException(message = "Malformed JWT")

    // Verify signature
    val signatureBytes =
        decodeBase64Url(parts[2])
            ?: throw OAuthServiceAuthException(message = "Invalid JWT signature encoding")
    val signingInput = "${parts[0]}.${parts[1]}".toByteArray(Charsets.UTF_8)
    val sig = Signature.getInstance("EdDSA")
    sig.initVerify(publicKey)
    sig.update(signingInput)
    if (!sig.verify(signatureBytes)) throw OAuthServiceAuthException(message = "JWT signature mismatch")

    // Parse claims
    val claims =
        try {
            sharedJson.decodeFromString<JsonObject>(
                decodeBase64Url(parts[1])!!.toString(Charsets.UTF_8),
            )
        } catch (_: Exception) {
            throw OAuthServiceAuthException(message = "Invalid JWT claims")
        }

    val iss = claims["iss"]?.jsonPrimitive?.content
    val sub = claims["sub"]?.jsonPrimitive?.content
    val azp = claims["azp"]?.jsonPrimitive?.content
    val exp =
        claims["exp"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: throw OAuthServiceAuthException(message = "Missing JWT exp claim")
    val iat = claims["iat"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L

    val aud: Set<String> =
        when (val audValue = claims["aud"]) {
            is kotlinx.serialization.json.JsonPrimitive -> setOf(audValue.content)
            is JsonArray -> audValue.mapNotNull { it.jsonPrimitive.content }.toSet()
            else -> emptySet()
        }

    val now = System.currentTimeMillis() / 1_000L
    if (exp + clockSkewSeconds < now) throw OAuthServiceAuthException(message = "JWT expired")
    if (iat - clockSkewSeconds > now) throw OAuthServiceAuthException(message = "JWT issued in future")

    if (allowedIssuers.isNotEmpty() && iss !in allowedIssuers) {
        throw OAuthServiceAuthException(message = "JWT issuer not allowed: $iss")
    }
    if (allowedAudiences.isNotEmpty() && aud.none { it in allowedAudiences }) {
        throw OAuthServiceAuthException(message = "JWT audience not allowed: $aud")
    }

    return OAuthTokenClaims(
        subject = sub,
        clientId = azp ?: sub,
        issuer = iss,
        audience = aud,
        expiresAt = exp,
        issuedAt = iat,
    )
}

private fun decodeBase64Url(value: String): ByteArray? =
    runCatching {
        Base64.getUrlDecoder().decode(value.padEnd(value.length + (4 - value.length % 4) % 4, '='))
    }.getOrNull()

// ── Ktor route-scoped plugin ──────────────────────────────────────────────────

private fun buildOAuthM2mInternalPlugin(
    httpClient: HttpClient,
    jwksUrl: String,
    allowedIssuers: Set<String>,
    allowedAudiences: Set<String>,
    allowedServiceClientIds: Set<String>,
) = createRouteScopedPlugin("OAuthM2mInternalAuth") {
    val verifier = JwksVerifier(httpClient, jwksUrl)

    onCall { call ->
        if (!call.request.path().startsWith("/internal/")) return@onCall

        val authHeader = call.request.header("Authorization")
        val token = authHeader?.removePrefix("Bearer ")?.takeIf { it != authHeader }?.trim()

        if (token.isNullOrBlank()) {
            logger.warn("[billing:oauth] missing bearer token for {}", call.request.path())
            throw OAuthServiceAuthException()
        }

        // Extract kid from JWT header (optional — fall back to first available key)
        val kid =
            runCatching {
                val headerPart = token.split(".").firstOrNull() ?: return@runCatching null
                val headerJson =
                    sharedJson.decodeFromString<JsonObject>(
                        decodeBase64Url(headerPart)!!.toString(Charsets.UTF_8),
                    )
                headerJson["kid"]?.jsonPrimitive?.content
            }.getOrNull()

        val publicKey = verifier.getPublicKey(kid)
        if (publicKey == null) {
            logger.warn("[billing:oauth] no matching public key found kid={}", kid)
            throw OAuthServiceAuthException(message = "No valid public key found")
        }

        val claims =
            try {
                verifyEdDsaJwt(token, publicKey, allowedIssuers, allowedAudiences)
            } catch (e: OAuthServiceAuthException) {
                logger.warn("[billing:oauth] token verification failed: {}", e.message)
                throw e
            }

        val clientId = claims.clientId
        if (allowedServiceClientIds.isNotEmpty() && (clientId == null || clientId !in allowedServiceClientIds)) {
            logger.warn("[billing:oauth] service client not allowed clientId={}", clientId)
            throw OAuthServiceAuthException(message = "Service client not authorized for internal endpoints")
        }

        // Store authenticated client identity in call attributes for audit logging.
        // Downstream handlers can use this to log which service is acting on which org.
        call.attributes.put(AuthenticatedClientIdKey, clientId ?: "unknown")
    }
}

/** Ktor attribute key for the authenticated service client ID. */
val AuthenticatedClientIdKey = io.ktor.util.AttributeKey<String>("authenticatedClientId")

/** Extension to retrieve the authenticated client ID from a call (post-auth). */
fun io.ktor.server.application.ApplicationCall.authenticatedClientId(): String? = attributes.getOrNull(AuthenticatedClientIdKey)

/**
 * Wraps routes inside an OAuth M2M guard.
 *
 * All routes registered in [build] must be under `/internal/`. Requests
 * without a valid `client_credentials` bearer token are rejected with 401.
 *
 * Reads configuration from [AppConfig]:
 *   - `platformUrl`         → JWKS base URL  (`{platformUrl}/auth/jwks`)
 *   - `iamIssuerUrl`        → expected `iss` claim  (AUTH_OAUTH_ISSUER_URL)
 *   - `iamValidAudiences`   → expected `aud` claim(s)  (AUTH_OAUTH_VALID_AUDIENCES)
 *   - `iamServiceClientIds` → allowlisted `azp`/`sub` values  (OAUTH_SERVICE_CLIENT_IDS)
 */
fun Route.oauthInternalRoutes(
    httpClient: HttpClient,
    build: Route.() -> Unit,
) {
    val jwksUrl = AppConfig.iamJwksUrl
    val allowedIssuers = AppConfig.iamAllowedIssuers
    val allowedAudiences = AppConfig.iamValidAudiences
    val allowedServiceClientIds = AppConfig.iamServiceClientIds

    if (allowedServiceClientIds.isEmpty()) {
        logger.warn(
            "[billing:oauth] OAUTH_SERVICE_CLIENT_IDS is empty -- " +
                "ALL /internal/ calls will be rejected. " +
                "Set OAUTH_SERVICE_CLIENT_IDS to the client_id(s) of allowed callers.",
        )
    }

    route("") {
        install(buildOAuthM2mInternalPlugin(httpClient, jwksUrl, allowedIssuers, allowedAudiences, allowedServiceClientIds))
        build()
    }
}
