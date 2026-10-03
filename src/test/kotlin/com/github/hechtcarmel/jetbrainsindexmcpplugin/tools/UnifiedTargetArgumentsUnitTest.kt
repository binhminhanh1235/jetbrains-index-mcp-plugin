package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools

import junit.framework.TestCase
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class UnifiedTargetArgumentsUnitTest : TestCase() {

    fun testSymbolIdTargetNormalizesToLegacySelector() {
        val normalized = UnifiedTargetArguments.normalize(buildJsonObject {
            put("direction", "callers")
            putJsonObject("target") { put("symbolId", " sym_saved ") }
        }).getOrThrow()

        assertEquals("sym_saved", normalized["symbolId"].toString().trim('"'))
        assertEquals("callers", normalized["direction"].toString().trim('"'))
        assertFalse(normalized.containsKey("target"))
    }

    fun testPositionTargetNormalizesToLegacyCoordinates() {
        val normalized = UnifiedTargetArguments.normalize(buildJsonObject {
            putJsonObject("target") {
                putJsonObject("position") {
                    put("file", "src/Foo.kt")
                    put("line", 12)
                    put("column", 9)
                }
            }
        }).getOrThrow()

        assertEquals("\"src/Foo.kt\"", normalized["file"].toString())
        assertEquals("12", normalized["line"].toString())
        assertEquals("9", normalized["column"].toString())
        assertFalse(normalized.containsKey("target"))
    }

    fun testQualifiedNameTargetNormalizesToLegacyLanguageAndSymbol() {
        val normalized = UnifiedTargetArguments.normalize(buildJsonObject {
            putJsonObject("target") {
                put("qualifiedName", "com.example.Foo#bar")
                put("language", "Kotlin")
            }
        }).getOrThrow()

        assertEquals("\"com.example.Foo#bar\"", normalized["symbol"].toString())
        assertEquals("\"Kotlin\"", normalized["language"].toString())
    }

    fun testTargetRequiresExactlyOneVariant() {
        assertTargetError(buildJsonObject { putJsonObject("target") {} }, "exactly one")
        assertTargetError(buildJsonObject {
            putJsonObject("target") {
                put("symbolId", "sym_one")
                putJsonObject("position") {
                    put("file", "src/Foo.kt")
                    put("line", 1)
                    put("column", 1)
                }
            }
        }, "exactly one")
    }

    fun testTargetRejectsLegacySelectorMixing() {
        assertTargetError(buildJsonObject {
            put("file", "src/Legacy.kt")
            putJsonObject("target") { put("symbolId", "sym_one") }
        }, "mutually exclusive")

        assertTargetError(buildJsonObject {
            put("className", "legacy.Foo")
            putJsonObject("target") { put("symbolId", "sym_one") }
        }, "className")
    }

    fun testTargetRejectsMemberSpecificLegacySelectorMixing() {
        listOf("class", "member", "parameterCount").forEach { selector ->
            assertTargetError(buildJsonObject {
                if (selector == "parameterCount") put(selector, 1) else put(selector, "legacy")
                putJsonObject("target") { put("symbolId", "sym_one") }
            }, selector)
        }
    }

    fun testPositionTargetIgnoresNullValuedSiblingVariants() {
        val normalized = UnifiedTargetArguments.normalize(buildJsonObject {
            putJsonObject("target") {
                putJsonObject("position") {
                    put("file", "src/Foo.kt")
                    put("line", 12)
                    put("column", 9)
                }
                put("symbolId", JsonNull)
                put("qualifiedName", JsonNull)
                put("language", JsonNull)
            }
        }).getOrThrow()

        assertEquals("\"src/Foo.kt\"", normalized["file"].toString())
        assertEquals("12", normalized["line"].toString())
        assertEquals("9", normalized["column"].toString())
    }

    fun testPositionTargetIgnoresNullValuedUnknownPositionFields() {
        val normalized = UnifiedTargetArguments.normalize(buildJsonObject {
            putJsonObject("target") {
                putJsonObject("position") {
                    put("file", "src/Foo.kt")
                    put("line", 12)
                    put("column", 9)
                    put("symbolId", JsonNull)
                }
            }
        }).getOrThrow()

        assertEquals("\"src/Foo.kt\"", normalized["file"].toString())
    }

    fun testNullTargetBehavesLikeOmittedOptionalArgument() {
        val arguments = buildJsonObject {
            put("target", JsonNull)
            put("file", "src/Legacy.kt")
            put("line", 3)
            put("column", 2)
        }

        assertSame(arguments, UnifiedTargetArguments.normalize(arguments).getOrThrow())
    }

    fun testPositionRequiresCompletePositiveCoordinates() {
        assertTargetError(buildJsonObject {
            putJsonObject("target") {
                putJsonObject("position") {
                    put("file", "src/Foo.kt")
                    put("line", 0)
                }
            }
        }, "target.position.line")
    }

    fun testQualifiedNameRequiresLanguage() {
        assertTargetError(buildJsonObject {
            putJsonObject("target") { put("qualifiedName", "com.example.Foo") }
        }, "target.language")
    }

    fun testNestedSymbolIdCanRouteProject() {
        val symbolId = UnifiedTargetArguments.symbolIdForRouting(buildJsonObject {
            putJsonObject("target") { put("symbolId", "sym_route") }
        }).getOrThrow()

        assertEquals("sym_route", symbolId)
    }

    fun testBlankLegacySymbolIdFailsRouting() {
        val error = UnifiedTargetArguments.symbolIdForRouting(buildJsonObject {
            put("symbolId", "   ")
        }).exceptionOrNull()

        assertNotNull(error)
        assertTrue(error!!.message.orEmpty(), error.message.orEmpty().contains("symbolId must not be blank"))
    }

    fun testUndeclaredSelectorsAreIgnoredForRouting() {
        val symbolId = UnifiedTargetArguments.symbolIdForRouting(
            arguments = buildJsonObject {
                put("symbolId", "legacy-handle")
                putJsonObject("target") { put("symbolId", "nested-handle") }
            },
            acceptsLegacySymbolId = false,
            acceptsNestedTarget = false
        ).getOrThrow()

        assertNull(symbolId)
    }

    private fun assertTargetError(arguments: JsonObject, expectedText: String) {
        val error = UnifiedTargetArguments.normalize(arguments).exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message.orEmpty(), error.message.orEmpty().contains(expectedText))
    }
}
