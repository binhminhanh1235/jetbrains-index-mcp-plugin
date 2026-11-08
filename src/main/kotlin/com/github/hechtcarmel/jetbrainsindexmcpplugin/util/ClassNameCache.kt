package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

/**
 * Cache for class names using [ClassValue] to avoid excessive string allocations
 * and CPU overhead during frequent operations like PSI tree traversals.
 */
object ClassNameCache {
    private val simpleNameCache = object : ClassValue<String>() {
        override fun computeValue(type: Class<*>): String = type.simpleName
    }

    private val lowercaseNameCache = object : ClassValue<String>() {
        override fun computeValue(type: Class<*>): String = type.simpleName.lowercase()
    }

    @JvmStatic
    fun getSimpleName(clazz: Class<*>): String = simpleNameCache.get(clazz)

    @JvmStatic
    fun getLowercaseSimpleName(clazz: Class<*>): String = lowercaseNameCache.get(clazz)

    @JvmStatic
    fun getSimpleName(obj: Any): String = simpleNameCache.get(obj.javaClass)

    @JvmStatic
    fun getLowercaseSimpleName(obj: Any): String = lowercaseNameCache.get(obj.javaClass)
}
