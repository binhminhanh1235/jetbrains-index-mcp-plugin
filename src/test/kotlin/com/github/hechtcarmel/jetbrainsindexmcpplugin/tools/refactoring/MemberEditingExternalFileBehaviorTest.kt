package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.settings.McpSettings
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.McpTool
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

class MemberEditingExternalFileBehaviorTest : McpPlatformTestCase() {
    override fun runInDispatchThread(): Boolean = false

    fun testEditMemberRefreshesNewExternalFile() = runBlocking {
        verifyExternalFile(EditMemberTool(), "int work() { return 2; }")
    }

    fun testReplaceMemberRefreshesNewExternalFile() = runBlocking {
        verifyExternalFile(ReplaceMemberTool(), "return 2;")
    }

    fun testEditMemberPositionRefreshesNewExternalFile() = runBlocking {
        verifyExternalFile(EditMemberTool(), "int work() { return 2; }", positionTarget = true)
    }

    fun testReplaceMemberPositionRefreshesNewExternalFile() = runBlocking {
        verifyExternalFile(ReplaceMemberTool(), "return 2;", positionTarget = true)
    }

    private suspend fun verifyExternalFile(tool: McpTool, content: String, positionTarget: Boolean = false) {
        assertFalse(ApplicationManager.getApplication().isDispatchThread)
        assertFalse(ApplicationManager.getApplication().isReadAccessAllowed)
        val settings = McpSettings.getInstance()
        val priorSync = settings.syncExternalChanges
        settings.syncExternalChanges = false
        try {
            registerSourceRoot("external-member-src")
            val anchor = writeProjectFile(
                "external-member-src/external/Anchor.java", "package external; class Anchor {}"
            )
            val directory = requireNotNull(LocalFileSystem.getInstance().findFileByPath(anchor.parent.toString()))
            ReadAction.run<Throwable> { directory.children }
            val file = "external-member-src/external/ExternalTarget.java"
            val path = Path.of(requireNotNull(project.basePath), file)
            val before = "package external; class ExternalTarget { int work() { return 1; } }"
            Files.writeString(path, before)
            assertNull("Target must require VFS refresh", LocalFileSystem.getInstance().findFileByPath(path.toString()))

            val result = tool.execute(project, buildJsonObject {
                if (positionTarget) {
                    put("target", buildJsonObject {
                        put("position", buildJsonObject {
                            put("file", file)
                            put("line", 1)
                            put("column", before.indexOf("work") + 1)
                        })
                    })
                } else {
                    put("file", file)
                    put("class", "ExternalTarget")
                    put("member", "work")
                }
                put("content", content)
                put("reformat", false)
            })
            assertToolSucceeded("${tool.name} must refresh an existing external target before reading PSI", result)
            val after = Files.readString(path)
            assertTrue("Replacement must reach source: $after", after.contains("return 2;"))
            assertFalse("Original body must be replaced: $after", after.contains("return 1;"))
        } finally {
            settings.syncExternalChanges = priorSync
        }
    }
}
