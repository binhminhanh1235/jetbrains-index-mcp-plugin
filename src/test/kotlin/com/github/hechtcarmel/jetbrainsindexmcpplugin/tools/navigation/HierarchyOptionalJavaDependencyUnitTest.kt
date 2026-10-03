package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.intellij.psi.PsiElement
import junit.framework.TestCase
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/** Executes the production helper in an IDE-shaped loader where Java PSI is unavailable. */
class HierarchyOptionalJavaDependencyUnitTest : TestCase() {
    fun testUniversalHierarchyIdentityDoesNotRequireJavaPsi() {
        val helperName = "com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation.HierarchyNodeIdentityKt"
        val parentLoader = javaClass.classLoader
        val loader = object : ClassLoader(parentLoader) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> =
                synchronized(getClassLoadingLock(name)) {
                    if (name == "com.intellij.psi.PsiMethod") {
                        throw ClassNotFoundException("Java PSI is unavailable in this probe")
                    }
                    if (name == helperName || name.startsWith(helperName + "$")) {
                        val existing = findLoadedClass(name)
                        val loaded = existing ?: run {
                            val resource = name.replace('.', '/') + ".class"
                            val bytes = requireNotNull(parentLoader.getResourceAsStream(resource)).use { it.readBytes() }
                            defineClass(name, bytes, 0, bytes.size)
                        }
                        if (resolve) resolveClass(loaded)
                        loaded
                    } else {
                        super.loadClass(name, resolve)
                    }
                }
        }
        val neutralPsi = Proxy.newProxyInstance(
            PsiElement::class.java.classLoader,
            arrayOf(PsiElement::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "neutral hierarchy declaration"
                "getNavigationElement", "getOriginalElement" -> proxy
                "getContainingFile", "getParent" -> null
                "isValid" -> true
                "isPhysical" -> false
                else -> error("Unexpected PSI access in identity probe: ${method.name}")
            }
        } as PsiElement
        val method = loader.loadClass(helperName).getMethod(
            "sameHierarchyDeclaration", PsiElement::class.java, PsiElement::class.java
        )
        val same = try {
            method.invoke(null, neutralPsi, neutralPsi)
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
        assertEquals("A non-Java declaration must remain comparable without the Java plugin", true, same)
    }
}
