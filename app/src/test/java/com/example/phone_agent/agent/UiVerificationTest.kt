package com.example.phone_agent.agent

import com.example.phone_agent.agent.core.*
import com.example.phone_agent.agent.mcp.ToolProvider
import com.example.phone_agent.agent.model.*
import com.example.phone_agent.agent.statemachine.AgentStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class UiVerificationTest {
    @Test fun focusedSearchAndSuggestionsCannotCompleteResultsGoal() {
        val machine = AgentStateMachine("Check weather")
        machine.startPlanning(); machine.onPlanCreated(listOf("Check weather"), successConditions = listOf("Weather results appear"))
        machine.startToolExecution(AgentToolCall("get_ui_tree"))
        machine.onToolResult(McpToolResult("""{"tree":{"children":[{"text":"today's weather","editable":true,"focused":true},{"text":"weather today ghaziabad"}]}}""", false, buildJsonObject {}))
        machine.startPlanning()
        assertNotNull(machine.completionBlockReason())
        assertFalse(machine.completeCurrentStep(StepCompletion("s1", 1, "weather today ghaziabad")))
        assertNotNull(machine.state.currentStep)
    }
    @Test fun foregroundGoalAutoCompletionNeedsExplicitGoalAndFreshMatchingUi() {
        fun machine(condition: String, observed: String): AgentStateMachine {
            val m = AgentStateMachine("task")
            m.startPlanning(); m.onPlanCreated(listOf("Open browser"), successConditions = listOf(condition))
            m.startToolExecution(AgentToolCall("open_app", buildJsonObject { put("package_name", "com.android.chrome") }))
            m.onToolResult(McpToolResult("Opened", false, buildJsonObject {}))
            assertFalse(m.completeVerifiedForegroundGoal()) // Receipt alone never completes.
            m.onUiSettled(); m.startToolExecution(AgentToolCall("get_ui_tree"))
            m.onToolResult(McpToolResult(observed, false, buildJsonObject {})); m.startPlanning()
            return m
        }
        val chrome = """{"package":"com.android.chrome","tree":{"text":"Search"}}"""
        assertTrue(machine("com.android.chrome is the current foreground package", chrome).completeVerifiedForegroundGoal())
        assertFalse(machine("Open Chrome and read weather", chrome).completeVerifiedForegroundGoal())
        assertFalse(machine("com.android.chrome is the current foreground package and weather is visible", chrome).completeVerifiedForegroundGoal())
        assertFalse(machine("com.android.chrome is the current foreground package", """{"package":"com.other","tree":{"text":"Home"}}""").completeVerifiedForegroundGoal())
        val compound = AgentStateMachine("Read weather")
        compound.startPlanning(); compound.onPlanCreated(listOf("Open Chrome and read weather"),
            mapOf("s1" to UiExpectation(packageName = "com.android.chrome")), listOf("Weather results are visible"))
        compound.startToolExecution(AgentToolCall("open_app", buildJsonObject { put("package_name", "com.android.chrome") }))
        compound.onToolResult(McpToolResult("Opened", false, buildJsonObject {})); compound.onUiSettled()
        compound.startToolExecution(AgentToolCall("get_ui_tree")); compound.onToolResult(McpToolResult(chrome, false, buildJsonObject {})); compound.startPlanning()
        assertFalse("A launch predicate must not automatically complete a search goal", compound.completeVerifiedForegroundGoal())
    }
    private val json = Json { encodeDefaults = true }
    private val open = AgentToolCall("open_app", buildJsonObject { put("package_name", "com.example.browser") })
    private val observe = AgentToolCall("get_ui_tree")
    private fun screen(pkg: String = "com.example.browser", text: String = "Ready", editable: Boolean = false): String = buildJsonObject {
        put("package", pkg); put("tree", buildJsonObject { put("text", text); if (editable) { put("editable", true); put("focused", true) } })
    }.toString()
    private fun result(text: String, error: Boolean = false) = McpToolResult(text, error, buildJsonObject {})
    private fun launchState(observed: String): AgentStateMachine {
        val m = AgentStateMachine("task")
        m.startPlanning(); m.onPlanCreated(listOf("Current objective", "Next objective"))
        m.startToolExecution(open); m.onToolResult(result("Opened app"))
        m.startUiSettling(); m.onUiSettled(); m.startToolExecution(observe); m.onToolResult(result(observed))
        return m
    }
    private class Fake(private val response: (AgentToolCall, Int) -> McpToolResult) : ToolProvider {
        val calls = mutableListOf<AgentToolCall>()
        override suspend fun initialize() {}
        override suspend fun listTools() = listOf("open_app", "get_ui_tree", "tap", "type_text", "press_enter").map { McpTool(it, "", buildJsonObject {}) }
        override suspend fun callTool(name: String, arguments: JsonObject): McpToolResult {
            val c = AgentToolCall(name, arguments); calls += c; return response(c, calls.count { it.name == name })
        }
    }
    private fun engine(p: ToolProvider, plans: List<FixtureDecision>, logs: MutableList<String> = mutableListOf(), limits: ExecutionLimits = ExecutionLimits()): AgentExecutor {
        val session = FixtureSession(plans)
        return AgentExecutor(p, session::respond, { "" }, limits.copy(maxTurns = limits.maxTurns + 1), settle = {}, log = { logs += it })
    }
    @Test fun successfulReceiptCannotCompleteWhenObservedLaunchFailed() {
        val m = launchState(screen("com.other", "Home"))
        assertTrue(m.state.actions.first().succeeded)
        assertEquals(false, m.state.actions.first().verified)
        assertEquals(AgentStatus.RECOVERING, m.state.status)
        m.startPlanning()
        assertFalse(m.completeCurrentStep(StepCompletion("s1", 2, "Home")))
        assertFalse(m.completeCurrentStep(StepCompletion("s1", 2, "Home", UiExpectation(text = "Home"))))
        assertEquals(0, m.state.currentStepIndex)
        assertFalse(m.duplicate(open)) // Same call is a valid retry: screen proves app did not open.
    }
    @Test fun cannotPlanExecuteOrFinishWhileMutationIsUnobserved() {
        val m = AgentStateMachine("task")
        m.startPlanning(); m.onPlanCreated(listOf("Open", "Next"))
        m.startToolExecution(open); m.onToolResult(result("Success"))
        try { m.startPlanning(); fail("Planning before verification") } catch (_: IllegalStateException) {}
        try { m.startToolExecution(open); fail("Second action before verification") } catch (_: IllegalStateException) {}
        try { m.finishTask(); fail("Finished without evidence") } catch (_: IllegalStateException) {}
        assertEquals(0, m.state.currentStepIndex)
    }
    @Test fun retryAfterFalseReceiptCanRecoverWithinBounds() = runBlocking {
        val p = Fake { c, count -> result(if (c.name == "get_ui_tree") screen(if (count == 1) "com.other" else "com.example.browser") else "Opened") }
        val logs = mutableListOf<String>()
        val r = engine(p, listOf(
            FixtureDecision(plan = listOf("Open"), toolCalls = listOf(open)),
            FixtureDecision(toolCalls = listOf(open)),
            FixtureDecision(completion = StepCompletion("s1", 4, expectedUi = UiExpectation(packageName = "com.example.browser")), final = "Opened"),
        ), logs).run("open")
        assertEquals("com.example.browser", r.answer)
        assertEquals(listOf("open_app", "get_ui_tree", "open_app", "get_ui_tree"), p.calls.map { it.name })
        assertTrue(logs.any { it.contains("verified=false") })
        assertTrue(logs.any { it.contains("attempt=2") })
    }
    @Test fun unchangedWrongUiDoesNotResetRetryLimitJustBecauseReceiptIsNonempty() = runBlocking {
        val p = Fake { c, _ -> result(if (c.name == "get_ui_tree") screen("com.other") else "Opened successfully") }
        val r = engine(p, List(8) { FixtureDecision(plan = listOf("Open"), toolCalls = listOf(open)) }).run("open")
        assertEquals(2, p.calls.count { it.name == "open_app" })
        assertTrue(r.answer.contains("not making verified progress"))
    }
    @Test fun verificationMatchesNormalizedNodeValuesAcrossChildren() {
        val m = AgentStateMachine("read")
        m.startPlanning(); m.onPlanCreated(listOf("Read weather"))
        m.startToolExecution(observe)
        m.onToolResult(result("""{"tree":{"children":[{"text":"Today's"},{"desc":"  WEATHER\n  24 C "}]}}"""))
        m.startPlanning()
        assertTrue(m.completeCurrentStep(StepCompletion("s1", 1, "today's weather 24 c")))
    }
    @Test fun frozenStepExpectationCannotBeWeakenedByCompletionProposal() {
        val m = AgentStateMachine("read")
        m.startPlanning(); m.onPlanCreated(listOf("Read results"), mapOf("s1" to UiExpectation(text = "Search results")))
        m.startToolExecution(observe); m.onToolResult(result(screen(text = "Search field")))
        m.startPlanning()
        assertFalse(m.completeCurrentStep(StepCompletion("s1", 1, "Search field", UiExpectation(text = "Search field"))))
        assertEquals(0, m.state.currentStepIndex)
    }
    @Test fun stepCanNavigateToAnotherPackageWithoutOldLaunchInvalidatingCompletion() {
        val m = launchState(screen())
        m.startPlanning()
        val tap = AgentToolCall("tap", buildJsonObject { put("x", 1); put("y", 2) }, UiExpectation(packageName = "com.example.destination", text = "Details"))
        m.startToolExecution(tap); m.onToolResult(result("Tapped"))
        m.startUiSettling(); m.onUiSettled(); m.startToolExecution(observe)
        m.onToolResult(result(screen("com.example.destination", "Details")))
        m.startPlanning()
        assertTrue(m.completeCurrentStep(StepCompletion("s1", 4, expectedUi = UiExpectation(text = "Details"))))
        assertEquals(1, m.state.currentStepIndex)
    }
    @Test fun recoveryReasonAllowsCorrectiveActionOnlyAfterActualObservation() {
        val m = launchState(screen("com.other"))
        m.startPlanning()
        val tap = AgentToolCall("tap", expectedUi = UiExpectation(text = "Ready"))
        assertNotNull(m.recoveryError(tap))
        assertNull(m.recoveryError(tap.copy(recoveryReason = "Dismiss the observed launch-blocking dialog")))
        assertEquals(0, m.state.currentStepIndex)
        m.startToolExecution(observe); m.onToolResult(result(screen()))
        m.startPlanning()
        assertNull(m.recoveryError(tap))
    }
    @Test fun malformedMcpPayloadsAndTreesAreNotSuccessfulObservations() = runBlocking {
        val bad = listOf(
            McpToolResult("{}", false, buildJsonObject { put("unexpected", true) }),
            result("""{"package":"com.example.browser","tree":{"children":"invalid"}}"""),
            result("""{"tree":{"text":42}}"""),
            McpToolResult("anything", false, buildJsonObject { put("isError", "invalid") }),
        )
        for (output in bad) {
            val p = Fake { _, _ -> output }
            val r = engine(p, listOf(FixtureDecision(plan = listOf("Read"), toolCalls = listOf(observe))), limits = ExecutionLimits(maxTurns = 1)).run("read")
            assertTrue(r.toolResults.isNotEmpty() && r.toolResults.all { it.isError })
            assertTrue(r.answer.startsWith("I couldn't finish"))
        }
    }
    @Test fun dynamicSubmitWithoutPredictedOutcomeIsObservedBeforeNextDecision() = runBlocking {
        val p = Fake { c, _ -> result(if (c.name == "get_ui_tree") screen(text = "Actual results", editable = true) else "Submitted") }
        val r = engine(p, listOf(
            FixtureDecision(plan = listOf("Search"), toolCalls = listOf(observe)),
            FixtureDecision(toolCalls = listOf(AgentToolCall("press_enter"))),
            FixtureDecision(completion = StepCompletion("s1", 3, "Actual results")),
        )).run("search")
        assertEquals("Actual results", r.answer)
        assertEquals(listOf("get_ui_tree", "press_enter", "get_ui_tree"), p.calls.map { it.name })
    }
    @Test fun fullObservationIsUsedForVerificationEvenWhenHistorySummaryOmitsEvidence() {
        val m = AgentStateMachine("read")
        m.startPlanning(); m.onPlanCreated(listOf("Find details"), mapOf("s1" to UiExpectation(text = "Tail result")))
        val full = buildJsonObject { put("tree", buildJsonObject { put("children", buildJsonArray {
            repeat(400) { add(buildJsonObject { put("text", "Earlier node $it") }) }
            add(buildJsonObject { put("text", "Tail result") })
        }) }) }.toString()
        m.startToolExecution(observe); m.onToolResult(result(full))
        assertFalse(m.state.actions.last().result.contains("Tail result"))
        m.startPlanning()
        assertTrue(m.completeCurrentStep(StepCompletion("s1", 1)))
    }
    @Test fun restoredLogsIncludePlanArgumentsResultsObservationVerificationAndFinal() = runBlocking {
        val p = Fake { c, _ -> result(if (c.name == "get_ui_tree") screen() else "Opened app") }
        val logs = mutableListOf<String>()
        engine(p, listOf(FixtureDecision(plan = listOf("Open full plan step"), toolCalls = listOf(open)),
            FixtureDecision(completion = StepCompletion("s1", 2, "com.example.browser"), final = "Opened")), logs).run("open")
        for (marker in listOf("FULL_PLAN", "description=Open full plan step", "arguments=", "TOOL_RESULT", "UI_OBSERVATION", "ACTION_VERIFICATION", "GOAL_COMPLETION", "FINAL_OUTCOME success=true"))
            assertTrue("Missing $marker", logs.any { it.contains(marker) })
    }
    @Test fun typingRetriesOnlyUntilTheExpectedFieldValueIsActuallyObserved() = runBlocking {
        val input = AgentToolCall("type_text", buildJsonObject { put("text", "weather") })
        val p = Fake { c, count ->
            if (c.name == "get_ui_tree") result(screen(text = if (count < 3) "" else "weather", editable = true))
            else result("Typed characters")
        }
        val r = engine(p, listOf(
            FixtureDecision(plan = listOf("Enter query"), toolCalls = listOf(observe), stepExpectations = mapOf("s1" to UiExpectation(editableText = "weather"))),
            FixtureDecision(toolCalls = listOf(input)),
            FixtureDecision(completion = StepCompletion("s1", 3, "weather")), // first receipt did not change the input
            FixtureDecision(toolCalls = listOf(input)),
            FixtureDecision(completion = StepCompletion("s1", 5), final = "Entered"),
        )).run("type query")
        assertEquals("weather", r.answer)
        assertEquals(2, p.calls.count { it.name == "type_text" })
    }
    @Test fun duplicateTapIsBlockedAfterVerifiedOutcomeButNewOutcomeRemainsPossible() {
        val m = launchState(screen(text = "Menu"))
        m.startPlanning()
        val tap = AgentToolCall("tap", buildJsonObject { put("x", 10); put("y", 20) }, UiExpectation(text = "Details"))
        m.startToolExecution(tap); m.onToolResult(result("Tapped"))
        m.onUiSettled(); m.startToolExecution(observe); m.onToolResult(result(screen(text = "Details")))
        assertTrue(m.duplicate(tap))
        assertFalse(m.duplicate(tap.copy(expectedUi = UiExpectation(text = "Next page"))))
    }
    @Test fun finalAllowedDecisionStillObservesItsUiAction() = runBlocking {
        val p = Fake { c, _ -> result(if (c.name == "get_ui_tree") screen() else "Opened") }
        val r = engine(p, listOf(FixtureDecision(plan = listOf("Open"), toolCalls = listOf(open))), limits = ExecutionLimits(maxTurns = 1)).run("open")
        assertEquals(listOf("open_app", "get_ui_tree"), p.calls.map { it.name })
        assertEquals(2, r.stepsUsed)
        assertTrue(r.answer.contains("not making verified progress"))
    }
    @Test fun noMutationExecutesWhenToolBudgetCannotIncludeObservation() = runBlocking {
        val p = Fake { _, _ -> result("Opened") }
        val r = engine(p, listOf(FixtureDecision(plan = listOf("Open"), toolCalls = listOf(open))), limits = ExecutionLimits(maxTools = 1)).run("open")
        assertTrue(p.calls.isEmpty())
        assertTrue(r.answer.contains("not making verified progress"))
    }
    @Test fun missingObservationIsNotMisclassifiedAsDuplicateSuccess() {
        val m = AgentStateMachine("open")
        m.startPlanning(); m.onPlanCreated(listOf("Open"))
        m.startToolExecution(open); m.onToolResult(result("Opened"))
        assertFalse(m.duplicate(open))
        assertNotNull(m.pendingVerification())
        try { m.startToolExecution(open); fail("Executed without observation") } catch (_: IllegalStateException) {}
        m.onUiSettled()
        assertTrue(m.requiresObservation())
    }
    @Test fun modelRequestsContainOneCurrentScreenWithoutReplayedHistory() = runBlocking {
        val marker = "Unique observed weather 24 C"
        val p = Fake { c, _ -> result(if (c.name == "get_ui_tree") screen(text = marker) else "Opened") }
        val requests = mutableListOf<List<ChatMessage>>()
        val session = FixtureSession(listOf(FixtureDecision(plan = listOf("Read"), toolCalls = listOf(open)),
            FixtureDecision(completion = StepCompletion("s1", 2, expectedUi = UiExpectation(text = marker)))))
        val agent = AgentExecutor(p, { messages ->
            if (!messages.last().content.contains("\"request_phase\":\"plan\"")) requests += messages
            session.respond(messages)
        }, { "instructions" }, settle = {})
        assertEquals(marker, agent.run("weather").answer)
        assertEquals(2, requests.size)
        assertEquals(3, requests[1].size)
        assertFalse(requests[1].any { it.role == "assistant" })
        assertEquals(1, Regex(Regex.escape(marker)).findAll(requests[1].joinToString { it.content }).count())
        val state = Json.parseToJsonElement(requests[1].last().content).jsonObject
        assertEquals("s1", state.getValue("current_goal").jsonPrimitive.content)
        assertEquals(2, state.getValue("ui_observation").jsonObject.getValue("action_id").jsonPrimitive.int)
        assertTrue(state.getValue("recent_results").jsonArray.first().jsonObject.getValue("ui_verified").jsonPrimitive.boolean)
    }
    @Test fun unusableObservationsAreRetriedAndStopBeforeAnotherModelDecision() = runBlocking {
        val invalid = listOf("", " ", "not json", """{"tree":{}}""",
            """{"package":"com.example.browser","tree":{"children":[]}}""",
            """{"tree":{"class":"FrameLayout"}}""",
            """{"tree":{"text":"  ","desc":" "}}""",
            """{"tree":{"text":"Ready"}}""") // Missing the package needed to verify this launch.
        for (bad in invalid) {
            val p = Fake { c, _ -> result(if (c.name == "get_ui_tree") bad else "Opened") }
            val r = engine(p, listOf(FixtureDecision(plan = listOf("Open"), toolCalls = listOf(open)))).run("open")
            assertEquals("Bad observation: $bad", listOf("open_app", "get_ui_tree", "get_ui_tree"), p.calls.map { it.name })
            assertEquals(2, r.stepsUsed)
            assertEquals(2, r.toolResults.count { it.isError })
            assertTrue(r.answer.contains("usable screen"))
        }
    }
    @Test fun usableLayoutWithBoundsIsAcceptedWithoutText() = runBlocking {
        val layout = """{"package":"com.example.browser","tree":{"class":"FrameLayout","bounds":[0,0,1080,1920]}}"""
        val p = Fake { c, _ -> result(if (c.name == "get_ui_tree") layout else "Opened") }
        val r = engine(p, listOf(FixtureDecision(plan = listOf("Open"), toolCalls = listOf(open)),
            FixtureDecision(completion = StepCompletion("s1", 2, expectedUi = UiExpectation(packageName = "com.example.browser")), final = "Opened"))).run("open")
        assertEquals("com.example.browser", r.answer)
    }
    @Test fun rejectedRecoveryCannotBypassRequiredObservationAfterFailedRead() {
        val m = launchState(screen())
        m.startPlanning(); m.startToolExecution(observe); m.onToolResult(result("", true))
        assertTrue(m.requiresObservation())
        try { m.startPlanning(); fail("Planned on failed observation") } catch (_: IllegalStateException) {}
        m.startObservation(); m.rejectDecision("Rejected recovery proposal")
        assertEquals(AgentStatus.CHECKING_PROGRESS, m.state.status)
        assertTrue(m.requiresObservation())
        try { m.startPlanning(); fail("Rejection cleared mandatory observation") } catch (_: IllegalStateException) {}
        m.startObservation(); m.startToolExecution(observe); m.onToolResult(result(screen()))
        m.startPlanning()
        assertFalse(m.requiresObservation())
    }
    @Test fun correctiveActionUsesFreshFailedLaunchScreenAndIsObservedBeforeCompletion() = runBlocking {
        val p = Fake { c, count -> result(if (c.name == "get_ui_tree") screen(if (count == 1) "com.other" else "com.example.browser", "Details") else "Receipt") }
        val tap = AgentToolCall("tap", buildJsonObject { put("x", 10); put("y", 20) }, UiExpectation(text = "Details"), "Dismiss observed launch-blocking dialog")
        val r = engine(p, listOf(FixtureDecision(plan = listOf("Open"), toolCalls = listOf(open)),
            FixtureDecision(toolCalls = listOf(tap)),
            FixtureDecision(completion = StepCompletion("s1", 4, "com.example.browser")))).run("open")
        assertEquals("com.example.browser", r.answer)
        assertEquals(listOf("open_app", "get_ui_tree", "tap", "get_ui_tree"), p.calls.map { it.name })
    }
    @Test fun repeatedReadsStopWithoutVerifiedProgressEvenWhenClockTextChanges() = runBlocking {
        val p = Fake { _, count -> result(screen(text = "Clock tick $count")) }
        val logs = mutableListOf<String>()
        val r = engine(p, List(20) { FixtureDecision(plan = listOf("Read required results"), toolCalls = listOf(observe.copy(recoveryReason = "Wait for pending page results"))) }, logs).run("read")
        assertTrue(r.answer, r.answer.contains("not making verified progress"))
        assertTrue(r.stepsUsed <= 5)
        assertTrue(p.calls.size <= 3)
        assertEquals(1, logs.count { it.startsWith("FULL_PLAN") })
        assertTrue(r.answer.contains("Remaining: Read required results"))
    }
    @Test fun alternatingNoProgressDecisionsDoNotResetTheStallBudget() = runBlocking {
        val p = Fake { _, _ -> result(screen()) }
        val plans = List(20) { i -> FixtureDecision(plan = listOf("Read"), toolCalls = listOf(if (i % 2 == 0) observe else AgentToolCall("unknown"))) }
        val r = engine(p, plans).run("read")
        assertTrue(r.answer, r.answer.contains("not making verified progress"))
        assertTrue(r.stepsUsed <= 6)
        assertTrue(p.calls.size <= 2)
    }
    @Test fun changingObservationIdsCannotHideRepeatedRejectedCompletion() = runBlocking {
        val p = Fake { c, _ -> result(if (c.name == "get_ui_tree") screen() else "Opened") }
        val plans = listOf(FixtureDecision(plan = listOf("Open"), toolCalls = listOf(open))) +
            List(10) { FixtureDecision(completion = StepCompletion("s1", 50 + it, "com.example.browser")) }
        val r = engine(p, plans).run("open")
        assertTrue(r.answer, r.answer.contains("not making verified progress"))
        assertTrue(r.stepsUsed <= 5)
        assertEquals(listOf("open_app", "get_ui_tree"), p.calls.map { it.name })
    }
    @Test fun earlierVerifiedActionIsNotRepeatedAfterAnInterveningAction() {
        val m = launchState(screen())
        m.startPlanning()
        val first = AgentToolCall("tap", buildJsonObject { put("x", 1); put("y", 2) }, UiExpectation(text = "Details"))
        val second = AgentToolCall("tap", buildJsonObject { put("x", 3); put("y", 4) }, UiExpectation(text = "Menu"))
        m.startToolExecution(first); m.onToolResult(result("Tapped")); m.onUiSettled()
        m.startToolExecution(observe); m.onToolResult(result(screen(text = "Details"))); m.startPlanning()
        m.startToolExecution(second); m.onToolResult(result("Tapped")); m.onUiSettled()
        m.startToolExecution(observe); m.onToolResult(result(screen(text = "Menu Details")))
        assertTrue(m.duplicate(first))
    }
    @Test fun aFailedObservationCanRecoverAutomaticallyAndFinish() = runBlocking {
        val p = Fake { c, count -> result(if (c.name == "get_ui_tree") { if (count == 1) """{"tree":{}}""" else screen() } else "Opened") }
        val r = engine(p, listOf(FixtureDecision(plan = listOf("Open"), toolCalls = listOf(open)),
            FixtureDecision(completion = StepCompletion("s1", 3, "com.example.browser"), final = "Opened"))).run("open")
        assertEquals("com.example.browser", r.answer)
        assertEquals(3, r.stepsUsed)
        assertEquals(1, r.toolResults.count { it.isError })
        assertEquals(listOf("open_app", "get_ui_tree", "get_ui_tree"), p.calls.map { it.name })
    }
    @Test fun laterReadStepDoesNotInheritCompletedLaunchVerification() {
        val m = launchState(screen())
        m.startPlanning()
        assertTrue(m.completeCurrentStep(StepCompletion("s1", 2, "com.example.browser")))
        m.rejectDecision("Next objective"); m.startPlanning(); m.startToolExecution(observe)
        m.onToolResult(result("""{"tree":{"text":"Weather results"}}"""))
        assertTrue(m.state.actions.last().succeeded)
        assertEquals(true, m.state.actions.first().verified)
        m.startPlanning()
        assertTrue(m.completeCurrentStep(StepCompletion("s2", 3, expectedUi = UiExpectation(text = "Weather results"))))
        m.finishTask()
        assertEquals(AgentStatus.COMPLETED, m.state.status)
    }
}
