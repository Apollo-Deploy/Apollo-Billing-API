/**
 * Tesseract SDK generation DSL for Ktor routes.
 *
 * Mirrors the Tesseract Fastify plugin pattern so that any Ktor route can be
 * opted into SDK generation with a single `.sdk { }` call:
 *
 * ```kotlin
 * post("/v1/emails") { controller.send(call) }.sdk {
 *     operationId = "sendEmail"
 *     summary     = "Send a transactional email"
 *     requestBody<SendEmailRequest>()
 *     response<SendEmailResponse>(201)
 * }
 * ```
 *
 * Trigger generation with:
 * ```shell
 * TESSERACT_GENERATE=1 ./gradlew run
 * ```
 */

package com.apollodeploy.tesseract

import io.github.smiley4.ktoropenapi.DocumentedRouteSelector
import io.ktor.http.*
import io.ktor.server.routing.*
import io.ktor.util.*
import kotlin.reflect.KClass

// ── Attribute key ─────────────────────────────────────────────────────────────

val SdkRouteConfigKey = AttributeKey<SdkRouteConfig>("TesseractSdkRouteConfig")

// ── Config types ──────────────────────────────────────────────────────────────

/**
 * SDK metadata for a single route. Mirrors Tesseract's `SDKRouteConfig` +
 * `ManifestRouteSchema` combined.
 *
 * @param operationId       Unique operation identifier (used by Tesseract for method-name derivation).
 * @param methodName        Explicit override for the generated SDK method name.
 * @param summary           One-line description shown in the README and docblock.
 * @param description       Extended description written into the generated docblock.
 * @param tags              OpenAPI-style tags (used for README grouping).
 * @param internal          When true the route is excluded from public SDK builds (maps to `sdk.internal`).
 * @param exclude           When true the route is skipped entirely.
 * @param requestBodyClass  @Serializable KClass for the request body; schema auto-derived.
 * @param responseClass     @Serializable KClass for the success response; schema auto-derived.
 * @param responseStatus    HTTP status code paired with [responseClass]. Defaults to 200.
 * @param queryParams       Query parameter declarations keyed by name.
 * @param sse               When true the operation is emitted as an event-stream in the SDK.
 */
data class SdkRouteConfig(
    val operationId: String? = null,
    val methodName: String? = null,
    val summary: String? = null,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val internal: Boolean = false,
    val exclude: Boolean = false,
    val requestBodyClass: KClass<*>? = null,
    val responseClass: KClass<*>? = null,
    val responseStatus: Int = 200,
    val queryParams: Map<String, QueryParamSpec> = emptyMap(),
    val sse: Boolean = false,
)

/** Declaration of a single query parameter for the generated SDK method signature. */
data class QueryParamSpec(
    /** JSON Schema primitive type: "string" | "integer" | "number" | "boolean". */
    val type: String = "string",
    val required: Boolean = false,
    val description: String? = null,
)

// ── Builder ───────────────────────────────────────────────────────────────────

class SdkRouteConfigBuilder {
    var operationId: String? = null
    var methodName: String? = null
    var summary: String? = null
    var description: String? = null
    val tags: MutableList<String> = mutableListOf()
    var internal: Boolean = false
    var exclude: Boolean = false
    var responseStatus: Int = 200
    var sse: Boolean = false

    @PublishedApi internal var requestBodyClass: KClass<*>? = null

    @PublishedApi internal var responseClass: KClass<*>? = null
    private val queryParams = mutableMapOf<String, QueryParamSpec>()

    /** Sets the request body schema source from a @Serializable class. */
    inline fun <reified T : Any> requestBody() {
        requestBodyClass = T::class
    }

    /** Sets the success-response schema source from a @Serializable class. */
    inline fun <reified T : Any> response(status: Int = 200) {
        responseClass = T::class
        responseStatus = status
    }

    /** Declares a query parameter for the generated SDK method signature. */
    fun queryParam(
        name: String,
        type: String = "string",
        required: Boolean = false,
        description: String? = null,
    ) {
        queryParams[name] = QueryParamSpec(type, required, description)
    }

    fun build() =
        SdkRouteConfig(
            operationId = operationId,
            methodName = methodName,
            summary = summary,
            description = description,
            tags = tags.toList(),
            internal = internal,
            exclude = exclude,
            requestBodyClass = requestBodyClass,
            responseClass = responseClass,
            responseStatus = responseStatus,
            queryParams = queryParams.toMap(),
            sse = sse,
        )
}

// ── Global route registry ─────────────────────────────────────────────────────
// Routes are registered here the moment .sdk { } is called during route
// installation, so TesseractPlugin can collect them without traversing the tree.

// ── Global domain registry ────────────────────────────────────────────────────
// Call sdkDomain() at the top of each route-registration function to declare
// the domain that owns those routes — mirrors the Fastify sdkDomain() helper.
//
//   fun Route.domainRoutes(controller: DomainController) {
//       sdkDomain("/signal/projects/:projectId/domains", "sendingDomains")
//       ...
//   }
//
//   fun Route.emailRoutes(controller: EmailController) {
//       sdkDomain("/v1/emails", "emails", stability = "internal")
//       ...
//   }

internal object SdkDomainRegistry {
    private val domains = mutableListOf<DomainRegistration>()
    private val lock = Any()

    fun register(domain: DomainRegistration) = synchronized(lock) { domains.add(domain) }

    fun snapshot(): List<DomainRegistration> = synchronized(lock) { domains.toList() }
}

/**
 * Declares that the routes under [prefix] belong to the named SDK domain.
 *
 * Call once at the top of each route-registration function. The declaration is
 * collected by [TesseractPlugin] at manifest-export time, so domain config lives
 * next to the routes rather than in a central [SignalApplication] block.
 *
 * @param prefix    URL prefix that identifies this domain (e.g. "/v1/emails").
 * @param domain    SDK domain key written into the manifest (e.g. "emails").
 * @param stability "stable" (default) | "experimental" | "internal".
 *                  Routes in an "internal" domain are excluded from public SDK builds.
 */
fun sdkDomain(
    prefix: String,
    domain: String,
    stability: String = "stable",
) {
    SdkDomainRegistry.register(DomainRegistration(prefix = prefix, domain = domain, stability = stability))
}

internal data class RegisteredSdkRoute(
    val url: String,
    val method: String,
    val pathParamNames: List<String>,
    val config: SdkRouteConfig,
)

internal object SdkRouteRegistry {
    private val routes = mutableListOf<RegisteredSdkRoute>()
    private val lock = Any()

    fun register(route: RegisteredSdkRoute) = synchronized(lock) { routes.add(route) }

    fun snapshot(): List<RegisteredSdkRoute> = synchronized(lock) { routes.toList() }

    fun clear() = synchronized(lock) { routes.clear() }
}

// ── DSL extensions ────────────────────────────────────────────────────────────

/**
 * Marks this Ktor route for Tesseract SDK generation.
 *
 * The route is immediately registered in [SdkRouteRegistry]; its full URL and
 * HTTP method are derived by walking the parent [Route] chain.
 *
 * ```kotlin
 * post("/v1/emails") { controller.send(call) }.sdk {
 *     operationId = "sendEmail"
 *     requestBody<SendEmailRequest>()
 *     response<SendEmailResponse>(201)
 * }
 * ```
 */
fun Route.sdk(configure: SdkRouteConfigBuilder.() -> Unit = {}): Route = sdk(SdkRouteConfigBuilder().apply(configure).build())

fun Route.sdk(config: SdkRouteConfig): Route {
    if (config.exclude) return this

    // Fall back to smiley4's OpenAPI docs for summary/description if not set explicitly.
    val smileyDoc = ((this as? RoutingNode)?.selector as? DocumentedRouteSelector)?.documentation
    val effectiveConfig =
        if (
            smileyDoc != null &&
            (config.summary == null || config.description == null || config.tags.isEmpty())
        ) {
            config.copy(
                summary = config.summary ?: smileyDoc.summary,
                description = config.description ?: smileyDoc.description,
                tags = config.tags.ifEmpty { smileyDoc.tags.toList() },
            )
        } else {
            config
        }

    // smiley4's post/get/etc returns a DocumentedRouteSelector wrapper whose
    // toString() is empty. The real path+method route lives in the children tree.
    val effectiveRoute = findHttpMethodRoute()
    val method = effectiveRoute.extractHttpMethod() ?: return this
    val path = effectiveRoute.extractFullPath()
    val params = effectiveRoute.extractPathParamNames()

    SdkRouteRegistry.register(
        RegisteredSdkRoute(
            url = path,
            method = method.value.uppercase(),
            pathParamNames = params,
            config = effectiveConfig,
        ),
    )

    attributes.put(SdkRouteConfigKey, effectiveConfig)
    return this
}

// ── Route introspection ───────────────────────────────────────────────────────
// Route.selector is internal in Ktor 3.x, so we derive path/method from the
// route's string representation instead of walking the selector tree directly.
// e.g. route.toString() → "//v1/emails/(method:POST)" or
//                          "//signal/projects/{projectId}/emails/(method:GET)"
//
// smiley4's post/get/etc wraps routes in a DocumentedRouteSelector whose
// toString() is "". The actual HttpMethodRouteSelector lives in the children
// tree. We walk RoutingNode.children (public in Ktor 3.x) to find it.

private val httpMethodRegex = Regex("""/?\(method:([A-Z]+)\)""")
private val parenSelectorRegex = Regex("""/?\([^)]+\)""")
private val pathParamRegex = Regex("""\{([^}?]+)\??}""")

/**
 * Returns this route if it already has a method selector, otherwise searches
 * the RoutingNode child tree for the HttpMethodRouteSelector leaf.
 */
private fun Route.findHttpMethodRoute(): Route {
    if (httpMethodRegex.containsMatchIn(toString())) return this
    return (this as? RoutingNode)?.findMethodInChildren() ?: this
}

private fun RoutingNode.findMethodInChildren(depth: Int = 0): Route? {
    if (depth > 8) return null
    for (child in children) {
        if (httpMethodRegex.containsMatchIn(child.toString())) return child
        (child as? RoutingNode)?.findMethodInChildren(depth + 1)?.let { return it }
    }
    return null
}

private fun Route.extractHttpMethod(): HttpMethod? {
    val str = toString()
    return httpMethodRegex
        .find(str)
        ?.groupValues
        ?.get(1)
        ?.let { HttpMethod(it) }
}

/**
 * Reconstructs the full path (e.g. "/v1/emails/:emailId") by inspecting the
 * route's string representation. Path parameters use Fastify-style `:param`
 * syntax so Tesseract's intake can convert them to `${param}` IR notation.
 */
internal fun Route.extractFullPath(): String {
    val str = toString()
    val withoutMethod = str.replace(httpMethodRegex, "")
    val withoutSelectors = withoutMethod.replace(parenSelectorRegex, "")
    val normalized =
        withoutSelectors
            .replace(Regex("/+"), "/")
            .trimEnd('/')
            .ifEmpty { "/" }
    return normalized.replace(pathParamRegex) { ":${it.groupValues[1]}" }
}

internal fun Route.extractPathParamNames(): List<String> {
    val str = toString()
    return pathParamRegex.findAll(str).map { it.groupValues[1] }.toList()
}
