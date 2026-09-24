package com.example.phone_agent.agent.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
@Serializable
data class ChatMessage(
    val role: String,
    val content: String = "",
    val thinking: String? = null,
)

@Serializable
data class OllamaOptions(
    val temperature: Double = 0.0,
    val num_predict: Int = 1024,
)

@Serializable
data class OllamaChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = false,
    val format: String? = "json",
    val think: Boolean = false,
    val options: OllamaOptions = OllamaOptions(),
)

@Serializable
data class OllamaChatResponse(
    val message: ChatMessage? = null,
    val done: Boolean = false,
)

@Serializable
data class McpRpcRequest(
    val jsonrpc: String = "2.0",
    val id: Int,
    val method: String,
    val params: JsonObject = buildJsonObject { },
)

data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
)

data class McpToolResult(
    val text: String,
    val isError: Boolean,
    val rawJson: JsonObject,
)

@Serializable
data class AgentPlan(
    /** High-level steps the agent intends to follow for the user's task. */
    val plan: List<String> = emptyList(),
    @SerialName("tool_calls")
    val toolCalls: List<AgentToolCall> = emptyList(),
    val final: String? = null,
    @Transient
    val parseError: Boolean = false,
)
@Serializable
data class AgentToolCall(
    val name: String,
    val arguments: JsonObject = buildJsonObject { },
)

data class AgentRunResult(
    val answer: String,
    val toolResults: List<McpToolResult>,
    val stepsUsed: Int = 0,
)

data class AgentPreflightResult(
    val ready: Boolean,
    val message: String,
)
