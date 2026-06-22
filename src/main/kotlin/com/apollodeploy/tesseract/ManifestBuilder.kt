/**
 * ManifestBuilder — converts collected Ktor SDK routes into a
 * `sdk-manifold/v1` JSON manifest that Tesseract's intake pipeline understands.
 *
 * The output is byte-for-byte compatible with manifests produced by
 * `@apollo-deploy/tesseract/fastify`, so the same `tesseract` CLI command
 * works for both TypeScript (Fastify) and Kotlin (Ktor) servers.
 *
 * Manifest shape:
 * ```json
 * {
 *   "$schema": "sdk-manifold/v1",
 *   "info": { "title": "...", "version": "...", "baseUrl": "..." },
 *   "domains": [
 *     {
 *       "name": "emails",
 *       "prefix": "/v1/emails",
 *       "routes": [
 *         {
 *           "method": "POST",
 *           "url": "/",
 *           "schema": {
 *             "operationId": "sendEmail",
 *             "body": { "$ref": "#/definitions/SendEmailRequest" },
 *             "response": { "201": { "$ref": "#/definitions/SendEmailResponse" } }
 *           },
 *           "sdk": { "operationId": "sendEmail", "visibility": "public" }
 *         }
 *       ]
 *     }
 *   ],
 *   "definitions": { "SendEmailRequest": { "type": "object", ... } }
 * }
 * ```
 */

package com.apollodeploy.tesseract

import kotlinx.serialization.json.*
import kotlinx.serialization.serializer
import kotlin.reflect.full.createType

internal object ManifestBuilder {
    /**
     * Builds the full `sdk-manifold/v1` manifest as a [JsonObject].
     *
     * @param routes        Routes collected by [SdkRouteRegistry].
     * @param info          API title / version / baseUrl.
     * @param domains       Domain prefix registrations from [TesseractPluginConfig].
     * @param schemaPackage Optional shared type package — causes Tesseract to emit
     *                      external imports instead of regenerating those types.
     */
    fun build(
        routes: List<RegisteredSdkRoute>,
        info: ManifestInfo,
        domains: List<DomainRegistration>,
        schemaPackage: SchemaPackageConfig?,
    ): JsonObject {
        // Resolve external type names from KClass references so SchemaExtractor
        // can emit bare $refs for them instead of expanding inline definitions.
        val externalTypeNames: Set<String> =
            schemaPackage
                ?.types
                ?.mapNotNull { kClass ->
                    try {
                        kotlinx.serialization
                            .serializer(kClass.createType())
                            .descriptor
                            .serialName
                            .substringAfterLast(".")
                    } catch (_: Exception) {
                        null
                    }
                }?.toSet()
                ?: emptySet()

        val extractor = SchemaExtractor(externalTypeNames)
        val domainGroups = groupByDomain(routes, domains)

        val manifestDomains =
            buildJsonArray {
                for (group in domainGroups) add(buildDomain(group, extractor))
            }

        val definitions = extractor.definitions()

        return buildJsonObject {
            put("\$schema", "sdk-manifold/v1")
            put(
                "info",
                buildJsonObject {
                    put("title", info.title)
                    put("version", info.version)
                    info.description?.let { put("description", it) }
                    info.baseUrl.takeIf { !it.isNullOrEmpty() }?.let { put("baseUrl", it) }
                },
            )
            put("domains", manifestDomains)
            if (definitions.isNotEmpty()) {
                put(
                    "definitions",
                    buildJsonObject {
                        for ((name, schema) in definitions) put(name, schema)
                    },
                )
            }
            schemaPackage?.let { pkg ->
                put(
                    "schemaPackage",
                    buildJsonObject {
                        put("name", pkg.name)
                        pkg.version?.let { put("version", it) }
                        pkg.importPath?.let { put("importPath", it) }
                    },
                )
            }
        }
    }

    // ── Domain grouping ───────────────────────────────────────────────────────

    private data class DomainGroup(
        val prefix: String,
        val domain: String?,
        val stability: String,
        val routes: List<RegisteredSdkRoute>,
    )

    /**
     * Assigns each route to a domain using longest-prefix-first matching
     * (mirrors Tesseract's `buildManifestFromRoutes` in collector.ts).
     *
     * Unregistered routes fall back to a derived prefix from the first fixed
     * path segment (mirrors Tesseract's `derivePrefix()`).
     */
    private fun groupByDomain(
        routes: List<RegisteredSdkRoute>,
        registrations: List<DomainRegistration>,
    ): List<DomainGroup> {
        val sorted = registrations.sortedByDescending { it.prefix.length }

        // Use LinkedHashMap to preserve first-seen insertion order per prefix.
        val grouped = linkedMapOf<String, MutableList<RegisteredSdkRoute>>()
        val prefixMeta = mutableMapOf<String, Pair<String?, String>>() // prefix → (domain?, stability)

        for (route in routes) {
            val reg = sorted.firstOrNull { route.url.startsWith(it.prefix) }
            val prefix = reg?.prefix ?: derivePrefix(route.url)
            val domain = reg?.domain // null = let Tesseract intake derive from prefix
            val stability = reg?.stability ?: "stable"

            grouped.getOrPut(prefix) { mutableListOf() }.add(route)
            prefixMeta.putIfAbsent(prefix, domain to stability)
        }

        return grouped.map { (prefix, routeList) ->
            val (domain, stability) = prefixMeta.getValue(prefix)
            DomainGroup(prefix, domain, stability, routeList)
        }
    }

    /** First non-parameter path segment, e.g. "/v1/emails/:id" → "/v1". */
    private fun derivePrefix(url: String): String {
        val first =
            url
                .trimStart('/')
                .split("/")
                .firstOrNull { it.isNotEmpty() && !it.startsWith(":") }
                ?: "api"
        return "/$first"
    }

    // ── Domain object ─────────────────────────────────────────────────────────

    private fun buildDomain(
        group: DomainGroup,
        extractor: SchemaExtractor,
    ): JsonObject =
        buildJsonObject {
            put("prefix", group.prefix)
            group.domain?.let { put("domain", it) }
            if (group.stability != "stable") put("stability", group.stability)
            put(
                "routes",
                buildJsonArray {
                    for (route in group.routes) add(buildRoute(group.prefix, route, extractor))
                },
            )
        }

    // ── Route object ──────────────────────────────────────────────────────────

    private fun buildRoute(
        domainPrefix: String,
        route: RegisteredSdkRoute,
        extractor: SchemaExtractor,
    ): JsonObject {
        // Strip the domain prefix to get the URL relative to the domain.
        // "/v1/emails"     under "/v1/emails" → "/"
        // "/v1/emails/:id" under "/v1/emails" → "/:id"
        val relativeUrl =
            route.url
                .removePrefix(domainPrefix)
                .let {
                    if (it.isEmpty() || it == "/") {
                        "/"
                    } else if (it.startsWith("/")) {
                        it
                    } else {
                        "/$it"
                    }
                }

        return buildJsonObject {
            put("method", route.method)
            put("url", relativeUrl)
            put("schema", buildRouteSchema(route, extractor))
            put("sdk", buildSdkBlock(route.config))
            if (route.config.sse) put("sse", JsonPrimitive(true))
        }
    }

    /**
     * Builds the `schema` block read by Tesseract's intake for path params,
     * querystring, body, and response type resolution.
     */
    private fun buildRouteSchema(
        route: RegisteredSdkRoute,
        extractor: SchemaExtractor,
    ): JsonObject =
        buildJsonObject {
            route.config.operationId?.let { put("operationId", it) }
            route.config.summary?.let { put("summary", it) }
            route.config.description?.let { put("description", it) }
            if (route.config.tags.isNotEmpty()) {
                put("tags", buildJsonArray { route.config.tags.forEach { add(it) } })
            }

            // Path parameters — { type: "object", properties: { paramName: { type: "string" } } }
            if (route.pathParamNames.isNotEmpty()) {
                put(
                    "params",
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                route.pathParamNames.forEach { name ->
                                    put(name, buildJsonObject { put("type", "string") })
                                }
                            },
                        )
                        put("required", buildJsonArray { route.pathParamNames.forEach { add(it) } })
                    },
                )
            }

            // Query parameters
            if (route.config.queryParams.isNotEmpty()) {
                val requiredKeys =
                    route.config.queryParams
                        .filterValues { it.required }
                        .keys
                put(
                    "querystring",
                    buildJsonObject {
                        put("type", "object")
                        put(
                            "properties",
                            buildJsonObject {
                                route.config.queryParams.forEach { (name, spec) ->
                                    put(
                                        name,
                                        buildJsonObject {
                                            put("type", spec.type)
                                            spec.description?.let { put("description", it) }
                                        },
                                    )
                                }
                            },
                        )
                        if (requiredKeys.isNotEmpty()) {
                            put("required", buildJsonArray { requiredKeys.forEach { add(it) } })
                        }
                    },
                )
            }

            // Request body — $ref resolved via SchemaExtractor
            route.config.requestBodyClass?.let { kClass ->
                extractor.refFor(kClass)?.let { put("body", it) }
            }

            // Response — $ref resolved via SchemaExtractor
            route.config.responseClass?.let { kClass ->
                extractor.refFor(kClass)?.let { ref ->
                    put(
                        "response",
                        buildJsonObject {
                            put(route.config.responseStatus.toString(), ref)
                        },
                    )
                }
            }
        }

    /**
     * Builds the `sdk` metadata block that Tesseract reads for visibility,
     * operationId fallback, and summary fallback.
     */
    private fun buildSdkBlock(config: SdkRouteConfig): JsonObject =
        buildJsonObject {
            config.operationId?.let { put("operationId", it) }
            config.methodName?.let { put("methodName", it) }
            if (config.internal) put("internal", JsonPrimitive(true))
        }
}
