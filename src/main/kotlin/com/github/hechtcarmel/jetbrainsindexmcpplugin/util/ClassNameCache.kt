package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

/**
 * Caches expensive per-class computations like simple name resolution.
 * Uses ClassValue to prevent memory leaks and provide high-performance concurrent access.
 */
object ClassNameCache {
    private val simpleNameCache = object : ClassValue<String>() {
        override fun computeValue(type: Class<*>): String {
            return type.simpleName
        }
    }

    private val lowercaseSimpleNameCache = object : ClassValue<String>() {
        override fun computeValue(type: Class<*>): String {
            return type.simpleName.lowercase()
        }
    }

    @JvmStatic
    fun getSimpleName(clazz: Class<*>): String {
        return simpleNameCache.get(clazz)
    }

    @JvmStatic
    fun getLowercaseSimpleName(clazz: Class<*>): String {
        return lowercaseSimpleNameCache.get(clazz)
    }
}
