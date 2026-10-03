package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools

import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import com.intellij.openapi.project.Project
import junit.framework.TestCase
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class AbstractMcpToolArgumentNormalizationUnitTest : TestCase() {

    private val tool = ProbeTool()
    private val project = Proxy.newProxyInstance(
        Project::class.java.classLoader,
        arrayOf(Project::class.java)
    ) { proxy, method, args ->
        when (method.name) {
            "equals" -> proxy === args?.get(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "argument-normalization-project-proxy"
            else -> null
        }
    } as Project

    fun testOptionalBlankToNullNormalization() {
        val arguments = buildJsonObject { put("cursor", JsonPrimitive("   ")) }

        val normalized = invokeOptionalStringArg(arguments, "cursor")

        assertNull("Blank optional value should normalize to null", normalized)
    }

    fun testOptionalNonEmptyValueIsPreservedTrimmed() {
        val arguments = buildJsonObject { put("cursor", JsonPrimitive("  page-2  ")) }

        val normalized = invokeOptionalStringArg(arguments, "cursor")

        assertEquals("page-2", normalized)
    }

    fun testOptionalNullNormalization() {
        val arguments = buildJsonObject { put("cursor", JsonNull) }

        val normalized = invokeOptionalStringArg(arguments, "cursor")

        assertNull("Null optional value should normalize to null", normalized)
    }

    fun testRequiredBlankRejected() {
        val arguments = buildJsonObject { put("file", JsonPrimitive("   ")) }

        val requiredResult = invokeRequiredStringArg(arguments, "file")

        assertTrue("Required blank value should be rejected", requiredResult.isFailure)
        val message = requiredResult.exceptionOrNull()?.message
        assertEquals(ErrorMessages.missingRequiredParam("file"), message)
    }

    fun testLookupInferenceIgnoresBlankFileLanguageAndSymbol() {
        val arguments = buildJsonObject {
            put("file", JsonPrimitive("   "))
            put("language", JsonPrimitive("  "))
            put("symbol", JsonPrimitive("\t"))
        }

        val result = tool.resolveElementForTest(project, arguments)

        assertTrue(result.isFailure)
        assertEquals(
            ErrorMessages.SYMBOL_OR_POSITION_REQUIRED,
            result.exceptionOrNull()?.message
        )
    }

    fun testMixedNonEmptyLookupModesRemainMutuallyExclusive() {
        val arguments = buildJsonObject {
            put("file", JsonPrimitive("src/Main.kt"))
            put("line", JsonPrimitive(12))
            put("column", JsonPrimitive(5))
            put("language", JsonPrimitive("kotlin"))
            put("symbol", JsonPrimitive("com.example.Main#run()"))
        }

        val result = tool.resolveElementForTest(project, arguments)

        assertTrue(result.isFailure)
        assertEquals(
            ErrorMessages.SYMBOL_AND_POSITION_EXCLUSIVE,
            result.exceptionOrNull()?.message
        )
    }

    fun testLoneLanguageWithCompletePositionUsesPositionMode() {
        val arguments = buildJsonObject {
            put("file", JsonPrimitive("src/Main.kt"))
            put("line", JsonPrimitive(12))
            put("column", JsonPrimitive(5))
            put("language", JsonPrimitive("Java"))
        }

        assertEquals("POSITION", tool.lookupModeNameForTest(arguments))
    }

    fun testLoneSymbolWithCompletePositionUsesPositionMode() {
        val arguments = buildJsonObject {
            put("file", JsonPrimitive("src/Main.kt"))
            put("line", JsonPrimitive(12))
            put("column", JsonPrimitive(5))
            put("symbol", JsonPrimitive("com.example.Main#run()"))
        }

        assertEquals("POSITION", tool.lookupModeNameForTest(arguments))
    }

    fun testLoneLanguageWithoutCompletePositionStillRequiresSymbol() {
        val arguments = buildJsonObject {
            put("language", JsonPrimitive("Java"))
        }

        val result = tool.resolveElementForTest(project, arguments)

        assertTrue(result.isFailure)
        assertEquals(
            ErrorMessages.missingParamForSymbol("symbol"),
            result.exceptionOrNull()?.message
        )
    }

    fun testSymbolIdIsIgnoredUnlessTheToolExplicitlyAllowsIt() {
        val arguments = buildJsonObject {
            put("symbolId", JsonPrimitive("sym_handle"))
        }

        assertEquals("MISSING", tool.lookupModeNameForTest(arguments))
        assertEquals("SYMBOL_ID", tool.lookupModeNameForTest(arguments, allowSymbolId = true))
    }

    fun testUndeclaredNonPrimitiveSymbolIdIsNotParsed() {
        val arguments = buildJsonObject {
            put("symbolId", buildJsonObject { put("unexpected", JsonPrimitive(true)) })
        }

        val result = tool.resolveElementForTest(project, arguments)

        assertTrue(result.isFailure)
        assertEquals(ErrorMessages.SYMBOL_OR_POSITION_REQUIRED, result.exceptionOrNull()?.message)
    }

    fun testAllowedSymbolIdCannotBeCombinedWithAnotherTargetSelector() {
        val arguments = buildJsonObject {
            put("symbolId", JsonPrimitive("sym_handle"))
            put("file", JsonPrimitive("src/Main.kt"))
            put("line", JsonPrimitive(12))
            put("column", JsonPrimitive(5))
        }

        val result = tool.resolveElementForTest(project, arguments, allowSymbolId = true)

        assertTrue(result.isFailure)
        assertEquals(
            ErrorMessages.SYMBOL_ID_AND_OTHER_TARGET_EXCLUSIVE,
            result.exceptionOrNull()?.message
        )
    }

    fun testHandleEnabledResolverUsesItsOwnMissingTargetMessage() {
        val result = tool.resolveElementForTest(
            project,
            buildJsonObject { },
            allowSymbolId = true
        )

        assertTrue(result.isFailure)
        assertEquals(
            ErrorMessages.SYMBOL_ID_OR_SYMBOL_OR_POSITION_REQUIRED,
            result.exceptionOrNull()?.message
        )
    }

    private fun invokeOptionalStringArg(arguments: JsonObject, name: String): String? {
        val method = findMethod("optionalStringArg")
        return method.invoke(tool, arguments, name) as String?
    }

    private fun invokeRequiredStringArg(arguments: JsonObject, name: String): Result<*> {
        return tool.requiredStringForTest(arguments, name)
    }

    private fun findMethod(name: String): Method {
        return try {
            AbstractMcpTool::class.java.getDeclaredMethod(name, JsonObject::class.java, String::class.java).apply {
                isAccessible = true
            }
        } catch (noSuchMethod: NoSuchMethodException) {
            fail("Expected AbstractMcpTool.$name(JsonObject, String) to exist for argument normalization contract")
            throw noSuchMethod
        }
    }

    private class ProbeTool : AbstractMcpTool() {
        override val name: String = "probe"
        override val description: String = "probe"
        override val inputSchema: ToolSchema = ToolSchema()

        override suspend fun doExecute(project: Project, arguments: JsonObject): CallToolResult {
            error("Not used in unit tests")
        }

        fun resolveElementForTest(
            project: Project,
            arguments: JsonObject,
            allowSymbolId: Boolean = false
        ): Result<com.intellij.psi.PsiElement> {
            return resolveElementFromArguments(project, arguments, allowSymbolId = allowSymbolId)
        }

        fun requiredStringForTest(arguments: JsonObject, name: String): Result<String> {
            return requiredStringArg(arguments, name)
        }

        fun lookupModeNameForTest(arguments: JsonObject, allowSymbolId: Boolean = false): String {
            return resolveLookupMode(arguments, allowSymbolId).name
        }
    }
}
