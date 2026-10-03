package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.refactoring

import com.github.hechtcarmel.jetbrainsindexmcpplugin.constants.ToolNames
import junit.framework.TestCase

class BatchOptimizeImportsToolUnitTest : TestCase() {
    fun testNameAndSchema() {
        val tool = BatchOptimizeImportsTool()
        assertEquals(ToolNames.BATCH_OPTIMIZE_IMPORTS, tool.name)
        assertNotNull(tool.inputSchema.properties)
    }
}
