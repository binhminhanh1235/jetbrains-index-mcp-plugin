package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.intellij.lang.LanguageDocumentation
import com.intellij.lang.documentation.DocumentationProvider
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.annotations.RequiresReadLock
import java.util.concurrent.CancellationException

/**
 * Reads a symbol's hover signature and documentation from the IDE's own documentation backend,
 * as plain text.
 *
 * Every language that supports Quick Documentation registers a
 * [DocumentationProvider] under `com.intellij.lang.documentationProvider`, so going through
 * [LanguageDocumentation] gets Kotlin, Python, JS/TS, Go, PHP and Rust for free — no
 * per-language handler in this plugin.
 *
 * ## Why this API and not `DocumentationTarget`
 *
 * `DocumentationTargetProvider`/`DocumentationTarget` is the newer backend, and the platform's
 * own `PsiElementDocumentationTarget` is a thin adapter over exactly the two
 * [DocumentationProvider] calls made here. It is not usable from a plugin that wants the *text*:
 * `DocumentationResult` exposes only factory methods, and reading the HTML back out requires
 * `com.intellij.platform.backend.documentation.impl.DocumentationData`, which is
 * `@ApiStatus.Internal` — banned outright by this repo's API-compliance rules and rejected by
 * the plugin verifier. [DocumentationProvider] is `@ApiStatus.Obsolete`, which is a "prefer the
 * new one when implementing" marker, not a compatibility problem; the platform still routes all
 * existing implementations through it.
 *
 * ## Threading
 *
 * Both entry points read PSI and must hold a read lock. `generateDoc` is documented as
 * potentially slow and must not run on the EDT — MCP tool calls arrive on Ktor worker threads,
 * so calling these from inside `suspendingReadAction` satisfies both.
 *
 * Only *local* documentation is produced. `ExternalDocumentationProvider.fetchExternalDocumentation`
 * is never called, so this makes no network requests.
 */
object SymbolDocumentation {

    private val LOG = logger<SymbolDocumentation>()

    private const val NON_BREAKING_SPACE = '\u00A0'

    /**
     * The Ctrl/Cmd-hover text for [element] — the signature line as the language renders it,
     * including its container.
     *
     * @return plain text, or null when no provider offers navigation info for this element.
     */
    @RequiresReadLock
    fun quickNavigationInfo(element: PsiElement, originalElement: PsiElement? = null): String? =
        firstNonBlank(element) { it.getQuickNavigateInfo(element, originalElement) }
            ?.let { normalizeSignature(it) }

    /**
     * The rendered documentation comment for [element] — Javadoc, KDoc, docstring, JSDoc.
     *
     * @return plain text, or null when the element carries no documentation.
     */
    @RequiresReadLock
    fun renderedDoc(element: PsiElement, originalElement: PsiElement? = null): String? =
        firstNonBlank(element) { it.generateDoc(element, originalElement) }
            ?.let { normalizeDoc(it) }

    /**
     * Asks each candidate provider in turn and returns the first non-blank answer.
     *
     * The language provider comes first because [LanguageDocumentation] already composites every
     * provider registered for that language; the application-level providers are a fallback for
     * elements no language claims (files, directories).
     */
    private fun firstNonBlank(element: PsiElement, ask: (DocumentationProvider) -> String?): String? {
        for (provider in providersFor(element)) {
            val answer = try {
                ask(provider)
            } catch (e: Exception) {
                rethrowControlFlowExceptions(e)
                // A provider that fails on an element it does not really handle is not a tool
                // failure — but it is worth a log line, because a provider failing on an element
                // it *does* handle silently degrades the result to the text fallback.
                LOG.info("Documentation provider ${provider.javaClass.name} failed for ${element.javaClass.simpleName}", e)
                null
            }
            if (!answer.isNullOrBlank()) return answer
        }
        return null
    }

    private fun providersFor(element: PsiElement): List<DocumentationProvider> {
        val languageProvider = LanguageDocumentation.INSTANCE.forLanguage(element.language)
        val generic = DocumentationProvider.EP_NAME.extensionList
        return if (languageProvider == null) generic else listOf(languageProvider) + generic
    }

    /**
     * [ProcessCanceledException] and [CancellationException] carry the platform's cancellation
     * signal and must never be swallowed into "no documentation".
     */
    private fun rethrowControlFlowExceptions(e: Exception) {
        when (e) {
            is ProcessCanceledException, is CancellationException -> throw e
        }
    }

    /**
     * Navigation info is HTML across two or three lines (container, then the declaration).
     * Blank lines carry no information here, so they are dropped rather than preserved.
     */
    internal fun normalizeSignature(html: String): String? {
        val raw = StringUtil.removeHtmlTags(html, true)
        return cleanSignature(raw)
    }

    /**
     * Documentation is prose, so paragraph breaks are kept — collapsed to a single blank line,
     * because the HTML layout produces long runs of them.
     */
    internal fun normalizeDoc(html: String): String? {
        val raw = StringUtil.removeHtmlTags(html, true)
        return cleanDoc(raw)
    }

    private fun cleanSignature(raw: String): String? {
        val sb = StringBuilder(raw.length)
        val lineSb = StringBuilder()
        var i = 0
        val n = raw.length

        fun flushLine() {
            val trimmed = lineSb.trim()
            lineSb.setLength(0)
            if (trimmed.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(trimmed)
            }
        }

        while (i < n) {
            val ch = raw[i]
            if (ch == '\r') {
                if (i + 1 < n && raw[i + 1] == '\n') i++
                flushLine()
            } else if (ch == '\n') {
                flushLine()
            } else {
                val c = if (ch == NON_BREAKING_SPACE) ' ' else ch
                lineSb.append(c)
            }
            i++
        }
        flushLine()

        return sb.toString().takeIf { it.isNotBlank() }
    }

    private fun cleanDoc(raw: String): String? {
        val sb = StringBuilder(raw.length)
        val lineSb = StringBuilder()
        var consecutiveNewlines = 0
        var hasContent = false
        var i = 0
        val n = raw.length

        fun flushDocLine() {
            val trimmed = lineSb.trim()
            lineSb.setLength(0)
            if (trimmed.isNotEmpty()) {
                if (hasContent) {
                    val newlinesToEmit = minOf(consecutiveNewlines, 2)
                    for (k in 0 until newlinesToEmit) {
                        sb.append('\n')
                    }
                }
                sb.append(trimmed)
                hasContent = true
                consecutiveNewlines = 1
            } else if (hasContent) {
                consecutiveNewlines++
            }
        }

        while (i < n) {
            val ch = raw[i]
            if (ch == '\r') {
                if (i + 1 < n && raw[i + 1] == '\n') i++
                flushDocLine()
            } else if (ch == '\n') {
                flushDocLine()
            } else {
                val c = if (ch == NON_BREAKING_SPACE) ' ' else ch
                lineSb.append(c)
            }
            i++
        }
        flushDocLine()

        return sb.toString().takeIf { it.isNotBlank() }
    }
}
