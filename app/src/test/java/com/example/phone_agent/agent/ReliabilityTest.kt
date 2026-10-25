package com.example.phone_agent.agent

import com.example.phone_agent.agent.core.*
import com.example.phone_agent.agent.model.*
import com.example.phone_agent.service.GestureBounds
import com.example.phone_agent.mcp.ToolRegistry
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import com.example.phone_agent.agent.mcp.ToolProvider
import kotlinx.coroutines.runBlocking

class ReliabilityTest {
    @Test fun executorAnchorsActionToObservedPackageInsteadOfModelGuess() = runBlocking {
        var reads = 0
        var anchored = false
        val provider = object : ToolProvider {
            override suspend fun initialize() {}
            override suspend fun listTools() = ToolRegistry.tools.map { McpTool(it.name, it.description, it.inputSchema) }
            override suspend fun callTool(name: String, arguments: JsonObject): McpToolResult {
                if (name == "tap") { assertEquals("com.spotify.music", arguments.getValue("expected_package").jsonPrimitive.content); anchored = true }
                val text = if (name == "get_ui_tree") """{"package":"com.spotify.music","tree":{"text":"${if (++reads == 1) "Home" else "Done"}"}}""" else "Tapped"
                return McpToolResult(text, false, buildJsonObject {})
            }
        }
        val responses = ArrayDeque(listOf(
            """{"type":"plan","goals":[{"description":"Open detail","success_condition":"Done is visible","expected_ui":{"text":"Done"}}]}""",
            """{"type":"act","goal_id":"s1","action":{"name":"get_ui_tree","arguments":{}}}""",
            """{"type":"act","goal_id":"s1","action":{"name":"tap","arguments":{"x":100,"y":200,"expected_package":"com.model.guess"}}}""",
            """{"type":"complete","goal_id":"s1","evidence":{"action_id":3,"text":"Done"}}"""
        ))
        val result = AgentExecutor(provider, { responses.removeFirst() }, { "" }, settle = {}).run("Open detail")
        assertEquals("Done", result.answer); assertTrue(anchored); assertEquals(2, reads)
    }
    @Test fun visibleSearchCaptionCannotProveNavigationTapChangedTab() {
        val machine = AgentStateMachine("Open Search tab")
        machine.startPlanning(); machine.onPlanCreated(listOf("Open Search"), mapOf("s1" to UiExpectation(selectedText = "Search")))
        val home = McpToolResult("""{"tree":{"text":"Search","selected":false}}""", false, buildJsonObject {})
        machine.startToolExecution(AgentToolCall("get_ui_tree")); machine.onToolResult(home); machine.startPlanning()
        machine.startToolExecution(AgentToolCall("tap_by_text", buildJsonObject { put("query", "Search") }, UiExpectation(text = "Search")))
        machine.onToolResult(McpToolResult("Clicked", false, buildJsonObject {})); machine.onUiSettled()
        machine.startToolExecution(AgentToolCall("get_ui_tree")); machine.onToolResult(home); machine.startPlanning()
        assertEquals(false, machine.state.actions.first { it.call.name == "tap_by_text" }.verified)
        assertFalse(machine.completeCurrentStep(StepCompletion("s1", 3, "Search")))
    }
    @Test fun nonEditableSearchPlaceholderRequiresActivationBeforeTyping() {
        val placeholder = """{"tree":{"text":"What do you want to listen to?","clickable":true}}"""
        assertNotNull(ToolExecutionPolicy.preconditionError("type_into_field_near", placeholder))
        assertNull(ToolExecutionPolicy.preconditionError("tap_by_text", placeholder))
        val input = """{"tree":{"hint":"Search","editable":true,"visible":true}}"""
        assertNull(ToolExecutionPolicy.preconditionError("type_into_field_near", input))
    }
    @Test fun appDiscoveryDoesNotBlockInitialLaunchButLaunchStillRequiresObservation() {
        val machine = AgentStateMachine("Open Instagram")
        machine.startPlanning(); machine.onPlanCreated(listOf("Open Instagram"))
        machine.startToolExecution(AgentToolCall("find_app", buildJsonObject { put("query", "Instagram") }))
        machine.onToolResult(McpToolResult("""{"apps":[{"package_name":"com.instagram.android"}]}""", false, buildJsonObject {}))
        machine.startPlanning()
        machine.startToolExecution(AgentToolCall("open_app", buildJsonObject { put("package_name", "com.instagram.android") }))
        machine.onToolResult(McpToolResult("Launch requested", false, buildJsonObject {})); machine.onUiSettled()
        assertTrue(machine.requiresObservation())
        assertEquals(0, machine.state.completedSteps.size)
    }
    @Test fun spotifyNavigationTypingSubmissionAndPlaybackEachNeedObservation() = runBlocking {
        val home = """{"package":"com.spotify.music","tree":{"text":"Search","selected":false,"view_id":"search_tab"}}"""
        val search = """{"package":"com.spotify.music","tree":{"children":[{"text":"Search","selected":true},{"hint":"Search artists","editable":true}]}}"""
        val typed = """{"package":"com.spotify.music","tree":{"text":"Yesterday","editable":true,"focused":true}}"""
        val results = """{"package":"com.spotify.music","tree":{"text":"Yesterday","clickable":true}}"""
        val playing = """{"package":"com.spotify.music","tree":{"children":[{"text":"Yesterday"},{"desc":"Pause"}]}}"""
        val screens = ArrayDeque(listOf(home, search, typed, results, playing))
        val calls = mutableListOf<String>()
        val provider = object : ToolProvider {
            override suspend fun initialize() {}
            override suspend fun listTools() = ToolRegistry.tools.map { McpTool(it.name, it.description, it.inputSchema) }
            override suspend fun callTool(name: String, arguments: JsonObject): McpToolResult {
                calls += name
                return McpToolResult(if (name == "get_ui_tree") screens.removeFirst() else "Action accepted", false, buildJsonObject {})
            }
        }
        val decisions = ArrayDeque(listOf(
            """{"type":"plan","goals":[{"description":"Play Yesterday","success_condition":"Yesterday and Pause are displayed","expected_ui":{"text":"Pause"}}]}""",
            """{"type":"act","goal_id":"s1","action":{"name":"get_ui_tree","arguments":{}}}""",
            """{"type":"act","goal_id":"s1","action":{"name":"tap_by_text","arguments":{"query":"Search","view_id":"search_tab"},"expected_ui":{"selected_text":"Search"}}}""",
            """{"type":"act","goal_id":"s1","action":{"name":"type_into_field_near","arguments":{"query":"Search artists","text":"Yesterday"}}}""",
            """{"type":"act","goal_id":"s1","action":{"name":"press_enter","arguments":{}}}""",
            """{"type":"act","goal_id":"s1","action":{"name":"tap_by_text","arguments":{"query":"Yesterday"},"expected_ui":{"text":"Pause"}}}""",
            """{"type":"complete","goal_id":"s1","evidence":{"action_id":9,"text":"Yesterday"}}"""
        ))
        val result = AgentExecutor(provider, { decisions.removeFirst() }, { "" }, settle = {}).run("Search Spotify and play Yesterday")
        assertEquals("Yesterday", result.answer)
        assertEquals(listOf("get_ui_tree", "tap_by_text", "get_ui_tree", "type_into_field_near", "get_ui_tree", "press_enter", "get_ui_tree", "tap_by_text", "get_ui_tree"), calls)
        assertTrue(screens.isEmpty())
    }
    @Test fun horizontalSwipesAndRotationUsePhysicalDisplayBounds() {
        GestureBounds.validate(1080, 2408, 900f, 1000f, 180f, 1000f)
        GestureBounds.validate(1080, 2408, 180f, 1000f, 900f, 1000f)
        GestureBounds.validate(2408, 1080, 2100f, 500f, 200f, 500f)
        for (args in listOf(listOf(-1f, 0f, 500f, 0f), listOf(1080f, 0f, 500f, 0f), listOf(1f, 1f, 2f, 2f), listOf(Float.NaN, 0f, 50f, 0f), listOf(5f, 5f, Float.POSITIVE_INFINITY, 5f))) {
            try { GestureBounds.validate(1080, 2408, args[0], args[1], args[2], args[3]); fail("Invalid gesture accepted") } catch (_: IllegalArgumentException) {}
        }
    }
    @Test fun navigationRequiresActualSelectionAndIgnoresHiddenInput() {
        val observed = UiObservation.parse("""{"tree":{"children":[{"text":"Search","selected":false},{"text":"Hidden value","editable":true,"focused":true,"visible":false}]}}""")!!
        assertFalse(observed.matches(UiExpectation(selectedText = "Search")))
        assertFalse(observed.matches(UiExpectation(focusedEditable = true)))
        assertFalse(observed.containsEvidence("Hidden value"))
        val selected = UiObservation.parse("""{"tree":{"text":"Search","selected":true}}""")!!
        assertTrue(selected.matches(UiExpectation(selectedText = "Search")))
        assertNull(UiObservation.parse("""{"tree":{"text":"Search","selected":"true"}}"""))
        assertNull(UiObservation.parse("""{"tree":{"hint":{},"editable":true}}"""))
    }
    @Test fun navigationSelectorDoesNotDeriveFocusFromDifferentSearchInput() {
        val observed = UiObservation.parse("""{"tree":{"children":[{"text":"Search","view_id":"input","editable":true},{"text":"Search","view_id":"nav"}]}}""")!!
        assertFalse(observed.tapsOnlyEditable(AgentToolCall("tap_by_text", buildJsonObject { put("query", "Search"); put("view_id", "nav") })))
        assertTrue(observed.tapsOnlyEditable(AgentToolCall("tap_by_text", buildJsonObject { put("query", "Search"); put("view_id", "input") })))
    }
    @Test fun errorsDoNotExposeJsonCredentialsOrStackTraces() {
        for (reason in listOf("MCP HTTP 401: {\"token\":\"secret_value\"}", "Unexpected {\"private\":\"secret_value\"}", "IllegalStateException\nsecret_value")) {
            val explanation = FailureMessage.explain(reason)
            assertFalse(explanation, explanation.contains("secret_value"))
            assertFalse(explanation.contains("{"))
        }
        assertTrue(FailureMessage.explain("No launchable activity is visible").contains("profile"))
        assertTrue(FailureMessage.explain("Ambiguous target").contains("More than one"))
    }
    @Test fun schemasExposeDiscoveryAndGroundedRetryWithoutInventedToolAlias() {
        assertTrue(ToolRegistry.tools.any { it.name == "find_app" })
        assertFalse(ToolRegistry.tools.any { it.name == "tap_by_name" })
        val properties = ToolRegistry.tools.first { it.name == "tap_by_text" }.inputSchema.getValue("properties").jsonObject
        assertTrue(properties.keys.containsAll(listOf("query", "view_id", "bounds", "prefer_gesture")))
        val prompt = java.io.File("src/main/assets/HardPrompt.txt").readText()
        assertFalse(prompt.contains("MUST NOT use `tap_by_text`"))
        assertTrue(prompt.contains("find_app"))
        assertTrue(prompt.contains("x1>x2"))
    }
}
