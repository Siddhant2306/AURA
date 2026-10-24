package com.example.phone_agent.agent.core

import com.example.phone_agent.agent.model.*
import kotlinx.serialization.json.*

/** JSON syntax and the application contract are distinct checks; no legacy aliases are accepted. */
class PlanParser {
    private val json = Json { ignoreUnknownKeys = false }
    data class Result(val response: AgentPlan? = null, val error: String? = null)

    fun parse(raw: String, planning: Boolean): Result = try {
        val root = json.parseToJsonElement(raw.trim()) as? JsonObject
            ?: throw IllegalArgumentException("Response must be a JSON object")
        val response = json.decodeFromJsonElement(AgentPlan.serializer(), root)
        val required = when (response.type) {
            AgentResponseType.PLAN -> setOf("type", "goals")
            AgentResponseType.ACT -> setOf("type", "goal_id", "action")
            AgentResponseType.COMPLETE -> setOf("type", "goal_id", "evidence")
            AgentResponseType.STOP -> setOf("type", "reason")
        }
        require(root.keys == required) { "${response.type} requires exactly $required" }
        require(if (planning) response.type in setOf(AgentResponseType.PLAN, AgentResponseType.STOP) else response.type != AgentResponseType.PLAN) { "Incompatible response type for this phase" }
        when (response.type) {
            AgentResponseType.PLAN -> {
                val goals = requireNotNull(response.goals)
                require(goals.size in 1..8 && goals.all { it.description.isNotBlank() && it.description.length <= 500 && it.successCondition.isNotBlank() && it.successCondition.length <= 500 }) { "Provide 1-8 concise user-level goals and success conditions" }
                require(goals.all { it.expectedUi == null || UiObservation.valid(it.expectedUi) }) { "Invalid goal expected_ui" }
            }
            AgentResponseType.ACT -> {
                require(!response.goalId.isNullOrBlank()) { "Missing goal_id" }
                val call = requireNotNull(response.action)
                require(call.name.isNotBlank() && root.getValue("action").jsonObject["arguments"] is JsonObject) { "Action requires name and arguments object" }
                require(call.expectedUi == null || UiObservation.valid(call.expectedUi)) { "Invalid action expected_ui" }
                require(call.recoveryReason == null || call.recoveryReason.isNotBlank()) { "Empty recovery_reason" }
            }
            AgentResponseType.COMPLETE -> {
                require(!response.goalId.isNullOrBlank()) { "Missing goal_id" }
                val evidence = requireNotNull(response.evidence)
                require(evidence.actionId > 0 && evidence.text.isNotBlank() && evidence.text.length <= 2_000) { "Completion requires a real action_id and concise observed text" }
                require(evidence.expectedUi == null || UiObservation.valid(evidence.expectedUi)) { "Invalid completion expected_ui" }
            }
            AgentResponseType.STOP -> require(!response.reason.isNullOrBlank() && response.reason.length <= 1_000) { "Stop requires a concise reason" }
        }
        Result(response)
    } catch (e: IllegalArgumentException) {
        // Serialization diagnostics can contain the entire model/UI payload.
        Result(error = if (e is kotlinx.serialization.SerializationException) "Invalid JSON or response fields" else e.message?.take(300) ?: "Invalid response schema")
    }
}
