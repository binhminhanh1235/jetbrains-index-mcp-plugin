package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.schema

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ParamNames
import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.SchemaConstants
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScope
import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.UnifiedTargetArguments
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.*

class SchemaBuilder private constructor() {
    private val properties = linkedMapOf<String, JsonObject>()
    private val requiredFields = mutableListOf<String>()

    fun projectPath() = apply {
        properties[ParamNames.PROJECT_PATH] = buildJsonObject {
            put(SchemaConstants.TYPE, SchemaConstants.TYPE_STRING)
            put(SchemaConstants.DESCRIPTION, SchemaConstants.DESC_PROJECT_PATH)
        }
    }

    fun file(required: Boolean = true, description: String = SchemaConstants.DESC_FILE) = apply {
        properties[ParamNames.FILE] = buildJsonObject {
            put(SchemaConstants.TYPE, SchemaConstants.TYPE_STRING)
            put(SchemaConstants.DESCRIPTION, description)
        }
        if (required) requiredFields.add(ParamNames.FILE)
    }

    fun lineAndColumn(required: Boolean = true) = apply {
        properties[ParamNames.LINE] = buildJsonObject {
            put(SchemaConstants.TYPE, SchemaConstants.TYPE_INTEGER)
            put(SchemaConstants.DESCRIPTION, SchemaConstants.DESC_LINE)
        }
        properties[ParamNames.COLUMN] = buildJsonObject {
            put(SchemaConstants.TYPE, SchemaConstants.TYPE_INTEGER)
            put(SchemaConstants.DESCRIPTION, SchemaConstants.DESC_COLUMN)
        }
        if (required) {
            requiredFields.add(ParamNames.LINE)
            requiredFields.add(ParamNames.COLUMN)
        }
    }

    fun languageAndSymbol(required: Boolean = true) = apply {
        val supportedLanguages = LanguageHandlerRegistry.getSupportedLanguageNamesForSymbolReference()
        properties[ParamNames.LANGUAGE] = buildJsonObject {
            put(SchemaConstants.TYPE, SchemaConstants.TYPE_STRING)
            val languageDescription = buildString {
                append(SchemaConstants.DESC_LANGUAGE)
                if (supportedLanguages.isEmpty()) {
                    append(" No symbol reference handlers are currently available.")
                } else {
                    append(" Currently supported languages: ${supportedLanguages.joinToString(", ")}.")
                }
            }
            put(SchemaConstants.DESCRIPTION, languageDescription)
            if (supportedLanguages.isNotEmpty()) {
                putJsonArray("enum") { supportedLanguages.forEach { add(JsonPrimitive(it)) } }
            }
        }
        properties[ParamNames.SYMBOL] = buildJsonObject {
            put(SchemaConstants.TYPE, SchemaConstants.TYPE_STRING)
            put(SchemaConstants.DESCRIPTION, SchemaConstants.DESC_SYMBOL)
        }
        if (required) {
            requiredFields.add(ParamNames.LANGUAGE)
            requiredFields.add(ParamNames.SYMBOL)
        }
    }

    fun symbolId() = apply {
        properties[ParamNames.SYMBOL_ID] = buildJsonObject {
            put(SchemaConstants.TYPE, SchemaConstants.TYPE_STRING)
            put(
                SchemaConstants.DESCRIPTION,
                "Reusable across edits/rename; expires with session, project, inactivity or eviction. " +
                    "Non-canonical: unequal handles may identify the same symbol."
            )
        }
    }

    /**
     * Additive nested selector contract. `oneOf` is intentionally expressed in prose and checked
     * at runtime because some MCP clients reject top-level combinator schemas.
     */
    fun target() = apply {
        properties[UnifiedTargetArguments.TARGET] = buildJsonObject {
            put(SchemaConstants.TYPE, SchemaConstants.TYPE_OBJECT)
            put(
                SchemaConstants.DESCRIPTION,
                "Choose symbolId, position, or qualifiedName+language. Mutually exclusive with flat selector parameters."
            )
            putJsonObject(SchemaConstants.PROPERTIES) {
                putJsonObject(ParamNames.SYMBOL_ID) {
                    put(SchemaConstants.TYPE, SchemaConstants.TYPE_STRING)
                    put(
                        SchemaConstants.DESCRIPTION,
                        "Same lifetime and non-canonical identity as top-level symbolId."
                    )
                }
                putJsonObject(UnifiedTargetArguments.POSITION) {
                    put(SchemaConstants.TYPE, SchemaConstants.TYPE_OBJECT)
                    putJsonObject(SchemaConstants.PROPERTIES) {
                        putJsonObject(ParamNames.FILE) {
                            put(SchemaConstants.TYPE, SchemaConstants.TYPE_STRING)
                            put(
                                SchemaConstants.DESCRIPTION,
                                "Project-relative file path, or a dependency/library absolute path or jar:// URL previously returned by the plugin."
                            )
                        }
                        putJsonObject(ParamNames.LINE) {
                            put(SchemaConstants.TYPE, SchemaConstants.TYPE_INTEGER)
                            put(SchemaConstants.DESCRIPTION, SchemaConstants.DESC_LINE)
                        }
                        putJsonObject(ParamNames.COLUMN) {
                            put(SchemaConstants.TYPE, SchemaConstants.TYPE_INTEGER)
                            put(SchemaConstants.DESCRIPTION, SchemaConstants.DESC_COLUMN)
                        }
                    }
                    putJsonArray(SchemaConstants.REQUIRED) {
                        add(ParamNames.FILE)
                        add(ParamNames.LINE)
                        add(ParamNames.COLUMN)
                    }
                }
                putJsonObject(UnifiedTargetArguments.QUALIFIED_NAME) {
                    put(SchemaConstants.TYPE, SchemaConstants.TYPE_STRING)
                    put(SchemaConstants.DESCRIPTION, "Language-qualified symbol reference, for example com.example.Foo#bar.")
                }
                putJsonObject(ParamNames.LANGUAGE) {
                    put(SchemaConstants.TYPE, SchemaConstants.TYPE_STRING)
                    put(SchemaConstants.DESCRIPTION, "Language used to resolve qualifiedName.")
                }
            }
        }
    }

    fun stringProperty(name: String, description: String, required: Boolean = false) = apply {
        properties[name] = buildJsonObject {
            put(SchemaConstants.TYPE, SchemaConstants.TYPE_STRING)
            put(SchemaConstants.DESCRIPTION, description)
        }
        if (required) requiredFields.add(name)
    }

    fun intProperty(name: String, description: String, required: Boolean = false) = apply {
        properties[name] = buildJsonObject {
            put(SchemaConstants.TYPE, SchemaConstants.TYPE_INTEGER)
            put(SchemaConstants.DESCRIPTION, description)
        }
        if (required) requiredFields.add(name)
    }

    fun booleanProperty(name: String, description: String, required: Boolean = false) = apply {
        properties[name] = buildJsonObject {
            put(SchemaConstants.TYPE, SchemaConstants.TYPE_BOOLEAN)
            put(SchemaConstants.DESCRIPTION, description)
        }
        if (required) requiredFields.add(name)
    }

    fun stringArrayProperty(name: String, description: String, required: Boolean = false) = apply {
        properties[name] = buildJsonObject {
            put(SchemaConstants.TYPE, SchemaConstants.TYPE_ARRAY)
            put(SchemaConstants.DESCRIPTION, description)
            put(SchemaConstants.ITEMS, buildJsonObject { put(SchemaConstants.TYPE, SchemaConstants.TYPE_STRING) })
            put("minItems", 1)
        }
        if (required) requiredFields.add(name)
    }

    fun enumProperty(name: String, description: String, values: List<String>, required: Boolean = false) = apply {
        properties[name] = buildJsonObject {
            put(SchemaConstants.TYPE, SchemaConstants.TYPE_STRING)
            put(SchemaConstants.DESCRIPTION, description)
            putJsonArray("enum") { values.forEach { add(JsonPrimitive(it)) } }
        }
        if (required) requiredFields.add(name)
    }

    fun scopeProperty(description: String, required: Boolean = false) = apply {
        enumProperty(
            name = ParamNames.SCOPE,
            description = description,
            values = BuiltInSearchScope.supportedWireValues(),
            required = required
        )
    }

    fun property(name: String, schema: JsonObject, required: Boolean = false) = apply {
        properties[name] = schema
        if (required) requiredFields.add(name)
    }

    /**
     * Builds the MCP tool input schema.
     *
     * [ToolSchema] hard-codes `"type": "object"` and omits `required` when null, which is exactly
     * the shape this builder emitted as a raw [JsonObject] before the SDK migration — the
     * serialized bytes are unchanged, and `ToolSchemaFidelityUnitTest` keeps them that way.
     */
    fun build(): ToolSchema = ToolSchema(
        properties = JsonObject(properties),
        required = requiredFields.takeIf { it.isNotEmpty() }
    )

    companion object {
        fun tool() = SchemaBuilder()
    }
}
