/**
 * TesseractPlugin — Ktor ApplicationPlugin that mirrors the behaviour of
 * `@apollo-deploy/tesseract/fastify`.
 *
 * When the environment variable `TESSERACT_GENERATE=1` is set, the plugin:
 *   1. Collects all routes annotated with `.sdk { }`.
 *   2. Builds a `sdk-manifold/v1` manifest (matching Tesseract's input format).
 *   3. Writes the manifest to a temp file.
 *   4. Invokes the Tesseract CLI (`npx tesseract generate …`) to generate the SDK.
 *   5. Exits the process with the CLI's exit code.
 *
 * ## Quick-start
 *
 * ```kotlin
 * // Application.kt
 * install(TesseractPlugin) {
 *     info        = ManifestInfo(title = "Apollo Signal API", version = "1.0.0",
 *                                baseUrl = "https://api.apollodeploy.com")
 *     packageName = "@apollo-deploy/signal-sdk"
 *     output      = "./packages/signal-sdk"
 *     language    = "typescript"
 *
 *     domain("/v1/emails") { domain = "emails" }
 *     domain("/signal/projects") { domain = "projects"; stability = "internal" }
 * }
 * ```
 *
 * ```shell
 * TESSERACT_GENERATE=1 ./gradlew run
 * ```
 *
 * ## Route annotation
 *
 * ```kotlin
 * post("/v1/emails") { controller.send(call) }.sdk {
 *     operationId = "sendEmail"
 *     summary     = "Send a transactional email"
 *     requestBody<SendEmailRequest>()
 *     response<SendEmailResponse>(201)
 * }
 * ```
 */

package com.apollodeploy.tesseract

import io.ktor.server.application.*
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.reflect.KClass
import kotlin.system.exitProcess

// ── Configuration types ───────────────────────────────────────────────────────

/** API title / version metadata written into the generated package. */
data class ManifestInfo(
    val title: String,
    val version: String,
    val description: String? = null,
    val baseUrl: String? = null,
)

/**
 * Declares that types from [name] are external — Tesseract imports rather than regenerates them.
 *
 * @param name        npm package name, e.g. `"@apollo-deploy/signal-schemas"`.
 * @param version     Optional semver range written into the generated `package.json`.
 * @param importPath  Import path override (defaults to [name]). Useful for packages that
 *                    expose types under a sub-path like `"@scope/pkg/types"`.
 * @param types       KClasses whose JSON Schema refs should be emitted as bare `$ref: "Name"`
 *                    instead of inline definitions. Every class listed here will be imported
 *                    from [name] in the generated SDK rather than re-declared in `models.ts`.
 */
data class SchemaPackageConfig(
    val name: String,
    val version: String? = null,
    val importPath: String? = null,
    val types: Set<KClass<*>> = emptySet(),
)

/** Registers a URL prefix as a named SDK domain/route-group. */
class DomainRegistration(
    var prefix: String,
    /** Override the SDK domain key. Derived from the first path segment when null. */
    var domain: String? = null,
    /** Stability level: "stable" (default) | "experimental" | "internal". */
    var stability: String = "stable",
)

// ── Plugin configuration ──────────────────────────────────────────────────────

class TesseractPluginConfig {
    /** Output directory for the generated SDK. */
    var output: String = "./sdk"

    /** npm / Maven package name, e.g. "@apollo-deploy/signal-sdk". */
    var packageName: String = "my-sdk"

    /** Package version. When null Tesseract will auto-bump the patch version. */
    var packageVersion: String? = null

    /** Client class name override, e.g. "SignalClient". */
    var clientName: String? = null

    /** API metadata written into the manifest and generated README. */
    var info: ManifestInfo = ManifestInfo("API", "1.0.0")

    /** Generation target: "typescript" | "kotlin" | "python" | "go" | "rust" | "csharp" | "ruby" | "php". */
    var language: String = "typescript"

    /** SDK style: "functional" (default) or "class". */
    var sdkStyle: String = "functional"

    /** Client type: "internal" (default) or "public". */
    var clientType: String = "internal"

    /** Optional shared type package. */
    var schemaPackage: SchemaPackageConfig? = null

    /**
     * Environment variable whose value of "1" or "true" triggers generation.
     * Default: "TESSERACT_GENERATE".
     */
    var generateEnvVar: String = "TESSERACT_GENERATE"

    /** Executable used to invoke Tesseract. Default: "npx". */
    var nodeExecutable: String = "npx"

    /** Tesseract CLI command. Default: "tesseract". */
    var tesseractCommand: String = "tesseract"

    internal val domainRegistrations = mutableListOf<DomainRegistration>()

    /**
     * Registers a URL prefix as a named SDK domain/route-group.
     *
     * Routes that start with [prefix] are grouped under this domain. The
     * domain-prefix is stripped from each route URL in the manifest, matching
     * the behaviour of Tesseract's Fastify `sdkDomain()` helper.
     *
     * ```kotlin
     * domain("/v1/emails")  { domain = "emails" }
     * domain("/v1/projects") { domain = "projects"; stability = "internal" }
     * ```
     */
    fun domain(
        prefix: String,
        configure: DomainRegistration.() -> Unit = {},
    ) {
        domainRegistrations.add(DomainRegistration(prefix = prefix).also(configure))
    }
}

// ── Plugin ────────────────────────────────────────────────────────────────────

val TesseractPlugin =
    createApplicationPlugin(
        name = "TesseractPlugin",
        createConfiguration = ::TesseractPluginConfig,
    ) {
        val config = pluginConfig
        val logger = LoggerFactory.getLogger("com.apollodeploy.tesseract.TesseractPlugin")

        val envVal = System.getenv(config.generateEnvVar)
        if (envVal == "1" || envVal == "true") {
            logger.info("Tesseract: SDK generation triggered ({}={})", config.generateEnvVar, envVal)

            val routes = SdkRouteRegistry.snapshot()

            if (routes.isEmpty()) {
                logger.error(
                    "Tesseract: no SDK-annotated routes found — " +
                        "did you call .sdk { } on your routes before install(TesseractPlugin)?",
                )
                exitProcess(1)
            }

            logger.info("Tesseract: collected {} route(s)", routes.size)

            val manifest =
                ManifestBuilder.build(
                    routes = routes,
                    info = config.info,
                    domains = config.domainRegistrations + SdkDomainRegistry.snapshot(),
                    schemaPackage = config.schemaPackage,
                )

            // Manifest-export mode: write to TESSERACT_MANIFEST_PATH and exit without running tesseract.
            // Used by scripts/generate-sdks.sh to produce both SDK variants in one server start.
            val manifestExportPath = System.getenv("TESSERACT_MANIFEST_PATH")
            if (manifestExportPath != null) {
                val exportFile = File(manifestExportPath)
                exportFile.parentFile?.mkdirs()
                exportFile.writeText(prettyJson.encodeToString(JsonObject.serializer(), manifest))
                logger.info("Tesseract: manifest exported → {}", exportFile.absolutePath)
                // halt() skips shutdown hooks (HikariCP, Netty) that would otherwise block for
                // minutes trying to close connections that were never fully established.
                Runtime.getRuntime().halt(0)
            }

            val tempFile = File.createTempFile("tesseract-manifest-", ".json")
            try {
                tempFile.writeText(prettyJson.encodeToString(JsonObject.serializer(), manifest))
                logger.info("Tesseract: manifest written → {}", tempFile.path)

                val args =
                    buildList {
                        add(config.nodeExecutable)
                        add(config.tesseractCommand)
                        add("generate")
                        add("--input")
                        add(tempFile.path)
                        add("--output")
                        add(config.output)
                        add("--language")
                        add(config.language)
                        if (config.packageName.isNotEmpty()) {
                            add("--name")
                            add(config.packageName)
                        }
                        config.packageVersion?.let {
                            add("--package-version")
                            add(it)
                        }
                        config.clientName?.let {
                            add("--client-name")
                            add(it)
                        }
                        add("--sdk-style")
                        add(config.sdkStyle)
                        add("--client-type")
                        add(config.clientType)
                    }

                logger.info("Tesseract: {}", args.joinToString(" "))

                val process =
                    ProcessBuilder(args)
                        .redirectErrorStream(true)
                        .start()

                val output = process.inputStream.bufferedReader().readText()
                val exitCode = process.waitFor()

                if (output.isNotBlank()) {
                    if (exitCode == 0) {
                        logger.info("Tesseract:\n{}", output.trimEnd())
                    } else {
                        logger.error("Tesseract:\n{}", output.trimEnd())
                    }
                }

                exitProcess(exitCode)
            } catch (e: Exception) {
                logger.error("Tesseract: generation failed", e)
                exitProcess(1)
            } finally {
                tempFile.delete()
            }
        }
    }

// ── Shared JSON codec ─────────────────────────────────────────────────────────

private val prettyJson =
    Json {
        prettyPrint = true
        encodeDefaults = false
    }
