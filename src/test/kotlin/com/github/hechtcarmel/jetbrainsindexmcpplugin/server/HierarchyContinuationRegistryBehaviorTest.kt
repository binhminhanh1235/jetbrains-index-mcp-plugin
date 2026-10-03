package com.github.hechtcarmel.jetbrainsindexmcpplugin.server

import com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.BuiltInSearchScope
import com.github.hechtcarmel.jetbrainsindexmcpplugin.server.HierarchyContinuationRegistry.TypeContinuation
import com.github.hechtcarmel.jetbrainsindexmcpplugin.testutil.McpPlatformTestCase
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.CallElement
import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TypeElement
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.SmartPointerManager

class HierarchyContinuationRegistryBehaviorTest : McpPlatformTestCase() {

    private val serverEpoch = McpServerEpoch()

    fun testDefaultsUseIndependentTenMinuteTtlAndBoundedLru() {
        assertEquals(128, HierarchyContinuationRegistry.DEFAULT_MAX_ENTRIES)
        assertEquals(10 * 60 * 1_000L, HierarchyContinuationRegistry.DEFAULT_TTL_MILLIS)

        var generated = 0
        val registry = HierarchyContinuationRegistry(
            idGenerator = { "default-cursor-${++generated}" },
            serverEpoch = serverEpoch
        ).also { Disposer.register(testRootDisposable, it) }
        repeat(HierarchyContinuationRegistry.DEFAULT_MAX_ENTRIES + 1) {
            registry.register(project, fixtureContinuation())
        }
        assertTrue("default LRU bound must evict the oldest cursor", registry.resolve(project, "default-cursor-1").isFailure)
        assertEquals(HierarchyContinuationRegistry.DEFAULT_MAX_ENTRIES, registry.sizeForTest())
        Disposer.dispose(registry)

        var now = 0L
        val ttlRegistry = HierarchyContinuationRegistry(
            clock = { now },
            idGenerator = { "default-ttl" },
            serverEpoch = serverEpoch
        ).also { Disposer.register(testRootDisposable, it) }
        val cursor = ttlRegistry.register(project, fixtureContinuation())
        now = HierarchyContinuationRegistry.DEFAULT_TTL_MILLIS - 1
        assertTrue(ttlRegistry.resolve(project, cursor).isSuccess)
        now = HierarchyContinuationRegistry.DEFAULT_TTL_MILLIS * 2
        assertTrue("default TTL must expire an idle cursor", ttlRegistry.resolve(project, cursor).isFailure)
        Disposer.dispose(ttlRegistry)
    }

    fun testAccessOrderLruEvictsTheLeastRecentlyUsedCursor() {
        var generated = 0
        val registry = HierarchyContinuationRegistry(
            maxEntries = 2,
            ttlMillis = 10_000,
            clock = { 1_000L },
            idGenerator = { "cursor-${++generated}" },
            serverEpoch = serverEpoch
        ).also { Disposer.register(testRootDisposable, it) }
        val continuation = fixtureContinuation()

        val first = registry.register(project, continuation)
        val second = registry.register(project, continuation)
        assertTrue(registry.resolve(project, first).isSuccess)
        val third = registry.register(project, continuation)

        assertTrue("second cursor should be evicted as LRU", registry.resolve(project, second).isFailure)
        assertTrue(registry.resolve(project, first).isSuccess)
        assertTrue(registry.resolve(project, third).isSuccess)
        assertEquals(2, registry.sizeForTest())
        Disposer.dispose(registry)
    }

    fun testTtlRefreshesOnAccessAndExpiresAtBoundary() {
        var now = 1_000L
        val registry = HierarchyContinuationRegistry(
            maxEntries = 2,
            ttlMillis = 100,
            clock = { now },
            idGenerator = { "ttl-cursor" },
            serverEpoch = serverEpoch
        ).also { Disposer.register(testRootDisposable, it) }
        val cursor = registry.register(project, fixtureContinuation())

        now = 1_099L
        assertTrue(registry.resolve(project, cursor).isSuccess)
        now = 1_198L
        assertTrue(registry.resolve(project, cursor).isSuccess)
        now = 1_298L
        assertTrue("cursor must expire at the exact TTL boundary", registry.resolve(project, cursor).isFailure)
        assertEquals(0, registry.sizeForTest())
        Disposer.dispose(registry)
    }

    fun testCursorIsBoundToExactProjectInstanceWithoutDestroyingOwnerState() {
        val registry = HierarchyContinuationRegistry(
            idGenerator = { "project-cursor" },
            serverEpoch = serverEpoch
        ).also { Disposer.register(testRootDisposable, it) }
        val cursor = registry.register(project, fixtureContinuation())
        val otherProject = ProjectManager.getInstance().defaultProject

        assertNotSame(project, otherProject)
        assertTrue(registry.resolve(otherProject, cursor).isFailure)
        assertTrue("wrong-project lookup must not consume the owner's cursor", registry.resolve(project, cursor).isSuccess)
        Disposer.dispose(registry)
    }

    fun testWrongProjectLookupDoesNotPromoteForeignCursorInAccessOrder() {
        var generated = 0
        val registry = HierarchyContinuationRegistry(
            maxEntries = 2,
            idGenerator = { "foreign-lru-${++generated}" },
            serverEpoch = serverEpoch
        ).also { Disposer.register(testRootDisposable, it) }
        val first = registry.register(project, fixtureContinuation())
        val second = registry.register(project, fixtureContinuation())
        val otherProject = ProjectManager.getInstance().defaultProject

        assertTrue(registry.resolve(otherProject, first).isFailure)
        val third = registry.register(project, fixtureContinuation())

        assertTrue("foreign lookup must not save the oldest cursor from eviction", registry.resolve(project, first).isFailure)
        assertTrue(registry.resolve(project, second).isSuccess)
        assertTrue(registry.resolve(project, third).isSuccess)
        Disposer.dispose(registry)
    }

    fun testResetRejectsRegisterFromPreviousGeneration() {
        val registry = HierarchyContinuationRegistry(
            idGenerator = { "stale-register" },
            serverEpoch = serverEpoch
        ).also { Disposer.register(testRootDisposable, it) }
        val generation = registry.currentGeneration()

        registry.resetSession()
        val result = registry.register(project, generation, fixtureContinuation())

        assertTrue("an in-flight old-session query must not resurrect a cursor", result.isFailure)
        assertEquals(0, registry.sizeForTest())
        Disposer.dispose(registry)
    }

    fun testResolvedLeaseCannotRegisterAfterSessionReset() {
        var generated = 0
        val registry = HierarchyContinuationRegistry(
            idGenerator = { "lease-${++generated}" },
            serverEpoch = serverEpoch
        ).also { Disposer.register(testRootDisposable, it) }
        val cursor = registry.register(project, fixtureContinuation())
        val lease = registry.resolveLease(project, cursor).getOrThrow()

        registry.resetSession()
        val result = registry.register(project, lease.generation, lease.continuation)

        assertTrue(result.isFailure)
        assertEquals(0, registry.sizeForTest())
        Disposer.dispose(registry)
    }

    fun testResetSessionInvalidatesAllContinuations() {
        val registry = HierarchyContinuationRegistry(
            idGenerator = { "reset-cursor" },
            serverEpoch = serverEpoch
        ).also { Disposer.register(testRootDisposable, it) }
        val cursor = registry.register(project, fixtureContinuation())

        registry.resetSession()

        assertTrue(registry.resolve(project, cursor).isFailure)
        assertEquals(0, registry.sizeForTest())
        Disposer.dispose(registry)
    }

    fun testMcpServerStopInvalidatesApplicationContinuationRegistry() {
        val registry = HierarchyContinuationRegistry.getInstance()
        registry.resetSession()
        val cursor = registry.register(project, fixtureContinuation())

        McpServerService.getInstance().stopServer()

        assertTrue("server generation boundary must expire hierarchy cursors", registry.resolve(project, cursor).isFailure)
    }

    fun testRegistryRetainsHistoricalSmartPointerIdentityBeyondFormerTruncationLimit() {
        val registry = HierarchyContinuationRegistry(
            idGenerator = { "bounded-pointers" },
            serverEpoch = serverEpoch
        ).also { Disposer.register(testRootDisposable, it) }
        val fixture = fixtureContinuation()
        val continuation = fixture.copy(
            visitedPointers = List(4_097) {
                fixture.rootPointer
            }
        )

        val cursor = registry.register(project, continuation)
        val restored = registry.resolve(project, cursor).getOrThrow() as TypeContinuation

        assertEquals(
            4_097,
            restored.visitedPointers.size
        )
        Disposer.dispose(registry)
    }

    fun testReplayHistoryIsBoundedWithoutConsumingLiveCursors() {
        var generated = 0
        val registry = HierarchyContinuationRegistry(
            idGenerator = { "history-${++generated}" }, serverEpoch = serverEpoch,
            maxSnapshotsPerTraversal = 2
        ).also { Disposer.register(testRootDisposable, it) }
        try {
            val state = fixtureContinuation()
            val generation = registry.currentGeneration()
            val first = registry.register(project, state)
            val second = registry.register(project, generation, state, first, "1:0").getOrThrow()
            val replayed = registry.register(project, generation, state, first, "1:0").getOrThrow()
            assertEquals("retrying the same live page must reuse its successor", second, replayed)
            assertEquals(2, registry.stats().entries)
            val third = registry.register(project, generation, state, second, "1:0").getOrThrow()
            assertTrue(registry.resolve(project, first).isFailure)
            assertTrue(registry.resolve(project, second).isSuccess)
            assertTrue(registry.resolve(project, second).isSuccess)
            assertTrue(registry.resolve(project, third).isSuccess)
            assertEquals(2, registry.stats().entries)
            assertEquals(1L, registry.stats().evictions[CacheEvictionReason.LRU])
        } finally {
            Disposer.dispose(registry)
        }
    }

    fun testAggregatePointerBudgetEvictsBeforeEntryCountLimit() {
        var generated = 0
        val registry = HierarchyContinuationRegistry(
            idGenerator = { "weighted-${++generated}" }, serverEpoch = serverEpoch,
            maxTotalPointers = 2
        ).also { Disposer.register(testRootDisposable, it) }
        try {
            val state = fixtureContinuation()
            val first = registry.register(project, state)
            val second = registry.register(project, state)
            val third = registry.register(project, state)
            assertTrue(registry.resolve(project, first).isFailure)
            assertTrue(registry.resolve(project, second).isSuccess)
            assertTrue(registry.resolve(project, third).isSuccess)
            assertEquals(2, registry.stats().entries)
            assertEquals(2L, registry.stats().storedPointers)
            assertEquals(1L, registry.stats().evictions[CacheEvictionReason.LRU])
        } finally {
            Disposer.dispose(registry)
        }
    }

    fun testAggregateTextBudgetIncludesSnapshotsAsWellAsVisitedKeys() {
        var generated = 0
        val registry = HierarchyContinuationRegistry(
            idGenerator = { "text-weighted-${++generated}" }, serverEpoch = serverEpoch,
            maxTotalCharacters = 256
        ).also { Disposer.register(testRootDisposable, it) }
        try {
            val state = fixtureContinuation().copy(visited = setOf("x".repeat(128)))
            val first = registry.register(project, state)
            val second = registry.register(project, state)
            assertTrue(registry.resolve(project, first).isFailure)
            assertTrue(registry.resolve(project, second).isSuccess)
            assertEquals(1, registry.stats().entries)
            assertTrue(registry.stats().storedResults > 0)
            assertEquals(1L, registry.stats().evictions[CacheEvictionReason.LRU])
        } finally {
            Disposer.dispose(registry)
        }
    }

    fun testWeightEvictionPrefersReplayHistoryOverAnotherActiveTraversal() {
        var generated = 0
        val registry = HierarchyContinuationRegistry(
            idGenerator = { "active-frontier-${++generated}" }, serverEpoch = serverEpoch,
            maxTotalPointers = 3
        ).also { Disposer.register(testRootDisposable, it) }
        try {
            val state = fixtureContinuation()
            val independent = registry.register(project, state)
            val first = registry.register(project, state)
            val second = registry.register(project, registry.currentGeneration(), state, first).getOrThrow()
            val third = registry.register(project, registry.currentGeneration(), state, second).getOrThrow()
            assertTrue("old independent traversal is still its active frontier", registry.resolve(project, independent).isSuccess)
            assertTrue("obsolete chain history is the eviction candidate", registry.resolve(project, first).isFailure)
            assertTrue(registry.resolve(project, second).isSuccess)
            assertTrue(registry.resolve(project, third).isSuccess)
        } finally {
            Disposer.dispose(registry)
        }
    }

    fun testRegisteredHistoryDoesNotAliasMutableInputCollections() {
        val registry = HierarchyContinuationRegistry(serverEpoch = serverEpoch).also { Disposer.register(testRootDisposable, it) }
        try {
            val state = fixtureContinuation()
            val keys = mutableSetOf("original")
            val pointers = mutableListOf(state.rootPointer)
            val cursor = registry.register(project, state.copy(visited = keys, visitedPointers = pointers))
            keys += "later"
            pointers.clear()
            val retained = registry.resolve(project, cursor).getOrThrow() as TypeContinuation
            assertEquals(setOf("original"), retained.visited)
            assertEquals(1, retained.visitedPointers.size)
        } finally {
            Disposer.dispose(registry)
        }
    }

    fun testMaintenanceAndProjectCloseReclaimStateWithoutClientLookup() {
        var now = 1_000L
        var generated = 0
        val registry = HierarchyContinuationRegistry(
            ttlMillis = 100, clock = { now }, idGenerator = { "cleanup-${++generated}" },
            serverEpoch = serverEpoch
        ).also { Disposer.register(testRootDisposable, it) }
        try {
            val state = fixtureContinuation()
            registry.register(project, state)
            now += 100
            assertEquals("read-only occupancy must not sweep", 1, registry.stats().entries)
            registry.sweepExpired()
            assertEquals(0, registry.stats().entries)
            assertEquals(1L, registry.stats().evictions[CacheEvictionReason.TTL])
            assertEquals(0L, registry.stats().storedPointers)
            registry.register(project, state)
            registry.removeProject(ProjectManager.getInstance().defaultProject)
            assertEquals(1, registry.stats().entries)
            registry.removeProject(project)
            assertEquals(0, registry.stats().entries)
            assertEquals(0L, registry.stats().storedResults)
            assertEquals(1L, registry.stats().evictions[CacheEvictionReason.PROJECT_CLOSED])
        } finally {
            Disposer.dispose(registry)
        }
    }

    fun testRegistryRejectsContinuationWhoseFrontierExceedsSmartPointerCap() {
        val registry = HierarchyContinuationRegistry(
            idGenerator = { "frontier-overflow" },
            serverEpoch = serverEpoch
        ).also { Disposer.register(testRootDisposable, it) }
        val fixture = fixtureContinuation()
        val continuation = fixture.copy(
            frontier = List(HierarchyContinuationRegistry.MAX_POINTERS_PER_CONTINUATION) {
                HierarchyContinuationRegistry.PendingTypeExpansion(
                    pointer = fixture.rootPointer,
                    direction = com.github.hechtcarmel.jetbrainsindexmcpplugin.handlers.TypeHierarchyDirection.SUBTYPE,
                    offset = it
                )
            }
        )

        assertTrue(registry.register(project, registry.currentGeneration(), continuation).isFailure)
        assertEquals(0, registry.sizeForTest())
        Disposer.dispose(registry)
    }

    fun testRegistryRejectsTypeContinuationWhoseVisitedKeyCountExceedsBudget() {
        val registry = HierarchyContinuationRegistry(
            idGenerator = { "visited-key-count-overflow" },
            serverEpoch = serverEpoch
        ).also { Disposer.register(testRootDisposable, it) }
        val fixture = fixtureContinuation()
        val continuation = fixture.copy(
            visited = (0..HierarchyContinuationRegistry.MAX_VISITED_KEYS_PER_CONTINUATION)
                .map { "type-key-$it" }
                .toSet()
        )

        val result = registry.register(project, registry.currentGeneration(), continuation)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("stable visited-key budget"))
        assertEquals(0, registry.sizeForTest())
        Disposer.dispose(registry)
    }

    fun testRegistryRejectsCallContinuationWhoseVisitedKeyCharactersExceedBudget() {
        val registry = HierarchyContinuationRegistry(
            idGenerator = { "visited-key-character-overflow" },
            serverEpoch = serverEpoch
        ).also { Disposer.register(testRootDisposable, it) }
        val continuation = fixtureCallContinuation().copy(
            visited = setOf("x".repeat(HierarchyContinuationRegistry.MAX_VISITED_KEY_CHARS_PER_CONTINUATION + 1))
        )

        val result = registry.register(project, registry.currentGeneration(), continuation)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("stable visited-key budget"))
        assertEquals(0, registry.sizeForTest())
        Disposer.dispose(registry)
    }

    private fun fixtureContinuation(): TypeContinuation {
        writeProjectFile("hierarchy-registry-src/RegistryRoot.java", "class RegistryRoot {}")
        val basePath = requireNotNull(project.basePath)
        return ReadAction.compute<TypeContinuation, Throwable> {
            val virtualFile = requireNotNull(
                LocalFileSystem.getInstance().findFileByPath("$basePath/hierarchy-registry-src/RegistryRoot.java")
            )
            val psiFile = requireNotNull(PsiManager.getInstance(project).findFile(virtualFile)) as PsiJavaFile
            val root = psiFile.classes.single()
            TypeContinuation(
                rootPointer = SmartPointerManager.getInstance(project).createSmartPsiElementPointer(root),
                root = TypeElement(
                    name = "RegistryRoot",
                    file = "hierarchy-registry-src/RegistryRoot.java",
                    kind = "CLASS",
                    language = "Java"
                ),
                frontier = emptyList(),
                visited = setOf("RegistryRoot"),
                scope = BuiltInSearchScope.PROJECT_FILES,
                excludeGenerated = false
            )
        }
    }

    private fun fixtureCallContinuation(): HierarchyContinuationRegistry.CallContinuation {
        val typeFixture = fixtureContinuation()
        return HierarchyContinuationRegistry.CallContinuation(
            rootPointer = typeFixture.rootPointer,
            root = CallElement(
                name = "RegistryRoot.call()",
                file = "hierarchy-registry-src/RegistryRoot.java",
                line = 1,
                column = 1,
                language = "Java"
            ),
            frontier = emptyList(),
            visited = setOf("RegistryRoot.call()"),
            direction = "callees",
            maxDepth = 1,
            scope = BuiltInSearchScope.PROJECT_FILES,
            excludeGenerated = false
        )
    }
}
