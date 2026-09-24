package com.example.phone_agent.agent

import android.content.Context
import android.util.Log
import com.example.phone_agent.agent.core.ContextManager
import com.example.phone_agent.agent.core.PlanParser
import com.example.phone_agent.agent.core.ToolExecutionPolicy
import com.example.phone_agent.agent.llm.OllamaLLMClient
import com.example.phone_agent.agent.mcp.ToolProvider
import com.example.phone_agent.agent.model.AgentPlan
import com.example.phone_agent.agent.model.AgentPreflightResult
import com.example.phone_agent.agent.model.AgentRunResult
import com.example.phone_agent.agent.model.ChatMessage
import com.example.phone_agent.agent.model.McpTool
import com.example.phone_agent.agent.model.McpToolResult
import com.example.phone_agent.service.McpForegroundService
import com.example.phone_agent.service.PhoneControlService
import kotlinx.serialization.json.JsonObject

class FridayAgent(
    private val context: Context,
    private val ollamaLLMClient: OllamaLLMClient,
    private val toolProvider: ToolProvider,
    private val maxSteps: Int = 12,
    private val maxJsonRetries: Int = 2,
) {
    private val planParser = PlanParser()

    suspend fun run(
        userCommand: String,
        onProgress: (String) -> Unit = { },
    ): AgentRunResult {
        require(userCommand.isNotBlank()) { "Command must not be blank." }

        onProgress("Connecting to phone tools")
        toolProvider.initialize()
        val tools = toolProvider.listTools()
        val messages = mutableListOf(
            ChatMessage("system", buildSystemPrompt(tools)),
            ChatMessage("user", userCommand),
        )
        val toolResults = mutableListOf<McpToolResult>()

        for (step in 0 until maxSteps) {

            Log.d(TAG, "========================================")
            Log.d(TAG, "AGENT STEP ${step + 1}")
            Log.d(TAG, "========================================")

            onProgress("Planning step ${step + 1}")
            logMessageStats(messages, step)


            val plan = requestPlan(messages, onProgress)
            Log.d(TAG, "LLM PLAN RECEIVED")
            Log.d(TAG, "Plan steps: ${plan.plan}")
            Log.d(TAG, "Tool calls: ${plan.toolCalls.size}")
            plan.toolCalls.forEachIndexed { index, toolCall ->
                Log.d(
                    TAG,
                    "TOOL ${index + 1}: ${toolCall.name} | args=${toolCall.arguments}"
                )
            }
            Log.d(TAG, "Final: ${plan.final}")

            if (plan.parseError) {
                messages += ChatMessage(
                    role = "user",
                    content = JSON_RETRY_PROMPT,
                )
                continue
            }

            plan.plan.takeIf { it.isNotEmpty() }?.let { steps ->
                onProgress("Plan: ${steps.first()}${if (steps.size > 1) " (+${steps.size - 1} more)" else ""}")
            }

            if (plan.toolCalls.isEmpty()) {
                val answer = plan.final?.trim().takeUnless { it.isNullOrBlank() }
                    ?: "I couldn't figure out how to complete that request."
                return AgentRunResult(
                    answer = answer,
                    toolResults = toolResults,
                    stepsUsed = step + 1,
                )
            }

            if (ToolExecutionPolicy.hasUnsafeBatch(plan.toolCalls.map { it.name })) {
                executeToolCallsSequentially(plan, messages, toolResults, onProgress)
            } else {
                executeToolCalls(plan, messages, toolResults, onProgress)
            }

            if (!plan.final.isNullOrBlank() && plan.toolCalls.isEmpty()) {
                return AgentRunResult(
                    answer = plan.final.trim(),
                    toolResults = toolResults,
                    stepsUsed = step + 1,
                )
            }
        }

        return summarizeAtLimit(messages, toolResults)
    }

    private suspend fun requestPlan(
        messages: MutableList<ChatMessage>,
        onProgress: (String) -> Unit,
    ): AgentPlan {
        repeat(maxJsonRetries + 1) { attempt ->
            val startTime = System.currentTimeMillis()

            val rawPlan = ollamaLLMClient.completeJson(messages)

            Log.d(TAG, "Sending ${messages.size} messages to Ollama")

            val elapsed = System.currentTimeMillis() - startTime

            Log.d(TAG, "Ollama response received in ${elapsed}ms")
            Log.d(TAG, "Raw response: $rawPlan")

            messages += ChatMessage("assistant", rawPlan)

            val plan = planParser.parse(rawPlan)
            if (!plan.parseError) return plan

            Log.w(TAG, "JSON parse failed on attempt ${attempt + 1}")
            if (attempt < maxJsonRetries) {
                onProgress("Fixing response format")
                messages += ChatMessage("user", JSON_RETRY_PROMPT)
            } else {
                return plan
            }
        }
        return AgentPlan(parseError = true)
    }

    private suspend fun executeToolCalls(
        plan: AgentPlan,
        messages: MutableList<ChatMessage>,
        toolResults: MutableList<McpToolResult>,
        onProgress: (String) -> Unit,
    ) {
        for (toolCall in plan.toolCalls) {
            executeSingleTool(toolCall.name, toolCall.arguments, messages, toolResults, onProgress)
        }
    }

    private suspend fun executeToolCallsSequentially(
        plan: AgentPlan,
        messages: MutableList<ChatMessage>,
        toolResults: MutableList<McpToolResult>,
        onProgress: (String) -> Unit,
    ) {
        for (toolCall in plan.toolCalls) {
            executeSingleTool(toolCall.name, toolCall.arguments, messages, toolResults, onProgress)
            if (ToolExecutionPolicy.isUiChanging(toolCall.name)) {
                messages += ChatMessage(
                    role = "user",
                    content = """
                        UI may have changed after ${toolCall.name}.
                        Call get_ui_tree before the next UI action if you need to inspect the screen.
                        Return ONLY the next JSON object.
                    """.trimIndent(),
                )
                break
            }
        }
    }

    private suspend fun executeSingleTool(
        name: String,
        arguments: JsonObject,
        messages: MutableList<ChatMessage>,
        toolResults: MutableList<McpToolResult>,
        onProgress: (String) -> Unit,
    ) {
        Log.d(TAG, "----------------------------------------")
        Log.d(TAG, "EXECUTING TOOL: $name")
        Log.d(TAG, "ARGUMENTS: $arguments")

        onProgress("Using $name")
        val startTime = System.currentTimeMillis()

        val result = toolProvider.callTool(name, arguments)


        val elapsed = System.currentTimeMillis() - startTime

        Log.d(TAG, "TOOL FINISHED: $name")
        Log.d(TAG, "TOOL TIME: ${elapsed}ms")
        Log.d(TAG, "TOOL ERROR: ${result.isError}")
        Log.d(TAG, "TOOL RESULT: ${result.text}")

        toolResults += result
        ToolExecutionPolicy.waitForUiSettle(name)

        messages += ChatMessage(
            role = "user",
            content = buildToolResultMessage(name, arguments, result),
        )
    }

    private suspend fun summarizeAtLimit(
        messages: MutableList<ChatMessage>,
        toolResults: List<McpToolResult>,
    ): AgentRunResult {
        messages += ChatMessage(
            role = "user",
            content = "Stop using tools and give a short spoken summary of what happened.",
        )
        val rawSummary = ollamaLLMClient.completeJson(messages)
        val summary = planParser.parse(rawSummary)
        return AgentRunResult(
            answer = summary.final?.takeIf { it.isNotBlank() }
                ?: "I reached my step limit before finishing. Please give me a narrower instruction.",
            toolResults = toolResults,
            stepsUsed = maxSteps,
        )
    }

    private fun buildToolResultMessage(
        toolName: String,
        arguments: JsonObject,
        result: McpToolResult,
    ): String {
        val truncated = ContextManager.truncateToolResult(toolName, result.text)
        return """
            TOOL RESULT
            name=$toolName
            arguments=$arguments
            is_error=${result.isError}
            result=$truncated

            Continue the task if it is not complete.
            Batch independent tools together; run dependent UI actions one at a time with get_ui_tree between screen changes.
            Return ONLY the next JSON object with plan, tool_calls, and final.
        """.trimIndent()
    }

    private fun buildSystemPrompt(tools: List<McpTool>): String {
        val hardPrompt = context.assets
            .open("HardPrompt.txt")
            .bufferedReader()
            .use { it.readText() }

        val toolDefinitions = tools.joinToString(separator = "\n") { tool ->
            """
            - ${tool.name}: ${tool.description}
              inputSchema=${tool.inputSchema}
            """.trimIndent()
        }

        return """
            $hardPrompt

            ==================================================
            AVAILABLE TOOLS
            ==================================================

            $toolDefinitions
        """.trimIndent()
    }

    private fun logMessageStats(messages: List<ChatMessage>, step: Int) {
        Log.d(TAG, "Step ${step + 1}: messages=${messages.size}, chars=${messages.sumOf { it.content.length }}")
    }

    companion object {
        private const val TAG = "FridayAgent"

        private const val JSON_RETRY_PROMPT =
            "Your last response was not valid JSON. Return ONLY one JSON object with plan, tool_calls, and final fields."

        fun preflight(
            accessibilityReady: Boolean,
            mcpServerRunning: Boolean,
            bearerToken: String,
        ): AgentPreflightResult = when {
            !accessibilityReady ->
                AgentPreflightResult(false, "Enable Phone Control in Accessibility settings first.")
            !mcpServerRunning ->
                AgentPreflightResult(false, "Start the Phone MCP server in the app first.")
            bearerToken.isBlank() ->
                AgentPreflightResult(false, "MCP bearer token is missing.")
            else ->
                AgentPreflightResult(true, "Ready")
        }

        fun isAccessibilityReady(): Boolean = PhoneControlService.instance != null

        fun isMcpServerRunning(): Boolean = McpForegroundService.isRunning
    }
}
