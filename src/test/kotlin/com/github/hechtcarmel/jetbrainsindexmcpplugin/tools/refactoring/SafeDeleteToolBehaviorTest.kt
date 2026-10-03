package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ErrorMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.RefactoringResult
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.PluginDetectors
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.extensions.LoadingOrder
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume

/**
 * Behavior coverage for `ide_refactor_safe_delete`.
 *
 * This tool is enabled by default and deletes source code, yet the only executions in the suite
 * were two error paths (missing arguments, missing file). Nothing proved it ever deletes anything,
 * and — worse for a tool whose selling point is the word "safe" — nothing proved it refuses when
 * the target is still referenced.
 *
 * Every fixture registers its own source root: without one `ReferencesSearch` sees no second file,
 * the usage scan comes back empty, and a "safe" delete happily removes a symbol that is still
 * called. A refusal test built on an unregistered root would pass for exactly that reason.
 */
class SafeDeleteToolBehaviorTest : McpPlatformTestCase() {

    private val json = Json { ignoreUnknownKeys = true }

    private fun decodeRefactoring(text: String): RefactoringResult = json.decodeFromString(text)

    private fun decodePreview(text: String): RefactoringPreviewResult = json.decodeFromString(text)

    private fun decodeSymbolBlocked(text: String): SafeDeleteBlockedResult = json.decodeFromString(text)

    private fun decodeFileBlocked(text: String): SafeDeleteFileBlockedResult = json.decodeFromString(text)

    private fun decodeNoSymbolFound(text: String): NoSymbolFoundResult = json.decodeFromString(text)

    fun testQualifiedSelectorMixedWithCoordinatesUsesDedicatedDiagnostic() = runBlocking {
        val result = SafeDeleteTool().execute(project, buildJsonObject {
            put("language", "Java")
            put("symbol", "example.Service#work()")
            put("file", "src/Service.java")
            put("line", 1)
            put("column", 1)
        })

        assertToolFailed("Mixed qualified and position selectors must fail", result)
        assertEquals(ErrorMessages.LANGUAGE_SYMBOL_AND_OTHER_TARGET_EXCLUSIVE, toolText(result))
    }

    fun testResourceDiscoveryFailureBlocksApplyAsWellAsPreview() = runBlocking {
        registerSourceRoot("sd-resource-failure-src")
        val file = "sd-resource-failure-src/ResourceProbe.java"
        val before = "class ResourceProbe {}"
        writeProjectFile(file, before)
        val processor = object : RenamePsiElementProcessor() {
            override fun canProcessElement(element: PsiElement): Boolean =
                element is PsiFile && element.name == "ResourceProbe.java"

            override fun prepareRenaming(
                element: PsiElement,
                newName: String,
                allRenames: MutableMap<PsiElement, String>
            ) {
                throw IllegalStateException("Resource discovery is unavailable")
            }
        }
        RenamePsiElementProcessor.EP_NAME.point.registerExtension(processor, LoadingOrder.FIRST, testRootDisposable)
        val arguments = buildJsonObject {
            put("file", file)
            put("target_type", "file")
        }
        val preview = SafeDeleteTool().execute(project, JsonObject(arguments + ("dryRun" to JsonPrimitive(true))))
        assertToolSucceeded("Preview must report incomplete discovery", preview)
        assertFalse(decodePreview(toolText(preview)).canApply)
        val applied = SafeDeleteTool().execute(project, arguments)
        assertToolFailed("Apply must not delete after the same discovery failure", applied)
        assertTrue(toolText(applied).contains("Resource discovery is unavailable"))
        assertEquals(before, readProjectFileVfs(file))
    }

    fun testOverriddenMethodParameterDeletionIsBlockedWithoutForce() = assertHierarchyParameterBlocked(baseParameter = true)

    fun testOverridingMethodParameterDeletionIsBlockedWithoutForce() = assertHierarchyParameterBlocked(baseParameter = false)

    private fun assertHierarchyParameterBlocked(baseParameter: Boolean) = runBlocking {
        registerSourceRoot("sd-override-param-src")
        val file = "sd-override-param-src/Base.java"
        val before = "abstract class Base { abstract void run(int value); } " +
            "class Child extends Base { @Override void run(int value) {} }"
        writeProjectFile(file, before)
        val arguments = buildJsonObject {
            put("file", file)
            put("line", 1)
            put("column", (if (baseParameter) before.indexOf("value") else before.lastIndexOf("value")) + 1)
        }
        val previewResult = SafeDeleteTool().execute(project, JsonObject(
            arguments + ("dryRun" to JsonPrimitive(true))
        ))
        assertToolSucceeded("Parameter preview should report hierarchy blockers", previewResult)
        val preview = decodePreview(toolText(previewResult))
        assertFalse("Deleting only one hierarchy parameter would break the override", preview.canApply)
        val applyResult = SafeDeleteTool().execute(project, arguments)
        assertToolSucceeded("A parameter in an override hierarchy must produce a structured refusal", applyResult)
        assertFalse(decodeSymbolBlocked(toolText(applyResult)).canDelete)
        assertEquals(before, readProjectFileVfs(file))
    }

    fun testParameterDeleteIgnoresNonCodeWordOccurrences() = runBlocking {
        registerSourceRoot("sd-word-src")
        val file = "sd-word-src/WordParameter.java"
        val before = "class WordParameter { void greet(String name) {} }"
        writeProjectFile(file, before)
        writeProjectFile("sd-word-src/README.md", "The name is a display name.")
        writeProjectFile("sd-word-src/messages.properties", "name=Display name")
        val arguments = buildJsonObject {
            put("file", file)
            put("line", 1)
            put("column", before.indexOf("name") + 1)
        }
        val previewResult = SafeDeleteTool().execute(project, JsonObject(
            arguments + ("dryRun" to JsonPrimitive(true))
        ))
        assertToolSucceeded("Unused method-parameter preview must succeed", previewResult)
        val preview = decodePreview(toolText(previewResult))
        assertTrue("Non-code words cannot block deleting an unused parameter", preview.canApply)
        assertEquals(0, preview.usageCount)
        assertEquals(before, readProjectFileVfs(file))

        val result = SafeDeleteTool().execute(project, arguments)
        assertToolSucceeded("Unused method-parameter delete must succeed", result)
        assertTrue(decodeRefactoring(toolText(result)).success)
        assertFileContains(file, "void greet()")
        assertFileDoesNotContain(file, "String name")
        assertEquals("name=Display name", readProjectFileVfs("sd-word-src/messages.properties"))
    }

    fun testLambdaParameterUsageBlocksDeletionWithoutPlatformAssertion() = runBlocking {
        assertLocalParameterUsageBlocksDeletion(
            "interface Action { void accept(String value); } " +
                "class LocalParameter { void run() { Action action = name -> System.out.println(name); } }"
        )
    }

    fun testCatchParameterUsageBlocksDeletionWithoutPlatformAssertion() = runBlocking {
        assertLocalParameterUsageBlocksDeletion(
            "class LocalParameter { void run() { try {} catch (Exception name) { System.out.println(name); } } }"
        )
    }

    fun testForEachParameterUsageBlocksDeletionWithoutPlatformAssertion() = runBlocking {
        assertLocalParameterUsageBlocksDeletion(
            "class LocalParameter { void run(String[] values) { for (String name : values) { System.out.println(name); } } }"
        )
    }

    private suspend fun assertLocalParameterUsageBlocksDeletion(before: String) {
        registerSourceRoot("sd-local-param-src")
        val file = "sd-local-param-src/LocalParameter.java"
        writeProjectFile(file, before)
        val arguments = buildJsonObject {
            put("file", file)
            put("line", 1)
            put("column", before.indexOf("name") + 1)
        }
        val previewResult = SafeDeleteTool().execute(project, JsonObject(
            arguments + ("dryRun" to JsonPrimitive(true))
        ))
        assertToolSucceeded("Local parameter preview must not invoke the method-parameter delegate", previewResult)
        val preview = decodePreview(toolText(previewResult))
        assertFalse(preview.canApply)
        assertTrue("The actual parameter reference must be discovered", preview.usageCount > 0)
        val applyResult = SafeDeleteTool().execute(project, arguments)
        assertToolSucceeded("A used local parameter must produce a structured refusal", applyResult)
        val blocked = decodeSymbolBlocked(toolText(applyResult))
        assertFalse(blocked.canDelete)
        assertTrue(blocked.blockingUsages.any { it.file == file })
        assertEquals(before, readProjectFileVfs(file))
    }

    fun testForcedFileDeleteWithoutDeclarationsAgreesWithPreview() = runBlocking {
        val file = "sd-force-opaque/payload.data"
        val before = "opaque payload"
        writeProjectFile(file, before)
        val arguments = buildJsonObject {
            put("file", file)
            put("target_type", "file")
            put("force", true)
        }
        val previewResult = SafeDeleteTool().execute(project, JsonObject(
            arguments + ("dryRun" to JsonPrimitive(true))
        ))
        assertToolSucceeded("Forced file preview must succeed", previewResult)
        val preview = decodePreview(toolText(previewResult))
        assertTrue("An applicable forced deletion must have an applicable preview", preview.canApply)
        assertTrue("Incomplete discovery must remain visible even with force", preview.warnings.isNotEmpty())
        assertEquals(listOf(file), preview.affectedFiles)
        assertEquals(before, readProjectFileVfs(file))
        val result = SafeDeleteTool().execute(project, arguments)
        assertToolSucceeded("Forced opaque-file deletion must succeed", result)
        assertTrue(decodeRefactoring(toolText(result)).success)
        assertProjectFileAbsent(file)
    }

    // ── Success: the symbol is really gone ──

    fun testUnusedJavaMethodIsRemovedAndItsSiblingSurvives() = runBlocking {
        registerSourceRoot("sd-symbol-src")
        writeProjectFile(
            "sd-symbol-src/housekeeping/Housekeeping.java", """
            package housekeeping;

            public class Housekeeping {
                public String keep() {
                    return "keep";
                }

                public String unusedHelper() {
                    return "unused";
                }
            }
        """.trimIndent()
        )

        assertFileContains("sd-symbol-src/housekeeping/Housekeeping.java", "unusedHelper")

        val result = SafeDeleteTool().execute(project, buildJsonObject {
            put("file", "sd-symbol-src/housekeeping/Housekeeping.java")
            put("line", 8)
            put("column", 19)
        })

        assertToolSucceeded("Deleting an unreferenced method should succeed", result)
        val payload = decodeRefactoring(toolText(result))
        assertTrue("Payload must report success", payload.success)
        assertEquals(listOf("sd-symbol-src/housekeeping/Housekeeping.java"), payload.affectedFiles)
        assertEquals("Successfully deleted 'unusedHelper'", payload.message)

        assertFileDoesNotContain("sd-symbol-src/housekeeping/Housekeeping.java", "unusedHelper")
        assertFileContains("sd-symbol-src/housekeeping/Housekeeping.java", "public String keep()")
        assertFileContains("sd-symbol-src/housekeeping/Housekeeping.java", "return \"keep\";")
    }

    fun testUnreferencedJavaFileIsDeletedFromDisk() = runBlocking {
        registerSourceRoot("sd-file-src")
        writeProjectFile(
            "sd-file-src/housekeeping/UnusedUtils.java", """
            package housekeeping;

            public class UnusedUtils {
                public static String helper() {
                    return "helper";
                }
            }
        """.trimIndent()
        )
        assertProjectFileExists("sd-file-src/housekeeping/UnusedUtils.java")

        val result = SafeDeleteTool().execute(project, buildJsonObject {
            put("file", "sd-file-src/housekeeping/UnusedUtils.java")
            put("target_type", "file")
        })

        assertToolSucceeded("Deleting an unreferenced file should succeed", result)
        val payload = decodeRefactoring(toolText(result))
        assertTrue("Payload must report success", payload.success)
        assertEquals(listOf("sd-file-src/housekeeping/UnusedUtils.java"), payload.affectedFiles)
        assertEquals(
            "Successfully deleted file 'UnusedUtils.java' (contained 1 symbol(s) with no external usages)",
            payload.message
        )
        assertProjectFileAbsent("sd-file-src/housekeeping/UnusedUtils.java")
    }

    // ── Self-contained symbols: references inside the deleted element must not block ──
    //
    // The references vanish together with the element, so counting them as blocking
    // usages pushes agents toward force=true for perfectly safe deletions. File-delete
    // mode always excluded same-file usages; symbol mode used to count them.

    fun testSelfRecursiveMethodDeletesWithoutForce() = runBlocking {
        registerSourceRoot("sd-selfref-src")
        writeProjectFile(
            "sd-selfref-src/selfref/MathUtil.java", """
            package selfref;

            public class MathUtil {
                public String keep() {
                    return "keep";
                }

                private int fact(int n) {
                    return n <= 1 ? 1 : fact(n - 1);
                }
            }
        """.trimIndent()
        )

        val result = SafeDeleteTool().execute(project, buildJsonObject {
            put("file", "sd-selfref-src/selfref/MathUtil.java")
            put("line", 8)
            put("column", 17)
        })

        assertToolSucceeded("A self-recursive method with no external callers must delete without force", result)
        val payload = decodeRefactoring(toolText(result))
        assertTrue("Payload must report success", payload.success)
        assertEquals("Successfully deleted 'fact'", payload.message)
        assertFileDoesNotContain("sd-selfref-src/selfref/MathUtil.java", "fact")
        assertFileContains("sd-selfref-src/selfref/MathUtil.java", "public String keep()")
    }

    fun testClassWhoseOnlyReferencesAreItsOwnFactoryDeletesWithoutForce() = runBlocking {
        registerSourceRoot("sd-selffactory-src")
        writeProjectFile(
            "sd-selffactory-src/selffactory/Widget.java", """
            package selffactory;

            public class Widget {
                public static Widget create() {
                    return new Widget();
                }
            }

            class WidgetSibling {
            }
        """.trimIndent()
        )

        val result = SafeDeleteTool().execute(project, buildJsonObject {
            put("file", "sd-selffactory-src/selffactory/Widget.java")
            put("line", 3)
            put("column", 14)
        })

        assertToolSucceeded("A class referenced only from inside itself must delete without force", result)
        val payload = decodeRefactoring(toolText(result))
        assertTrue("Payload must report success", payload.success)
        assertEquals("Successfully deleted 'Widget'", payload.message)
        assertFileDoesNotContain("sd-selffactory-src/selffactory/Widget.java", "class Widget {")
        assertFileDoesNotContain("sd-selffactory-src/selffactory/Widget.java", "new Widget()")
        assertFileContains("sd-selffactory-src/selffactory/Widget.java", "class WidgetSibling")
    }

    // ── Refusal: the blocking usage is named and nothing is deleted ──

    fun testReferencedJavaMethodIsRefusedAndTheCallSiteIsReported() = runBlocking {
        registerSourceRoot("sd-blocked-src")
        writeProjectFile(
            "sd-blocked-src/blocked/PaymentGateway.java", """
            package blocked;

            public class PaymentGateway {
                public String charge() {
                    return "charged";
                }
            }
        """.trimIndent()
        )
        writeProjectFile(
            "sd-blocked-src/blocked/CheckoutService.java", """
            package blocked;

            public class CheckoutService {
                public String checkout(PaymentGateway gateway) {
                    return gateway.charge();
                }
            }
        """.trimIndent()
        )

        val result = SafeDeleteTool().execute(project, buildJsonObject {
            put("file", "sd-blocked-src/blocked/PaymentGateway.java")
            put("line", 4)
            put("column", 19)
        })

        assertToolSucceeded("A refusal is a structured answer, not a protocol error", result)
        val payload = decodeSymbolBlocked(toolText(result))
        assertFalse("canDelete must be false while a call site exists", payload.canDelete)
        assertEquals("charge", payload.elementName)
        assertEquals("method", payload.elementType)
        assertEquals(1, payload.usageCount)
        assertEquals(
            "The blocking call site must be named so the agent can fix it. Got: ${payload.blockingUsages}",
            listOf("sd-blocked-src/blocked/CheckoutService.java"),
            payload.blockingUsages.map { it.file }
        )
        val usage = payload.blockingUsages.single()
        assertEquals(5, usage.line)
        assertEquals("return gateway.charge();", usage.context)
        assertEquals(
            "Cannot delete 'charge': found 1 usage(s). Use force=true to delete anyway.",
            payload.message
        )

        assertFileContains("sd-blocked-src/blocked/PaymentGateway.java", "public String charge()")
        assertFileContains("sd-blocked-src/blocked/CheckoutService.java", "return gateway.charge();")
    }

    fun testBaseMethodWithOverrideIsBlockedWithoutOrdinaryReferences() = runBlocking {
        registerSourceRoot("sd-override-src")
        val basePath = "sd-override-src/overrides/BaseWorker.java"
        val implementationPath = "sd-override-src/overrides/Worker.java"
        val baseBefore = """
            package overrides;

            public abstract class BaseWorker {
                public abstract void run();
            }
        """.trimIndent()
        val implementationBefore = """
            package overrides;

            public final class Worker extends BaseWorker {
                @Override
                public void run() {}
            }
        """.trimIndent()
        writeProjectFile(basePath, baseBefore)
        writeProjectFile(implementationPath, implementationBefore)

        val previewResult = SafeDeleteTool().execute(project, buildJsonObject {
            put("file", basePath)
            put("line", 4)
            put("column", 26)
            put("dryRun", true)
        })

        assertToolSucceeded("An unsafe preview is a structured result", previewResult)
        val preview = decodePreview(toolText(previewResult))
        assertFalse("An overriding implementation must block deleting its base declaration", preview.canApply)
        assertTrue("The semantic override must count as a usage", preview.usageCount >= 1)
        assertEquals(preview.usageCount, preview.conflictCount)
        assertEquals(baseBefore, readProjectFileVfs(basePath))
        assertEquals(implementationBefore, readProjectFileVfs(implementationPath))

        val applyResult = SafeDeleteTool().execute(project, buildJsonObject {
            put("file", basePath)
            put("line", 4)
            put("column", 26)
        })
        assertToolSucceeded("A blocked safe delete is a structured result", applyResult)
        val blocked = decodeSymbolBlocked(toolText(applyResult))
        assertFalse(blocked.canDelete)
        assertTrue(blocked.blockingUsages.any { it.file == implementationPath })
        assertEquals(baseBefore, readProjectFileVfs(basePath))
        assertEquals(implementationBefore, readProjectFileVfs(implementationPath))
    }

    fun testParameterDeleteIsBlockedByCallSiteArgument() = runBlocking {
        registerSourceRoot("sd-parameter-src")
        val declarationPath = "sd-parameter-src/parameters/Calculator.java"
        val callerPath = "sd-parameter-src/parameters/CalculatorUser.java"
        val declarationBefore = """
            package parameters;

            public final class Calculator {
                public int add(int left, int right) {
                    return left + right;
                }
            }
        """.trimIndent()
        val callerBefore = """
            package parameters;

            public final class CalculatorUser {
                public int total() {
                    return new Calculator().add(1, 2);
                }
            }
        """.trimIndent()
        writeProjectFile(declarationPath, declarationBefore)
        writeProjectFile(callerPath, callerBefore)

        val previewResult = SafeDeleteTool().execute(project, buildJsonObject {
            put("file", declarationPath)
            put("line", 4)
            put("column", 35)
            put("dryRun", true)
        })

        assertToolSucceeded("An unsafe parameter preview is a structured result", previewResult)
        val preview = decodePreview(toolText(previewResult))
        assertFalse("A call-site argument must block literal parameter deletion", preview.canApply)
        assertTrue("The call-site argument must count as a usage", preview.usageCount >= 1)
        assertEquals(declarationBefore, readProjectFileVfs(declarationPath))
        assertEquals(callerBefore, readProjectFileVfs(callerPath))

        val applyResult = SafeDeleteTool().execute(project, buildJsonObject {
            put("file", declarationPath)
            put("line", 4)
            put("column", 35)
        })
        assertToolSucceeded("A blocked parameter delete is a structured result", applyResult)
        val blocked = decodeSymbolBlocked(toolText(applyResult))
        assertFalse(blocked.canDelete)
        assertTrue("Expected the caller among ${blocked.blockingUsages}", blocked.blockingUsages.any {
            it.file == callerPath
        })
        assertEquals(declarationBefore, readProjectFileVfs(declarationPath))
        assertEquals(callerBefore, readProjectFileVfs(callerPath))
    }

    fun testReferencedJavaFileIsRefusedAndTheReferencingFileIsReported() = runBlocking {
        registerSourceRoot("sd-fileblocked-src")
        writeProjectFile(
            "sd-fileblocked-src/fileblocked/ReportFormatter.java", """
            package fileblocked;

            public class ReportFormatter {
                public String format() {
                    return "report";
                }
            }
        """.trimIndent()
        )
        writeProjectFile(
            "sd-fileblocked-src/fileblocked/ReportPrinter.java", """
            package fileblocked;

            public class ReportPrinter {
                public String print(ReportFormatter formatter) {
                    return formatter.format();
                }
            }
        """.trimIndent()
        )

        val result = SafeDeleteTool().execute(project, buildJsonObject {
            put("file", "sd-fileblocked-src/fileblocked/ReportFormatter.java")
            put("target_type", "file")
        })

        assertToolSucceeded("A refusal is a structured answer, not a protocol error", result)
        val payload = decodeFileBlocked(toolText(result))
        assertFalse("canDelete must be false while the file's class is referenced", payload.canDelete)
        assertEquals("ReportFormatter.java", payload.fileName)
        assertEquals(1, payload.symbolCount)
        assertEquals(1, payload.externalUsageCount)
        val usage = payload.blockingUsages.single()
        assertEquals("sd-fileblocked-src/fileblocked/ReportPrinter.java", usage.file)
        assertEquals("public String print(ReportFormatter formatter) {", usage.context)
        assertEquals(
            "Cannot delete file 'ReportFormatter.java': found 1 external usage(s) of symbols in " +
                "this file. Use force=true to delete anyway.",
            payload.message
        )

        assertProjectFileExists("sd-fileblocked-src/fileblocked/ReportFormatter.java")
        assertFileContains("sd-fileblocked-src/fileblocked/ReportFormatter.java", "public String format()")
    }

    /**
     * `force=true` is the documented escape hatch, and it is the only argument that can turn a
     * refusal into a deletion. Left unexercised, dropping the flag from the blocking check would
     * be indistinguishable from honouring it.
     */
    fun testForceDeletesAReferencedMethodAndSaysTheUsagesMayBeBroken() = runBlocking {
        registerSourceRoot("sd-force-src")
        writeProjectFile(
            "sd-force-src/forced/PaymentGateway.java", """
            package forced;

            public class PaymentGateway {
                public String charge() {
                    return "charged";
                }
            }
        """.trimIndent()
        )
        writeProjectFile(
            "sd-force-src/forced/CheckoutService.java", """
            package forced;

            public class CheckoutService {
                public String checkout(PaymentGateway gateway) {
                    return gateway.charge();
                }
            }
        """.trimIndent()
        )

        val result = SafeDeleteTool().execute(project, buildJsonObject {
            put("file", "sd-force-src/forced/PaymentGateway.java")
            put("line", 4)
            put("column", 19)
            put("force", true)
        })

        assertToolSucceeded("force=true must delete despite usages", result)
        val payload = decodeRefactoring(toolText(result))
        assertEquals(
            "Force-deleted 'charge' (had 1 usage(s) that may now be broken)",
            payload.message
        )
        assertFileDoesNotContain("sd-force-src/forced/PaymentGateway.java", "charge()")
        assertFileContains("sd-force-src/forced/PaymentGateway.java", "public class PaymentGateway")
    }

    /**
     * Targeting whitespace must not fall back to deleting the enclosing file. `findNamedElement`
     * excludes `PsiFile` precisely to prevent that, and this is the only test that would notice if
     * the exclusion were dropped.
     */
    fun testWhitespacePositionSuggestsNearbySymbolsInsteadOfDeletingTheFile() = runBlocking {
        registerSourceRoot("sd-suggest-src")
        writeProjectFile(
            "sd-suggest-src/suggest/Suggestable.java", """
            package suggest;

            public class Suggestable {
                public String keep() {
                    return "keep";
                }
            }
        """.trimIndent()
        )

        val result = SafeDeleteTool().execute(project, buildJsonObject {
            put("file", "sd-suggest-src/suggest/Suggestable.java")
            put("line", 2)
            put("column", 1)
        })

        assertToolSucceeded("A miss returns suggestions, not a protocol error", result)
        val payload = decodeNoSymbolFound(toolText(result))
        assertEquals("No symbol found at line 2, column 1 (found whitespace)", payload.error)
        assertEquals("whitespace", payload.position.elementType)
        assertTrue(
            "Nearby declarations must be offered. Got: ${payload.suggestions}",
            payload.suggestions.any { it.name == "keep" && it.type == "method" }
        )

        assertProjectFileExists("sd-suggest-src/suggest/Suggestable.java")
        assertFileContains("sd-suggest-src/suggest/Suggestable.java", "public String keep()")
    }

    // ── Stale PSI between the usage check (phase 1) and the write action (phase 2) ──
    //
    // The write action used to skip the delete when the element had been invalidated, yet
    // still report "Successfully deleted". These tests recreate the file in the window
    // between the two phases (via the tool's @TestOnly hook) and require an explicit
    // error, with the source text untouched.

    fun testStaleSymbolElementIsAnErrorNotASilentSuccess() = runBlocking {
        registerSourceRoot("sd-stale-src")
        writeProjectFile(
            "sd-stale-src/stale/Stale.java", """
            package stale;

            public class Stale {
                public String unusedHelper() {
                    return "unused";
                }
            }
        """.trimIndent()
        )

        val tool = SafeDeleteTool()
        tool.beforeDeletionHook = { recreateFileOnDisk("sd-stale-src/stale/Stale.java") }

        val result = tool.execute(project, buildJsonObject {
            put("file", "sd-stale-src/stale/Stale.java")
            put("line", 4)
            put("column", 19)
        })

        assertToolFailed("An invalidated element must be an error, not 'Successfully deleted'", result)
        val text = toolText(result)
        assertTrue(
            "Error must say the element went stale and ask for a retry. Got: $text",
            text.contains("no longer valid") && text.contains("Retry")
        )
        assertFileContains("sd-stale-src/stale/Stale.java", "unusedHelper")
    }

    fun testStaleFileElementIsAnErrorNotASilentSuccess() = runBlocking {
        registerSourceRoot("sd-stalefile-src")
        writeProjectFile(
            "sd-stalefile-src/stalefile/StaleFile.java", """
            package stalefile;

            public class StaleFile {
                public String helper() {
                    return "helper";
                }
            }
        """.trimIndent()
        )

        val tool = SafeDeleteTool()
        tool.beforeDeletionHook = { recreateFileOnDisk("sd-stalefile-src/stalefile/StaleFile.java") }

        val result = tool.execute(project, buildJsonObject {
            put("file", "sd-stalefile-src/stalefile/StaleFile.java")
            put("target_type", "file")
        })

        assertToolFailed("An invalidated file must be an error, not 'Successfully deleted'", result)
        val text = toolText(result)
        assertTrue(
            "Error must say the file went stale and ask for a retry. Got: $text",
            text.contains("no longer valid") && text.contains("Retry")
        )
        assertProjectFileExists("sd-stalefile-src/stalefile/StaleFile.java")
        assertFileContains("sd-stalefile-src/stalefile/StaleFile.java", "class StaleFile")
    }

    // ── A failed usage search must refuse the delete, never report "no usages" ──
    //
    // findUsages used to swallow every exception and return the partial (usually empty)
    // list, so a broken search made any symbol look safe to delete.

    fun testUsageSearchFailureRefusesSymbolDeleteWithoutForce() = runBlocking {
        registerSourceRoot("sd-searchfail-src")
        writeProjectFile(
            "sd-searchfail-src/searchfail/SearchFail.java", """
            package searchfail;

            public class SearchFail {
                public String unusedHelper() {
                    return "unused";
                }
            }
        """.trimIndent()
        )

        val tool = SafeDeleteTool()
        tool.usageSearchHook = { throw IllegalStateException("simulated search failure") }

        val result = tool.execute(project, buildJsonObject {
            put("file", "sd-searchfail-src/searchfail/SearchFail.java")
            put("line", 4)
            put("column", 19)
        })

        assertToolFailed("A failed usage search must refuse the delete", result)
        val text = toolText(result)
        assertTrue("Error must name the failure. Got: $text", text.contains("Usage search failed"))
        assertTrue("Error must carry the underlying reason. Got: $text", text.contains("simulated search failure"))
        assertTrue("Error must offer the force=true escape hatch. Got: $text", text.contains("force=true"))
        assertFileContains("sd-searchfail-src/searchfail/SearchFail.java", "unusedHelper")
    }

    fun testUsageSearchFailureRefusesFileDeleteWithoutForce() = runBlocking {
        registerSourceRoot("sd-filesearchfail-src")
        writeProjectFile(
            "sd-filesearchfail-src/filesearchfail/FileSearchFail.java", """
            package filesearchfail;

            public class FileSearchFail {
                public String helper() {
                    return "helper";
                }
            }
        """.trimIndent()
        )

        val tool = SafeDeleteTool()
        tool.usageSearchHook = { throw IllegalStateException("simulated search failure") }

        val result = tool.execute(project, buildJsonObject {
            put("file", "sd-filesearchfail-src/filesearchfail/FileSearchFail.java")
            put("target_type", "file")
        })

        assertToolFailed("A failed usage search must refuse the file delete", result)
        val text = toolText(result)
        assertTrue("Error must name the failure. Got: $text", text.contains("Usage search failed"))
        assertTrue("Error must offer the force=true escape hatch. Got: $text", text.contains("force=true"))
        assertProjectFileExists("sd-filesearchfail-src/filesearchfail/FileSearchFail.java")
        assertFileContains("sd-filesearchfail-src/filesearchfail/FileSearchFail.java", "class FileSearchFail")
    }

    /**
     * force=true means "delete regardless of usages", so a failed usage search must not
     * block it — otherwise the documented escape hatch in the refusal message would be
     * a dead end.
     */
    fun testUsageSearchFailureWithForceStillDeletesTheSymbol() = runBlocking {
        registerSourceRoot("sd-forcefail-src")
        writeProjectFile(
            "sd-forcefail-src/forcefail/ForceFail.java", """
            package forcefail;

            public class ForceFail {
                public String unusedHelper() {
                    return "unused";
                }
            }
        """.trimIndent()
        )

        val tool = SafeDeleteTool()
        tool.usageSearchHook = { throw IllegalStateException("simulated search failure") }

        val result = tool.execute(project, buildJsonObject {
            put("file", "sd-forcefail-src/forcefail/ForceFail.java")
            put("line", 4)
            put("column", 19)
            put("force", true)
        })

        assertToolSucceeded("force=true must delete even when the usage search fails", result)
        val payload = decodeRefactoring(toolText(result))
        assertTrue("Payload must report success", payload.success)
        assertFileDoesNotContain("sd-forcefail-src/forcefail/ForceFail.java", "unusedHelper")
        assertFileContains("sd-forcefail-src/forcefail/ForceFail.java", "public class ForceFail")
    }

    fun testUsageSearchFailureWithForceStillDeletesTheFile() = runBlocking {
        registerSourceRoot("sd-forcefilefail-src")
        writeProjectFile(
            "sd-forcefilefail-src/forcefilefail/ForceFileFail.java", """
            package forcefilefail;

            public class ForceFileFail {
                public String helper() {
                    return "helper";
                }
            }
        """.trimIndent()
        )

        val tool = SafeDeleteTool()
        tool.usageSearchHook = { throw IllegalStateException("simulated search failure") }

        val result = tool.execute(project, buildJsonObject {
            put("file", "sd-forcefilefail-src/forcefilefail/ForceFileFail.java")
            put("target_type", "file")
            put("force", true)
        })

        assertToolSucceeded("force=true must delete the file even when the usage search fails", result)
        val payload = decodeRefactoring(toolText(result))
        assertTrue("Payload must report success", payload.success)
        assertProjectFileAbsent("sd-forcefilefail-src/forcefilefail/ForceFileFail.java")
    }

    /**
     * [IndexNotReadyException] thrown mid-search (dumb mode starting after the smart-mode
     * gate) must reach [com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.AbstractMcpTool]'s
     * translation into the standard dumb-mode retry error instead of being swallowed into
     * an empty usage list followed by a delete.
     */
    fun testDumbModeDuringUsageSearchSurfacesTheRetryErrorInsteadOfDeleting() = runBlocking {
        registerSourceRoot("sd-dumbfail-src")
        writeProjectFile(
            "sd-dumbfail-src/dumbfail/DumbFail.java", """
            package dumbfail;

            public class DumbFail {
                public String unusedHelper() {
                    return "unused";
                }
            }
        """.trimIndent()
        )

        val tool = SafeDeleteTool()
        tool.usageSearchHook = { throw IndexNotReadyException.create() }

        val result = tool.execute(project, buildJsonObject {
            put("file", "sd-dumbfail-src/dumbfail/DumbFail.java")
            put("line", 4)
            put("column", 19)
        })

        assertToolFailed("Dumb mode during the usage search must not end in a delete", result)
        assertTrue(
            "The standard dumb-mode retry guidance must surface. Got: ${toolText(result)}",
            toolText(result).contains("ide_index_status")
        )
        assertFileContains("sd-dumbfail-src/dumbfail/DumbFail.java", "unusedHelper")
    }

    fun testProcessCancellationDuringUsageSearchIsNotSwallowedIntoADelete() {
        registerSourceRoot("sd-pcefail-src")
        writeProjectFile(
            "sd-pcefail-src/pcefail/PceFail.java", """
            package pcefail;

            public class PceFail {
                public String unusedHelper() {
                    return "unused";
                }
            }
        """.trimIndent()
        )

        val tool = SafeDeleteTool()
        tool.usageSearchHook = { throw ProcessCanceledException() }

        val thrown = runCatching {
            runBlocking {
                tool.execute(project, buildJsonObject {
                    put("file", "sd-pcefail-src/pcefail/PceFail.java")
                    put("line", 4)
                    put("column", 19)
                })
            }
        }.exceptionOrNull()

        assertNotNull("Cancellation must propagate, not turn into a successful delete", thrown)
        assertFileContains("sd-pcefail-src/pcefail/PceFail.java", "unusedHelper")
    }

    // ── Issue #336: the file-mode usage scan must actually enumerate the file's declarations ──
    //
    // File mode looks for external usages in three layers, and layer 3 — "search every
    // top-level declaration" — is the only one that fires for an ordinary source file.
    // It walked `psiFile.children` and stopped there, so any language that nests its
    // top-level declarations below the file node produced *zero* declarations to search:
    // the scan found nothing, `externalUsages` stayed empty, and the tool deleted a file
    // that the rest of the project still referenced while reporting success.
    //
    // JS/TS reproduces it: `const X = …` names the `JSVariable` inside a `JSVarStatement`,
    // never the file's own child. Scala (the language in the report) has the same shape via
    // `ScPackaging`, but its plugin is not on the test classpath.
    //
    // Both tests below were confirmed to FAIL against the pre-fix `SafeDeleteTool` by reverting
    // it and running them. That check is what caught a third test which did *not* fail: an
    // ES-module fixture whose `import … from "./config"` is a reference to the file itself, so
    // layer 1 blocked the delete and layer 3 was never exercised. It now lives below as
    // `testModuleImportingTheFileByPathRefusesFileDelete`, labelled for the layer it covers.
    // Keep fixtures here free of module imports, or they stop testing this fix.
    //
    // What is deliberately NOT here is an end-to-end "nested declaration is used from another
    // file, so the delete is refused" case. It cannot run in this harness: a cross-file search
    // for a JS/TS *symbol* reaches the platform's polySymbols module, which dies with
    //   NoSuchMethodError: kotlin.sequences.SequencesKt.sequenceOf(java.lang.Object)
    //   at com.intellij.polySymbols.patterns.impl.SymbolReferencePattern.getStaticPrefixes
    // — an older kotlin-stdlib on the test classpath than polySymbols was compiled against.
    // The plugin ships no kotlin-stdlib of its own (kotlin.stdlib.default.dependency = false),
    // so a real IDE supplies a matching one and this does not arise there. The existing Java
    // fixtures cover "non-empty enumeration ⇒ layer 3 blocks the delete"; what the tests below
    // add is the other half, that the enumeration is non-empty for a nested declaration.
    // Note the failure mode if it ever did arise in production: findUsages wraps it in
    // UsageSearchException and the tool refuses the delete. It fails closed.

    fun testTypeScriptModuleConstantIsCountedAsATopLevelDeclaration() = runBlocking {
        Assume.assumeTrue("JavaScript plugin required for this fixture", PluginDetectors.javaScript.isAvailable)
        registerSourceRoot("sd-tsdecl-src")
        writeProjectFile(
            "sd-tsdecl-src/settings.ts", """
            export const API_URL = "https://example.test";
        """.trimIndent()
        )

        // Precondition, and the whole reason this fixture stands in for the Scala report: the
        // declaration is NOT a direct child of the file, so the old direct-children scan had
        // nothing to search. Asserted rather than assumed — if a future JS PSI flattens this
        // shape, the fixture silently stops reproducing #336, and that must fail loudly here
        // rather than leave the assertion below passing for the wrong reason.
        assertNoDirectlyNamedChildren("sd-tsdecl-src/settings.ts")

        val result = SafeDeleteTool().execute(project, buildJsonObject {
            put("file", "sd-tsdecl-src/settings.ts")
            put("target_type", "file")
        })

        assertToolSucceeded("Deleting an unreferenced module should succeed", result)
        val payload = decodeRefactoring(toolText(result))
        // The count is the assertion: at zero declarations the usage scan never ran, and the
        // deletion below proves nothing about safety.
        assertEquals(
            "Successfully deleted file 'settings.ts' (contained 1 symbol(s) with no external usages)",
            payload.message
        )
        assertProjectFileAbsent("sd-tsdecl-src/settings.ts")
    }

    /**
     * Layer 1 coverage, kept for what it actually tests rather than what it looked like it
     * tested: an ES6 module specifier (`from "./config"`) is a PSI reference to the *file*, so
     * `ReferencesSearch` on the `PsiFile` blocks the delete without layer 3 contributing
     * anything. Confirmed to pass against the pre-fix code, so it is **not** coverage of #336 —
     * it is coverage of the file-reference layer, and it fails if that layer breaks.
     */
    fun testModuleImportingTheFileByPathRefusesFileDelete() = runBlocking {
        Assume.assumeTrue("JavaScript plugin required for this fixture", PluginDetectors.javaScript.isAvailable)
        registerSourceRoot("sd-tsblocked-src")
        writeProjectFile(
            "sd-tsblocked-src/config.ts", """
            export const API_URL = "https://example.test";
        """.trimIndent()
        )
        writeProjectFile(
            "sd-tsblocked-src/client.ts", """
            import { API_URL } from "./config";

            export function endpoint(): string {
                return API_URL + "/v1";
            }
        """.trimIndent()
        )

        val result = SafeDeleteTool().execute(project, buildJsonObject {
            put("file", "sd-tsblocked-src/config.ts")
            put("target_type", "file")
        })

        assertToolSucceeded("A refusal is a structured answer, not a protocol error", result)
        val payload = decodeFileBlocked(toolText(result))
        assertFalse("canDelete must be false while another module imports the file", payload.canDelete)
        assertEquals("config.ts", payload.fileName)
        assertTrue("Expected at least one blocking usage, got ${payload.externalUsageCount}", payload.externalUsageCount >= 1)
        assertTrue(
            "Blocking usages must name the importing file. Got: ${payload.blockingUsages.map { it.file }}",
            payload.blockingUsages.any { it.file == "sd-tsblocked-src/client.ts" }
        )
        assertProjectFileExists("sd-tsblocked-src/config.ts")
        assertFileContains("sd-tsblocked-src/config.ts", "API_URL")
    }

    /**
     * The other half of issue #336: when the scan finds nothing to search, saying so is the
     * difference between "checked, and it is safe" and "there was nothing here to check".
     * A plain-text file has no declarations in any language, so this is the honest wording —
     * and it is the wording an agent needs in order to not read silence as a clean bill of health.
     */
    fun testDeletingAFileWithNoDeclarationsSaysTheScanFoundNothingToCheck() = runBlocking {
        registerSourceRoot("sd-nodecl-src")
        writeProjectFile("sd-nodecl-src/notes.txt", "just some prose, no declarations here\n")

        val result = SafeDeleteTool().execute(project, buildJsonObject {
            put("file", "sd-nodecl-src/notes.txt")
            put("target_type", "file")
        })

        assertToolSucceeded("An unreferenced text file must still be deletable", result)
        val payload = decodeRefactoring(toolText(result))
        assertEquals(
            "Successfully deleted file 'notes.txt' (no top-level declarations found in it — " +
                "only direct references to the file itself were checked)",
            payload.message
        )
        assertProjectFileAbsent("sd-nodecl-src/notes.txt")
    }

    /**
     * Asserts that [relativePath] declares nothing at its own top level — every named element
     * sits below a wrapper node. This is the PSI shape that broke `collectTopLevelDeclarations`
     * in issue #336, and pinning it is what keeps the tests above honest reproductions of the
     * bug rather than assertions that would have passed before the fix too.
     */
    private fun assertNoDirectlyNamedChildren(relativePath: String) {
        val basePath = requireNotNull(project.basePath)
        val virtualFile = requireNotNull(
            LocalFileSystem.getInstance().refreshAndFindFileByPath("$basePath/$relativePath")
        ) { "Missing test file $relativePath" }
        val psiFile = requireNotNull(PsiManager.getInstance(project).findFile(virtualFile)) {
            "No PSI for $relativePath — is the language plugin on the test classpath?"
        }
        val namedChildren = psiFile.children
            .filter { it is PsiNamedElement && it !is PsiFile && it.name != null }
            .map { "${it.javaClass.simpleName}(${(it as PsiNamedElement).name})" }
        assertEquals(
            "$relativePath must nest its declaration below the file node for this fixture to " +
                "reproduce #336, but the file declares these directly",
            emptyList<String>(),
            namedChildren
        )
    }

    /**
     * Deletes and recreates [relativePath] with identical content. Every PSI element the
     * tool captured during its preparation phase belongs to the old, now-deleted file and
     * is therefore invalid — while the source text is still on disk, exactly like an
     * external tool rewriting the file mid-operation.
     */
    private fun recreateFileOnDisk(relativePath: String) {
        val basePath = requireNotNull(project.basePath)
        val content = readProjectFileVfs(relativePath)
        val virtualFile = requireNotNull(
            LocalFileSystem.getInstance().refreshAndFindFileByPath("$basePath/$relativePath")
        ) { "Missing test file $relativePath" }
        ApplicationManager.getApplication().runWriteAction { virtualFile.delete(this) }
        writeProjectFile(relativePath, content)
    }
}
