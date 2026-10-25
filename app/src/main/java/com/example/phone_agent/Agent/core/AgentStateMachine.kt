package com.example.phone_agent.agent.core

import com.example.phone_agent.agent.model.*
import com.example.phone_agent.agent.statemachine.*
import kotlinx.serialization.json.*

class AgentStateMachine(command: String, private val log: (String) -> Unit = {}) {
    var state = AgentState(command)
        private set
    private var nextActionId = 1
    private fun transition(to: AgentStatus, reason: String, vararg from: AgentStatus) {
        check(state.status in from) { "Invalid transition ${state.status} -> $to" }
        log("${state.status} -> $to: $reason")
        state = state.copy(status = to)
    }
    private fun lastMutation() = state.actions.lastOrNull { ToolExecutionPolicy.isUiChanging(it.call.name) }
    private fun foregroundGoalPackage(condition: String): String? =
        Regex("^([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+) is the current foreground package[.]?$", RegexOption.IGNORE_CASE)
            .matchEntire(condition.trim())?.groupValues?.get(1)
    private fun inputGoalExpectation(condition: String): UiExpectation? {
        val normalized = condition.trim().trimEnd('.').lowercase()
        if (normalized in setOf("the intended editable field is focused", "search field is focused (text input field)",
                "the search field is focused", "search field is focused", "the field is focused", "the editable field is focused"))
            return UiExpectation(focusedEditable = true)
        val text = if (condition.startsWith("editable field contains: ")) condition.substringAfter("editable field contains: ")
            else Regex("^the text ['\"](.+)['\"] appears in the focused field[.]?$", RegexOption.IGNORE_CASE)
                .matchEntire(condition.trim())?.groupValues?.get(1)
        return text?.takeIf { it.isNotBlank() }?.let { UiExpectation(editableText = it, focusedEditable = true) }
    }
    fun pendingVerification(): ActionRecord? = lastMutation()?.takeIf { it.verified == null }
    internal fun completionBlockReason(): String? {
        val goal = state.currentStep ?: return null
        if (inputGoalExpectation(goal.successCondition) != null || foregroundGoalPackage(goal.successCondition) != null) return null
        val resultsGoal = Regex("\\b(results?|weather|forecast)\\b", RegexOption.IGNORE_CASE).containsMatchIn(goal.successCondition)
        val editing = state.currentScreen?.let { UiObservation.parse(it)?.matches(UiExpectation(focusedEditable = true)) } == true
        return if (resultsGoal && editing) "The search input is still focused for editing; query text and suggestions do not prove results. If the query is correct, submit it with an available tool, then inspect the resulting page." else null
    }
    fun startPlanning() {
        check(pendingVerification() == null) { "Observe and verify the pending action before planning" }
        check(!requiresObservation()) { "A fresh usable observation is required before planning" }
        transition(AgentStatus.PLANNING, "current_step=${state.currentStep?.id} description=${state.currentStep?.description}",
            AgentStatus.IDLE, AgentStatus.CHECKING_PROGRESS, AgentStatus.RECOVERING)
    }
    fun requiresObservation(): Boolean = state.needsObservation &&
        (lastMutation() != null || state.actions.lastOrNull()?.call?.name == "get_ui_tree") &&
        state.status in setOf(AgentStatus.OBSERVING, AgentStatus.RECOVERING, AgentStatus.CHECKING_PROGRESS, AgentStatus.PLANNING)
    fun startObservation() = transition(AgentStatus.OBSERVING, "fresh UI required", AgentStatus.PLANNING, AgentStatus.RECOVERING, AgentStatus.CHECKING_PROGRESS)
    fun startUiSettling() {
        check(state.status == AgentStatus.WAITING_FOR_UI_SETTLE) { "No UI action is waiting to settle" }
        log("WAITING_FOR_UI_SETTLE tool=${lastMutation()?.call?.name}")
    }
    fun onUiSettled() = transition(AgentStatus.OBSERVING, "UI settled; observation required", AgentStatus.WAITING_FOR_UI_SETTLE)
    fun onPlanCreated(descriptions: List<String>, expectations: Map<String, UiExpectation> = emptyMap(), successConditions: List<String> = descriptions) {
        check(state.status == AgentStatus.PLANNING && state.plan.isEmpty())
        require(descriptions.isNotEmpty() && descriptions.size <= 16 && descriptions.all { it.isNotBlank() && it.length <= 500 })
        require(expectations.values.all { UiObservation.valid(it) }) { "Invalid step expectation" }
        require(successConditions.size == descriptions.size && successConditions.all { it.isNotBlank() })
        state = state.copy(plan = descriptions.mapIndexed { i, text ->
            // Recognize only this explicit package predicate, never infer completion
            // from a goal description or from a successful open_app receipt.
            val packageGoal = foregroundGoalPackage(successConditions[i])?.let { UiExpectation(packageName = it) }
            PlanStep("s${i + 1}", text.trim(), expectedUi = expectations["s${i + 1}"] ?: packageGoal ?: inputGoalExpectation(successConditions[i]), successCondition = successConditions[i])
        })
        log("FULL_PLAN steps=${state.plan.size}")
        state.plan.forEach { log("PLAN_GOAL id=${it.id} description=${it.description} condition=${it.successCondition}") }
    }
    fun expectedOutcome(call: AgentToolCall): UiExpectation? {
        val supplied = call.expectedUi
        return when (call.name) {
            "open_app" -> (supplied ?: UiExpectation()).copy(packageName = (call.arguments["package_name"] as? JsonPrimitive)?.contentOrNull)
            "type_into_field_near" -> (supplied ?: UiExpectation()).copy(editableText = (call.arguments["text"] as? JsonPrimitive)?.contentOrNull)
            "tap_by_text", "tap" -> if (state.currentScreen?.let { UiObservation.parse(it)?.tapsOnlyEditable(call) } == true)
                (supplied ?: UiExpectation()).copy(focusedEditable = true) else supplied
            "type_text" -> {
                val before = state.currentScreen?.let { UiObservation.parse(it) }?.inputText()
                if (before == null) supplied else (supplied ?: UiExpectation()).copy(editableText = before + (call.arguments["text"] as? JsonPrimitive)?.contentOrNull.orEmpty())
            }
            else -> supplied
        }?.takeIf { UiObservation.valid(it) }
    }
    fun startToolExecution(call: AgentToolCall) {
        check(state.currentStep != null)
        check(state.status != AgentStatus.OBSERVING || call.name == "get_ui_tree") { "Only observation is allowed while OBSERVING" }
        check(pendingVerification() == null || call.name == "get_ui_tree") { "Previous UI action is not verified" }
        check(recoveryError(call) == null) { recoveryError(call).orEmpty() }
        if (ToolExecutionPolicy.isUiChanging(call.name))
            check(!state.needsObservation || call.name == "open_app" && lastMutation() == null && state.actions.none { it.call.name == "get_ui_tree" }) { "Fresh UI is required" }
        transition(AgentStatus.EXECUTING, "tool=${call.name} arguments=${safeArguments(call.arguments)} goal=${state.currentStep?.id}", AgentStatus.PLANNING, AgentStatus.OBSERVING)
        state = state.copy(activeTool = call.copy(expectedUi = expectedOutcome(call)))
        transition(AgentStatus.WAITING_FOR_RESULT, "await actual result", AgentStatus.EXECUTING)
    }
    fun recoveryError(call: AgentToolCall): String? {
        val failed = lastMutation()?.takeIf { it.stepId == state.currentStep?.id && it.verified == false } ?: return null
        if (state.needsObservation || state.currentScreen == null || latestObservation()?.screenRevision != state.screenRevision)
            return "Fresh actual screen evidence is required before recovery"
        return if (ToolExecutionPolicy.isUiChanging(call.name) && actionKey(call) != actionKey(failed.call) && failed.expectedUi != null && call.recoveryReason.isNullOrBlank())
            "Action ${failed.id} did not meet its expectation. Choose a corrective action with recovery_reason using the actual screen." else null
    }
    fun latestObservation(): ActionRecord? = state.actions.lastOrNull { it.call.name == "get_ui_tree" && it.succeeded }
    fun validateToolResult(call: AgentToolCall, actual: McpToolResult): McpToolResult {
        val result = normalizeToolResult(call, actual)
        val expected = lastMutation()?.takeIf { it.stepId == state.currentStep?.id }?.expectedUi ?: state.currentStep?.expectedUi
        if (call.name == "get_ui_tree" && !result.isError && expected?.packageName != null &&
            UiObservation.parse(result.text)?.packageName.isNullOrBlank())
            return result.copy(isError = true, text = "Unusable UI observation: foreground package is required to verify the pending app outcome")
        return result
    }
    fun onToolResult(actual: McpToolResult) {
        check(state.status == AgentStatus.WAITING_FOR_RESULT)
        val call = checkNotNull(state.activeTool)
        val result = validateToolResult(call, actual)
        val changed = ToolExecutionPolicy.isUiChanging(call.name)
        val before = state.currentScreen
        val revision = state.screenRevision + if (changed) 1 else 0
        val record = ActionRecord(nextActionId++, checkNotNull(state.currentStep).id, call,
            !result.isError, ContextManager.truncateToolResult(call.name, result.text), revision,
            expectedUi = call.expectedUi, beforeScreen = if (changed) before else null)
        state = state.copy(actions = (state.actions + record).takeLast(32), activeTool = null, screenRevision = revision,
            currentScreen = if (changed || call.name == "get_ui_tree" && result.isError) null else if (call.name == "get_ui_tree") result.text else state.currentScreen,
            needsObservation = if (changed) true else if (call.name == "get_ui_tree") result.isError else state.needsObservation)
        log("TOOL_RESULT action=${record.id} tool=${call.name} is_error=${result.isError} result_chars=${result.text.length}")
        transition(if (changed) AgentStatus.WAITING_FOR_UI_SETTLE else if (result.isError) AgentStatus.RECOVERING else AgentStatus.CHECKING_PROGRESS,
            "action=${record.id} receipt_success=${record.succeeded} needs_observation=${state.needsObservation}", AgentStatus.WAITING_FOR_RESULT)
        if (call.name == "get_ui_tree" && !result.isError) {
            log("UI_OBSERVATION action=${record.id} package=${UiObservation.parse(result.text)?.packageName} fresh=true chars=${result.text.length}")
            verifyObservedAction(record)
        }
    }
    private fun verifyObservedAction(observation: ActionRecord) {
        val mutation = lastMutation()?.takeIf { it.stepId == observation.stepId && it.screenRevision == observation.screenRevision } ?: return
        val screen = state.currentScreen?.let { UiObservation.parse(it) } ?: return
        val expected = mutation.expectedUi
        val preexistingTapPredicate = mutation.call.name in setOf("tap", "tap_by_text") && expected != null && expected.focusedEditable != true && expected.editableText == null &&
            mutation.beforeScreen?.let { UiObservation.parse(it)?.matches(expected) } == true
        val verified = expected != null && screen.matches(expected) && !preexistingTapPredicate
        val reason = if (verified) "Expected UI outcome observed" else if (preexistingTapPredicate)
            "The tap expectation was already true before the action; use a destination-specific condition such as selected_text or focused_editable"
            else if (expected == null) "Action observed; choose next action from actual screen" else "Expected UI outcome not established"
        state = state.copy(actions = state.actions.map { if (it.id == mutation.id) it.copy(verified = verified, verificationReason = reason) else it })
        log("ACTION_VERIFICATION action=${mutation.id} observation=${observation.id} verified=$verified reason=$reason")
        if (!verified) transition(AgentStatus.RECOVERING, reason, AgentStatus.CHECKING_PROGRESS)
    }
    fun rejectDecision(reason: String) = transition(AgentStatus.CHECKING_PROGRESS, reason, AgentStatus.PLANNING, AgentStatus.OBSERVING)
    fun completeVerifiedForegroundGoal(): Boolean {
        val step = state.currentStep ?: return false
        val goal = step.expectedUi ?: return false
        if (goal.packageName == null || goal.text != null || goal.editableText != null || goal.focusedEditable != null) return false
        if (foregroundGoalPackage(step.successCondition) != goal.packageName) return false
        val action = lastMutation()?.takeIf { it.stepId == step.id && it.call.name == "open_app" && it.verified == true } ?: return false
        if (action.expectedUi?.packageName != goal.packageName) return false
        val observation = latestObservation() ?: return false
        // completeCurrentStep still enforces freshness, revision, source and the goal predicate.
        return completeCurrentStep(StepCompletion(step.id, observation.id, goal.packageName))
    }
    fun completeVerifiedUiGoal(): Boolean {
        if (completeVerifiedForegroundGoal()) return true
        val step = state.currentStep ?: return false
        val declared = inputGoalExpectation(step.successCondition) ?: return false
        val goal = step.expectedUi ?: return false
        // An explicit desired input can already be satisfied by the fresh screen.
        // Requiring another type action here would append duplicate text.
        if (declared.editableText == null) {
            val action = lastMutation()?.takeIf { it.stepId == step.id && it.verified == true } ?: return false
            if (action.call.name !in setOf("tap", "tap_by_text")) return false
        }
        val screen = state.currentScreen?.let { UiObservation.parse(it) } ?: return false
        if (!screen.matches(declared) || !screen.matches(goal)) return false
        val observation = latestObservation() ?: return false
        val quote = declared.editableText ?: screen.packageName ?: screen.visibleText.trim().take(200)
        if (quote.isBlank()) return false
        return completeCurrentStep(StepCompletion(step.id, observation.id, quote))
    }
    fun completeCurrentStep(proposal: StepCompletion): Boolean {
        check(state.status == AgentStatus.PLANNING)
        val step = state.currentStep ?: return false
        fun reject(reason: String): Boolean { log("STEP_VERIFICATION step=${step.id} verified=false reason=$reason"); return false }
        completionBlockReason()?.let { return reject(it) }
        if (proposal.stepId != step.id || pendingVerification() != null) return reject("Wrong goal or pending observation")
        if (lastMutation()?.let { it.stepId == step.id && it.call.name in setOf("tap", "tap_by_text") &&
                it.expectedUi?.focusedEditable == true && it.verified != true } == true)
            return reject("The intended input has not been observed focused after the tap")
        val source = state.actions.lastOrNull { it.id == proposal.actionId && it.succeeded } ?: return reject("No successful evidence source")
        if (ToolExecutionPolicy.isUiChanging(source.call.name)) return reject("A mutation receipt cannot prove goal completion")
        // A failed, known action outcome cannot be waived by quoting an unrelated
        // node on the wrong screen. A goal-specific predicate may instead prove
        // that recovery reached the actual requested outcome.
        val expected = step.expectedUi ?: lastMutation()
            ?.takeIf { it.stepId == step.id && it.verified == false }?.expectedUi ?: proposal.expectedUi
        val matched = if (source.call.name == "get_ui_tree") {
            val observation = latestObservation() ?: return reject("No UI evidence")
            if (source.id != observation.id || state.needsObservation || source.screenRevision != state.screenRevision) return reject("Stale UI evidence")
            val screen = state.currentScreen?.let { UiObservation.parse(it) } ?: return reject("Malformed observation")
            (expected == null || screen.matches(expected)) && (if (proposal.evidence.isNotBlank()) screen.containsEvidence(proposal.evidence) else expected != null)
        } else {
            source.stepId == step.id && expected == null && proposal.evidence.isNotBlank() &&
                UiObservation.normalize(source.result).contains(UiObservation.normalize(proposal.evidence))
        }
        if (!matched) return reject("Step outcome does not match observed nodes")
        val evidence = proposal.evidence.trim().ifBlank { expected.toString() }
        state = state.copy(plan = state.plan.map { if (it.id == step.id) it.copy(evidence = evidence) else it }, currentStepIndex = state.currentStepIndex + 1)
        log("GOAL_COMPLETION goal=${step.id} verified=true source_action=${source.id} source_tool=${source.call.name} evidence_chars=${evidence.length}")
        return true
    }
    fun finishTask() {
        check(state.plan.isNotEmpty() && state.currentStep == null && !requiresObservation() && pendingVerification() == null)
        transition(AgentStatus.COMPLETED, "all steps verified", AgentStatus.PLANNING)
    }
    fun failTask(reason: String) {
        if (state.status in setOf(AgentStatus.COMPLETED, AgentStatus.FAILED)) return
        transition(AgentStatus.FAILED, reason, AgentStatus.IDLE, AgentStatus.PLANNING, AgentStatus.EXECUTING,
            AgentStatus.WAITING_FOR_RESULT, AgentStatus.CHECKING_PROGRESS, AgentStatus.WAITING_FOR_UI_SETTLE, AgentStatus.OBSERVING, AgentStatus.RECOVERING)
        state = state.copy(failure = reason, activeTool = null)
    }
    fun duplicate(call: AgentToolCall): Boolean {
        if (!ToolExecutionPolicy.isUiChanging(call.name)) return false
        // Missing evidence means "observe first", not "this action already succeeded".
        // Execution/transition guards separately forbid bypassing pending observation/verification.
        if (state.needsObservation || pendingVerification() != null) return false
        val current = state.currentScreen?.takeIf { !state.needsObservation }?.let { UiObservation.parse(it) }
        if (call.name == "open_app" && current?.packageName != null && current.packageName == (call.arguments["package_name"] as? JsonPrimitive)?.contentOrNull) return true
        val last = state.actions.lastOrNull {
            it.stepId == state.currentStep?.id && ToolExecutionPolicy.isUiChanging(it.call.name) && actionKey(it.call) == actionKey(call)
        } ?: return false
        // A fresh, complete observation proving the expected effect is absent permits a bounded retry.
        if (last.verified == false && current != null && !current.truncated && last.expectedUi != null && !current.matches(last.expectedUi)) return false
        if (!last.succeeded && current != null && !current.truncated &&
            (last.expectedUi == null || !current.matches(last.expectedUi))) return false
        if (call.name == "open_app" && current?.packageName != null) return false
        if (last.verified == true && current != null && last.expectedUi != null) {
            val wanted = expectedOutcome(call)
            if (wanted != null && wanted != last.expectedUi && !current.matches(wanted)) return false
            if (current.matches(last.expectedUi)) return true
        }
        if (call.name in setOf("type_text", "type_into_field_near") || current == null) return true
        val before = last.beforeScreen?.let { UiObservation.parse(it) }
        return before == null || before.root == current.root
    }
    fun actionKey(call: AgentToolCall): String = call.name + ":" + canonical(call.arguments)
    private fun canonical(value: JsonElement): String = when (value) {
        is JsonObject -> value.toSortedMap().entries.joinToString(prefix = "{", postfix = "}") { "${it.key}:${canonical(it.value)}" }
        is JsonArray -> value.joinToString(prefix = "[", postfix = "]") { canonical(it) }
        is JsonPrimitive -> if (value.isString) value.toString() else value.doubleOrNull?.toString() ?: value.toString()
    }
}
