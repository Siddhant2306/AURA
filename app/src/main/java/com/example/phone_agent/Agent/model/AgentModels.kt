package com.example.phone_agent.agent.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
@Serializable
data class ChatMessage(
    val role: String,
    val content: String = "",
    val thinking: String? = null,
)


@Serializable
data class OllamaOptions(
    val temperature: Double = 0.2,
    val repeat_penalty: Double = 1.1,
    val repeat_last_n: Int = 64,
    val num_predict: Int = 256,
    val num_ctx: Int = 8192,
)



@Serializable
data class OllamaChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean = false,
    val format: JsonElement? = JsonPrimitive("json"),
    val think: Boolean = false,
    val options: OllamaOptions = OllamaOptions(),
)

@Serializable
data class OllamaChatResponse(
    val message: ChatMessage? = null,
    val done: Boolean = false,
    @SerialName("done_reason") val doneReason: String? = null,
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
enum class AgentResponseType {
    @SerialName("plan") PLAN,
    @SerialName("act") ACT,
    @SerialName("complete") COMPLETE,
    @SerialName("stop") STOP,
}

/** One documented wire contract, with mutually exclusive payloads validated by PlanParser. */
@Serializable
data class AgentPlan(
    val type: AgentResponseType,
    val goals: List<AgentGoal>? = null,
    @SerialName("goal_id") val goalId: String? = null,
    val action: AgentToolCall? = null,
    val evidence: CompletionEvidence? = null,
    val reason: String? = null,
)

@Serializable
data class AgentGoal(
    val description: String,
    @SerialName("success_condition") val successCondition: String,
    @SerialName("expected_ui") val expectedUi: UiExpectation? = null,
)

@Serializable
data class CompletionEvidence(
    @SerialName("action_id") val actionId: Int,
    val text: String,
    @SerialName("expected_ui") val expectedUi: UiExpectation? = null,
)

@Serializable
data class AgentToolCall(
    val name: String,
    val arguments: JsonObject = buildJsonObject { },
    @SerialName("expected_ui") val expectedUi: UiExpectation? = null,
    @SerialName("recovery_reason") val recoveryReason: String? = null,
)
@Serializable
data class StepCompletion(
    @SerialName("step_id") val stepId: String,
    @SerialName("action_id") val actionId: Int,
    val evidence: String = "",
    @SerialName("expected_ui") val expectedUi: UiExpectation? = null,
)

/** Observable conditions, checked against the actual accessibility nodes (all supplied fields must match). */
@Serializable
data class UiExpectation(
    @SerialName("package_name") val packageName: String? = null,
    val text: String? = null,
    @SerialName("editable_text") val editableText: String? = null,
    @SerialName("focused_editable") val focusedEditable: Boolean? = null,
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
