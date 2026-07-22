package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import junit.framework.TestCase

class GetDependenciesToolUnitTest : TestCase() {
    fun testNameAndSchema() {
        val tool = GetDependenciesTool()
        assertEquals(ToolNames.GET_DEPENDENCIES, tool.name)
        assertTrue(tool.inputSchema.containsKey("properties"))
    }
}
