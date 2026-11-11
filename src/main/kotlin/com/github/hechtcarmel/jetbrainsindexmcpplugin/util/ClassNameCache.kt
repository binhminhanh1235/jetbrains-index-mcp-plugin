package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

/**
 * Thread-safe class name cache using [ClassValue] to avoid repeated
 * expensive string allocations and CPU overhead during reflection.
 */
object ClassNameCache {
    private val simpleNameCache = object : ClassValue<String>() {
        override fun computeValue(type: Class<*>): String = type.simpleName
    }

    private val lowercaseSimpleNameCache = object : ClassValue<String>() {
        override fun computeValue(type: Class<*>): String = type.simpleName.lowercase()
    }

    /**
     * Retrieves the simple name of a class, caching it per class.
     */
    fun getSimpleName(clazz: Class<*>): String = simpleNameCache.get(clazz)

    /**
     * Retrieves the lowercase simple name of a class, caching it per class.
     */
    fun getLowercaseSimpleName(clazz: Class<*>): String = lowercaseSimpleNameCache.get(clazz)
}
