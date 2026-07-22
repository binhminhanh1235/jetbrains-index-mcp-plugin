package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import junit.framework.TestCase

class GetProjectOverviewToolUnitTest : TestCase() {
    fun testNameAndSchema() {
        val tool = GetProjectOverviewTool()
        assertEquals(ToolNames.GET_PROJECT_OVERVIEW, tool.name)
        assertTrue(tool.inputSchema.containsKey("properties"))
    }
}
