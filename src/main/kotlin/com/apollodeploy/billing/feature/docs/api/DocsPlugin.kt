package com.apollodeploy.billing.feature.docs.api

import io.github.smiley4.ktoropenapi.OpenApiPlugin
import io.github.smiley4.ktoropenapi.config.OpenApiPluginConfig
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

private val JsonContentType = ContentType.Application.Json

private val HtmlContentType = ContentType.Text.Html

private val OpenApiJson = Json

/**
 * Serves the OpenAPI specification and Scalar API reference.
 */
fun Route.docsRoutes() {
    val specCache = OpenApiSpecCache()

    get("/docs/openapi.json") {
        call.respondBytes(
            bytes = specCache.get(call.application),
            contentType = JsonContentType,
        )
    }

    get("/docs") {
        call.respondBytes(
            bytes = SCALAR_HTML_BYTES,
            contentType = HtmlContentType,
        )
    }
}

private class OpenApiSpecCache {
    @Volatile
    private var cached: ByteArray? = null

    fun get(application: Application): ByteArray {
        cached?.let { return it }

        return synchronized(this) {
            cached ?: generate(application).also {
                cached = it
            }
        }
    }

    private fun generate(application: Application): ByteArray = generateOpenApiSpec(application).encodeToByteArray()
}

internal fun generateOpenApiSpec(application: Application): String {
    OpenApiPlugin.generateOpenApiSpecs(application)

    return addSdkMetadata(
        OpenApiPlugin.getOpenApiSpec(OpenApiPluginConfig.DEFAULT_SPEC_ID),
    )
}

private fun addSdkMetadata(spec: String): String {
    val document = OpenApiJson.parseToJsonElement(spec).jsonObject
    val paths = document["paths"]?.jsonObject ?: return spec
    val polarWebhook = paths["/webhooks/polar"]?.jsonObject ?: return spec
    val operation = polarWebhook["post"]?.jsonObject ?: return spec
    val decoratedOperation =
        JsonObject(
            operation +
                (
                    "x-tesseract" to
                        JsonObject(mapOf("exclude" to JsonPrimitive(true)))
                ),
        )
    val decoratedPath = JsonObject(polarWebhook + ("post" to decoratedOperation))

    return OpenApiJson.encodeToString(
        JsonObject(document + ("paths" to JsonObject(paths + ("/webhooks/polar" to decoratedPath)))),
    )
}

// language=HTML
private const val SCALAR_HTML =
    """
<!doctype html>
<html lang="en">
  <head>
    <title>Apollo Billing API Reference</title>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1" />
  </head>
  <body>
    <script
      id="api-reference"
      data-url="/docs/openapi.json"
      data-configuration='{
        "theme": "saturn",
        "layout": "modern",
        "defaultHttpClient": {
          "targetKey": "node",
          "clientKey": "fetch"
        },
        "hideModels": true,
        "hideTestRequestButton": true,
        "searchHotKey": "k",
        "metaData": {
          "title": "Apollo Billing API Reference"
        }
      }'
    ></script>
    <script src="https://cdn.jsdelivr.net/npm/@scalar/api-reference"></script>
  </body>
</html>
    """

private val SCALAR_HTML_BYTES =
    SCALAR_HTML.trimIndent().encodeToByteArray()
