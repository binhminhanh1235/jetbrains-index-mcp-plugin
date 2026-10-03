package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.intelligence

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import junit.framework.TestCase

class BatchDiagnosticsToolUnitTest : TestCase() {
    fun testNameAndSchema() {
        val tool = BatchDiagnosticsTool()
        assertEquals(ToolNames.BATCH_DIAGNOSTICS, tool.name)
        assertNotNull(tool.inputSchema.properties)
    }
}
