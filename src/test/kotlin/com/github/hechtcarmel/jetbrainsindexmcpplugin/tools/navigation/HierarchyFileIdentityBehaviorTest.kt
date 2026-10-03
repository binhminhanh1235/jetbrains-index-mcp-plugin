package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiDocumentManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class HierarchyFileIdentityBehaviorTest : McpPlatformTestCase() {
    private val file = "r4-replacement/Target.java"
    private val source = "package r4replace; class Target { void work() {} void work(int value) {} void a() { work(); } void b() { work(); } }"

    override fun setUp() {
        super.setUp()
        LanguageHandlerRegistry.registerHandlers()
        registerSourceRoot("r4-replacement")
        writeProjectFile(file, source)
    }

    override fun tearDown() {
        try { LanguageHandlerRegistry.clear() } finally { super.tearDown() }
    }

    fun testHierarchyRootMustNotBecomeDeclarationInRecreatedFile() = runBlocking {
        val tool = CallHierarchyTool()
        val first = tool.execute(project, buildJsonObject {
            put("language", "Java")
            put("symbol", "r4replace.Target#work()")
            put("direction", "callers")
            put("depth", 1)
            put("maxNodes", 1)
        })
        assertToolSucceeded("Discover actual caller continuation", first)
        val cursor = Json.parseToJsonElement(toolText(first)).jsonObject.getValue("cursor").jsonPrimitive.content
        replaceFile()
        val next = tool.execute(project, buildJsonObject { put("cursor", cursor); put("maxNodes", 1) })
        assertToolFailed("The deleted hierarchy root must not follow a replacement: ${toolText(next)}", next)
        val error = toolText(next).lowercase()
        assertTrue("Deleted root error should explain stale or invalid identity: $error",
            "deleted" in error || "stale" in error || "invalid" in error || "expired" in error)
    }

    fun testTypeHierarchyRootRejectsARecreatedFile() = runBlocking {
        val path = "r4-replacement/Types.java"
        val types = "package r4types; class Target {} class A extends Target {} class B extends Target {}"
        writeProjectFile(path, types)
        val tool = TypeHierarchyTool()
        val first = tool.execute(project, buildJsonObject {
            put("language", "Java")
            put("symbol", "r4types.Target")
            put("maxNodes", 1)
        })
        assertToolSucceeded("Discover a real subtype continuation", first)
        val cursor = Json.parseToJsonElement(toolText(first)).jsonObject.getValue("cursor").jsonPrimitive.content
        replaceFile(path, types.replace("Target", "Victim"))
        val next = tool.execute(project, buildJsonObject { put("cursor", cursor); put("maxNodes", 1) })
        assertToolFailed("The deleted type root must not select a new declaration at the same path", next)
        val error = toolText(next).lowercase()
        assertTrue("Deleted type error should explain stale or invalid identity: $error",
            "deleted" in error || "stale" in error || "invalid" in error || "expired" in error)
    }

    private fun replaceFile(path: String = file, replacement: String = source.replace("work", "evil")) {
        val oldFile = requireNotNull(LocalFileSystem.getInstance().findFileByPath("${project.basePath}/$path"))
        WriteCommandAction.runWriteCommandAction(project) { oldFile.delete(this) }
        assertFalse(oldFile.isValid)
        writeProjectFile(path, replacement)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertNotSame(oldFile, LocalFileSystem.getInstance().findFileByPath("${project.basePath}/$path"))
    }
}
