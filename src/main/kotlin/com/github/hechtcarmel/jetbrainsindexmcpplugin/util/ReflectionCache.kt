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

    private data class MethodKey1(val name: String, val param1: Class<*>)

    private class MethodKeyN(val name: String, val paramTypes: Array<out Class<*>>) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as MethodKeyN
            if (name != other.name) return false
            return paramTypes.contentEquals(other.paramTypes)
        }
        override fun hashCode(): Int {
            return 31 * name.hashCode() + paramTypes.contentHashCode()
        }
    }

    private val cache = object : ClassValue<ConcurrentHashMap<Any, Method>>() {
        override fun computeValue(type: Class<*>): ConcurrentHashMap<Any, Method> = ConcurrentHashMap()
    }

    /**
     * Retrieves a public method by name (0 parameters), caching both successes
     * and failures (null) per class.
     */
    fun getMethod(clazz: Class<*>, name: String): Method? {
        val classCache = cache.get(clazz)
        var method = classCache[name]
        if (method == null) {
            method = try {
                clazz.getMethod(name)
            } catch (_: NoSuchMethodException) {
                NULL_SENTINEL
            } catch (_: SecurityException) {
                NULL_SENTINEL
            }
            classCache[name] = method
        }
        return if (method === NULL_SENTINEL) null else method
    }

    /**
     * Retrieves a public method by name and 1 parameter type, caching both successes
     * and failures (null) per class.
     */
    fun getMethod(clazz: Class<*>, name: String, param1: Class<*>): Method? {
        val key = MethodKey1(name, param1)
        val classCache = cache.get(clazz)
        var method = classCache[key]
        if (method == null) {
            method = try {
                clazz.getMethod(name, param1)
            } catch (_: NoSuchMethodException) {
                NULL_SENTINEL
            } catch (_: SecurityException) {
                NULL_SENTINEL
            }
            classCache[key] = method
        }
        return if (method === NULL_SENTINEL) null else method
    }

    /**
     * Retrieves a public method by name and parameter types, caching both successes
     * and failures (null) per class.
     */
    fun getMethod(clazz: Class<*>, name: String, vararg paramTypes: Class<*>): Method? {
        if (paramTypes.isEmpty()) return getMethod(clazz, name)
        if (paramTypes.size == 1) return getMethod(clazz, name, paramTypes[0])

        val key = MethodKeyN(name, paramTypes)
        val classCache = cache.get(clazz)
        var method = classCache[key]
        if (method == null) {
            method = try {
                clazz.getMethod(name, *paramTypes)
            } catch (_: NoSuchMethodException) {
                NULL_SENTINEL
            } catch (_: SecurityException) {
                NULL_SENTINEL
            }
            classCache[key] = method
        }
        return if (method === NULL_SENTINEL) null else method
    }
}
