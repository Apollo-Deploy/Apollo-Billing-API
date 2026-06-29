/**
 * SchemaExtractor — converts @Serializable Kotlin classes to JSON Schema objects,
 * collecting all transitively-referenced type definitions as a side effect.
 *
 * ```kotlin
 * val extractor = SchemaExtractor()
 * val ref  = extractor.refFor(SendEmailRequest::class)
 * // → { "$ref": "#/definitions/SendEmailRequest" }
 *
 * val defs = extractor.definitions()
 * // → { "SendEmailRequest": { "type": "object", "properties": { ... } }, ... }
 * ```
 *
 * Output follows Tesseract's JSON Schema dialect: nullable properties carry
 * `"nullable": true` alongside their type/ref, matching what the intake
 * pipeline expects when it calls `jsonSchemaToType()`.
 */

package com.apollodeploy.tesseract

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.json.*
import kotlinx.serialization.serializer
import kotlin.reflect.KClass
import kotlin.reflect.full.createType

@OptIn(ExperimentalSerializationApi::class)
class SchemaExtractor(
    /**
     * Type names (as returned by [typeName]) that live in an external schema package.
     * For these types [refFor] emits a bare `{ "$ref": "Name" }` and skips adding
     * a local definition, so Tesseract will import them from the schema package instead.
     */
    private val externalTypeNames: Set<String> = emptySet(),
) {
    private val definitions = mutableMapOf<String, JsonObject>()

    // Guards against infinite recursion for self-referential CLASS/OBJECT types.
    private val processing = mutableSetOf<String>()

    // Guards against infinite recursion for inline types (LIST/MAP/SEALED/OPEN)
    // that are not protected by the `processing` / definitions cache.
    private val currentlyDescribing = mutableSetOf<String>()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns a `$ref` JSON object for [kClass] and, for non-external types, ensures
     * [definitions] contains the full schema (and all transitively-referenced types).
     *
     * - External types (in [externalTypeNames]): `{ "$ref": "Name" }` — bare ref, no definition.
     * - Local types: `{ "$ref": "#/definitions/Name" }` — adds to definitions.
     *
     * Returns null when [kClass] is not @Serializable.
     */
    fun refFor(kClass: KClass<*>): JsonObject? {
        val desc = descriptorFor(kClass) ?: return null
        val name = typeName(desc)
        if (name in externalTypeNames) {
            return buildJsonObject { put("\$ref", name) }
        }
        ensureDefinition(desc, name)
        return buildJsonObject { put("\$ref", "#/definitions/$name") }
    }

    /** All JSON Schema definitions collected across all [refFor] calls so far. */
    fun definitions(): Map<String, JsonObject> = definitions.toMap()

    // ── Descriptor resolution ─────────────────────────────────────────────────

    private fun descriptorFor(kClass: KClass<*>): SerialDescriptor? =
        try {
            serializer(kClass.createType()).descriptor
        } catch (_: SerializationException) {
            null
        } catch (_: Exception) {
            null
        }

    /**
     * Simple, non-nullable type name used as the definition key.
     * "com.example.SendEmailRequest?" → "SendEmailRequest"
     */
    private fun typeName(desc: SerialDescriptor): String = desc.serialName.substringAfterLast(".").removeSuffix("?")

    // ── Definition building ───────────────────────────────────────────────────

    private fun ensureDefinition(
        desc: SerialDescriptor,
        name: String,
    ) {
        if (name in definitions || name in processing) return
        processing.add(name)
        definitions[name] = buildDefinition(desc)
        processing.remove(name)
    }

    private fun buildDefinition(desc: SerialDescriptor): JsonObject =
        when (desc.kind) {
            SerialKind.ENUM ->
                buildJsonObject {
                    put("type", "string")
                    if (desc.elementsCount > 0) {
                        put(
                            "enum",
                            buildJsonArray {
                                for (i in 0 until desc.elementsCount) add(desc.getElementName(i))
                            },
                        )
                    }
                }

            StructureKind.CLASS,
            StructureKind.OBJECT,
            -> {
                val required = mutableListOf<String>()
                val properties =
                    buildJsonObject {
                        for (i in 0 until desc.elementsCount) {
                            val propName = desc.getElementName(i)
                            val propDesc = desc.getElementDescriptor(i)
                            if (!desc.isElementOptional(i) && !propDesc.isNullable) required.add(propName)
                            put(propName, descriptorToSchema(propDesc))
                        }
                    }
                buildJsonObject {
                    put("type", "object")
                    put("properties", properties)
                    if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(it) } })
                }
            }

            else -> buildJsonObject { put("type", "object") }
        }

    // ── Schema node generation ────────────────────────────────────────────────

    /**
     * Converts a [SerialDescriptor] to an inline JSON Schema node.
     *
     * For CLASS / OBJECT kinds a `$ref` is emitted and the definition is
     * registered as a side effect, enabling transitive collection.
     */
    fun descriptorToSchema(desc: SerialDescriptor): JsonElement {
        val base =
            when (desc.kind) {
                // LIST / MAP / SEALED / OPEN can form reference cycles and are not
                // protected by ensureDefinition. Guard them with currentlyDescribing.
                StructureKind.LIST,
                StructureKind.MAP,
                is PolymorphicKind,
                -> {
                    // Use a key that includes element type(s) so that List<A> and List<B>
                    // are not treated as the same container — all List<*> share the same
                    // serialName ("kotlin.collections.ArrayList"), which caused nested lists
                    // to falsely trigger the cycle guard and emit { "type": "object" }.
                    val key =
                        buildString {
                            append(desc.serialName)
                            for (i in 0 until desc.elementsCount) append("<").append(desc.getElementDescriptor(i).serialName).append(">")
                        }
                    if (key in currentlyDescribing) {
                        // Cycle detected (e.g. JsonElement ↔ JsonArray) — emit opaque object.
                        return buildJsonObject { put("type", "object") }
                    }
                    currentlyDescribing.add(key)
                    try {
                        schemaForKind(desc)
                    } finally {
                        currentlyDescribing.remove(key)
                    }
                }
                // CLASS/OBJECT: protected by ensureDefinition; primitives/ENUM: no recursion.
                else -> schemaForKind(desc)
            }
        if (!desc.isNullable) return base
        // Tesseract intake understands "nullable": true as a sibling of any schema type.
        return if (base is JsonObject) {
            buildJsonObject {
                base.forEach { (k, v) -> put(k, v) }
                put("nullable", JsonPrimitive(true))
            }
        } else {
            base
        }
    }

    private fun schemaForKind(desc: SerialDescriptor): JsonElement =
        when (desc.kind) {
            PrimitiveKind.STRING -> buildJsonObject { put("type", "string") }

            PrimitiveKind.INT,
            PrimitiveKind.LONG,
            PrimitiveKind.SHORT,
            PrimitiveKind.BYTE,
            -> buildJsonObject { put("type", "integer") }

            PrimitiveKind.DOUBLE,
            PrimitiveKind.FLOAT,
            -> buildJsonObject { put("type", "number") }

            PrimitiveKind.BOOLEAN -> buildJsonObject { put("type", "boolean") }
            PrimitiveKind.CHAR -> buildJsonObject { put("type", "string") }

            SerialKind.ENUM ->
                buildJsonObject {
                    put("type", "string")
                    // Only emit enum values when present; empty arrays (e.g. SEALED discriminators
                    // with no registered subtypes) produce `""` in the TypeScript emitter.
                    if (desc.elementsCount > 0) {
                        put(
                            "enum",
                            buildJsonArray {
                                for (i in 0 until desc.elementsCount) add(desc.getElementName(i))
                            },
                        )
                    }
                }

            StructureKind.LIST -> {
                // index 0 is always the item descriptor for lists
                buildJsonObject {
                    put("type", "array")
                    put("items", descriptorToSchema(desc.getElementDescriptor(0)))
                }
            }

            StructureKind.MAP -> {
                // index 1 is the value descriptor; keys are always strings in JSON
                buildJsonObject {
                    put("type", "object")
                    put("additionalProperties", descriptorToSchema(desc.getElementDescriptor(1)))
                }
            }

            StructureKind.CLASS,
            StructureKind.OBJECT,
            -> {
                val name = typeName(desc)
                if (name in externalTypeNames) {
                    buildJsonObject { put("\$ref", name) }
                } else {
                    ensureDefinition(desc, name)
                    buildJsonObject { put("\$ref", "#/definitions/$name") }
                }
            }

            PolymorphicKind.SEALED ->
                buildJsonObject {
                    put(
                        "anyOf",
                        buildJsonArray {
                            for (i in 0 until desc.elementsCount) {
                                add(descriptorToSchema(desc.getElementDescriptor(i)))
                            }
                        },
                    )
                }

            else -> buildJsonObject { put("type", "object") }
        }
}
