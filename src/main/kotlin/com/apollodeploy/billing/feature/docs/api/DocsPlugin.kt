package com.apollodeploy.billing.feature.docs.api

import io.github.smiley4.ktoropenapi.OpenApiPlugin
import io.github.smiley4.ktoropenapi.config.OpenApiPluginConfig
import io.ktor.http.ContentType
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.concurrent.atomic.AtomicReference

/**
 * Apollo Billing — Docs module (OpenAPI spec + Scalar API Reference).
 *
 * Serves:
 *   GET /docs              → Scalar interactive API reference
 *   GET /docs/openapi.json → live OpenAPI 3.0 spec
 */
fun Route.docsRoutes() {
    val specCache = AtomicReference<String?>(null)

    get("/docs/openapi.json") {
        val spec =
            specCache.get() ?: run {
                OpenApiPlugin.generateOpenApiSpecs(application)
                val generated =
                    prettyJson.encodeToString(
                        Json.parseToJsonElement(
                            OpenApiPlugin.getOpenApiSpec(OpenApiPluginConfig.DEFAULT_SPEC_ID),
                        ),
                    )
                generated.also { specCache.set(it) }
            }
        call.respondText(spec, ContentType.Application.Json)
    }

    get("/docs") {
        call.respondText(scalarHtml, ContentType.Text.Html)
    }
}

private val prettyJson = Json { prettyPrint = true }

// language=HTML
private val scalarHtml =
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
        "defaultHttpClient": { "targetKey": "node", "clientKey": "fetch" },
        "hideModels": true,
        "hideTestRequestButton": true,
        "searchHotKey": "k",
        "metaData": { "title": "Apollo Billing API Reference" }
      }'
    ></script>
    <script src="https://cdn.jsdelivr.net/npm/@scalar/api-reference"></script>
  </body>
</html>
    """.trimIndent()
