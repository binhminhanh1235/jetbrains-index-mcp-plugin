package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import junit.framework.TestCase

class VerifyChangeToolUnitTest : TestCase() {
    fun testNameAndSchema() {
        val tool = VerifyChangeTool()
        assertEquals(ToolNames.VERIFY_CHANGE, tool.name)
        assertTrue(tool.inputSchema.containsKey("properties"))
    }
}
