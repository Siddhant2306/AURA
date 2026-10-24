package com.example.phone_agent.agent

import com.example.phone_agent.agent.core.*
import com.example.phone_agent.agent.mcp.ToolProvider
import com.example.phone_agent.agent.model.*
import com.example.phone_agent.mcp.ToolRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Deterministic model/phone substitutes exercise the real executor with real registry schemas. */
class DynamicAgentTest {
    private val weather = "Bengaluru Today 24 C Clear"
    private val query = "today's weather Bengaluru"
    @Test fun capturedForegroundConditionAdvancesToSearchWithoutAnotherLaunchGoalDecision() = runBlocking {
        val phone = BrowserPhone(false)
        val seen = mutableListOf<String>()
        val logs = mutableListOf<String>()
        val result = AgentExecutor(phone, { messages ->
            assertFalse(phone.pendingObservation)
            val state = Json.parseToJsonElement(messages.last().content).jsonObject
            if (state.getValue("request_phase").jsonPrimitive.content == "plan") {
                wire(AgentPlan(AgentResponseType.PLAN, goals = listOf(
                    AgentGoal("Open Chrome app", "com.android.chrome is the current foreground package"),
                    AgentGoal("Tap search field", "The field is focused"),
                    AgentGoal("Type query", "The field contains the query"),
                    AgentGoal("Submit query", "Actual results are displayed"),
                )))
            } else {
                val goal = state.getValue("current_goal").jsonPrimitive.content
                seen += goal
                val observation = state["ui_observation"] as? JsonObject
                val node = observation?.getValue("tree")?.jsonObject?.getValue("tree")?.jsonObject
                fun act(name: String, args: JsonObject = buildJsonObject {}, expected: UiExpectation? = null) =
                    wire(AgentPlan(AgentResponseType.ACT, goalId = goal, action = AgentToolCall(name, args, expected)))
                fun complete(text: String) = wire(AgentPlan(AgentResponseType.COMPLETE, goalId = goal,
                    evidence = CompletionEvidence(observation!!.getValue("action_id").jsonPrimitive.int, text)))
                when (goal) {
                    "s1" -> {
                        assertNull("Should not ask model to complete an explicitly verified launch goal", observation)
                        act("open_app", buildJsonObject { put("package_name", "com.android.chrome") })
                    }
                    "s2" -> if (node?.get("focused")?.jsonPrimitive?.boolean == true) complete("Search or type URL")
                        else act("tap_by_text", buildJsonObject { put("query", "Search or type URL") }, UiExpectation(focusedEditable = true))
                    "s3" -> if (node?.get("text")?.jsonPrimitive?.content == query) complete(query)
                        else act("type_text", buildJsonObject { put("text", query) })
                    else -> if (node?.get("text")?.jsonPrimitive?.content == weather) complete(weather) else act("press_enter")
                }
            }
        }, { "" }, settle = {}, log = { logs += it }).run("Open Chrome, tap search, type today's weather and press Enter")
        assertFalse(result.answer, result.answer.startsWith("I couldn't finish"))
        assertTrue(result.answer.contains(weather))
        assertEquals(1, seen.count { it == "s1" })
        assertEquals(listOf("open_app", "get_ui_tree", "tap_by_text", "get_ui_tree", "type_text", "get_ui_tree", "press_enter", "get_ui_tree"), phone.calls.map { it.name })
        assertTrue(logs.any { it.contains("foreground goal verified by Kotlin") })
    }
    @Test fun rawOllamaOpenChromeDecisionReachesProviderAndObservationWithoutRetry() = runBlocking {
        val calls = mutableListOf<String>()
        val logs = mutableListOf<String>()
        val provider = object : ToolProvider {
            override suspend fun initialize() {}
            override suspend fun listTools() = ToolRegistry.tools.map { McpTool(it.name, it.description, it.inputSchema) }
            override suspend fun callTool(name: String, arguments: JsonObject): McpToolResult {
                calls += name
                if (name == "open_app") assertEquals("com.android.chrome", arguments.getValue("package_name").jsonPrimitive.content)
                return McpToolResult(if (name == "get_ui_tree") """{"package":"com.android.chrome","tree":{"text":"Chrome"}}""" else "Opened", false, buildJsonObject {})
            }
        }
        val responses = listOf(
            """{"type":"plan","goals":[{"description":"Open Chrome","success_condition":"Chrome is foreground"}]}""",
            """{"type":"act","goal_id":"s1","action":{"name":"open_app","arguments":{"package_name":"com.android.chrome"}}}""",
            """{"type":"complete","goal_id":"s1","evidence":{"action_id":2,"text":"com.android.chrome"}}""",
        )
        var request = 0
        val answer = AgentExecutor(provider, {
            val content = responses[request++]
            OllamaOutput.parse(buildJsonObject {
                put("done", true); put("done_reason", "stop")
                put("message", buildJsonObject { put("role", "assistant"); put("content", content) })
            }.toString())
        }, { "" }, settle = {}, log = { logs += it }).run("Open Chrome")
        assertEquals("com.android.chrome", answer.answer)
        assertEquals(3, request)
        assertEquals(listOf("open_app", "get_ui_tree"), calls)
        assertFalse(logs.any { it.contains("PARSE_FAILURE") || it.startsWith("REJECTED") })
        for (marker in listOf("SELECTED_TOOL", "TOOL_RESULT", "UI_OBSERVATION", "GOAL_COMPLETION"))
            assertTrue(marker, logs.any { it.contains(marker) })
    }
    private inner class BrowserPhone(val labeledField: Boolean, val failFirstTap: Boolean = false) : ToolProvider {
        val calls = mutableListOf<AgentToolCall>()
        var opened = false
        var focused = false
        var value = ""
        var submitted = false
        var pendingObservation = false
        var settles = 0
        override suspend fun initialize() {}
        override suspend fun listTools() = ToolRegistry.tools.map { McpTool(it.name, it.description, it.inputSchema) }
        override suspend fun callTool(name: String, arguments: JsonObject): McpToolResult {
            assertFalse("Another tool executed before mandatory observation", pendingObservation && name != "get_ui_tree")
            calls += AgentToolCall(name, arguments)
            if (name == "get_ui_tree") {
                assertTrue(opened)
                pendingObservation = false
                val tree = buildJsonObject {
                    put("package", "com.android.chrome")
                    put("tree", buildJsonObject {
                        if (submitted) put("text", weather)
                        else {
                            put("desc", if (labeledField) "Search the web" else "Search or type URL")
                            put("text", value); put("editable", true); put("focused", focused)
                            put("clickable", true); put("bounds", buildJsonArray { listOf(0, 0, 1080, 140).forEach { add(it) } })
                        }
                    })
                }
                return McpToolResult(tree.toString(), false, buildJsonObject {})
            }
            pendingObservation = true
            when (name) {
                "open_app" -> { assertEquals("com.android.chrome", arguments["package_name"]?.jsonPrimitive?.content); opened = true }
                "tap_by_text" -> {
                    assertEquals("Search or type URL", arguments["query"]?.jsonPrimitive?.content)
                    if (failFirstTap) return McpToolResult("No clickable element matched the query", true, buildJsonObject {})
                    focused = true
                }
                "type_text" -> { assertTrue(focused); value += arguments.getValue("text").jsonPrimitive.content }
                "type_into_field_near" -> {
                    assertEquals(if (labeledField) "Search the web" else "Search or type URL", arguments["query"]?.jsonPrimitive?.content)
                    value = arguments.getValue("text").jsonPrimitive.content; focused = true
                }
                "press_enter" -> { assertEquals(query, value); assertTrue(focused); submitted = true }
                else -> fail("Unexpected tool $name")
            }
            return McpToolResult("Tool receipt", false, buildJsonObject {})
        }
    }
    private suspend fun runWeather(phone: BrowserPhone): AgentRunResult {
        var planningRequests = 0
        val model: suspend (List<ChatMessage>) -> String = { messages ->
            assertFalse("Model saw an unobserved mutation", phone.pendingObservation)
            assertEquals(3, messages.size)
            val state = Json.parseToJsonElement(messages.last().content).jsonObject
            val supplied = state.getValue("available_tools").jsonArray.associate { it.jsonObject.getValue("name").jsonPrimitive.content to it.jsonObject.getValue("input_schema") }
            supplied.forEach { (name, schema) -> assertEquals(ToolRegistry.tools.first { it.name == name }.inputSchema, schema) }
            if (state.getValue("request_phase").jsonPrimitive.content == "plan") {
                planningRequests++
                wire(AgentPlan(AgentResponseType.PLAN, goals = listOf(
                    AgentGoal("Chrome is available", "Chrome is in the foreground", UiExpectation(packageName = "com.android.chrome")),
                    AgentGoal("Read today's weather", "Actual location, day and temperature are visible"),
                )))
            } else {
                val goal = state.getValue("current_goal").jsonPrimitive.content
                val observation = state["ui_observation"] as? JsonObject
                fun act(name: String, args: JsonObject = buildJsonObject {}, expected: UiExpectation? = null, recovery: String? = null) =
                    wire(AgentPlan(AgentResponseType.ACT, goalId = goal, action = AgentToolCall(name, args, expected, recovery)))
                fun complete(text: String) = wire(AgentPlan(AgentResponseType.COMPLETE, goalId = goal,
                    evidence = CompletionEvidence(observation!!.getValue("action_id").jsonPrimitive.int, text)))
                if (observation == null) act("open_app", buildJsonObject { put("package_name", "com.android.chrome") })
                else {
                    assertTrue(observation.getValue("fresh").jsonPrimitive.boolean)
                    assertEquals("ToolProvider", observation.getValue("source").jsonPrimitive.content)
                    val actual = observation.getValue("tree").jsonObject
                    val node = actual.getValue("tree").jsonObject
                    val text = node["text"]?.jsonPrimitive?.content.orEmpty()
                    val label = node["desc"]?.jsonPrimitive?.content.orEmpty()
                    val failed = state.getValue("recent_results").jsonArray.any {
                        it.jsonObject["tool"]?.jsonPrimitive?.content == "tap_by_text" && !it.jsonObject.getValue("receipt_success").jsonPrimitive.boolean
                    }
                    when {
                        goal == "s1" -> complete(actual.getValue("package").jsonPrimitive.content)
                        text == weather -> complete(text)
                        text == query -> act("press_enter")
                        node["focused"]?.jsonPrimitive?.boolean == true -> act("type_text", buildJsonObject { put("text", query) })
                        label == "Search the web" || failed -> act("type_into_field_near", buildJsonObject { put("query", label); put("text", query) },
                            recovery = if (failed) "Actual tap result reported no match; use the observed editable field label" else null)
                        else -> act("tap_by_text", buildJsonObject { put("query", label) }, UiExpectation(focusedEditable = true))
                    }
                }
            }
        }
        val answer = AgentExecutor(phone, model, { "Typed response contract" }, settle = { phone.settles++ }).run("Open Chrome and check today's weather")
        assertEquals(1, planningRequests)
        assertEquals("com.android.chrome\n$weather", answer.answer)
        assertFalse(answer.answer.contains("99 C"))
        val mutations = phone.calls.filter { it.name != "get_ui_tree" }
        assertEquals(mutations.size, phone.settles)
        assertEquals(mutations.size, phone.calls.count { it.name == "get_ui_tree" })
        phone.calls.forEachIndexed { i, call -> if (call.name != "get_ui_tree") assertEquals("get_ui_tree", phone.calls[i + 1].name) }
        assertEquals(1, phone.calls.count { it.name == "open_app" })
        return answer
    }
    @Test fun weatherUsesObservedAddressBarToChooseTapThenTypeThenSubmit() = runBlocking {
        val phone = BrowserPhone(false)
        runWeather(phone)
        assertEquals(listOf("open_app", "tap_by_text", "type_text", "press_enter"), phone.calls.filter { it.name != "get_ui_tree" }.map { it.name })
    }
    @Test fun weatherAdaptsToDifferentScreenByChoosingLabeledFieldTool() = runBlocking {
        val phone = BrowserPhone(true)
        runWeather(phone)
        assertEquals(listOf("open_app", "type_into_field_near", "press_enter"), phone.calls.filter { it.name != "get_ui_tree" }.map { it.name })
    }
    @Test fun failedTapRecoversUsingRealErrorAndNewScreenInsteadOfRepeatingTap() = runBlocking {
        val phone = BrowserPhone(false, failFirstTap = true)
        val result = runWeather(phone)
        assertEquals(1, result.toolResults.count { it.isError })
        assertEquals(listOf("open_app", "tap_by_text", "type_into_field_near", "press_enter"), phone.calls.filter { it.name != "get_ui_tree" }.map { it.name })
    }
    @Test fun malformedAndTruncatedResponsesRetrySamePhaseWithoutExecutingTools() = runBlocking {
        val provider = BrowserPhone(false)
        val requests = mutableListOf<JsonObject>()
        var attempt = 0
        val result = AgentExecutor(provider, { messages ->
            val state = Json.parseToJsonElement(messages.last().content).jsonObject
            requests += state
            when (++attempt) {
                1 -> throw InvalidLlmResponseException("Truncated Ollama output")
                2 -> "{}"
                3 -> wire(AgentPlan(AgentResponseType.PLAN, goals = listOf(AgentGoal("Read", "Read actual result"))))
                4 -> """{"type":"act","goal_id":"s1","action":{"name":"get_ui_tree"}}"""
                else -> wire(AgentPlan(AgentResponseType.STOP, reason = "No action performed"))
            }
        }, { "Full original system prompt" }, limits = ExecutionLimits(maxJsonRetries = 99), settle = {}).run("read")
        assertTrue(result.answer.contains("Repeated invalid model responses"))
        assertTrue(provider.calls.isEmpty())
        assertEquals(listOf("plan", "plan"), requests.map { it.getValue("request_phase").jsonPrimitive.content })
        assertEquals(listOf(0, 1), requests.map { it.getValue("response_retry").jsonPrimitive.int })
        assertEquals("Return only the plan object matching the required schema. Do not repeat the input state.", requests[1].getValue("next_decision").jsonPrimitive.content)
        assertFalse(requests[1].containsKey("available_tools"))
    }
    @Test fun oneCompactPlanningRetryCanSucceedAndDecisionRetriesAreAlsoBounded() = runBlocking {
        val provider = BrowserPhone(false)
        val requests = mutableListOf<List<ChatMessage>>()
        val result = AgentExecutor(provider, { messages ->
            requests += messages
            when (requests.size) {
                1 -> "{}"
                2 -> wire(AgentPlan(AgentResponseType.PLAN, goals = listOf(AgentGoal("Read", "Read actual result"))))
                else -> "{}"
            }
        }, { "Original system prompt with tool rules" }, settle = {}).run("read")
        assertEquals(4, result.stepsUsed)
        assertTrue(result.answer.contains("Repeated invalid model responses"))
        assertFalse(requests[1].any { it.content.contains("Original system prompt") || it.content.contains("available_tools") })
        val decision = Json.parseToJsonElement(requests[2].last().content).jsonObject
        assertEquals("s1", decision.getValue("current_goal").jsonPrimitive.content)
        assertTrue(provider.calls.isEmpty())
    }
    @Test fun realSchemasRejectUnknownToolsMissingArgumentsWrongTypesAndExtraArguments() = runBlocking {
        for (bad in listOf(AgentToolCall("invented"), AgentToolCall("tap"),
            AgentToolCall("tap", buildJsonObject { put("x", "5"); put("y", 4) }),
            AgentToolCall("get_ui_tree", buildJsonObject { put("fake_ui", "weather 99 C") }))) {
            val provider = BrowserPhone(false)
            var first = true
            val answer = AgentExecutor(provider, {
                if (first) { first = false; wire(AgentPlan(AgentResponseType.PLAN, goals = listOf(AgentGoal("Read", "Read weather")))) }
                else wire(AgentPlan(AgentResponseType.ACT, goalId = "s1", action = bad))
            }, { "" }, settle = {}).run("read")
            assertTrue(answer.answer.startsWith("I couldn't finish"))
            assertTrue(provider.calls.isEmpty())
        }
    }
    @Test fun readOnlyGoalCompletesFromActualResultWithoutRequiringAnUnrelatedUiTree() = runBlocking {
        val calls = mutableListOf<String>()
        val tool = McpTool("read_status", "Read status", buildJsonObject {
            put("type", "object"); put("properties", buildJsonObject {}); put("additionalProperties", false)
        })
        val provider = object : ToolProvider {
            override suspend fun initialize() {}
            override suspend fun listTools() = listOf(tool)
            override suspend fun callTool(name: String, arguments: JsonObject): McpToolResult {
                calls += name
                return McpToolResult("Battery is 80 percent", false, buildJsonObject {})
            }
        }
        var turn = 0
        val result = AgentExecutor(provider, {
            when (++turn) {
                1 -> wire(AgentPlan(AgentResponseType.PLAN, goals = listOf(AgentGoal("Read battery", "Actual battery status is returned"))))
                2 -> wire(AgentPlan(AgentResponseType.ACT, goalId = "s1", action = AgentToolCall("read_status")))
                else -> wire(AgentPlan(AgentResponseType.COMPLETE, goalId = "s1", evidence = CompletionEvidence(1, "Battery is 80 percent")))
            }
        }, { "" }).run("Read battery")
        assertEquals("Battery is 80 percent", result.answer)
        assertEquals(listOf("read_status"), calls)
    }
    @Test fun quotedFabricationOrMutationReceiptCannotAdvanceToNextGoal() = runBlocking {
        for (evidence in listOf(CompletionEvidence(2, "Invented weather 99 C"), CompletionEvidence(1, "Tool receipt"))) {
            val phone = BrowserPhone(false)
            var turn = 0
            val result = AgentExecutor(phone, {
                when (++turn) {
                    1 -> wire(AgentPlan(AgentResponseType.PLAN, goals = listOf(AgentGoal("Open Chrome", "Chrome is foreground"), AgentGoal("Read weather", "Actual weather observed"))))
                    2 -> wire(AgentPlan(AgentResponseType.ACT, goalId = "s1", action = AgentToolCall("open_app", buildJsonObject { put("package_name", "com.android.chrome") })))
                    else -> wire(AgentPlan(AgentResponseType.COMPLETE, goalId = "s1", evidence = evidence))
                }
            }, { "" }, settle = {}).run("Open Chrome and read weather")
            assertTrue(result.answer.startsWith("I couldn't finish"))
            assertTrue(result.answer.contains("Remaining: Open Chrome, Read weather"))
            assertEquals(listOf("open_app", "get_ui_tree"), phone.calls.map { it.name })
        }
    }
    @Test fun severalUnpredictedScreenChangesAllowNavigationButDoNotCompleteTheGoal() = runBlocking {
        var page = 0
        val calls = mutableListOf<String>()
        val provider = object : ToolProvider {
            override suspend fun initialize() {}
            override suspend fun listTools() = ToolRegistry.tools.map { McpTool(it.name, it.description, it.inputSchema) }
            override suspend fun callTool(name: String, arguments: JsonObject): McpToolResult {
                calls += name
                if (name == "tap") page++
                return McpToolResult(if (name == "get_ui_tree") """{"tree":{"text":"Page $page","clickable":true}}""" else "Tapped", false, buildJsonObject {})
            }
        }
        val result = AgentExecutor(provider, { messages ->
            val state = Json.parseToJsonElement(messages.last().content).jsonObject
            if (state.getValue("request_phase").jsonPrimitive.content == "plan")
                wire(AgentPlan(AgentResponseType.PLAN, goals = listOf(AgentGoal("Reach details", "Page 5 is visible"))))
            else if (state["ui_observation"] == null)
                wire(AgentPlan(AgentResponseType.ACT, goalId = "s1", action = AgentToolCall("get_ui_tree")))
            else {
                val observation = state.getValue("ui_observation").jsonObject
                val actualPage = observation.getValue("tree").jsonObject.getValue("tree").jsonObject.getValue("text").jsonPrimitive.content.substringAfter("Page ").toInt()
                if (actualPage == 5) wire(AgentPlan(AgentResponseType.COMPLETE, goalId = "s1", evidence = CompletionEvidence(observation.getValue("action_id").jsonPrimitive.int, "Page 5")))
                else wire(AgentPlan(AgentResponseType.ACT, goalId = "s1", action = AgentToolCall("tap", buildJsonObject { put("x", actualPage + 1); put("y", 20) })))
            }
        }, { "" }, settle = {}).run("Reach page 5")
        assertEquals("Page 5", result.answer)
        assertEquals(5, calls.count { it == "tap" })
        assertEquals(6, calls.count { it == "get_ui_tree" })
    }
    @Test fun failedGenericActionCanRetryOnlyAfterFreshObservation() {
        val machine = AgentStateMachine("Tap")
        val screen = McpToolResult("""{"tree":{"text":"Ready","clickable":true}}""", false, buildJsonObject {})
        val tap = AgentToolCall("tap", buildJsonObject { put("x", 10); put("y", 20) })
        machine.startPlanning(); machine.onPlanCreated(listOf("Tap control"))
        machine.startToolExecution(AgentToolCall("get_ui_tree")); machine.onToolResult(screen); machine.startPlanning()
        machine.startToolExecution(tap); machine.onToolResult(McpToolResult("Gesture rejected", true, buildJsonObject {}))
        try { machine.startPlanning(); fail("Failed action bypassed observation") } catch (_: IllegalStateException) {}
        machine.onUiSettled(); machine.startToolExecution(AgentToolCall("get_ui_tree")); machine.onToolResult(screen)
        assertFalse(machine.duplicate(tap))
        machine.startPlanning(); machine.startToolExecution(tap)
    }
    @Test fun currentTreeIncludesEvidenceBeyondHistorySummaryWithoutReplayingOldScreens() = runBlocking {
        val tail = "Actual final weather result 24 C"
        val tree = buildJsonObject { put("tree", buildJsonObject { put("children", buildJsonArray {
            repeat(400) { add(buildJsonObject { put("text", "Earlier node $it") }) }
            add(buildJsonObject { put("text", tail) })
        }) }) }.toString()
        val provider = object : ToolProvider {
            override suspend fun initialize() {}
            override suspend fun listTools() = ToolRegistry.tools.map { McpTool(it.name, it.description, it.inputSchema) }
            override suspend fun callTool(name: String, arguments: JsonObject) = McpToolResult(tree, false, buildJsonObject {})
        }
        var turn = 0
        val result = AgentExecutor(provider, { messages ->
            when (++turn) {
                1 -> wire(AgentPlan(AgentResponseType.PLAN, goals = listOf(AgentGoal("Read weather", "Actual result visible"))))
                2 -> wire(AgentPlan(AgentResponseType.ACT, goalId = "s1", action = AgentToolCall("get_ui_tree")))
                else -> {
                    assertEquals(1, Regex(Regex.escape(tail)).findAll(messages.joinToString { it.content }).count())
                    wire(AgentPlan(AgentResponseType.COMPLETE, goalId = "s1", evidence = CompletionEvidence(1, tail)))
                }
            }
        }, { "" }).run("Read weather")
        assertEquals(tail, result.answer)
    }
}
