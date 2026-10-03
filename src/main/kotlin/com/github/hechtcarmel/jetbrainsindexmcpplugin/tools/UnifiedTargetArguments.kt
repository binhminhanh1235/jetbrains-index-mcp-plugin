package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.SchemaConstants
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Parses the additive nested `target` contract and translates it to the legacy flat arguments
 * consumed by existing tools. Keeping normalization in one place makes the runtime validation
 * identical for navigation and refactoring tools while preserving every old request shape.
 */
internal object UnifiedTargetArguments {
    const val TARGET = "target"
    const val POSITION = "position"
    const val QUALIFIED_NAME = "qualifiedName"
    /** Internal provenance retained after flattening when a tool must distinguish nested intent. */
    const val NORMALIZED_VARIANT = "__unifiedTargetVariant"

    private val legacySelectorNames = listOf(
        ParamNames.SYMBOL_ID,
        ParamNames.FILE,
        ParamNames.LINE,
        ParamNames.COLUMN,
        ParamNames.LANGUAGE,
        ParamNames.SYMBOL,
        ParamNames.CLASS_NAME,
        // These are legacy selectors specific to member-editing tools. They are still fully
        // supported when `target` is omitted, but cannot identify a second target alongside it.
        ParamNames.CLASS,
        ParamNames.MEMBER,
        ParamNames.PARAMETER_COUNT
    )

    fun normalize(arguments: JsonObject): Result<JsonObject> {
        val rawTarget = arguments[TARGET] ?: return Result.success(arguments)
        if (rawTarget == JsonNull) {
            // Optional JSON-RPC arguments are commonly serialized explicitly as null. Treat that
            // exactly like an omitted target so legacy selectors remain compatible with clients
            // that do not strip null optionals.
            return Result.success(arguments)
        }
        val target = rawTarget as? JsonObject
            ?: return Result.failure(IllegalArgumentException("target must be an object when present"))

        val mixedLegacy = legacySelectorNames.filter { isPresent(arguments[it]) }
        if (mixedLegacy.isNotEmpty()) {
            return Result.failure(
                IllegalArgumentException(
                    "Nested 'target' requires exactly one selector and is mutually exclusive with legacy top-level selector parameter(s): " +
                        mixedLegacy.joinToString(", ")
                )
            )
        }

        val presentTargetKeys = target.filterValues { it != JsonNull }.keys
        val hasSymbolId = ParamNames.SYMBOL_ID in presentTargetKeys
        val hasPosition = POSITION in presentTargetKeys
        val hasQualifiedName = QUALIFIED_NAME in presentTargetKeys
        val variants = listOf(hasSymbolId, hasPosition, hasQualifiedName).count { it }
        if (variants != 1) {
            return Result.failure(
                IllegalArgumentException(
                    "target must select exactly one variant: symbolId, position, or qualifiedName + language"
                )
            )
        }

        val normalized = arguments.toMutableMap()
        normalized.remove(TARGET)
        // Compatibility placeholders accepted as absent above must not reach legacy parsers
        // (in particular the strict integer parser for line and column).
        legacySelectorNames.forEach(normalized::remove)

        when {
            hasSymbolId -> {
                val unexpected = presentTargetKeys - setOf(ParamNames.SYMBOL_ID)
                if (unexpected.isNotEmpty()) return unexpectedFields("symbolId", unexpected)
                val symbolId = requiredNonBlankString(target[ParamNames.SYMBOL_ID], "target.symbolId")
                    .getOrElse { return Result.failure(it) }
                normalized[ParamNames.SYMBOL_ID] = JsonPrimitive(symbolId)
                normalized[NORMALIZED_VARIANT] = JsonPrimitive(ParamNames.SYMBOL_ID)
            }

            hasPosition -> {
                val unexpected = presentTargetKeys - setOf(POSITION)
                if (unexpected.isNotEmpty()) return unexpectedFields("position", unexpected)
                val position = target[POSITION] as? JsonObject
                    ?: return Result.failure(IllegalArgumentException("target.position must be an object"))
                val unexpectedPosition = position.filterValues { it != JsonNull }.keys -
                    setOf(ParamNames.FILE, ParamNames.LINE, ParamNames.COLUMN)
                if (unexpectedPosition.isNotEmpty()) {
                    return Result.failure(
                        IllegalArgumentException(
                            "target.position contains unsupported field(s): ${unexpectedPosition.sorted().joinToString(", ")}"
                        )
                    )
                }
                val file = requiredNonBlankString(position[ParamNames.FILE], "target.position.file")
                    .getOrElse { return Result.failure(it) }
                val line = requiredPositiveInt(position[ParamNames.LINE], "target.position.line")
                    .getOrElse { return Result.failure(it) }
                val column = requiredPositiveInt(position[ParamNames.COLUMN], "target.position.column")
                    .getOrElse { return Result.failure(it) }
                normalized[ParamNames.FILE] = JsonPrimitive(file)
                normalized[ParamNames.LINE] = JsonPrimitive(line)
                normalized[ParamNames.COLUMN] = JsonPrimitive(column)
                normalized[NORMALIZED_VARIANT] = JsonPrimitive(POSITION)
            }

            else -> {
                val unexpected = presentTargetKeys - setOf(QUALIFIED_NAME, ParamNames.LANGUAGE)
                if (unexpected.isNotEmpty()) return unexpectedFields("qualifiedName", unexpected)
                val qualifiedName = requiredNonBlankString(target[QUALIFIED_NAME], "target.qualifiedName")
                    .getOrElse { return Result.failure(it) }
                val language = requiredNonBlankString(target[ParamNames.LANGUAGE], "target.language")
                    .getOrElse { return Result.failure(it) }
                normalized[ParamNames.SYMBOL] = JsonPrimitive(qualifiedName)
                normalized[ParamNames.LANGUAGE] = JsonPrimitive(language)
                normalized[NORMALIZED_VARIANT] = JsonPrimitive(QUALIFIED_NAME)
            }
        }

        return Result.success(JsonObject(normalized))
    }

    /** Whether a tool explicitly advertises the object-valued nested target contract. */
    fun isSupportedBy(inputSchema: ToolSchema): Boolean =
        ((inputSchema.properties?.get(TARGET) as? JsonObject)
            ?.get(SchemaConstants.TYPE) as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.contentOrNull == SchemaConstants.TYPE_OBJECT

    /**
     * Extracts only the opaque handle needed for dispatcher project routing. A string `target`
     * belongs to tools such as ide_run_tests and is deliberately ignored here; target-aware tools
     * perform the complete validation in [normalize].
     */
    fun symbolIdForRouting(
        arguments: JsonObject,
        acceptsLegacySymbolId: Boolean = true,
        acceptsNestedTarget: Boolean = true
    ): Result<String?> {
        val legacyElement = arguments[ParamNames.SYMBOL_ID]
        val legacy = if (!acceptsLegacySymbolId || legacyElement == null || legacyElement == JsonNull) {
            null
        } else {
            requiredNonBlankString(legacyElement, "symbolId").getOrElse { return Result.failure(it) }
        }
        val target = (if (acceptsNestedTarget) arguments[TARGET] as? JsonObject else null)
            ?: return Result.success(legacy)
        val nestedElement = target[ParamNames.SYMBOL_ID]
        val nested = if (nestedElement == null || nestedElement == JsonNull) {
            null
        } else {
            requiredNonBlankString(nestedElement, "target.symbolId").getOrElse { return Result.failure(it) }
        }
        if (legacy != null && nested != null) {
            return Result.failure(
                IllegalArgumentException("Nested 'target' is mutually exclusive with legacy top-level symbolId")
            )
        }
        return Result.success(nested ?: legacy)
    }

    private fun isPresent(element: JsonElement?): Boolean = when (element) {
        null, JsonNull -> false
        is JsonPrimitive -> if (element.isString) !element.contentOrNull.isNullOrBlank() else true
        else -> true
    }

    private fun requiredNonBlankString(element: JsonElement?, path: String): Result<String> {
        val primitive = element as? JsonPrimitive
        if (primitive?.isString != true) {
            return Result.failure(IllegalArgumentException("$path must be a string"))
        }
        val value = primitive.contentOrNull?.trim().orEmpty()
        if (value.isEmpty()) {
            return Result.failure(IllegalArgumentException("$path must not be blank"))
        }
        return Result.success(value)
    }

    private fun requiredPositiveInt(element: JsonElement?, path: String): Result<Int> {
        val value = (element as? JsonPrimitive)?.intOrNull
            ?: return Result.failure(IllegalArgumentException("$path must be an integer"))
        if (value <= 0) {
            return Result.failure(IllegalArgumentException("$path must be positive and 1-based"))
        }
        return Result.success(value)
    }

    private fun unexpectedFields(variant: String, fields: Set<String>): Result<JsonObject> =
        Result.failure(
            IllegalArgumentException(
                "target variant '$variant' contains incompatible field(s): ${fields.sorted().joinToString(", ")}"
            )
        )
}
