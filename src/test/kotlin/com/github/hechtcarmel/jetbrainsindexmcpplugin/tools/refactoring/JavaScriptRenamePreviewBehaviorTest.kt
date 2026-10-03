package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.refactoring.RefactoringSettings
import com.intellij.refactoring.rename.naming.AutomaticRenamer
import com.intellij.refactoring.rename.naming.AutomaticRenamerFactory
import com.intellij.usageView.UsageInfo
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assume
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class JavaScriptRenamePreviewBehaviorTest : McpPlatformTestCase() {

    override fun runInDispatchThread(): Boolean = false

    fun testTypeScriptFileRenameDryRunDoesNotAskToRenameDeclaration() = runBlocking {
        val preview = previewTypeScriptFile()

        assertFalse("An unresolved declaration rename choice must fail closed", preview.getValue("canApply").jsonPrimitive.boolean)
        assertMatchingDeclarationWarning(preview)
    }

    fun testTypeScriptFileRenamePreviewIncludesDeclarationUsagesWhenConfirmationIsDisabled() = runBlocking {
        val preview = previewTypeScriptFile(
            source = "export class Foo {} const instance = new Foo();",
            askToRenameDeclaration = false
        )

        assertTrue(preview.toString(), preview.getValue("canApply").jsonPrimitive.boolean)
        assertTrue(
            "The related declaration's references must remain in the full preview plan",
            preview.getValue("usageCount").jsonPrimitive.int >= 1
        )
    }

    fun testTypeScriptFileRenamePreviewRemainsApplicableWhenDeclarationRenamingIsDisabled() = runBlocking {
        val preview = previewTypeScriptFile(
            source = "export class Foo {} const instance = new Foo();",
            renameDeclaration = false
        )

        assertTrue(preview.toString(), preview.getValue("canApply").jsonPrimitive.boolean)
        assertEquals("The disabled declaration rename must not add symbol usages", 0, preview.getValue("usageCount").jsonPrimitive.int)
    }

    fun testTypeScriptFileRenamePreviewRemainsApplicableWithoutAMatchingDeclaration() = runBlocking {
        val preview = previewTypeScriptFile(source = "export class Other {}")

        assertTrue(preview.toString(), preview.getValue("canApply").jsonPrimitive.boolean)
    }

    fun testAutomaticTypeScriptFileRenamePreviewDoesNotAskToRenameDeclaration() = runBlocking {
        Assume.assumeTrue("JavaScript plugin required for this fixture", PluginDetectors.javaScript.isAvailable)
        registerSourceRoot("dry-ts-automatic-src")
        val target = writeProjectFile("dry-ts-automatic-src/trigger.txt", "trigger")
        val related = writeProjectFile("dry-ts-automatic-src/Foo.ts", "export class Foo {}")
        val originalTargetBytes = Files.readAllBytes(target)
        val originalRelatedBytes = Files.readAllBytes(related)
        val relatedFile = ReadAction.compute<PsiFile, RuntimeException> {
            val virtualFile = requireNotNull(LocalFileSystem.getInstance().findFileByPath(related.toString()))
            requireNotNull(PsiManager.getInstance(project).findFile(virtualFile))
        }
        val factory = object : AutomaticRenamerFactory {
            override fun isApplicable(element: PsiElement): Boolean = (element as? PsiFile)?.name == "trigger.txt"
            override fun getOptionName(): String? = null
            override fun isEnabled(): Boolean = true
            override fun setEnabled(enabled: Boolean) = Unit
            override fun createRenamer(element: PsiElement, newName: String, usages: Collection<UsageInfo>): AutomaticRenamer =
                object : AutomaticRenamer() {
                    init {
                        myElements.add(relatedFile)
                        suggestAllNames("Foo", "Bar")
                    }

                    override fun getDialogTitle(): String = "Related TypeScript file"
                    override fun getDialogDescription(): String = "Related TypeScript file"
                    override fun entityName(): String = "file"
                }
        }
        AutomaticRenamerFactory.EP_NAME.point.registerExtension(factory, testRootDisposable)

        val preview = previewWithoutDialogs(renameDeclaration = true, askToRenameDeclaration = true) {
            RenameSymbolTool().execute(project, buildJsonObject {
                put("file", "dry-ts-automatic-src/trigger.txt")
                put("newName", "renamed.txt")
                put("dryRun", true)
            })
        }

        assertFalse("The automatically included file also requires an unresolved choice", preview.getValue("canApply").jsonPrimitive.boolean)
        assertMatchingDeclarationWarning(preview)
        assertTrue(originalTargetBytes.contentEquals(Files.readAllBytes(target)))
        assertTrue(originalRelatedBytes.contentEquals(Files.readAllBytes(related)))
        ReadAction.run<Throwable> {
            assertEquals("trigger", readProjectFileVfs("dry-ts-automatic-src/trigger.txt"))
            assertEquals("export class Foo {}", readProjectFileVfs("dry-ts-automatic-src/Foo.ts"))
        }
        assertProjectFileAbsent("dry-ts-automatic-src/renamed.txt")
        assertProjectFileAbsent("dry-ts-automatic-src/Bar.ts")
    }

    private suspend fun previewTypeScriptFile(
        source: String = "export class Foo {}",
        renameDeclaration: Boolean = true,
        askToRenameDeclaration: Boolean = true
    ): JsonObject {
        Assume.assumeTrue("JavaScript plugin required for this fixture", PluginDetectors.javaScript.isAvailable)
        registerSourceRoot("dry-ts-file-src")
        val file = "dry-ts-file-src/Foo.ts"
        val path = writeProjectFile(file, source)
        val originalBytes = Files.readAllBytes(path)

        val preview = previewWithoutDialogs(renameDeclaration, askToRenameDeclaration) {
            RenameSymbolTool().execute(project, buildJsonObject {
                put("file", file)
                put("newName", "Bar.ts")
                put("dryRun", true)
            })
        }

        assertTrue("Dry-run must preserve file bytes", originalBytes.contentEquals(Files.readAllBytes(path)))
        ReadAction.run<Throwable> {
            assertEquals("Dry-run must preserve in-memory source", source, readProjectFileVfs(file))
        }
        assertProjectFileAbsent("dry-ts-file-src/Bar.ts")
        return preview
    }

    private suspend fun previewWithoutDialogs(
        renameDeclaration: Boolean,
        askToRenameDeclaration: Boolean,
        action: suspend () -> CallToolResult
    ): JsonObject {
        val settings = RefactoringSettings.getInstance()
        val previousRenameDeclaration = settings.RENAME_DECLARATION_WHEN_RENAME_FILE
        val previousAskToRenameDeclaration = settings.ASK_FOR_RENAME_DECLARATION_WHEN_RENAME_FILE
        val dialogCount = AtomicInteger()
        val previousDialog = TestDialogManager.setTestDialog(TestDialog {
            dialogCount.incrementAndGet()
            Messages.YES
        })

        try {
            settings.RENAME_DECLARATION_WHEN_RENAME_FILE = renameDeclaration
            settings.ASK_FOR_RENAME_DECLARATION_WHEN_RENAME_FILE = askToRenameDeclaration

            val result = action()

            assertEquals("A dry-run must not open the declaration rename dialog", 0, dialogCount.get())
            assertToolSucceeded("Dry-run should return a preview payload", result)
            val preview = Json.parseToJsonElement(toolText(result)).jsonObject
            assertTrue(preview.getValue("dryRun").jsonPrimitive.boolean)
            assertEquals("Dry-run must retain the declaration rename preference", renameDeclaration, settings.RENAME_DECLARATION_WHEN_RENAME_FILE)
            assertEquals("Dry-run must retain the ask preference", askToRenameDeclaration, settings.ASK_FOR_RENAME_DECLARATION_WHEN_RENAME_FILE)
            return preview
        } finally {
            settings.RENAME_DECLARATION_WHEN_RENAME_FILE = previousRenameDeclaration
            settings.ASK_FOR_RENAME_DECLARATION_WHEN_RENAME_FILE = previousAskToRenameDeclaration
            TestDialogManager.setTestDialog(previousDialog)
        }
    }

    private fun assertMatchingDeclarationWarning(preview: JsonObject) {
        val warnings = preview.getValue("warnings").jsonArray.joinToString { it.jsonPrimitive.content }
        assertTrue(warnings, warnings.contains("interactive choice for its matching declaration"))
        assertTrue("The preview must explain how to proceed", warnings.contains("before retrying the preview"))
    }
}
