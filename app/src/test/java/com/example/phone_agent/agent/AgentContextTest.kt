package com.example.phone_agent.agent

import com.example.phone_agent.agent.core.*
import com.example.phone_agent.agent.model.*
import com.example.phone_agent.agent.mcp.ToolProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentContextTest {
    @Test fun usableSmallLaunchScreenOffersActionsInsteadOfRedundantReads() {
        val m = AgentStateMachine("Search weather")
        m.startPlanning(); m.onPlanCreated(listOf("Search"), successConditions = listOf("Weather results appear"))
        m.startToolExecution(AgentToolCall("open_app", buildJsonObject { put("package_name", "com.android.chrome") }))
        m.onToolResult(McpToolResult("Opened", false, buildJsonObject {})); m.onUiSettled()
        m.startToolExecution(AgentToolCall("get_ui_tree"))
        m.onToolResult(McpToolResult("""{"package":"com.android.chrome","tree":{"text":"about:blank","editable":true}}""", false, buildJsonObject {})); m.startPlanning()
        val provider = object : ToolProvider {
            override suspend fun initialize() = error("Not used")
            override suspend fun listTools(): List<McpTool> = error("Not used")
            override suspend fun callTool(name: String, arguments: JsonObject): McpToolResult = error("Not used")
        }
        val tools = listOf(McpTool("get_ui_tree", "", buildJsonObject {}), McpTool("tap_by_text", "", buildJsonObject {}))
        val state = Json.parseToJsonElement(AgentExecutor(provider, { error("Not used") }, { "" }).statePrompt(m, tools, false, "Choose", 0, false)).jsonObject
        val available = state.getValue("available_tools").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }
        assertEquals(listOf("tap_by_text"), available)
        assertFalse(m.requiresObservation())
    }
    @Test fun cachedInspectionAfterRepeatedReadsDoesNotTripPhoneReadLoopGuard() = runBlocking {
        val tree = """{"tree":{"class":"OuterLayout","children":[{"text":"Today 24 C"}]}}"""
        var calls = 0
        val provider = object : ToolProvider {
            override suspend fun initialize() {}
            override suspend fun listTools() = listOf(McpTool("get_ui_tree", "", buildJsonObject {}))
            override suspend fun callTool(name: String, arguments: JsonObject): McpToolResult {
                calls++; return McpToolResult(tree, false, buildJsonObject {})
            }
        }
        var request = 0
        val result = AgentExecutor(provider, { messages ->
            when (++request) {
                1 -> wire(AgentPlan(AgentResponseType.PLAN, goals = listOf(AgentGoal("Read weather", "Weather value is visible"))))
                2 -> wire(AgentPlan(AgentResponseType.ACT, goalId = "s1", action = AgentToolCall("get_ui_tree")))
                3, 4 -> wire(AgentPlan(AgentResponseType.ACT, goalId = "s1", action = AgentToolCall("get_ui_tree", recoveryReason = "page_loading")))
                5, 6 -> wire(AgentPlan(AgentResponseType.ACT, goalId = "s1", action = AgentToolCall("get_ui_tree", recoveryReason = "expand_cached_observation")))
                else -> {
                    assertTrue(messages.last().content.contains("OuterLayout"))
                    val finalState = Json.parseToJsonElement(messages.last().content).jsonObject
                    assertTrue(finalState.getValue("final_assessment").jsonPrimitive.boolean)
                    assertEquals("Today 24 C", finalState.getValue("ui_observation").jsonObject.getValue("readable_result_text").jsonPrimitive.content)
                    wire(AgentPlan(AgentResponseType.COMPLETE, goalId = "s1", evidence = CompletionEvidence(3, "Today 24 C")))
                }
            }
        }, { "" }, settle = {}).run("Read weather")
        assertEquals("Today 24 C", result.answer)
        assertEquals(3, calls)
    }
    @Test fun alreadyCorrectFocusedInputCompletesWithoutAppendingAgain() {
        val m = AgentStateMachine("Type weather")
        m.startPlanning(); m.onPlanCreated(listOf("Type weather"), successConditions = listOf("editable field contains: weather"))
        m.startToolExecution(AgentToolCall("get_ui_tree"))
        m.onToolResult(McpToolResult("""{"tree":{"text":"weather","editable":true,"focused":true}}""", false, buildJsonObject {}))
        m.startPlanning()
        assertTrue(m.completeVerifiedUiGoal())
        assertEquals(1, m.state.actions.size)
        val corrupted = """{"tree":{"text":"weatherweather","editable":true,"focused":true}}"""
        assertNotNull(ToolExecutionPolicy.preconditionError("type_text", corrupted, "weather"))
        val partial = """{"tree":{"text":"wea","editable":true,"focused":true}}"""
        assertNull(ToolExecutionPolicy.preconditionError("type_text", partial, "weather"))
    }
    @Test fun tapExpectationIdentifiesOneInputAmongMultipleEditableFields() {
        val screen = UiObservation.parse("""{"tree":{"children":[{"text":"google.com/search?q=weather","editable":true},{"text":"today's weather","editable":true,"bounds":[10,20,200,80]}]}}""")!!
        assertTrue(screen.tapsOnlyEditable(AgentToolCall("tap_by_text", buildJsonObject { put("query", "today's weather") })))
        assertFalse(screen.tapsOnlyEditable(AgentToolCall("tap_by_text", buildJsonObject { put("query", "weather") })))
        assertTrue(screen.tapsOnlyEditable(AgentToolCall("tap", buildJsonObject { put("x", 50); put("y", 40) })))
    }
    @Test fun largeObservationPagesAreBoundedAndPreserveInputAndOmittedEvidence() {
        val root = buildJsonObject { put("package", "com.android.chrome"); put("tree", buildJsonObject { put("children", buildJsonArray {
            repeat(120) { i -> add(buildJsonObject { put("text", "Other result $i " + "detail ".repeat(20)); put("class", "TextView") }) }
            add(buildJsonObject { put("text", "google.com/search"); put("editable", true); put("bounds", buildJsonArray { listOf(0, 0, 100, 50).forEach { add(it) } }) })
            add(buildJsonObject { put("text", "Weather Today 24 C Clear"); put("class", "TextView") })
        }) }) }
        val observation = UiObservation.parse(root.toString())!!
        val first = observation.decisionTree("Focus search field for weather")
        assertTrue(first.toString().length <= 7_000)
        assertTrue(first.toString().contains("google.com/search"))
        assertTrue(first.toString().contains("Weather Today 24 C Clear"))
        val pages = first.getValue("page_count").jsonPrimitive.int
        assertTrue(pages > 1)
        val all = (0 until pages).map { observation.decisionTree("Focus search field for weather", it) }
        assertTrue(all.all { it.toString().length <= 7_000 })
        assertTrue(all.joinToString().contains("Other result 0"))
        assertTrue(all.joinToString().contains("Other result 119"))
        assertTrue("Full evidence remains authoritative", observation.containsEvidence("Other result 119"))
    }
    @Test fun inputPreconditionsRequireEditableFocusNotLayoutFocus() {
        val screen = """{"tree":{"focused":true,"children":[{"text":"Search","editable":true}]}}"""
        assertNotNull(ToolExecutionPolicy.preconditionError("press_enter", screen))
        assertNotNull(ToolExecutionPolicy.preconditionError("type_text", screen))
        assertNull(ToolExecutionPolicy.preconditionError("tap_by_text", screen))
        assertNull(ToolExecutionPolicy.preconditionError("press_enter", screen.replace("\"editable\":true", "\"editable\":true,\"focused\":true")))
    }
    @Test fun optionalDialogRecoveryObservesEachActionAndDoesNotRepeatEnter() = runBlocking {
        var page = 0
        val calls = mutableListOf<String>()
        val provider = object : ToolProvider {
            override suspend fun initialize() {}
            override suspend fun listTools() = com.example.phone_agent.mcp.ToolRegistry.tools.map { McpTool(it.name, it.description, it.inputSchema) }
            override suspend fun callTool(name: String, arguments: JsonObject): McpToolResult {
                calls += name
                if (name == "press_enter") page = 1
                if (name == "tap_by_text") { assertEquals("Not now", arguments.getValue("query").jsonPrimitive.content); page = 2 }
                val text = when (page) {
                    0 -> """{"tree":{"text":"weather","editable":true,"focused":true}}"""
                    1 -> """{"tree":{"children":[{"text":"Use precise location","clickable":true},{"text":"Not now","clickable":true},{"text":"Search","editable":true}]}}"""
                    else -> """{"tree":{"text":"Weather Today 24 C"}}"""
                }
                return McpToolResult(if (name == "get_ui_tree") text else "Receipt", false, buildJsonObject {})
            }
        }
        var turn = 0
        val result = AgentExecutor(provider, { messages ->
            val state = Json.parseToJsonElement(messages.last().content).jsonObject
            when (++turn) {
                1 -> wire(AgentPlan(AgentResponseType.PLAN, goals = listOf(AgentGoal("Read results", "Weather results are visible"))))
                2 -> wire(AgentPlan(AgentResponseType.ACT, goalId = "s1", action = AgentToolCall("get_ui_tree")))
                3 -> wire(AgentPlan(AgentResponseType.ACT, goalId = "s1", action = AgentToolCall("press_enter")))
                4 -> {
                    assertFalse(state.getValue("available_tools").jsonArray.any { it.jsonObject.getValue("name").jsonPrimitive.content == "press_enter" })
                    wire(AgentPlan(AgentResponseType.ACT, goalId = "s1", action = AgentToolCall("press_enter"))) // Runtime rejects even a schema-bypassing model.
                }
                5 -> wire(AgentPlan(AgentResponseType.ACT, goalId = "s1", action = AgentToolCall("tap_by_text", buildJsonObject { put("query", "Not now") }, recoveryReason = "Decline optional location access blocking results")))
                else -> wire(AgentPlan(AgentResponseType.COMPLETE, goalId = "s1", evidence = CompletionEvidence(5, "Weather Today 24 C")))
            }
        }, { "" }, settle = {}, limits = ExecutionLimits(maxRejectedDecisions = 5)).run("Search weather")
        assertEquals("Weather Today 24 C", result.answer)
        assertEquals(listOf("get_ui_tree", "press_enter", "get_ui_tree", "tap_by_text", "get_ui_tree"), calls)
    }
    @Test fun focusedTextMustBelongToTheSameEditableNode() {
        val screen = UiObservation.parse("""{"tree":{"children":[{"text":"weather","editable":true},{"text":"another field","editable":true,"focused":true}]}}""")!!
        assertFalse(screen.matches(UiExpectation(editableText = "weather", focusedEditable = true)))
        assertTrue(screen.matches(UiExpectation(editableText = "another field", focusedEditable = true)))
    }
    @Test fun explicitFocusAndTypingGoalsAdvanceOnlyFromFreshObservedEffects() {
        val machine = AgentStateMachine("Focus and type")
        machine.startPlanning(); machine.onPlanCreated(listOf("Focus field", "Type weather", "Read results"),
            successConditions = listOf("the intended editable field is focused", "editable field contains: weather", "Weather results are visible"))
        fun screen(value: String, focused: Boolean) = McpToolResult(buildJsonObject { put("package", "com.android.chrome"); put("tree", buildJsonObject {
            put("desc", "Search"); put("text", value); put("editable", true); put("focused", focused)
        }) }.toString(), false, buildJsonObject {})
        machine.startToolExecution(AgentToolCall("get_ui_tree")); machine.onToolResult(screen("", false)); machine.startPlanning()
        assertFalse(machine.completeVerifiedUiGoal())
        machine.startToolExecution(AgentToolCall("tap_by_text", buildJsonObject { put("query", "Search") }))
        machine.onToolResult(McpToolResult("Tapped", false, buildJsonObject {}))
        assertFalse(machine.completeVerifiedUiGoal())
        machine.onUiSettled(); machine.startToolExecution(AgentToolCall("get_ui_tree")); machine.onToolResult(screen("", true)); machine.startPlanning()
        assertTrue(machine.completeVerifiedUiGoal())
        machine.startToolExecution(AgentToolCall("type_text", buildJsonObject { put("text", "weather") }))
        machine.onToolResult(McpToolResult("Typed", false, buildJsonObject {})); machine.onUiSettled()
        machine.startToolExecution(AgentToolCall("get_ui_tree")); machine.onToolResult(screen("", true)); machine.startPlanning()
        assertFalse(machine.completeVerifiedUiGoal())
        machine.startToolExecution(AgentToolCall("get_ui_tree")); machine.onToolResult(screen("weather", true)); machine.startPlanning()
        assertTrue(machine.completeVerifiedUiGoal())
        assertFalse("A search-result goal must not complete from typed input", machine.completeVerifiedUiGoal())
        assertEquals(2, machine.state.currentStepIndex)
    }
    @Test fun inputContextIsSerializedIndependentlyOfFixedOutputBudget() {
        val json = Json { encodeDefaults = true }
        val options = json.encodeToJsonElement(OllamaOptions.serializer(), OllamaOptions(num_predict = 128)).jsonObject
        assertEquals(8192, options.getValue("num_ctx").jsonPrimitive.int)
        assertEquals(128, options.getValue("num_predict").jsonPrimitive.int)
    }
    @Test fun phasePromptsDoNotReplayOtherPhaseInstructions() {
        val source = "common\n[PLANNING]\ncreate goals\n[DECISION]\nchoose a tool"
        assertEquals("common\n\ncreate goals", AgentPrompt.forPhase(source, true))
        assertEquals("common\n\nchoose a tool", AgentPrompt.forPhase(source, false))
        assertEquals("custom instructions", AgentPrompt.forPhase("custom instructions", false))
    }
    @Test fun semanticCompactionPreservesAllTextControlsAndFocusWhileRemovingEmptyLayouts() {
        val tree = """{"package":"com.android.chrome","tree":{"class":"FrameLayout","bounds":[0,0,100,200],"children":[{"class":"Layout","children":[{"text":"Search","editable":true,"focused":true,"bounds":[0,0,100,30]},{"text":"Today 24 C Clear"},{"desc":"Submit","clickable":true}]}]}}"""
        val compact = UiObservation.parse(tree)!!.decisionTree()
        assertFalse(compact.toString().contains("FrameLayout"))
        assertTrue(compact.toString().contains("Today 24 C Clear"))
        assertTrue(UiObservation.parse(compact.toString())!!.matches(UiExpectation(focusedEditable = true)))
        assertEquals(3, compact.getValue("tree").jsonObject.getValue("children").jsonArray.size)
    }
    @Test fun fullCachedTreeCanBeInspectedWithoutAnotherPhoneObservation() = runBlocking {
        val tree = """{"tree":{"class":"OuterLayout","children":[{"text":"Result"}]}}"""
        var calls = 0
        val provider = object : ToolProvider {
            override suspend fun initialize() {}
            override suspend fun listTools() = listOf(McpTool("get_ui_tree", "", buildJsonObject {}))
            override suspend fun callTool(name: String, arguments: JsonObject): McpToolResult {
                calls++; return McpToolResult(tree, false, buildJsonObject {})
            }
        }
        var request = 0
        val result = AgentExecutor(provider, { messages ->
            when (++request) {
                1 -> wire(AgentPlan(AgentResponseType.PLAN, goals = listOf(AgentGoal("Read result", "Result is visible"))))
                2 -> wire(AgentPlan(AgentResponseType.ACT, goalId = "s1", action = AgentToolCall("get_ui_tree")))
                3 -> {
                    assertFalse(messages.last().content.contains("OuterLayout"))
                    wire(AgentPlan(AgentResponseType.ACT, goalId = "s1", action = AgentToolCall("get_ui_tree", recoveryReason = "expand_cached_observation")))
                }
                else -> {
                    val state = Json.parseToJsonElement(messages.last().content).jsonObject
                    val observation = state.getValue("ui_observation").jsonObject
                    assertTrue(observation.toString().contains("OuterLayout"))
                    assertEquals(1, observation.getValue("action_id").jsonPrimitive.int)
                    wire(AgentPlan(AgentResponseType.COMPLETE, goalId = "s1", evidence = CompletionEvidence(1, "Result")))
                }
            }
        }, { "" }, settle = {}).run("Read result")
        assertEquals("Result", result.answer)
        assertEquals(1, calls)
    }
    @Test fun visibleInputDoesNotProveTapSucceededEvenIfGoalConditionOnlyMentionsVisibility() {
        val machine = AgentStateMachine("Activate search")
        machine.startPlanning(); machine.onPlanCreated(listOf("Tap search"), mapOf("s1" to UiExpectation(text = "Search")))
        val screen = McpToolResult("""{"tree":{"text":"Search","editable":true,"clickable":true}}""", false, buildJsonObject {})
        machine.startToolExecution(AgentToolCall("get_ui_tree")); machine.onToolResult(screen); machine.startPlanning()
        machine.startToolExecution(AgentToolCall("tap_by_text", buildJsonObject { put("query", "Search") }))
        machine.onToolResult(McpToolResult("Tapped", false, buildJsonObject {})); machine.onUiSettled()
        machine.startToolExecution(AgentToolCall("get_ui_tree")); machine.onToolResult(screen); machine.startPlanning()
        assertFalse(machine.completeCurrentStep(StepCompletion("s1", 3, "Search")))
        assertEquals(false, machine.state.actions.first { it.call.name == "tap_by_text" }.verified)
    }
}
