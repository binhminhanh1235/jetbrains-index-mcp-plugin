package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class ChangeSignaturePreviewDefaultsBehaviorTest : McpPlatformTestCase() {
    fun testUnchangedReturnTypeWithOverrideCanBeAppliedWithoutChoice() = runBlocking {
        registerSourceRoot("r2-same-return-src")
        val file = "r2-same-return-src/ReturnHierarchy.java"
        val source = """
            class ReturnBase {
                int work() { return 1; }
            }
            class ReturnChild extends ReturnBase {
                @Override int work() { return 2; }
            }
        """.trimIndent()
        writeProjectFile(file, source)
        val arguments = buildJsonObject {
            put("file", file)
            put("line", 2)
            put("column", 9)
            put("newName", "compute")
            put("newReturnType", "int")
        }
        val previewResult = ChangeSignatureTool().execute(
            project, JsonObject(arguments + ("dryRun" to JsonPrimitive(true)))
        )
        assertToolSucceeded("Return-type preview", previewResult)
        val preview = Json.parseToJsonElement(toolText(previewResult)).jsonObject
        assertEquals(source, readProjectFileVfs(file))

        val applied = ChangeSignatureTool().execute(project, arguments)
        assertToolSucceeded("Rename with unchanged return type must apply", applied)
        val after = readProjectFileVfs(file)
        assertFalse("Old method names survived: $after", after.contains("work("))
        assertTrue("Both declarations should be renamed: $after", after.split("compute(").size == 3)
        assertTrue(
            "An unchanged return type does not enter the platform covariant-return chooser. " +
                "Preview=$preview; apply=${toolText(applied)}; source=$after",
            preview.getValue("canApply").jsonPrimitive.boolean
        )
    }

    fun testNewParameterNeedsNoDefaultWhenOnlyOverridesExist() = runBlocking {
        registerSourceRoot("r2-override-default-src")
        val file = "r2-override-default-src/OverrideHierarchy.java"
        val source = """
            class DefaultBase {
                void work() {}
            }
            class DefaultChild extends DefaultBase {
                @Override void work() {}
            }
        """.trimIndent()
        writeProjectFile(file, source)
        val arguments = buildJsonObject {
            put("file", file)
            put("line", 2)
            put("column", 10)
            put("newParameters", buildJsonArray {
                add(buildJsonObject {
                    put("oldIndex", -1)
                    put("name", "enabled")
                    put("type", "boolean")
                })
            })
        }
        val previewResult = ChangeSignatureTool().execute(
            project, JsonObject(arguments + ("dryRun" to JsonPrimitive(true)))
        )
        assertToolSucceeded("Override-only default preview", previewResult)
        val preview = Json.parseToJsonElement(toolText(previewResult)).jsonObject
        assertEquals(source, readProjectFileVfs(file))

        val applied = ChangeSignatureTool().execute(project, arguments)
        assertToolSucceeded("Only overrides exist, so no inserted argument needs a default", applied)
        val after = readProjectFileVfs(file)
        assertTrue("Both signatures should change: $after", after.split("work(boolean enabled)").size == 3)
        assertTrue(
            "An overriding declaration is not an argument-insertion call site. " +
                "Preview=$preview; apply=${toolText(applied)}; source=$after",
            preview.getValue("canApply").jsonPrimitive.boolean
        )
    }

    fun testDelegateNeedsDefaultEvenWithoutPreexistingCalls() = runBlocking {
        registerSourceRoot("r2-delegate-default-src")
        val file = "r2-delegate-default-src/DelegateTarget.java"
        val source = "class DelegateTarget { void work() {} }"
        writeProjectFile(file, source)
        val arguments = buildJsonObject {
            put("file", file)
            put("line", 1)
            put("column", source.indexOf("work") + 1)
            put("newParameters", buildJsonArray {
                add(buildJsonObject {
                    put("oldIndex", -1)
                    put("name", "enabled")
                    put("type", "boolean")
                })
            })
            put("generateDelegate", true)
        }
        val previewResult = ChangeSignatureTool().execute(
            project, JsonObject(arguments + ("dryRun" to JsonPrimitive(true)))
        )
        assertToolSucceeded("Delegate default preview", previewResult)
        val preview = Json.parseToJsonElement(toolText(previewResult)).jsonObject
        assertEquals(source, readProjectFileVfs(file))

        val applied = ChangeSignatureTool().execute(project, arguments)
        assertTrue("Apply must reject a delegate without an argument default", applied.isError == true)
        assertEquals("Rejected apply must preserve the source", source, readProjectFileVfs(file))
        assertFalse(
            "The generated old-signature delegate needs an argument for the new required parameter",
            preview.getValue("canApply").jsonPrimitive.boolean
        )
    }

    fun testDelegateUsesAnExplicitDefaultForTheNewParameter() = runBlocking {
        registerSourceRoot("delegate-default-src")
        val file = "delegate-default-src/DelegateTarget.java"
        val source = "class DelegateTarget { void work() {} }"
        writeProjectFile(file, source)
        val arguments = buildJsonObject {
            put("file", file)
            put("line", 1)
            put("column", source.indexOf("work") + 1)
            put("newParameters", buildJsonArray {
                add(buildJsonObject {
                    put("oldIndex", -1)
                    put("name", "enabled")
                    put("type", "boolean")
                    put("defaultValue", "true")
                })
            })
            put("generateDelegate", true)
        }
        val previewResult = ChangeSignatureTool().execute(
            project, JsonObject(arguments + ("dryRun" to JsonPrimitive(true)))
        )
        assertToolSucceeded("Delegate preview with an explicit default", previewResult)
        val preview = Json.parseToJsonElement(toolText(previewResult)).jsonObject
        assertTrue(preview.getValue("canApply").jsonPrimitive.boolean)
        assertEquals(source, readProjectFileVfs(file))

        val applied = ChangeSignatureTool().execute(project, arguments)
        assertToolSucceeded("Delegate with an explicit argument must apply", applied)
        val after = readProjectFileVfs(file)
        assertTrue("The delegate must call the new signature: $after", after.contains("work(true);"))
        assertTrue("The new signature must retain the parameter: $after", after.contains("work(boolean enabled)"))
    }
}
