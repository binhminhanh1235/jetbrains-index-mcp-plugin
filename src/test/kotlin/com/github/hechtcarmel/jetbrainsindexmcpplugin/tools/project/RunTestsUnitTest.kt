package com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.project

import com.github.hechtcarmel.jetbrainsindexmcpplugin.tools.models.TestStatus
import com.github.hechtcarmel.jetbrainsindexmcpplugin.util.TestResultsCollector
import junit.framework.TestCase
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class RunTestsUnitTest : TestCase() {

    // ── parseTarget ────────────────────────────────────────────────────────────

    fun testParseTargetSimpleClassName() {
        val (className, method) = RunTestsTool.parseTarget("com.example.MyTest")
        assertEquals("com.example.MyTest", className)
        assertNull("No method expected", method)
    }

    fun testParseTargetClassAndMethod() {
        val (className, method) = RunTestsTool.parseTarget("com.example.MyTest#testFoo")
        assertEquals("com.example.MyTest", className)
        assertEquals("testFoo", method)
    }

    fun testParseTargetBlankMethodAfterHash() {
        // "Class#" — the part after # is empty/blank, so method should be null
        val (className, method) = RunTestsTool.parseTarget("com.example.MyTest#")
        assertEquals("com.example.MyTest", className)
        assertNull("Blank method part should become null", method)
    }

    fun testParseTargetRunConfigName() {
        // Run-config names may contain spaces and no # — passthrough as class, no method
        val (className, method) = RunTestsTool.parseTarget("All Tests")
        assertEquals("All Tests", className)
        assertNull(method)
    }

    fun testParseTargetMultipleHashes() {
        // split with limit=2 → second part preserves any further # characters
        val (className, method) = RunTestsTool.parseTarget("com.example.MyTest#foo#extra")
        assertEquals("com.example.MyTest", className)
        assertEquals("foo#extra", method)
    }

    fun testParseTargetEmptyString() {
        val (className, method) = RunTestsTool.parseTarget("")
        assertEquals("", className)
        assertNull(method)
    }

    // ── shouldActivateToolWindow ──────────────────────────────────────────────

    /**
     * The default is part of the tool contract (issue #278): agent runs must not pop the Run tool
     * window unless explicitly requested. The literal wire name pins the schema parameter.
     */
    fun testActivateToolWindowDefaultsToFalse() {
        assertFalse(RunTestsTool.shouldActivateToolWindow(buildJsonObject { }))
    }

    fun testActivateToolWindowExplicitTrue() {
        assertTrue(RunTestsTool.shouldActivateToolWindow(buildJsonObject { put("activateToolWindow", true) }))
    }

    fun testActivateToolWindowExplicitFalse() {
        assertFalse(RunTestsTool.shouldActivateToolWindow(buildJsonObject { put("activateToolWindow", false) }))
    }

    // ── shouldIncludeSuccessOutput ─────────────────────────────────────────────

    fun testIncludeSuccessOutputDefaultsToFalse() {
        assertFalse(RunTestsTool.shouldIncludeSuccessOutput(buildJsonObject { }))
    }

    fun testIncludeSuccessOutputExplicitTrue() {
        assertTrue(RunTestsTool.shouldIncludeSuccessOutput(buildJsonObject { put("includeSuccessOutput", true) }))
    }

    fun testIncludeSuccessOutputExplicitFalse() {
        assertFalse(RunTestsTool.shouldIncludeSuccessOutput(buildJsonObject { put("includeSuccessOutput", false) }))
    }

    // ── resolveRequestMode ─────────────────────────────────────────────────────

    /**
     * The target/runId split is the tool contract for issue #277: a fresh call starts a run, a
     * runId call attaches to one already in flight. Both or neither must be rejected before any
     * project work happens.
     */
    fun testRequestModeStartWhenOnlyTargetProvided() {
        val mode = RunTestsTool.resolveRequestMode(buildJsonObject { put("target", "com.example.MyTest") })
        assertEquals(RunTestsTool.RequestMode.Start("com.example.MyTest"), mode)
    }

    fun testRequestModeAttachWhenOnlyRunIdProvided() {
        val mode = RunTestsTool.resolveRequestMode(buildJsonObject { put("runId", "abc-123") })
        assertEquals(RunTestsTool.RequestMode.Attach("abc-123"), mode)
    }

    fun testRequestModeInvalidWhenBothProvided() {
        val mode = RunTestsTool.resolveRequestMode(buildJsonObject {
            put("target", "com.example.MyTest")
            put("runId", "abc-123")
        })
        assertTrue("target and runId together must be rejected", mode is RunTestsTool.RequestMode.Invalid)
    }

    fun testRequestModeInvalidWhenNeitherProvided() {
        val mode = RunTestsTool.resolveRequestMode(buildJsonObject { })
        assertTrue("one of target/runId is required", mode is RunTestsTool.RequestMode.Invalid)
    }

    fun testRequestModeTreatsBlankValuesAsAbsent() {
        val mode = RunTestsTool.resolveRequestMode(buildJsonObject {
            put("target", "   ")
            put("runId", "")
        })
        assertTrue("blank strings are not real values", mode is RunTestsTool.RequestMode.Invalid)
    }

    fun testRequestModeStartTargetsWhenTargetsProvided() {
        val mode = RunTestsTool.resolveRequestMode(buildJsonObject {
            putJsonArray("targets") {
                add(kotlinx.serialization.json.JsonPrimitive("com.example.TestA"))
                add(kotlinx.serialization.json.JsonPrimitive("com.example.TestB"))
            }
        })
        assertEquals(RunTestsTool.RequestMode.StartTargets(listOf("com.example.TestA", "com.example.TestB")), mode)
    }

    fun testRequestModeStartTargetsEmptyRejected() {
        val mode = RunTestsTool.resolveRequestMode(buildJsonObject {
            putJsonArray("targets") { }
        })
        assertTrue("empty targets array must be rejected", mode is RunTestsTool.RequestMode.Invalid)
        assertEquals("targets array must not be empty.", (mode as RunTestsTool.RequestMode.Invalid).message)
    }

    fun testRequestModeStartTargetsExceedingFiftyRejected() {
        val mode = RunTestsTool.resolveRequestMode(buildJsonObject {
            putJsonArray("targets") {
                for (i in 1..51) {
                    add(kotlinx.serialization.json.JsonPrimitive("com.example.Test$i"))
                }
            }
        })
        assertTrue("more than 50 targets must be rejected", mode is RunTestsTool.RequestMode.Invalid)
        assertTrue((mode as RunTestsTool.RequestMode.Invalid).message.contains("at most 50 entries"))
    }

    fun testRequestModeStartPackageWhenPackageProvided() {
        val mode = RunTestsTool.resolveRequestMode(buildJsonObject {
            put("package", "com.example.service")
        })
        assertEquals(RunTestsTool.RequestMode.StartPackage("com.example.service"), mode)
    }

    fun testRequestModeStartDirectoryWhenDirectoryProvided() {
        val mode = RunTestsTool.resolveRequestMode(buildJsonObject {
            put("directory", "src/test/kotlin/com/example")
        })
        assertEquals(RunTestsTool.RequestMode.StartDirectory("src/test/kotlin/com/example"), mode)
    }

    fun testRequestModeStartModuleWhenModuleProvided() {
        val mode = RunTestsTool.resolveRequestMode(buildJsonObject {
            put("module", "my-core-module")
        })
        assertEquals(RunTestsTool.RequestMode.StartModule("my-core-module"), mode)
    }

    fun testRequestModeMultipleSelectorsRejected() {
        val mode1 = RunTestsTool.resolveRequestMode(buildJsonObject {
            put("target", "com.example.TestA")
            put("package", "com.example")
        })
        assertTrue("multiple selectors must be rejected", mode1 is RunTestsTool.RequestMode.Invalid)

        val mode2 = RunTestsTool.resolveRequestMode(buildJsonObject {
            putJsonArray("targets") { add(kotlinx.serialization.json.JsonPrimitive("com.example.TestA")) }
            put("runId", "some-run-id")
        })
        assertTrue("targets + runId must be rejected", mode2 is RunTestsTool.RequestMode.Invalid)

        val mode3 = RunTestsTool.resolveRequestMode(buildJsonObject {
            put("directory", "src/test")
            put("module", "app")
        })
        assertTrue("directory + module must be rejected", mode3 is RunTestsTool.RequestMode.Invalid)
    }

    // ── resolveWaitSeconds ─────────────────────────────────────────────────────

    /**
     * Every call must return well under the MCP client's own request timeout (60s in Claude
     * Code / the TS SDK) — that client timeout is what issue #277 actually hit. The default and
     * ceiling below keep worst-case call time (wait + tree-finalize grace) under it.
     */
    fun testWaitSecondsDefaultsToSafeBudget() {
        assertEquals(45, RunTestsTool.resolveWaitSeconds(buildJsonObject { }))
    }

    fun testWaitSecondsClampsAboveCeiling() {
        assertEquals(55, RunTestsTool.resolveWaitSeconds(buildJsonObject { put("waitSeconds", 3600) }))
    }

    fun testWaitSecondsClampsNegativeToZero() {
        assertEquals(0, RunTestsTool.resolveWaitSeconds(buildJsonObject { put("waitSeconds", -5) }))
    }

    fun testWaitSecondsExplicitValueWithinRange() {
        assertEquals(10, RunTestsTool.resolveWaitSeconds(buildJsonObject { put("waitSeconds", 10) }))
    }

    // ── finalizeWaitMillis ─────────────────────────────────────────────────────

    /**
     * The tree-finalize grace after process exit must fit inside what remains of the call's wait
     * budget — otherwise a late exit stacks up to ~65s total, the client aborts at 60s, and the
     * collected-and-removed results are lost forever. A small floor keeps collection possible
     * even when the budget is already spent (e.g. waitSeconds=0 polls).
     */
    fun testFinalizeWaitCappedAtTreeFinalizeTimeout() {
        // 45s budget, 5s elapsed: plenty left, but never wait longer than the 10s finalize cap
        assertEquals(10_000L, RunTestsTool.finalizeWaitMillis(45, callStartMs = 0, nowMs = 5_000))
    }

    fun testFinalizeWaitShrinksToRemainingBudget() {
        // 45s budget, 40s elapsed: only 5s left — use it instead of the full 10s cap
        assertEquals(5_000L, RunTestsTool.finalizeWaitMillis(45, callStartMs = 0, nowMs = 40_000))
    }

    fun testFinalizeWaitFlooredWhenBudgetExhausted() {
        // 55s budget, 54.5s elapsed: remaining 500ms is too little to ever collect a tree —
        // floor at 3s rather than returning with results silently dropped
        assertEquals(3_000L, RunTestsTool.finalizeWaitMillis(55, callStartMs = 0, nowMs = 54_500))
        assertEquals(3_000L, RunTestsTool.finalizeWaitMillis(0, callStartMs = 0, nowMs = 0))
    }

    // ── outputWaitMillis ───────────────────────────────────────────────────────

    /**
     * Console-output collection (issue #346) runs after tree finalize, so its ceiling must also
     * fit inside what remains of the call's wait budget — stacking finalize + output past the
     * MCP client's ~60s request timeout would lose the just-collected results. Its floor is
     * deliberately tiny (it stacks on the 3s finalize floor once the budget is spent, and at
     * waitSeconds=55 the call is then ~2s from the client's 60s default): dropped output
     * degrades the result, it does not lose it.
     */
    fun testOutputWaitCappedAtCollectionTimeout() {
        // 45s budget, 5s elapsed: plenty left, but never wait longer than the 5s collection cap
        assertEquals(5_000L, RunTestsTool.outputWaitMillis(45, callStartMs = 0, nowMs = 5_000))
    }

    fun testOutputWaitShrinksToRemainingBudget() {
        // 45s budget, 43s elapsed: only 2s left — use it instead of the full 5s cap
        assertEquals(2_000L, RunTestsTool.outputWaitMillis(45, callStartMs = 0, nowMs = 43_000))
    }

    fun testOutputWaitFlooredWhenBudgetExhausted() {
        assertEquals(250L, RunTestsTool.outputWaitMillis(55, callStartMs = 0, nowMs = 55_000))
        assertEquals(250L, RunTestsTool.outputWaitMillis(0, callStartMs = 0, nowMs = 0))
    }

    // ── processStartAllowanceMs ────────────────────────────────────────────────

    /**
     * A run is registered before its test process starts, because the IDE's before-run build
     * can outlast any single call's wait budget (issue #348) — a slow build must long-poll,
     * never error. The starting phase is bounded by this allowance instead of `timeoutSeconds`
     * (build time is not billed to the run), so the allowance must dwarf any wait budget.
     */
    fun testProcessStartAllowanceDefaultsFarBeyondAnyWaitBudget() {
        assertEquals(
            30 * 60 * 1000L,
            ActiveTestRunRegistry.processStartAllowanceMs(timeoutSeconds = 120)
        )
    }

    /**
     * `timeoutSeconds` extends (never shrinks) the start allowance: a caller budgeting a
     * multi-hour run has implicitly accepted a build longer than the 30-minute default, and
     * there is no separate knob for the build phase.
     */
    fun testProcessStartAllowanceExtendsWithLargeTimeoutSeconds() {
        assertEquals(
            7_200_000L,
            ActiveTestRunRegistry.processStartAllowanceMs(timeoutSeconds = 7_200)
        )
    }

    // ── needsPsiSync ───────────────────────────────────────────────────────────

    /**
     * Attach polls touch no PSI until final collection, and with "Sync external file changes"
     * enabled a full VFS refresh per poll costs seconds on large repos — a 2h run polls ~160
     * times. Start calls resolve the target from PSI and must keep syncing.
     */
    fun testPollCallsSkipPsiSyncButStartCallsDoNot() {
        val tool = RunTestsTool()
        assertFalse(
            "runId polls must not pay the PSI sync tax",
            tool.needsPsiSync(buildJsonObject { put("runId", "abc-123") })
        )
        assertTrue(
            "target starts resolve PSI and must sync",
            tool.needsPsiSync(buildJsonObject { put("target", "com.example.MyTest") })
        )
    }

    // ── in-progress result composition ────────────────────────────────────────

    /**
     * The in-progress payload is what an agent sees instead of a client-side timeout. It must
     * carry the runId both as a field and inside an actionable poll instruction.
     */
    fun testInProgressResultTellsAgentHowToPoll() {
        val result = RunTestsTool.buildInProgressResult(
            runId = "abc-123",
            configName = "MyTest config",
            elapsedSeconds = 61,
            timeoutSeconds = 7200,
            processStarted = true
        )
        assertEquals("running", result.status)
        assertEquals("abc-123", result.runId)
        assertEquals("MyTest config", result.configName)
        assertEquals(61L, result.elapsedSeconds)
        assertEquals(7200, result.timeoutSeconds)
        assertTrue("message must repeat the runId for the poll call", result.message.contains("abc-123"))
        assertTrue("message must name the runId parameter", result.message.contains("runId"))
    }

    /**
     * While the IDE is still compiling (issue #348) the payload must tell the agent the process
     * has not started — so it keeps polling instead of concluding the tests are stuck — and
     * still carry the actionable runId instruction.
     */
    fun testInProgressResultDistinguishesStartingPhase() {
        val result = RunTestsTool.buildInProgressResult(
            runId = "abc-123",
            configName = "MyTest config",
            elapsedSeconds = 61,
            timeoutSeconds = 120,
            processStarted = false
        )
        assertEquals("running", result.status)
        assertTrue(
            "starting-phase message must say the process has not started, got: ${result.message}",
            result.message.contains("not started")
        )
        assertTrue("message must repeat the runId for the poll call", result.message.contains("abc-123"))
        assertTrue("message must name the runId parameter", result.message.contains("runId"))
    }

    // ── TestStatus.isFailure ───────────────────────────────────────────────────

    /**
     * Drives whether [TestResultsCollector] attaches an error message to a run entry, so the
     * failure/non-failure split is part of the ide_run_tests output contract.
     */
    fun testOnlyFailedAndErrorStatusesAreFailures() {
        assertTrue(TestStatus.FAILED.isFailure)
        assertTrue(TestStatus.ERROR.isFailure)
        assertFalse(TestStatus.PASSED.isFailure)
        assertFalse(TestStatus.SKIPPED.isFailure)
    }

    // ── TestResultsCollector.composeName ──────────────────────────────────────

    fun testComposeNameWithSuite() {
        assertEquals("MyClass.testFoo", TestResultsCollector.composeName("testFoo", "MyClass"))
    }

    fun testComposeNameWithoutSuite() {
        assertEquals("testFoo", TestResultsCollector.composeName("testFoo", null))
    }

    fun testComposeNameWithBlankParent() {
        // Blank parent should be treated as absent
        assertEquals("testFoo", TestResultsCollector.composeName("testFoo", ""))
        assertEquals("testFoo", TestResultsCollector.composeName("testFoo", "   "))
    }
}
