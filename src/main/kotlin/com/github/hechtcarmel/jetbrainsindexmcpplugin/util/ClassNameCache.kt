package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

/**
 * Thread-safe cache using [ClassValue] for simple class name operations to prevent
 * repeated allocations and CPU overhead during PSI tree traversals.
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

    /**
     * Retrieves the simple name of a class from the cache.
     */
    fun getSimpleName(clazz: Class<*>): String {
        return simpleNameCache.get(clazz)
    }

    /**
     * Retrieves the lowercased simple name of a class from the cache.
     */
    fun getLowercaseSimpleName(clazz: Class<*>): String {
        return lowercaseSimpleNameCache.get(clazz)
    }
}
