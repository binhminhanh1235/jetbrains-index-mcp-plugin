package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe reflection cache using [ClassValue] to avoid repeated [Class.getMethod]
 * reflections and expensive [NoSuchMethodException] stack trace captures.
 */
object ReflectionCache {
    private val NULL_SENTINEL: Method = ReflectionCache::class.java.getDeclaredMethod("nullSentinel")

    @Suppress("unused")
    private fun nullSentinel() {}

    private val cache = object : ClassValue<ConcurrentHashMap<String, Method>>() {
        override fun computeValue(type: Class<*>): ConcurrentHashMap<String, Method> = ConcurrentHashMap()
    }

    /**
     * Retrieves a public method by name and parameter types, caching both successes
     * and failures (null) per class.
     */
    fun getMethod(clazz: Class<*>, name: String, vararg paramTypes: Class<*>): Method? {
        val key = if (paramTypes.isEmpty()) name else "$name(${paramTypes.joinToString(",") { it.name }})"
        val method = cache.get(clazz).computeIfAbsent(key) {
            try {
                clazz.getMethod(name, *paramTypes)
            } catch (_: NoSuchMethodException) {
                NULL_SENTINEL
            } catch (_: SecurityException) {
                NULL_SENTINEL
            }
        }
        return if (method === NULL_SENTINEL) null else method
    }
}
