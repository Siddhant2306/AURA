package com.example.phone_agent.agent

import com.example.phone_agent.agent.core.*
import com.example.phone_agent.agent.mcp.ToolProvider
import com.example.phone_agent.agent.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Opt-in network/device validation; skipped in normal builds. Never prints bearer tokens or full UI. */
class LiveAgentValidationTest {
    private val json = Json { encodeDefaults = true }
    private val report = File("../diagnostics/live_validation_trace.log")
    private fun trace(text: String) { report.appendText(text + "\n") }
    private fun post(url: String, body: JsonObject, token: String? = null): JsonObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"; connection.connectTimeout = 10_000; connection.readTimeout = 120_000
        connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json")
        token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode}" }
        return connection.inputStream.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
    }
    private fun prompt() = File("src/main/assets/HardPrompt.txt").readText()
    private fun model(messages: List<ChatMessage>, seed: Int? = null): String {
        val request = OllamaChatRequest("qwen3:4b", messages, format = OllamaOutput.responseFormat(messages),
            options = OllamaOptions(num_predict = OllamaOutput.outputTokens(messages)))
        var body = json.encodeToJsonElement(OllamaChatRequest.serializer(), request).jsonObject
        if (seed != null) body = JsonObject(body + ("options" to JsonObject(body.getValue("options").jsonObject + ("seed" to JsonPrimitive(seed)))))
        trace("REQUEST chars=${messages.sumOf { it.content.length }} num_ctx=${request.options.num_ctx} output=${request.options.num_predict}")
        val result = post("http://10.20.7.72:11434/api/chat", body)
        val content = OllamaOutput.parse(result.toString())
        trace("RESPONSE prompt_tokens=${result["prompt_eval_count"]} done_reason=${result["done_reason"]} content=$content")
        return content
    }
    @Test fun replayCapturedScreenUsingProductionRequest() {
        assumeTrue(System.getenv("FRIDAY_LIVE_MODE") == "replay")
        report.writeText("Production prompt replay; phone tools are not executed.\n")
        val source = Json.parseToJsonElement(File(requireNotNull(System.getenv("FRIDAY_CAPTURE_STATE"))).readText()).jsonObject
        val goals = source.getValue("goals").jsonArray.map { it.jsonObject }
        val tools = source.getValue("available_tools").jsonArray.map { it.jsonObject }.map {
            McpTool(it.getValue("name").jsonPrimitive.content, it.getValue("description").jsonPrimitive.content, it.getValue("input_schema").jsonObject)
        }
        val command = source.getValue("command").jsonPrimitive.content
        val m = AgentStateMachine(command)
        m.startPlanning(); m.onPlanCreated(goals.map { it.getValue("description").jsonPrimitive.content },
            successConditions = goals.map { it.getValue("success_condition").jsonPrimitive.content })
        m.startToolExecution(AgentToolCall("open_app", buildJsonObject { put("package_name", "com.android.chrome") }))
        m.onToolResult(McpToolResult("Opened app package com.android.chrome", false, buildJsonObject {})); m.onUiSettled()
        m.startToolExecution(AgentToolCall("get_ui_tree"))
        m.onToolResult(McpToolResult(source.getValue("ui_observation").jsonObject.getValue("tree").toString(), false, buildJsonObject {}))
        m.startPlanning(); assertTrue(m.completeVerifiedForegroundGoal())
        val unused = object : ToolProvider {
            override suspend fun initialize() = error("No phone calls in replay")
            override suspend fun listTools(): List<McpTool> = error("No phone calls in replay")
            override suspend fun callTool(name: String, arguments: JsonObject): McpToolResult = error("No phone calls in replay")
        }
        val executor = AgentExecutor(unused, { error("Not executed") }, { prompt() })
        val messages = listOf(ChatMessage("system", AgentPrompt.forPhase(prompt(), false)), ChatMessage("user", command),
            ChatMessage("user", executor.statePrompt(m, tools, false, "Previous foreground goal is verified. Choose an action for s2 from the current screen.", 0, false)))
        for (seed in listOf(11, 22, 33)) {
            val decision = PlanParser().parse(model(messages, seed), false)
            assertNull(decision.error)
            assertEquals(AgentResponseType.ACT, decision.response?.type)
            assertEquals("tap_by_text", decision.response?.action?.name)
            assertEquals("Search Google or type URL", decision.response?.action?.arguments?.get("query")?.jsonPrimitive?.content)
        }
    }
    @Test fun runProductionExecutorOnConnectedPhone() = runBlocking {
        assumeTrue(System.getenv("FRIDAY_LIVE_MODE") == "phone")
        report.writeText("Live production AgentExecutor; real MCP tools and real Ollama.\n")
        fun adb(vararg args: String): String {
            val process = ProcessBuilder(listOf("adb") + args).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { "ADB operation failed" }
            return output
        }
        val prefs = adb("shell", "run-as", "com.example.phone_agent", "cat", "shared_prefs/mcp_settings.xml")
        val token = Regex("<string name=\"bearer_token\">([^<]+)</string>").find(prefs)?.groupValues?.get(1)
        require(!token.isNullOrBlank()) { "Phone MCP token not configured" }
        adb("forward", "tcp:18080", "tcp:8080")
        var id = 0
        fun rpc(method: String, params: JsonObject = buildJsonObject {}): JsonObject {
            val response = post("http://127.0.0.1:18080/mcp", buildJsonObject {
                put("jsonrpc", "2.0"); put("id", ++id); put("method", method); put("params", params)
            }, token)
            check(response["error"] == null) { "MCP RPC error" }
            return response.getValue("result").jsonObject
        }
        val calls = mutableListOf<String>()
        val provider = object : ToolProvider {
            override suspend fun initialize() { rpc("initialize") }
            override suspend fun listTools() = rpc("tools/list").getValue("tools").jsonArray.map { it.jsonObject }.map {
                McpTool(it.getValue("name").jsonPrimitive.content, it.getValue("description").jsonPrimitive.content, it.getValue("inputSchema").jsonObject)
            }
            override suspend fun callTool(name: String, arguments: JsonObject): McpToolResult {
                calls += name
                trace("PHONE_TOOL name=$name")
                val result = rpc("tools/call", buildJsonObject { put("name", name); put("arguments", arguments) })
                return McpToolResult(result.getValue("content").jsonArray.joinToString("\n") { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() },
                    result["isError"]?.jsonPrimitive?.booleanOrNull == true, result)
            }
        }
        val result = AgentExecutor(provider, { model(it) }, { prompt() },
            limits = ExecutionLimits(maxTurns = 24, maxTools = 24, timeoutMillis = 300_000), log = ::trace)
            .run("Open Chrome, tap the search field, type today's weather, and press Enter to show the search results.")
        trace("ANSWER ${result.answer}")
        trace("CALLS ${calls.joinToString()}")
        assertFalse(result.answer, result.answer.startsWith("I couldn't finish"))
        assertTrue("Desired text was not actually observed in the focused input", result.toolResults.any {
            !it.isError && UiObservation.parse(it.text)?.matches(UiExpectation(editableText = "today's weather", focusedEditable = true)) == true
        })
        trace("TEXT_ENTRY_TOOL_EXECUTED ${calls.any { it == "type_text" || it == "type_into_field_near" }}")
        assertTrue("No Enter submission executed", calls.contains("press_enter"))
        val final = provider.callTool("get_ui_tree", buildJsonObject {})
        assertFalse(final.isError)
        val observed = UiObservation.parse(final.text) ?: error("No final UI tree")
        val resultText = observed.nodes.filter { it["editable"]?.jsonPrimitive?.booleanOrNull != true }
            .joinToString(" ") { it["text"]?.jsonPrimitive?.content.orEmpty() + " " + it["desc"]?.jsonPrimitive?.content.orEmpty() }
        assertTrue("No weather-related result outside the input field", Regex("weather|forecast|°", RegexOption.IGNORE_CASE).containsMatchIn(resultText))
        trace("FINAL_DEVICE_CHECK weather_content_outside_input=true")
    }
}
