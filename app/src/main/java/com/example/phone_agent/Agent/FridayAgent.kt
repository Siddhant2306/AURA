package com.example.phone_agent.agent

import android.content.Context
import android.util.Log
import com.example.phone_agent.agent.core.AgentExecutor
import com.example.phone_agent.agent.core.ExecutionLimits
import com.example.phone_agent.agent.llm.OllamaLLMClient
import com.example.phone_agent.agent.mcp.ToolProvider
import com.example.phone_agent.agent.model.AgentPreflightResult
import com.example.phone_agent.agent.model.AgentRunResult
import com.example.phone_agent.agent.model.McpTool
import com.example.phone_agent.service.McpForegroundService
import com.example.phone_agent.service.PhoneControlService

class FridayAgent(
    private val context: Context,
    private val ollamaLLMClient: OllamaLLMClient,
    private val toolProvider: ToolProvider,
    private val maxSteps: Int = 48,
    private val maxJsonRetries: Int = 1,
    private val executionLimits: ExecutionLimits = ExecutionLimits(maxTurns = maxSteps, maxJsonRetries = maxJsonRetries),
) {
    suspend fun run(userCommand: String, onProgress: (String) -> Unit = {}): AgentRunResult =
        AgentExecutor(
            provider = toolProvider,
            completeJson = ollamaLLMClient::completeJson,
            systemPrompt = ::buildSystemPrompt,
            limits = executionLimits,
            log = { message -> message.chunked(3_000).forEach { Log.d(TAG, it) } },
        ).run(userCommand, onProgress)

    private fun buildSystemPrompt(tools: List<McpTool>): String {
        val instructions = context.assets.open("HardPrompt.txt").bufferedReader().use { it.readText() }
        Log.d(TAG, "DISCOVERED_TOOLS names=${tools.joinToString { it.name }}")
        return instructions // Exact schemas appear once in the authoritative request state.
    }
    companion object {
        private const val TAG = "FridayAgent"

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
