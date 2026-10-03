package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.intellij.openapi.project.IndexNotReadyException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive

class PaginationIndexNotReadyBehaviorTest : McpPlatformTestCase() {

    override fun setUp() {
        super.setUp()
        PaginationService.getInstance().resetSession()
    }

    override fun tearDown() {
        try {
            PaginationService.getInstance().resetSession()
        } finally {
            super.tearDown()
        }
    }

    fun testIndexNotReadyExceptionFromExtenderIsPropagated() = runBlocking {
        val service = PaginationService.getInstance()
        val initial = (1..5).map {
            PaginationService.SerializedResult("key$it", JsonPrimitive("data$it"))
        }
        val projectPath = requireNotNull(project.basePath)
        val token = service.createCursor(
            "tool",
            initial,
            initial.map { it.key }.toSet(),
            { _, _ -> throw IndexNotReadyException.create() },
            42L,
            projectPath
        )

        try {
            service.getPage(token, initial.size, projectPath, 42L)
            fail("IndexNotReadyException must not be translated to SEARCH_INVALIDATED")
        } catch (_: IndexNotReadyException) {
            // Expected: the tool layer translates this into the standard retryable indexing error.
        }
    }
}
