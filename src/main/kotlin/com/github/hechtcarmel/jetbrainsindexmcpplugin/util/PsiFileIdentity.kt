package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileWithId
import com.intellij.psi.PsiElement
import com.intellij.psi.SmartPsiElementPointer
import com.intellij.util.concurrency.annotations.RequiresReadLock
import java.lang.ref.WeakReference

/** File identity survives ordinary edits/moves, but never deletion and recreation at the same path. */
internal class PsiFileIdentity(file: VirtualFile) {
    private val persistentId = (file as? VirtualFileWithId)?.id
    private val protocol = file.fileSystem.protocol
    private val reference = WeakReference(file)

    fun matches(file: VirtualFile?): Boolean {
        if (file == null || !file.isValid) return false
        return if (persistentId != null) {
            (file as? VirtualFileWithId)?.id == persistentId && file.fileSystem.protocol == protocol
        } else {
            reference.get() === file
        }
    }
}

/** A raw smart pointer can recover by path after deletion; retained search state must not do so. */
@RequiresReadLock
internal fun <T : PsiElement> SmartPsiElementPointer<T>.withOriginalFileIdentity(): SmartPsiElementPointer<T> =
    this as? FileBoundPsiPointer<T> ?: FileBoundPsiPointer(this)

private class FileBoundPsiPointer<T : PsiElement>(
    private val delegate: SmartPsiElementPointer<T>
) : SmartPsiElementPointer<T> by delegate {
    private val originalFile = delegate.virtualFile?.let(::PsiFileIdentity)

    override fun getElement(): T? =
        if (originalFile?.matches(delegate.virtualFile) == false) null else delegate.element
}
