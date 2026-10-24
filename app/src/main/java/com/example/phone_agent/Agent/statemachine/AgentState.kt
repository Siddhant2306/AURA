package com.example.phone_agent.agent.statemachine

import com.example.phone_agent.agent.model.AgentToolCall
import com.example.phone_agent.agent.model.UiExpectation

data class PlanStep(val id: String, val description: String, val evidence: String? = null,
    val expectedUi: UiExpectation? = null, val successCondition: String = description)
data class ActionRecord(val id: Int, val stepId: String, val call: AgentToolCall,
    val succeeded: Boolean, val result: String, val screenRevision: Int,
    val expectedUi: UiExpectation? = null, val beforeScreen: String? = null,
    val verified: Boolean? = null, val verificationReason: String? = null)

/** Immutable progress snapshots; only AgentStateMachine updates execution state. */
data class AgentState(
    val command: String,
    val status: AgentStatus = AgentStatus.IDLE,
    val plan: List<PlanStep> = emptyList(),
    val currentStepIndex: Int = 0,
    val actions: List<ActionRecord> = emptyList(),
    val activeTool: AgentToolCall? = null,
    val screenRevision: Int = 0,
    val needsObservation: Boolean = true,
    /** Full latest observation for verification; history keeps bounded summaries. */
    val currentScreen: String? = null,
    val failure: String? = null,
) {
    val currentStep: PlanStep? get() = plan.getOrNull(currentStepIndex)
    val completedSteps: List<PlanStep> get() = plan.take(currentStepIndex)
    val pendingSteps: List<PlanStep> get() = plan.drop(currentStepIndex)
}
