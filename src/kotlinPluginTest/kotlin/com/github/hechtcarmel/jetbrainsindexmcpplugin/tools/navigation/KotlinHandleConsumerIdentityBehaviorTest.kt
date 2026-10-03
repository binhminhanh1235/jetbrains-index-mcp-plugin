package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.SymbolIdRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SymbolInfoResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiElement
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class KotlinHandleConsumerIdentityBehaviorTest : McpPlatformTestCase() {
    private val json = Json { ignoreUnknownKeys = true }
    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        assertTrue("Real Kotlin runtime required", PluginDetectors.kotlin.isAvailable)
        LanguageHandlerRegistry.registerHandlers()
        registerSourceRoot("r4-accessor")
        writeProjectFile("r4-accessor/Bean.kt", """
            package r4accessor
            class Bean { var value: Int = 0 }
        """.trimIndent())
        writeProjectFile("r4-accessor/Use.java", """
            package r4accessor;
            class Use { void root(Bean bean) { bean.getValue(); bean.setValue(1); } }
        """.trimIndent())
    }

    override fun tearDown() {
        try { LanguageHandlerRegistry.clear() } finally { super.tearDown() }
    }

    fun testFindReferencesMustPreservePublishedSetterCallableHandle() = runBlocking {
        val handle = setterHandle()
        val original = ReadAction.compute<PsiElement, Throwable> {
            SymbolIdRegistry.getInstance().resolve(project, handle).getOrThrow()
        }
        val usages = FindUsagesTool().execute(project, buildJsonObject { put("symbolId", handle) })
        assertToolSucceeded("Find setter usages", usages)
        val info = SymbolInfoTool().execute(project, buildJsonObject { put("symbolId", handle) })
        assertToolSucceeded("Read the original setter handle again", info)
        assertEquals("Reference search must not retarget the original handle to its navigation property", "setValue",
            json.decodeFromString<SymbolInfoResult>(toolText(info)).name)
        ReadAction.run<Throwable> {
            assertSame(original, SymbolIdRegistry.getInstance().resolve(project, handle).getOrThrow())
        }
    }

    fun testFindSuperMethodsMustPreservePublishedSetterCallableHandle() = runBlocking {
        val handle = setterHandle()
        val original = ReadAction.compute<PsiElement, Throwable> {
            SymbolIdRegistry.getInstance().resolve(project, handle).getOrThrow()
        }
        val hierarchy = FindSuperMethodsTool().execute(project, buildJsonObject { put("symbolId", handle) })
        assertToolSucceeded("Inspect super methods of a setter", hierarchy)
        val info = SymbolInfoTool().execute(project, buildJsonObject { put("symbolId", handle) })
        assertToolSucceeded("Read the original setter handle again", info)
        assertEquals("Super-method lookup must not retarget the original handle to its navigation property", "setValue",
            json.decodeFromString<SymbolInfoResult>(toolText(info)).name)
        ReadAction.run<Throwable> {
            assertSame(original, SymbolIdRegistry.getInstance().resolve(project, handle).getOrThrow())
        }
    }
    private fun setterHandle(): String = ReadAction.compute<String, RuntimeException> {
        val bean = requireNotNull(JavaPsiFacade.getInstance(project).findClass(
            "r4accessor.Bean", GlobalSearchScope.allScope(project)
        ))
        val setter = bean.findMethodsByName("setValue", false).single()
        SymbolIdRegistry.getInstance().bind(project, setter)
    }
}
