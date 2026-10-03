package com.github.hechtcarmel.jetbrainsindexmcpplugin.server.mcp

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.ToolRegistry
import junit.framework.TestCase

class McpServerFactoryUnitTest : TestCase() {

    fun testRegisteredToolsAreCachedAcrossCalls() {
        val registry = ToolRegistry()
        val dispatcher = McpToolDispatcher(registry)
        val factory = McpServerFactory(registry, dispatcher)

        val tools1 = factory.registeredTools()
        val tools2 = factory.registeredTools()

        assertSame("Subsequent calls to registeredTools() should return the cached list instance", tools1, tools2)
    }

    fun testInvalidateCacheForcesRebuild() {
        val registry = ToolRegistry()
        val dispatcher = McpToolDispatcher(registry)
        val factory = McpServerFactory(registry, dispatcher)

        val tools1 = factory.registeredTools()
        factory.invalidateCache()
        val tools2 = factory.registeredTools()

        assertNotSame("After invalidateCache(), a fresh tool list instance should be built", tools1, tools2)
        assertEquals("Both tool lists should contain identical tool counts", tools1.size, tools2.size)
    }
}
