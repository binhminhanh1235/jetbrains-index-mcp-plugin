package com.github.hechtcarmel.jetbrainsindexmcpplugin.util

import junit.framework.TestCase
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class ReflectionCacheUnitTest : TestCase() {

    fun testResolvesExistingMethod() {
        val method = ReflectionCache.getMethod(String::class.java, "length")
        assertNotNull(method)
        assertEquals("length", method?.name)
        assertEquals(0, method?.parameterCount)
    }

    fun testResolvesMethodWithParameters() {
        val method = ReflectionCache.getMethod(String::class.java, "substring", Int::class.javaPrimitiveType!!)
        assertNotNull(method)
        assertEquals("substring", method?.name)
        assertEquals(1, method?.parameterCount)
    }

    fun testReturnsNullForNonExistentMethod() {
        val method = ReflectionCache.getMethod(String::class.java, "nonExistentMethodXYZ")
        assertNull(method)
    }

    fun testCachesRepeatedCalls() {
        val m1 = ReflectionCache.getMethod(String::class.java, "trim")
        val m2 = ReflectionCache.getMethod(String::class.java, "trim")
        assertSame(m1, m2)

        val null1 = ReflectionCache.getMethod(String::class.java, "nonExistent1")
        val null2 = ReflectionCache.getMethod(String::class.java, "nonExistent1")
        assertNull(null1)
        assertNull(null2)
    }

    fun testThreadSafety() {
        val executor = Executors.newFixedThreadPool(8)
        try {
            val tasks = (1..100).map {
                Callable {
                    ReflectionCache.getMethod(String::class.java, "toLowerCase")
                    ReflectionCache.getMethod(String::class.java, "missingMethod")
                }
            }
            val futures = executor.invokeAll(tasks)
            futures.forEach { it.get() }
        } finally {
            executor.shutdown()
        }
    }
}
