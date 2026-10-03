package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ConflictMessages
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.ProjectUtils
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.psi.PsiElement
import com.intellij.refactoring.RefactoringBundle
import com.intellij.refactoring.rename.RenameUtil
import com.intellij.usageView.UsageInfo
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.containers.MultiMap
import java.lang.reflect.InvocationTargetException

/**
 * Pre-flight writability check for the *full* refactoring scope (issue #310).
 *
 * `BaseRefactoringProcessor.run()` routes read-only affected files through
 * `ReadonlyStatusHandler`, which in a real IDE shows a modal "these files are
 * read-only" dialog. A headless MCP call then blocks the EDT until the client
 * times out. Checking every file discovered by `findUsages()` *before* running
 * the processor turns that hang into an actionable error listing the files.
 *
 * The per-target `isWritable` checks in the individual tools still run first;
 * this guard covers referencing files (generated sources, SQL migrations,
 * files from other content roots).
 *
 * The pre-check's usage search must never run on the EDT — route it through
 * [computeUsagesOffEdt] (issue #357).
 */
internal object RefactoringScopeGuard {
    private val LOG = Logger.getInstance(RefactoringScopeGuard::class.java)

    /**
     * The outcome of change-signature conflict discovery.
     *
     * [usages] is the processor's original usage snapshot. Conflict extensions may replace their
     * temporary `Ref`, but the Java processor restores the original array before apply, so preview
     * metadata must retain it as well.
     */
    internal data class ChangeSignatureConflictResult(
        val usages: Array<UsageInfo>,
        val conflicts: List<String>
    )

    /**
     * Runs the pre-check usage search off the EDT.
     *
     * The Kotlin K2 Analysis API forbids resolution on the EDT, so a `findUsages()`
     * call made directly from the tools' EDT execution phase fails for every Kotlin
     * target with "Analysis is not allowed: Called in the EDT thread" (issue #357).
     * This runs the search the same way `BaseRefactoringProcessor.doRun()` runs its
     * own: called on the EDT, the search executes on a pooled thread under a read
     * action while a modal progress pumps events (so no new PSI-mutation window
     * opens between processor setup and `run()`); called on a background thread, it
     * executes under a plain read action.
     *
     * Returns null when the search is cancelled — callers fail open, skipping the
     * pre-check, because `run()` immediately repeats the same search under its own
     * cancellable progress. Search failures propagate to the caller unchanged.
     */
    fun computeUsagesOffEdt(project: Project, findUsages: () -> Array<UsageInfo>?): Array<UsageInfo>? {
        if (!ApplicationManager.getApplication().isDispatchThread) {
            return ReadAction.compute<Array<UsageInfo>?, RuntimeException> { findUsages() }
        }
        val search = ThrowableComputable<Array<UsageInfo>?, RuntimeException> {
            ReadAction.compute<Array<UsageInfo>?, RuntimeException> { findUsages() }
        }
        return try {
            ProgressManager.getInstance().runProcessWithProgressSynchronously(
                search,
                RefactoringBundle.message("progress.text"),
                true,
                project
            )
        } catch (_: ProcessCanceledException) {
            LOG.info("Read-only scope pre-check cancelled — proceeding without it")
            null
        }
    }

    /**
     * Fail-closed variant of [computeUsagesOffEdt]. Cancellation and discovery failures propagate:
     * neither preview nor apply may treat an incomplete search as a safe zero-usage plan.
     */
    fun computeUsagesOffEdtStrict(project: Project, findUsages: () -> Array<UsageInfo>): Array<UsageInfo> {
        if (!ApplicationManager.getApplication().isDispatchThread) {
            return ReadAction.compute<Array<UsageInfo>, RuntimeException> { findUsages() }
        }
        val search = ThrowableComputable<Array<UsageInfo>, RuntimeException> {
            ReadAction.compute<Array<UsageInfo>, RuntimeException> { findUsages() }
        }
        return ProgressManager.getInstance().runProcessWithProgressSynchronously(
            search,
            RefactoringBundle.message("progress.text"),
            true,
            project
        )
    }

    /** Relative paths of read-only files among the usages' containing files. */
    fun readOnlyFilesIn(project: Project, usages: Array<UsageInfo>): List<String> =
        usages.asSequence()
            .mapNotNull { it.virtualFile }
            .distinct()
            .filterNot { it.isWritable }
            .map { ProjectUtils.getToolFilePath(project, it) }
            .distinct()
            .sorted()
            .toList()

    /**
     * Invokes the public static change-signature usage search. Reflection keeps the Java plugin
     * optional without accessing protected processor hooks. Discovery failures propagate.
     */
    fun findChangeSignatureUsagesReflectivelyStrict(changeInfo: Any): Array<UsageInfo> {
        val processorBaseClass = Class.forName(
            "com.intellij.refactoring.changeSignature.ChangeSignatureProcessorBase"
        )
        val changeInfoClass = Class.forName("com.intellij.refactoring.changeSignature.ChangeInfo")
        val method = processorBaseClass.getMethod("findUsages", changeInfoClass)
        return try {
            @Suppress("UNCHECKED_CAST")
            (method.invoke(null, changeInfo) as? Array<UsageInfo>)
                ?: error("ChangeSignatureProcessorBase.findUsages returned an invalid result")
        } catch (exception: InvocationTargetException) {
            throw exception.cause ?: exception
        }
    }

    /**
     * Runs the conflict collectors used by Java change-signature processors.
     * Reflection keeps the Java plugin optional, while a missing or changed API is surfaced to
     * the caller so preview can report `canApply=false` instead of silently assuming no conflicts.
     *
     * Must be called on EDT without an enclosing read action. The platform method delegates its
     * extension work through `ActionUtil.underModalProgress`; invoking it under a read lock can
     * deadlock when the modal worker needs PSI access.
     */
    fun collectChangeSignatureConflictsReflectively(
        changeInfo: Any,
        usages: Array<UsageInfo>
    ): ChangeSignatureConflictResult {
        ThreadingAssertions.assertEventDispatchThread()
        val conflicts = MultiMap<PsiElement, String>()
        val usagesRef = Ref.create(usages.copyOf())
        val changeInfoClass = Class.forName("com.intellij.refactoring.changeSignature.ChangeInfo")
        val processorBaseClass = Class.forName(
            "com.intellij.refactoring.changeSignature.ChangeSignatureProcessorBase"
        )
        val method = processorBaseClass.getMethod(
            "collectConflictsFromExtensions",
            Ref::class.java,
            MultiMap::class.java,
            changeInfoClass
        )
        try {
            method.invoke(null, usagesRef, conflicts, changeInfo)
        } catch (e: InvocationTargetException) {
            val cause = e.cause ?: e
            when (cause) {
                is ProcessCanceledException -> throw cause
                is RuntimeException -> throw cause
                else -> throw IllegalStateException(cause.message ?: "Conflict discovery failed", cause)
            }
        }
        // Java's processor snapshots the usage set before conflict extensions run, then restores
        // that set for apply. A replacement of this Ref affects conflict discovery only; using
        // it as the edit scope would hide callers that the real processor will still rewrite.
        ReadAction.compute<Unit, RuntimeException> {
            RenameUtil.addConflictDescriptions(usages, conflicts)
        }
        return ChangeSignatureConflictResult(
            usages = usages,
            conflicts = ConflictMessages.sanitizeAll(conflicts.values()).distinct()
        )
    }

    /** Builds the error message for a scope blocked by [readOnlyFiles]. */
    fun blockedMessage(readOnlyFiles: List<String>): String =
        "Blocked by read-only files in the refactoring scope: " +
            readOnlyFiles.joinToString(", ") +
            ". Make these files writable, or remove them from the scope " +
            "(for generated sources: run a clean build and ide_sync_files; " +
            "or exclude the directory from the project's content roots), then retry."
}
