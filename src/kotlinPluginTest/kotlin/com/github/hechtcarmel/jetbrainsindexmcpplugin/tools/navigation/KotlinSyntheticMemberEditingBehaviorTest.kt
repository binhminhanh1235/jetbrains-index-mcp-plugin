package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.LanguageHandlerRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.SymbolIdRegistry
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.EditMemberTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring.ReplaceMemberTool
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.search.GlobalSearchScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class KotlinSyntheticMemberEditingBehaviorTest : McpPlatformTestCase() {
    private val file = "r4-member-edit/Bean.kt"
    private val source = "package r4memberedit\nclass Bean { var value: Int = 0 }"
    override fun runInDispatchThread(): Boolean = false

    override fun setUp() {
        super.setUp()
        assertTrue("Real Kotlin runtime required", PluginDetectors.kotlin.isAvailable)
        LanguageHandlerRegistry.registerHandlers()
        registerSourceRoot("r4-member-edit")
        writeProjectFile(file, source)
        writeProjectFile("r4-member-edit/Use.java", """
            package r4memberedit;
            class Use { int root(Bean bean) { return bean.getValue(); } }
        """.trimIndent())
    }

    override fun tearDown() {
        try { LanguageHandlerRegistry.clear() } finally { super.tearDown() }
    }

    fun testGetterBodyEditMustNotRewriteThePropertyInitializer() = runBlocking {
        val handle = getterHandle()
        val changed = ReplaceMemberTool().execute(project, buildJsonObject {
            put("symbolId", handle)
            put("content", "return 42;")
            put("reformat", false)
        })
        assertEquals("Selecting a getter must preserve the backing-property initializer: ${toolText(changed)}",
            source, ReadAction.compute<String, RuntimeException> { readProjectFileVfs(file) })
        assertToolFailed("An implicit getter has no standalone source body to replace", changed)
    }

    fun testGetterFullEditMustNotReplaceTheWholeProperty() = runBlocking {
        val handle = getterHandle()
        val changed = EditMemberTool().execute(project, buildJsonObject {
            put("symbolId", handle)
            put("content", "var other: Int = 42")
            put("reformat", false)
        })
        assertEquals("A method handle cannot authorize replacing a different property declaration: ${toolText(changed)}",
            source, ReadAction.compute<String, RuntimeException> { readProjectFileVfs(file) })
        assertToolFailed("Method-to-property category change must be rejected", changed)
    }

    private fun getterHandle(): String = ReadAction.compute<String, RuntimeException> {
        val bean = requireNotNull(JavaPsiFacade.getInstance(project).findClass(
            "r4memberedit.Bean", GlobalSearchScope.allScope(project)
        ))
        SymbolIdRegistry.getInstance().bind(project, bean.findMethodsByName("getValue", false).single())
    }
}
