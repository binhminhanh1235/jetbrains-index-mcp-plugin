package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.SymbolParameterInfo
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNameIdentifierOwner

/**
 * Wire values for `SymbolInfoResult.signatureSource`.
 *
 * The value is part of the response contract: it tells a client how much to trust the type
 * names in the signature, which differs a lot between the three paths below.
 */
object SignatureSources {
    /** Built from Java PSI. Parameter and return types are `PsiType.getCanonicalText()` — fully qualified. */
    const val JAVA_PSI = "java_psi"

    /** Rendered by the language's own documentation provider (the Ctrl/Cmd-hover text). */
    const val QUICK_NAVIGATION = "quick_navigation"

    /** No documentation provider produced anything; the declaration's own source line is echoed. */
    const val ELEMENT_TEXT = "element_text"
}

/**
 * A symbol's declaration facts, however they could be resolved.
 *
 * Only [signature] and [source] are always present. The structured fields are populated on the
 * [SignatureSources.JAVA_PSI] path, where the platform hands us real types rather than rendered
 * text; on the other paths they stay null, and [source] says so.
 */
data class ResolvedSignature(
    val signature: String,
    val source: String,
    val parameters: List<SymbolParameterInfo>? = null,
    val returnType: String? = null,
    val typeParameters: List<String>? = null,
    val thrownTypes: List<String>? = null,
    val modifiers: List<String>? = null,
    val visibility: String? = null,
    val containingDeclaration: String? = null
)

/**
 * Resolves a declaration's signature, preferring precision over uniformity.
 *
 * The order is deliberate:
 *
 * 1. **Java PSI** — the only path that can expand every parameter type to its qualified name,
 *    because `PsiType.getCanonicalText()` reports the *resolved* type rather than the short name
 *    the author wrote. This is the capability `ide_find_definition` cannot offer: its preview is
 *    source text, so an imported, aliased or inherited type stays short and ambiguous.
 * 2. **The language's documentation provider** — every language with Quick Documentation
 *    registers one, so this covers Kotlin, Python, JS/TS, Go, PHP and Rust with no per-language
 *    handler here. It resolves by that language's own rules, which is why it is preferred over
 *    reading source text, but it renders type names however it likes — often short.
 * 3. **The declaration's source line** — a last resort that is still better than failing, per
 *    the fallback the feature request asked for.
 *
 * Kotlin deliberately takes path 2 rather than being converted to a JVM light method first: a
 * light signature would report `java.lang.String` for a `String` parameter and erase the
 * declaration's own generics, which reads as wrong even though it is accurate about the JVM.
 * When the *resolved element itself* is already Java PSI — as it is for the light elements the
 * Kotlin symbol resolver returns — path 1 applies as normal.
 */
object SymbolSignatureResolver {

    private const val MAX_TEXT_FALLBACK_CHARS = 400

    /**
     * @param element the declaration to describe
     * @param originalElement the leaf the caller pointed at, when it is not the declaration
     *   itself; language documentation providers use it to substitute type arguments
     */
    fun resolve(element: PsiElement, originalElement: PsiElement? = null): ResolvedSignature {
        if (PluginDetectors.java.isAvailable) {
            JavaSignatureExtractor.extract(element)?.let { return it }
        }

        SymbolDocumentation.quickNavigationInfo(element, originalElement)?.let { rendered ->
            return ResolvedSignature(
                signature = rendered,
                source = SignatureSources.QUICK_NAVIGATION,
                containingDeclaration = containingDeclarationFromAstPath(element)
            )
        }

        return ResolvedSignature(
            signature = declarationLine(element),
            source = SignatureSources.ELEMENT_TEXT,
            containingDeclaration = containingDeclarationFromAstPath(element)
        )
    }

    /**
     * The source line containing the declaration's name, cut at the body opener. A declaration
     * can start with annotations or documentation, so its first line need not describe the symbol.
     * Elements without a source name identifier retain the first-nonblank-line fallback.
     */
    private fun declarationLine(element: PsiElement): String {
        val text = element.text.orEmpty()
        val identifier = (element as? PsiNameIdentifierOwner)?.nameIdentifier
        val nameOffset = identifier?.textRange?.startOffset
            ?.minus(element.textRange.startOffset)
            ?.takeIf { it in text.indices }
        val lineStart = nameOffset?.let { text.lastIndexOf('\n', it - 1) + 1 } ?: 0
        val sourceLine = text.substring(lineStart).lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        val head = sourceLine.trim()
        // A brace inside an inline annotation or a quoted identifier is not the body opener.
        val bodySearchStart = if (nameOffset != null) {
            val indentation = sourceLine.length - sourceLine.trimStart().length
            nameOffset + identifier.textLength - lineStart - indentation
        } else 0
        val cut = head.indexOf('{', bodySearchStart.coerceAtLeast(0))
        val line = if (cut > 0) head.take(cut).trim() else head
        return line.take(MAX_TEXT_FALLBACK_CHARS)
    }

    /**
     * Enclosing named declarations, dotted — the language-agnostic stand-in for a containing
     * class when no language-specific container is available.
     *
     * Internal because [JavaSignatureExtractor] needs the same fallback for a local variable or
     * parameter, which has no containing class to report.
     */
    internal fun containingDeclarationFromAstPath(element: PsiElement): String? =
        PsiUtils.getAstPath(element).joinToString(".").takeIf { it.isNotEmpty() }
}
