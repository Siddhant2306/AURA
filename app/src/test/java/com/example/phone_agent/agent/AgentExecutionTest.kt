package com.example.phone_agent.agent

import com.example.phone_agent.agent.core.*
import com.example.phone_agent.agent.mcp.ToolProvider
import com.example.phone_agent.agent.model.*
import com.example.phone_agent.agent.statemachine.AgentStatus
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentExecutionTest {
    private val json = Json { encodeDefaults = true }
    private fun call(name: String, args: String = "{}") = AgentToolCall(name, Json.parseToJsonElement(args).jsonObject)
    private fun result(text: String = "OK", error: Boolean = false) = McpToolResult(text, error, buildJsonObject {})
    private val screen = """{"package":"com.android.chrome","tree":{"text":"Weather 24 C","editable":true}}"""
    private val open = call("open_app", """{"package_name":"com.android.chrome"}""")
    private val observe = call("get_ui_tree")
    private val type = call("type_text", """{"text":"weather"}""")
    private val enter = call("press_enter").copy(expectedUi = UiExpectation(text = "Weather 24 C"))
    private fun settleAndObserve(machine: AgentStateMachine, observed: String = screen) {
        machine.startUiSettling(); machine.onUiSettled()
        machine.startToolExecution(observe); machine.onToolResult(result(observed))
    }
    private val schema = Json.parseToJsonElement("""{"properties":{"package_name":{"type":"string"}},"required":["package_name"],"additionalProperties":false}""").jsonObject
    private inner class FakeProvider : ToolProvider {
        val calls = mutableListOf<AgentToolCall>()
        var response: suspend (AgentToolCall) -> McpToolResult = { if (it.name == "get_ui_tree") result(screen) else result() }
        override suspend fun initialize() {}
        override suspend fun listTools() = listOf("open_app", "get_ui_tree", "type_text", "press_enter", "tap", "go_back").map {
            McpTool(it, "", if (it == "open_app") schema else buildJsonObject {})
        }
        override suspend fun callTool(name: String, arguments: JsonObject): McpToolResult {
            val c = AgentToolCall(name, arguments); calls += c; return response(c)
        }
    }
    private fun executor(provider: FakeProvider, decisions: List<FixtureDecision>, limits: ExecutionLimits = ExecutionLimits(), requests: MutableList<List<ChatMessage>> = mutableListOf()): AgentExecutor {
        val session = FixtureSession(decisions)
        return AgentExecutor(provider, { request ->
            if (!request.last().content.contains("\"request_phase\":\"plan\"")) requests += request
            session.respond(request)
        }, { "instructions" }, limits.copy(maxTurns = limits.maxTurns + 1), settle = {})
    }
    @Test fun sequentialWeatherWithSeveralActionsPerObjective() = runBlocking {
        val p = FakeProvider()
        p.response = { c ->
            if (c.name != "get_ui_tree") result()
            else if (p.calls.any { it.name == "press_enter" }) result(screen)
            else if (p.calls.any { it.name == "type_text" }) result("""{"package":"com.android.chrome","tree":{"text":"weather","editable":true,"focused":true}}""")
            else result("""{"package":"com.android.chrome","tree":{"desc":"Search or type URL","editable":true,"focused":true}}""")
        }
        val requests = mutableListOf<List<ChatMessage>>()
        val decisions = listOf(
            FixtureDecision(plan = listOf("Open Chrome", "Search and read weather"), toolCalls = listOf(open, type)),
            FixtureDecision(completion = StepCompletion("s1", 2, "com.android.chrome")),
            FixtureDecision(toolCalls = listOf(type, enter)),
            FixtureDecision(toolCalls = listOf(enter)),
            FixtureDecision(completion = StepCompletion("s2", 6, "Weather 24 C"), final = "Weather is 24 C"),
        )
        val answer = executor(p, decisions, requests = requests).run("Open Chrome and check weather")
        assertTrue(answer.answer.contains("Weather 24 C"))
        assertEquals(listOf("open_app", "get_ui_tree", "type_text", "get_ui_tree", "press_enter", "get_ui_tree"), p.calls.map { it.name })
        assertEquals(6, answer.stepsUsed)
        val afterOpen = Json.parseToJsonElement(requests[1].last().content).jsonObject
        assertFalse(afterOpen.getValue("needs_observation").jsonPrimitive.boolean)
        assertEquals("get_ui_tree", afterOpen.getValue("recent_results").jsonArray.last().jsonObject.getValue("tool").jsonPrimitive.content)

    }
    @Test fun completesWithValidEvidence() = runBlocking {
        val p = FakeProvider()
        val r = executor(p, listOf(
            FixtureDecision(plan = listOf("Check weather"), toolCalls = listOf(observe)),
            FixtureDecision(completion = StepCompletion("s1", 1, "Weather 24 C"), final = "24 C"),
        )).run("weather")
        assertTrue(r.answer.contains("24 C"))
    }
    @Test fun duplicateLaunchIsRejectedWithoutReopening() = runBlocking {
        val p = FakeProvider()
        val requests = mutableListOf<List<ChatMessage>>()
        val r = executor(p, listOf(
            FixtureDecision(plan = listOf("Open Chrome"), toolCalls = listOf(open)),
            FixtureDecision(toolCalls = listOf(open)),
            FixtureDecision(completion = StepCompletion("s1", 2, "com.android.chrome"), final = "Opened Chrome"),
        ), requests = requests).run("Open Chrome")
        assertEquals("com.android.chrome", r.answer)
        assertEquals(1, p.calls.count { it.name == "open_app" })
        assertTrue(requests[2].last().content.contains("Duplicate confirmed action rejected"))
    }
    @Test fun failedToolsHaveBoundedRetriesAndNeverComplete() = runBlocking {
        val p = FakeProvider().apply { response = { if (it.name == "open_app") result("Launch failed", true)
            else result("""{"package":"com.other","tree":{"text":"Home"}}""") } }
        val r = executor(p, List(8) { FixtureDecision(plan = listOf("Open Chrome"), toolCalls = listOf(open)) }).run("Open Chrome")
        assertEquals(2, p.calls.count { it.name == "open_app" })
        assertEquals(2, r.toolResults.count { it.isError })
        assertTrue(r.answer.contains("Remaining: Open Chrome"))
    }
    @Test fun exceptionsBecomeRecordedErrorsAndCanRecover() = runBlocking {
        val p = FakeProvider()
        p.response = { if (p.calls.size == 1) error("transport failed") else result(screen) }
        val r = executor(p, listOf(
            FixtureDecision(plan = listOf("Read weather"), toolCalls = listOf(observe)),
            FixtureDecision(completion = StepCompletion("s1", 2, "Weather 24 C"), final = "24 C"),
        )).run("weather")
        assertTrue(r.answer.contains("24 C"))
        assertTrue(r.toolResults.first().isError)
    }
    @Test fun emptyToolListCannotClaimSuccess() = runBlocking {
        val p = FakeProvider()
        val r = executor(p, List(4) { FixtureDecision(plan = listOf("Send message"), final = "Sent!") }).run("send")
        assertTrue(r.answer.startsWith("I couldn't finish"))
        assertTrue(p.calls.isEmpty())
    }
    @Test fun invalidNamesAndArgumentsAreNotExecuted() = runBlocking {
        for (bad in listOf(call("unknown"), call("open_app"), call("open_app", """{"package_name":3}"""))) {
            val p = FakeProvider()
            val r = executor(p, List(4) { FixtureDecision(plan = listOf("Open"), toolCalls = listOf(bad)) }).run("open")
            assertTrue(p.calls.isEmpty()); assertTrue(r.answer.startsWith("I couldn't finish"))
        }
    }
    @Test fun freshTaskDoesNotInheritHistory() = runBlocking {
        val p = FakeProvider()
        val requests = mutableListOf<List<ChatMessage>>()
        val engine = AgentExecutor(p, { request ->
            if (!request.last().content.contains("\"request_phase\":\"plan\"")) requests += request
            FixtureSession(listOf(FixtureDecision(plan = listOf("Open"), toolCalls = listOf(open)))).respond(request)
        }, { "" }, ExecutionLimits(maxTurns = 2), settle = {})
        engine.run("one"); engine.run("two")
        assertEquals(2, p.calls.count { it.name == "open_app" })
        assertFalse(requests[1].last().content.contains("\"id\":1"))
    }
    @Test fun decisionAndToolLimitsStopCleanly() = runBlocking {
        val p = FakeProvider()
        val r = executor(p, List(5) { FixtureDecision(plan = listOf("Read"), toolCalls = listOf(observe)) }, ExecutionLimits(maxTurns = 2)).run("read")
        assertEquals(3, r.stepsUsed); assertTrue(r.answer.contains("not making verified progress"))
        val r2 = executor(p, List(5) { FixtureDecision(plan = listOf("Read"), toolCalls = listOf(observe)) }, ExecutionLimits(maxTools = 1)).run("read")
        assertEquals(1, r2.toolResults.size); assertTrue(r2.answer.contains("not making verified progress"))
    }
    @Test fun timeoutAndCancellationAreHandled() = runBlocking {
        val p = FakeProvider().apply { response = { delay(500); result() } }
        val r = executor(p, listOf(FixtureDecision(plan = listOf("Read"), toolCalls = listOf(observe))), ExecutionLimits(timeoutMillis = 20)).run("read")
        assertTrue(r.answer.contains("timed out"))
        val engine = AgentExecutor(p, { throw CancellationException("cancel") }, { "" })
        try { engine.run("read"); fail("Cancellation swallowed") } catch (_: CancellationException) {}
    }
    @Test fun parserRejectsInvalidTypesAndRetainsCompatibility() {
        val parser = PlanParser()
        assertNotNull(parser.parse("not json", true).error)
        assertNotNull(parser.parse("""{"tool_calls":[{}]}""", false).error)
        assertNull(parser.parse("""{"type":"plan","goals":[{"description":"Read","success_condition":"Information is observed"}]}""", true).error)
    }
    @Test fun invalidJsonAndEmptyPlanAreBounded() = runBlocking {
        val p = FakeProvider()
        val engine = AgentExecutor(p, { "invalid" }, { "" }, settle = {})
        assertEquals(2, engine.run("read").stepsUsed)
        val r = executor(p, List(4) { FixtureDecision() }).run("read")
        assertTrue(r.answer.contains("model did not return a valid action"))
        assertTrue(engine.run(" ").answer.contains("enter a command"))
    }
    @Test fun stateTransitionsAndEvidenceAreValidated() {
        val m = AgentStateMachine("open")
        try { m.startToolExecution(open); fail("Invalid transition accepted") } catch (_: IllegalStateException) {}
        m.startPlanning(); m.onPlanCreated(listOf("Open Chrome", "Read"))
        m.startToolExecution(open); m.onToolResult(result())
        assertEquals(0, m.state.currentStepIndex)
        assertEquals(AgentStatus.WAITING_FOR_UI_SETTLE, m.state.status)
        try { m.startPlanning(); fail("Planned before observation") } catch (_: IllegalStateException) {}
        settleAndObserve(m)
        m.startPlanning()
        assertFalse(m.completeCurrentStep(StepCompletion("s1", 1, "OK")))
        assertFalse(m.completeCurrentStep(StepCompletion("s2", 2, "Weather 24 C")))
        assertFalse(m.completeCurrentStep(StepCompletion("s1", 2, "fabricated")))
        assertTrue(m.completeCurrentStep(StepCompletion("s1", 2, "com.android.chrome")))
        assertEquals(1, m.state.currentStepIndex)
        m.failTask("test")
        try { m.startPlanning(); fail("Terminal state reopened") } catch (_: IllegalStateException) {}
    }
    @Test fun wrongForegroundPackageCannotVerifyLaunch() {
        val m = AgentStateMachine("open")
        m.startPlanning(); m.onPlanCreated(listOf("Open Chrome"))
        m.startToolExecution(open); m.onToolResult(result())
        settleAndObserve(m, """{"package":"com.other","tree":{"text":"Other"}}""")
        m.startPlanning()
        assertFalse(m.completeCurrentStep(StepCompletion("s1", 2, "com.other")))
    }
    @Test fun staleAndFailedObservationsCannotCompleteSteps() {
        val m = AgentStateMachine("read")
        m.startPlanning(); m.onPlanCreated(listOf("Read weather"))
        m.startToolExecution(observe); m.onToolResult(result(screen))
        m.startPlanning(); m.startToolExecution(type); m.onToolResult(result())
        m.startUiSettling(); m.onUiSettled()
        m.startToolExecution(observe); m.onToolResult(result("unavailable", true))
        assertTrue(m.requiresObservation())
        m.startObservation(); m.startToolExecution(observe)
        m.onToolResult(result("""{"package":"com.android.chrome","tree":{"text":"Weather 24 Cweather","editable":true}}"""))
        m.startPlanning()
        assertFalse(m.completeCurrentStep(StepCompletion("s1", 1, "Weather 24 C")))
        assertFalse(m.completeCurrentStep(StepCompletion("s1", 3, "unavailable")))
    }
    @Test fun duplicateArgumentsAreNormalizedAndNavigationAllowsReopen() {
        val m = AgentStateMachine("tap")
        m.startPlanning(); m.onPlanCreated(listOf("Tap"))
        m.startToolExecution(observe); m.onToolResult(result(screen)); m.startPlanning()
        m.startToolExecution(call("tap", """{"x":10,"y":20}""").copy(expectedUi = UiExpectation(text = "New page"))); m.onToolResult(result())
        assertFalse(m.duplicate(call("tap", """{"y":20.0,"x":10.0}""")))
        settleAndObserve(m, """{"package":"com.android.chrome","tree":{"text":"New page"}}""")
        assertTrue(m.duplicate(call("tap", """{"y":20.0,"x":10.0}""")))
        m.startPlanning(); m.startToolExecution(open); m.onToolResult(result())
        settleAndObserve(m, """{"package":"com.other","tree":{"text":"Home"}}""")
        assertFalse(m.duplicate(open))
    }
    @Test fun repeatedReadOnlyToolsRemainAllowedAndExecuteSequentially() = runBlocking {
        val p = FakeProvider()
        val r = executor(p, listOf(
            FixtureDecision(plan = listOf("Read"), toolCalls = listOf(observe, observe)),
            FixtureDecision(toolCalls = listOf(observe.copy(recoveryReason = "Check for updated results"))),
            FixtureDecision(completion = StepCompletion("s1", 2, "Weather 24 C"), final = "24 C"),
        )).run("read")
        assertEquals(2, p.calls.size)
        assertTrue(r.answer.contains("24 C"))
    }
    @Test fun largeScreenKeepsValidBoundedJsonAndPackage() {
        val full = buildJsonObject {
            put("package", "com.android.chrome")
            put("tree", buildJsonObject { put("children", buildJsonArray {
                repeat(300) { add(buildJsonObject { put("text", "Weather result $it") }) }
            }) })
        }.toString()
        val compact = ContextManager.truncateToolResult("get_ui_tree", full)
        assertTrue(compact.length <= 6_000)
        val parsed = Json.parseToJsonElement(compact).jsonObject
        assertEquals("com.android.chrome", parsed["package"]?.jsonPrimitive?.content)
        assertTrue(parsed["tree"] is JsonObject)
    }
    @Test fun malformedObservationIsRecordedAsError() = runBlocking {
        val p = FakeProvider().apply { response = { result("not a screen") } }
        val r = executor(p, listOf(FixtureDecision(plan = listOf("Read"), toolCalls = listOf(observe))), ExecutionLimits(maxTurns = 1)).run("read")
        assertTrue(r.toolResults.isNotEmpty() && r.toolResults.all { it.isError })
    }
    @Test fun launchSettlesAndObservesBeforeSecondModelRequest() = runBlocking {
        val p = FakeProvider()
        val events = mutableListOf<String>()
        p.response = { c -> events += "tool:${c.name}"; if (c.name == "get_ui_tree") result(screen) else result() }
        var decisions = 0
        val session = FixtureSession(listOf(FixtureDecision(plan = listOf("Open browser"), toolCalls = listOf(open)),
            FixtureDecision(completion = StepCompletion("s1", 2, "com.android.chrome"))))
        val engine = AgentExecutor(p, { request ->
            events += "model:${++decisions}"
            session.respond(request)
        }, { "instructions" }, settle = { events += "settle:$it" }, log = { events += "log:$it" })
        assertEquals("com.android.chrome", engine.run("open browser").answer)
        assertEquals(listOf("model:1", "model:2", "tool:open_app", "settle:open_app", "tool:get_ui_tree", "model:3"), events.filterNot { it.startsWith("log:") })
        assertTrue(events.any { it.contains("WAITING_FOR_UI_SETTLE -> OBSERVING") })
    }
    @Test fun repeatedOriginalPlanDoesNotRestartOrReopenAndStopsBoundedly() = runBlocking {
        val p = FakeProvider()
        val requests = mutableListOf<List<ChatMessage>>()
        val r = executor(p, List(10) { FixtureDecision(plan = listOf("Open browser", "Search"), toolCalls = listOf(open)) }, requests = requests).run("search")
        assertEquals(listOf("open_app", "get_ui_tree"), p.calls.map { it.name })
        assertEquals(5, r.stepsUsed)
        assertTrue(r.answer.contains("not making verified progress"))
        requests.drop(1).forEach { request ->
            val state = Json.parseToJsonElement(request.last().content).jsonObject
            assertEquals(2, state.getValue("goals").jsonArray.size)
            request.filter { it.role == "assistant" }.forEach {
                assertTrue(Json.parseToJsonElement(it.content).jsonObject.getValue("plan").jsonArray.isEmpty())
            }
        }
    }
    @Test fun observationFailuresRetryWithoutModelAndStopSafely() = runBlocking {
        val p = FakeProvider().apply { response = { if (it.name == "get_ui_tree") result("Accessibility unavailable", true) else result() } }
        val requests = mutableListOf<List<ChatMessage>>()
        val r = executor(p, listOf(FixtureDecision(plan = listOf("Open browser"), toolCalls = listOf(open))), requests = requests).run("open")
        assertEquals(listOf("open_app", "get_ui_tree", "get_ui_tree"), p.calls.map { it.name })
        assertEquals(1, requests.size)
        assertTrue(r.answer.contains("usable screen"))
    }
    @Test fun latestObservationSupersedesOlderEvidenceEvenWithoutAgentMutation() {
        val m = AgentStateMachine("read")
        m.startPlanning(); m.onPlanCreated(listOf("Read"))
        m.startToolExecution(observe); m.onToolResult(result(screen))
        m.startPlanning(); m.startToolExecution(observe)
        m.onToolResult(result("""{"package":"com.android.chrome","tree":{"text":"Offline"}}"""))
        m.startPlanning()
        assertFalse(m.completeCurrentStep(StepCompletion("s1", 1, "Weather 24 C")))
    }
    @Test fun missingObservationCapabilityFailsBeforeUnobservableLaunch() = runBlocking {
        val p = FakeProvider()
        val noObserver = object : ToolProvider {
            override suspend fun initialize() = p.initialize()
            override suspend fun listTools() = p.listTools().filter { it.name != "get_ui_tree" }
            override suspend fun callTool(name: String, arguments: JsonObject) = p.callTool(name, arguments)
        }
        val session = FixtureSession(listOf(FixtureDecision(plan = listOf("Open"), toolCalls = listOf(open))))
        val engine = AgentExecutor(noObserver, session::respond, { "" }, settle = {})
        val r = engine.run("open")
        assertTrue(p.calls.isEmpty())
        assertTrue(r.answer.contains("Fresh screen observation is unavailable"))
    }
    @Test fun structuredScreenOutputIsUsedAndStructuredErrorsAreNotSuccess() = runBlocking {
        val p = FakeProvider().apply { response = { McpToolResult("", false, buildJsonObject {
            put("structuredContent", Json.parseToJsonElement(screen))
        }) } }
        val r = executor(p, listOf(FixtureDecision(plan = listOf("Read"), toolCalls = listOf(observe)),
            FixtureDecision(completion = StepCompletion("s1", 1, "Weather 24 C"), final = "24 C"))).run("read")
        assertTrue(r.answer.contains("24 C"))
        p.response = { McpToolResult(screen, false, buildJsonObject { put("isError", true) }) }
        val failed = executor(p, listOf(FixtureDecision(plan = listOf("Read"), toolCalls = listOf(observe))), ExecutionLimits(maxTurns = 1)).run("read")
        assertTrue(failed.toolResults.isNotEmpty() && failed.toolResults.all { it.isError })
    }
    @Test fun observationStatesRejectUiActionUntilObservationFinishes() {
        val m = AgentStateMachine("open")
        m.startPlanning(); m.onPlanCreated(listOf("Open"))
        m.startToolExecution(open); m.onToolResult(result())
        m.startUiSettling()
        try { m.startPlanning(); fail("Planning during settle") } catch (_: IllegalStateException) {}
        m.onUiSettled()
        assertTrue(m.requiresObservation())
        try { m.startToolExecution(type); fail("UI action before observation") } catch (_: IllegalStateException) {}
        m.startToolExecution(observe); m.onToolResult(result(screen))
        assertFalse(m.requiresObservation())
        m.startPlanning()
    }
}

