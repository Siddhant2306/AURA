package com.example.phone_agent.agent

import com.example.phone_agent.agent.model.*
import kotlinx.serialization.json.*

/** Test scenario notation only. The application accepts only the strict typed AgentPlan wire contract. */
internal data class FixtureDecision(
    val plan: List<String> = emptyList(),
    val toolCalls: List<AgentToolCall> = emptyList(),
    val final: String? = null,
    val completion: StepCompletion? = null,
    val stopReason: String? = null,
    val stepExpectations: Map<String, UiExpectation> = emptyMap(),
)
internal fun wire(response: AgentPlan): String {
    val json = Json { encodeDefaults = false }
    val fields = json.encodeToJsonElement(AgentPlan.serializer(), response).jsonObject.toMutableMap()
    response.action?.let { fields["action"] = JsonObject(fields.getValue("action").jsonObject + ("arguments" to it.arguments)) }
    return JsonObject(fields).toString()
}
internal class FixtureSession(private val decisions: List<FixtureDecision>) {
    private var next = 0
    fun respond(messages: List<ChatMessage>): String {
        val state = Json.parseToJsonElement(messages.last().content).jsonObject
        if (state["request_phase"]?.jsonPrimitive?.content == "plan") {
            val first = decisions.firstOrNull() ?: FixtureDecision()
            return wire(AgentPlan(AgentResponseType.PLAN, goals = first.plan.mapIndexed { i, description ->
                AgentGoal(description, description, first.stepExpectations["s${i + 1}"])
            }))
        }
        val d = decisions.getOrElse(next++) { FixtureDecision(stopReason = "Script exhausted") }
        d.stopReason?.let { return wire(AgentPlan(AgentResponseType.STOP, reason = it)) }
        d.completion?.let { c ->
            val e = c.expectedUi ?: decisions.firstOrNull()?.stepExpectations?.get(c.stepId)
            val text = c.evidence.ifBlank { e?.text ?: e?.packageName ?: e?.editableText ?: "Missing evidence" }
            return wire(AgentPlan(AgentResponseType.COMPLETE, goalId = c.stepId, evidence = CompletionEvidence(c.actionId, text, e)))
        }
        d.toolCalls.firstOrNull()?.let { return wire(AgentPlan(AgentResponseType.ACT, goalId = state["current_goal"]?.jsonPrimitive?.content, action = it)) }
        return "{}" // Empty legacy scenarios must fail strict application-schema validation.
    }
}
