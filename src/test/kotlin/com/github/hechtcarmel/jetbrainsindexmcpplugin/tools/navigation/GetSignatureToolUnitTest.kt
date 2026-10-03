package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.navigation

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import junit.framework.TestCase

class GetSignatureToolUnitTest : TestCase() {
    fun testNameAndSchema() {
        val tool = GetSignatureTool()
        assertEquals(ToolNames.GET_SIGNATURE, tool.name)
        assertNotNull(tool.inputSchema.properties)
    }
}
