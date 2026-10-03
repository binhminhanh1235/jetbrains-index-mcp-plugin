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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put

class CachedSymbolFileIdentityBehaviorTest : McpPlatformTestCase() {
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

    fun testCachedSymbolMustNotBecomeDeclarationInRecreatedFile() = runBlocking {
        val tool = FindSymbolTool()
        val first = tool.execute(project, buildJsonObject {
            put("query", "work")
            put("pageSize", 1)
        })
        assertToolSucceeded("Discover actual cached overloads", first)
        val cursor = Json.parseToJsonElement(toolText(first)).jsonObject.getValue("nextCursor").jsonPrimitive.content
        replaceFile()
        val next = tool.execute(project, buildJsonObject { put("cursor", cursor) })
        if (next.isError != true) {
            val handle = Json.parseToJsonElement(toolText(next)).jsonObject.getValue("symbols")
                .jsonArray.single().jsonObject.getValue("symbolId").jsonPrimitive.content
            val info = SymbolInfoTool().execute(project, buildJsonObject { put("symbolId", handle) })
            assertToolSucceeded("Follow the supposedly surviving cached declaration", info)
            assertEquals("Cached handle selected a different declaration in the replacement file: ${toolText(info)}",
                "work", Json.parseToJsonElement(toolText(info)).jsonObject.getValue("name").jsonPrimitive.content)
        }
        assertToolFailed("A search cursor must not resurrect a deleted file's declaration: ${toolText(next)}", next)
    }

    fun testCachedReferenceMetadataRejectsARecreatedTargetFile() = runBlocking {
        val tool = FindUsagesTool()
        val first = tool.execute(project, buildJsonObject {
            put("language", "Java")
            put("symbol", "r4replace.Target#work()")
            put("pageSize", 1)
        })
        assertToolSucceeded("Discover cached references and their target metadata", first)
        val cursor = Json.parseToJsonElement(toolText(first)).jsonObject.getValue("nextCursor").jsonPrimitive.content
        replaceFile()
        val next = tool.execute(project, buildJsonObject { put("cursor", cursor) })
        assertToolFailed("Cached reference metadata must not bind the replacement declaration", next)
    }

    private fun replaceFile() {
        val oldFile = requireNotNull(LocalFileSystem.getInstance().findFileByPath("${project.basePath}/$file"))
        WriteCommandAction.runWriteCommandAction(project) { oldFile.delete(this) }
        assertFalse(oldFile.isValid)
        writeProjectFile(file, source.replace("work", "evil"))
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertNotSame(oldFile, LocalFileSystem.getInstance().findFileByPath("${project.basePath}/$file"))
    }
}
