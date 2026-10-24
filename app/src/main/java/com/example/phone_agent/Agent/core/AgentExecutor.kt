package com.example.phone_agent.agent.core

import com.example.phone_agent.agent.mcp.ToolProvider
import com.example.phone_agent.agent.model.*
import com.example.phone_agent.agent.statemachine.AgentStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

data class ExecutionLimits(
    val maxTurns: Int = 48,
    val maxTools: Int = 40,
    val maxFailedAttempts: Int = 2,
    val maxRejectedDecisions: Int = 3,
    val maxJsonRetries: Int = 1,
    val timeoutMillis: Long = 600_000,
) {
    init {
        require(maxTurns > 0 && maxTools > 0 && maxFailedAttempts > 0 && maxRejectedDecisions > 0 && maxJsonRetries >= 0 && timeoutMillis > 0)
    }
}

/** Model proposes goals/actions; Kotlin owns progress, real evidence and execution policy. */
class AgentExecutor(
    private val provider: ToolProvider,
    private val completeJson: suspend (List<ChatMessage>) -> String,
    private val systemPrompt: (List<McpTool>) -> String,
    private val limits: ExecutionLimits = ExecutionLimits(),
    private val settle: suspend (String) -> Unit = { ToolExecutionPolicy.waitForUiSettle(it) },
    private val log: (String) -> Unit = {},
) {
    private val json = Json { encodeDefaults = false }

    suspend fun run(command: String, progress: (String) -> Unit = {}): AgentRunResult {
        val machine = AgentStateMachine(command, log)
        val results = mutableListOf<McpToolResult>()
        var turns = 0
        fun stopped(reason: String): AgentRunResult {
            machine.failTask(reason)
            log("FINAL_OUTCOME success=false reason=${reason.take(300)}")
            val remaining = machine.state.pendingSteps.joinToString { it.description }
            return AgentRunResult("I couldn't finish: $reason" + if (remaining.isBlank()) "" else ". Remaining: $remaining", results.toList(), turns)
        }
        if (command.isBlank()) return stopped("Please enter a command")
        return try {
            withTimeoutOrNull(limits.timeoutMillis) {
                progress("Connecting to phone tools")
                provider.initialize()
                val tools = provider.listTools()
                if (tools.isEmpty()) return@withTimeoutOrNull stopped("No tools are available")
                val instructions = systemPrompt(tools)
                val parser = PlanParser()
                val failures = mutableMapOf<String, Int>()
                val repeated = mutableMapOf<String, Int>()
                var stalled = 0
                var rejected = 0
                var malformed = 0
                var feedback = "Return type=plan with user-level goals and success conditions. No tool calls in a plan."
                var firstScreen = true
                var observationPage = 0
                fun progressMade(reason: String) {
                    stalled = 0; rejected = 0; repeated.clear()
                    observationPage = 0
                    log("PROGRESS goal=${machine.state.currentStep?.id} reason=$reason")
                }
                fun reject(reason: String) {
                    feedback = reason
                    rejected++
                    machine.rejectDecision(reason)
                    log("REJECTED goal=${machine.state.currentStep?.id} count=$rejected reason=$reason")
                }
                suspend fun execute(call: AgentToolCall): String? {
                    if (results.size >= limits.maxTools) return "Tool execution limit reached"
                    if (ToolExecutionPolicy.isUiChanging(call.name)) {
                        if (tools.none { it.name == "get_ui_tree" }) return "Fresh screen observation is unavailable"
                        if (results.size + 2 > limits.maxTools) return "Tool limit leaves no budget to observe the UI action"
                    }
                    val key = machine.actionKey(call)
                    val previous = machine.state.actions.lastOrNull { ToolExecutionPolicy.isUiChanging(it.call.name) }
                    val pending = machine.pendingVerification()
                    log("SELECTED_TOOL goal=${machine.state.currentStep?.id} name=${call.name} validated_arguments=${safeArguments(call.arguments)} attempt=${(failures[key] ?: 0) + 1}")
                    machine.startToolExecution(call)
                    progress("Using ${call.name}")
                    val result = try {
                        machine.validateToolResult(call, provider.callTool(call.name, call.arguments))
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (e: Exception) { McpToolResult("Tool exception: ${e.message?.take(500) ?: e.javaClass.simpleName}", true, buildJsonObject {}) }
                    results += result
                    observationPage = 0
                    machine.onToolResult(result)
                    if (result.isError) failures[key] = (failures[key] ?: 0) + 1
                    else if (!ToolExecutionPolicy.isUiChanging(call.name)) failures.remove(key)
                    log("TOOL_RETRY_COUNT tool=${call.name} failures=${failures[key] ?: 0} limit=${limits.maxFailedAttempts}")
                    if (call.name == "get_ui_tree" && !result.isError) {
                        if (firstScreen) { firstScreen = false; progressMade("first usable observation") }
                        val mutation = machine.state.actions.lastOrNull { ToolExecutionPolicy.isUiChanging(it.call.name) }
                        if (mutation != null && mutation.stepId == machine.state.currentStep?.id) {
                            val mutationKey = machine.actionKey(mutation.call)
                            if (mutation.verified == true) {
                                failures.remove(mutationKey)
                                if (previous?.id == mutation.id && previous.verified != true) progressMade("action effect verified")
                            } else if (pending?.id == mutation.id && mutation.succeeded && mutation.expectedUi != null)
                                failures[mutationKey] = (failures[mutationKey] ?: 0) + 1
                            else if (pending?.id == mutation.id && mutation.succeeded && mutation.expectedUi == null &&
                                mutation.beforeScreen?.let { UiObservation.parse(it)?.root } != null &&
                                UiObservation.parse(mutation.beforeScreen)?.root != machine.state.currentScreen?.let { UiObservation.parse(it)?.root })
                                progressMade("actual screen changed after action; goal still requires evidence")
                        }
                    }
                    if (ToolExecutionPolicy.isUiChanging(call.name)) {
                        machine.startUiSettling()
                        progress("Waiting for phone UI")
                        settle(call.name)
                        machine.onUiSettled()
                    }
                    val last = machine.state.actions.last()
                    feedback = "Actual tool result recorded as action ${last.id}. Choose the next action for the current goal from this state."
                    if (result.isError) feedback = "Action ${last.id} failed. Use its actual error and fresh observation to choose a recovery action."
                    else if (call.name == "get_ui_tree") {
                        val previousAction = machine.state.actions.lastOrNull { ToolExecutionPolicy.isUiChanging(it.call.name) }
                        feedback = "Assess the current goal's success_condition against this fresh observation first. Complete with evidence if achieved; otherwise choose a continuation or recovery using this page."
                        if (previousAction?.call?.name == "press_enter" && previousAction.succeeded)
                            feedback += " Enter was already executed as action ${previousAction.id}; do not resubmit. Inspect actual results, loading state or any blocking dialog. Decline optional permissions rather than grant unrequested access."
                    }
                    return null
                }
                while (turns < limits.maxTurns || machine.requiresObservation()) {
                    if (machine.requiresObservation()) {
                        val observation = AgentToolCall("get_ui_tree")
                        if (tools.none { it.name == observation.name }) return@withTimeoutOrNull stopped("Fresh screen observation is unavailable")
                        if ((failures[machine.actionKey(observation)] ?: 0) >= limits.maxFailedAttempts)
                            return@withTimeoutOrNull stopped("Could not obtain a fresh screen observation within the retry limit: ${machine.state.actions.lastOrNull()?.result}")
                        if (machine.state.status != AgentStatus.OBSERVING) machine.startObservation()
                        log("MANDATORY_OBSERVATION goal=${machine.state.currentStep?.id} fresh=false")
                        execute(observation)?.let { return@withTimeoutOrNull stopped(it) }
                        continue
                    }
                    machine.startPlanning()
                    if (machine.completeVerifiedUiGoal()) {
                        progressMade(if (machine.state.completedSteps.last().expectedUi?.packageName != null)
                            "foreground goal verified by Kotlin against fresh UI" else "declared input goal verified by Kotlin against fresh UI")
                        if (machine.state.currentStep == null) {
                            machine.finishTask()
                            log("FINAL_OUTCOME success=true goals=${machine.state.completedSteps.size}")
                            return@withTimeoutOrNull AgentRunResult(machine.state.completedSteps.joinToString("\n") { it.evidence.orEmpty() }, results.toList(), turns)
                        }
                        feedback = "Previous goal is verified. Choose an action for ${machine.state.currentStep?.id} from the current screen."
                    }
                    val planning = machine.state.plan.isEmpty()
                    progress(if (planning) "Planning goals" else "Deciding: ${machine.state.currentStep?.description}")
                    turns++
                    val parsed = try {
                        val request = if (planning && malformed > 0) listOf(
                            ChatMessage("system", "Required plan schema: {\"type\":\"plan\",\"goals\":[{\"description\":\"user-level goal\",\"success_condition\":\"observable success condition\"}]}. Propose 1-8 concise goals. Kotlin assigns goal IDs and verifies real tool results."),
                            ChatMessage("user", command),
                            ChatMessage("user", buildJsonObject {
                                put("request_phase", "plan"); put("response_retry", malformed); put("next_decision", feedback)
                            }.toString()),
                        ) else listOf(ChatMessage("system", AgentPrompt.forPhase(instructions, planning)), ChatMessage("user", command),
                            ChatMessage("user", statePrompt(machine, tools, planning, feedback, malformed, observationPage > 0, observationPage)))
                        log("MODEL_REQUEST phase=${if (planning) "plan" else "decision"} messages=${request.size} chars=${request.sumOf { it.content.length }} input_context=8192 retry=$malformed")
                        parser.parse(completeJson(request), planning)
                    } catch (e: InvalidLlmResponseException) { PlanParser.Result(error = e.message ?: "Invalid Ollama output") }
                    if (parsed.response == null) {
                        malformed++
                        log("PARSE_FAILURE phase=${if (planning) "plan" else "decision"} attempt=$malformed detail=${parsed.error}")
                        if (malformed > limits.maxJsonRetries.coerceAtMost(1)) return@withTimeoutOrNull stopped("Repeated invalid model responses: ${parsed.error}")
                        reject(if (planning) "Return only the plan object matching the required schema. Do not repeat the input state."
                            else "Return only the decision object matching the required schema. Do not repeat the input state.")
                        continue
                    }
                    malformed = 0
                    val decision = parsed.response
                    if (decision.type == AgentResponseType.STOP) return@withTimeoutOrNull stopped(requireNotNull(decision.reason))
                    if (decision.type == AgentResponseType.PLAN) {
                        val goals = requireNotNull(decision.goals)
                        machine.onPlanCreated(goals.map { it.description }, goals.mapIndexedNotNull { i, goal -> goal.expectedUi?.let { "s${i + 1}" to it } }.toMap(), goals.map { it.successCondition })
                        feedback = "Goals stored. Choose one action for goal ${machine.state.currentStep?.id}. Use actual tool schemas."
                        machine.rejectDecision("goals stored; select action dynamically")
                        continue
                    }
                    val cachedInspection = decision.action?.let { it.name == "get_ui_tree" && it.recoveryReason == "expand_cached_observation" } == true
                    if (!cachedInspection) stalled++
                    val key = "${machine.state.currentStep?.id}:" + when (decision.type) {
                        AgentResponseType.ACT -> machine.actionKey(requireNotNull(decision.action)) + if (cachedInspection) ":cached:$observationPage" else ""
                        else -> "complete:${decision.goalId}:${decision.evidence?.text}:${decision.evidence?.expectedUi}"
                    }
                    val repeats = (repeated[key] ?: 0) + 1
                    repeated[key] = repeats
                    log("MODEL_DECISION goal=${machine.state.currentStep?.id} type=${decision.type} no_progress=$stalled repeats=$repeats fresh=${!machine.state.needsObservation}")
                    if (stalled > limits.maxRejectedDecisions || repeats > 1 && repeats >= limits.maxRejectedDecisions)
                        return@withTimeoutOrNull stopped("Repeated model decisions made no verified goal progress: $feedback")
                    if (decision.goalId != machine.state.currentStep?.id) {
                        reject("Wrong goal_id. Continue the current unfinished goal ${machine.state.currentStep?.id}.")
                    } else if (decision.type == AgentResponseType.COMPLETE) {
                        val evidence = requireNotNull(decision.evidence)
                        if (machine.completeCurrentStep(StepCompletion(requireNotNull(decision.goalId), evidence.actionId, evidence.text, evidence.expectedUi))) {
                            progressMade("goal completed with real evidence")
                            if (machine.state.currentStep == null) {
                                machine.finishTask()
                                // Report actual observed excerpts, not an unverified model-written final claim.
                                val answer = machine.state.completedSteps.joinToString("\n") { it.evidence.orEmpty() }
                                log("FINAL_OUTCOME success=true goals=${machine.state.completedSteps.size}")
                                return@withTimeoutOrNull AgentRunResult(answer, results.toList(), turns)
                            }
                            feedback = "Goal verified. Select the next action for ${machine.state.currentStep?.id} using the current observation."
                            machine.rejectDecision("next goal")
                        } else reject("Completion rejected. Cite a successful real read result or current UI action_id and observed text supporting the goal's success condition.")
                    } else {
                        val call = requireNotNull(decision.action)
                        val tool = tools.firstOrNull { it.name == call.name }
                        val invalid = if (tool == null) "Unknown tool ${call.name}" else validateArguments(tool.inputSchema, call.arguments)
                        if (invalid != null) reject(invalid)
                        else if (ToolExecutionPolicy.preconditionError(call.name, machine.state.currentScreen, machine.state.currentStep?.expectedUi?.editableText) != null)
                            reject(requireNotNull(ToolExecutionPolicy.preconditionError(call.name, machine.state.currentScreen, machine.state.currentStep?.expectedUi?.editableText)))
                        else if (call.name == "type_text" && machine.state.currentStep?.expectedUi?.editableText?.let { desired ->
                            val before = machine.state.currentScreen?.let { UiObservation.parse(it)?.inputText() }
                            before != null && before + (call.arguments["text"] as? JsonPrimitive)?.contentOrNull.orEmpty() != desired
                        } == true) reject("Append only the missing suffix of the declared input value, or use the set-field tool.")
                        else if (call.name == "get_ui_tree" && call.recoveryReason == "expand_cached_observation" && machine.state.currentScreen != null && !machine.state.needsObservation) {
                            val full = requireNotNull(machine.state.currentScreen)
                            val goal = machine.state.currentStep
                            val pages = UiObservation.parse(full)?.decisionTree("${goal?.description} ${goal?.successCondition} ${machine.state.command}")
                                ?.get("page_count")?.jsonPrimitive?.intOrNull ?: 2
                            if (observationPage >= minOf(pages, 2) - 1) reject("Cached inspection budget exhausted; assess the real results or stop.")
                            else {
                                observationPage++
                                feedback = "Cached UI detail page $observationPage is now supplied with the same observation ID. No phone action occurred."
                                machine.rejectDecision("inspect cached observation detail")
                                log("CACHED_OBSERVATION_EXPANDED goal=${machine.state.currentStep?.id} page=$observationPage")
                            }
                        }
                        else if (call.name == "get_ui_tree" && machine.state.currentScreen != null && !machine.state.needsObservation && call.recoveryReason.isNullOrBlank())
                            reject("A current usable UI tree is already supplied. Select an action or complete the goal; use recovery_reason only if a new observation is actually needed.")
                        else if (machine.duplicate(call)) reject("Duplicate confirmed action rejected: ${call.name}. Use the observed outcome to continue or complete the goal.")
                        else if (ToolExecutionPolicy.isUiChanging(call.name) && call.name != "open_app" && machine.state.needsObservation) {
                            if (tools.none { it.name == "get_ui_tree" }) return@withTimeoutOrNull stopped("Fresh screen observation is unavailable")
                            machine.startObservation()
                            execute(AgentToolCall("get_ui_tree"))?.let { return@withTimeoutOrNull stopped(it) }
                        } else {
                            val recovery = machine.recoveryError(call)
                            if (recovery != null) reject(recovery)
                            else if ((failures[machine.actionKey(call)] ?: 0) >= limits.maxFailedAttempts) reject("Retry limit for ${call.name}; choose another evidence-grounded recovery action or stop.")
                            else {
                                execute(call)?.let { return@withTimeoutOrNull stopped(it) }
                                continue
                            }
                        }
                    }
                    if (rejected >= limits.maxRejectedDecisions) return@withTimeoutOrNull stopped("Repeated rejected decisions: $feedback")
                }
                stopped("LLM decision limit reached")
            } ?: stopped("Task time limit reached; check the phone before retrying actions")
        } catch (cancel: CancellationException) {
            machine.failTask("Cancelled; an in-flight action may have happened")
            throw cancel
        } catch (e: Exception) { stopped("Agent or connection error: ${e.message?.take(500) ?: e.javaClass.simpleName}") }
    }

    internal fun statePrompt(machine: AgentStateMachine, tools: List<McpTool>, planning: Boolean, feedback: String, responseRetry: Int, expanded: Boolean, page: Int = if (expanded) 1 else 0): String = buildJsonObject {
        val s = machine.state
        put("request_phase", if (planning) "plan" else "decision")
        put("response_retry", responseRetry)
        put("authoritative_state", true)
        put("command", s.command)
        put("goals", buildJsonArray { s.plan.forEach { goal -> add(buildJsonObject {
            put("id", goal.id); put("completed", goal.evidence != null)
        }) } })
        put("current_goal", s.currentStep?.id)
        s.currentStep?.let { goal -> put("current_goal_detail", buildJsonObject {
            put("id", goal.id); put("description", goal.description); put("success_condition", goal.successCondition)
            goal.expectedUi?.let { put("expected_ui", json.encodeToJsonElement(UiExpectation.serializer(), it)) }
        }) }
        if (s.currentStep?.expectedUi?.focusedEditable == true) {
            put("input_target_guidance", "Target an actual editable node for this input goal, not a logo, heading or Search button. Use its observed text/description/hint with a matching semantic tool, or its bounds with tap.")
        }
        put("needs_observation", s.needsObservation)
        val finalAssessment = feedback.startsWith("Cached inspection budget exhausted") && machine.completionBlockReason() == null
        put("final_assessment", finalAssessment)
        val lastMutation = s.actions.lastOrNull { ToolExecutionPolicy.isUiChanging(it.call.name) }
        put("recovery_required", lastMutation?.let { it.stepId == s.currentStep?.id && it.verified == false && it.expectedUi != null } == true)
        machine.completionBlockReason()?.let { put("completion_blocked_reason", it) }
        val usableInput = s.currentScreen?.let { UiObservation.parse(it)?.matches(UiExpectation(focusedEditable = true)) } == true
        val redundantRead = !s.needsObservation && s.currentScreen != null &&
            (usableInput || lastMutation?.call?.name == "open_app" && s.currentScreen!!.length < 7_000)
        if (redundantRead) put("observation_guidance", "The current UI is usable. Select the next action from its actual controls; another screen read is unnecessary. A focused correct search query needs submission.")
        put("available_tools", buildJsonArray { tools.filter { planning ||
            !(it.name == "get_ui_tree" && redundantRead) && ToolExecutionPolicy.preconditionError(it.name, s.currentScreen, s.currentStep?.expectedUi?.editableText) == null }.forEach { add(buildJsonObject {
            put("name", it.name); put("description", it.description); put("input_schema", it.inputSchema)
        }) } })
        if (!planning) put("unavailable_tools", buildJsonArray { tools.forEach { tool ->
            (if (tool.name == "get_ui_tree" && redundantRead) "Current screen is already usable; choose an action or supported completion."
                else ToolExecutionPolicy.preconditionError(tool.name, s.currentScreen, s.currentStep?.expectedUi?.editableText))?.let { reason -> add(buildJsonObject {
                put("name", tool.name); put("reason", reason)
            }) }
        } })
        machine.latestObservation()?.let { observation ->
            val reads = s.actions.filter { it.call.name == "get_ui_tree" && it.succeeded }.takeLast(2)
            val unchanged = reads.size == 2 && reads[0].screenRevision == reads[1].screenRevision && reads[0].result == reads[1].result
            put("observation_unchanged", unchanged)
            if (unchanged) put("observation_guidance", "The last two real reads are identical. Do not refresh again. Assess completion from this evidence, inspect omitted cached detail, or choose a necessary recovery action.")
            put("ui_observation", buildJsonObject {
                put("source", "ToolProvider"); put("action_id", observation.id)
                put("fresh", !s.needsObservation && observation.screenRevision == s.screenRevision)
                put("screen_revision", observation.screenRevision)
                val full = Json.parseToJsonElement(s.currentScreen ?: observation.result).jsonObject
                put("tree", UiObservation.parse(full.toString())?.decisionTree("${s.currentStep?.description} ${s.currentStep?.successCondition} ${s.command}", page) ?: full)
                put("detail", if (expanded && full.toString().length <= 7_000) "full" else if (expanded) "cached_detail_page" else "semantic_nodes")
                put("full_tree_available", true)
                if (finalAssessment) {
                    val observed = UiObservation.parse(full.toString())
                    put("readable_result_text", observed?.nodes.orEmpty().filter { node ->
                        node["editable"]?.toString() != "true" && node["children"] == null
                    }.flatMap { node -> listOf("text", "desc").mapNotNull { (node[it] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) } }
                        .distinct().joinToString("\n").take(4_000))
                }
            })
            val verified = s.actions.lastOrNull { ToolExecutionPolicy.isUiChanging(it.call.name) }
            if (!s.needsObservation && verified != null && verified.stepId == s.currentStep?.id && verified.verified == true) {
                put("completion_guidance", "Action ${verified.id} is already UI-verified. If this satisfies the current goal, return type=complete with goal_id=${s.currentStep?.id}, action_id=${observation.id} and an actual observed excerpt. Do not replay the verified action. Otherwise choose the next tool for the remaining goal outcome.")
            }
        }
        put("recent_results", buildJsonArray { s.actions.takeLast(4).forEach { action -> add(buildJsonObject {
            put("source", "ToolProvider"); put("action_id", action.id); put("goal_id", action.stepId)
            put("tool", action.call.name); put("arguments", action.call.arguments)
            put("receipt_success", action.succeeded)
            if (action.expectedUi != null) action.verified?.let { put("ui_verified", it) }
            if (ToolExecutionPolicy.isUiChanging(action.call.name)) put("effect_status", when {
                !action.succeeded -> "tool_failed"
                action.verified == null -> "awaiting_observation"
                action.expectedUi == null -> "observed_unassessed"
                action.verified == true -> "verified"
                else -> "expected_effect_missing"
            })
            action.verificationReason?.let { put("verification", it) }
            if (action.call.name != "get_ui_tree" || !action.succeeded) put("result", action.result.take(1_000))
        }) } })
        put("next_decision", if (finalAssessment) "The action/inspection budget is exhausted. Assess the current goal from readable_result_text, copied from real non-editable UI nodes. Return COMPLETE with a short actual result excerpt and the observation ID if achieved, otherwise STOP with the concrete unmet condition. Do not request another tool." else feedback)
    }.toString()

    private fun validateArguments(schema: JsonObject, args: JsonObject): String? {
        val properties = schema["properties"] as? JsonObject ?: return null
        val required = (schema["required"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        if (required.any { it !in args }) return "Missing required arguments: ${required.filter { it !in args }}"
        if ((schema["additionalProperties"] as? JsonPrimitive)?.booleanOrNull == false && args.keys.any { it !in properties }) return "Unknown argument"
        for ((name, value) in args) {
            val type = (properties[name] as? JsonObject)?.get("type")?.jsonPrimitive?.content
            val p = value as? JsonPrimitive
            val valid = when (type) {
                "string" -> p != null && p.isString && p.content.isNotBlank()
                "number" -> p != null && !p.isString && p.doubleOrNull?.isFinite() == true
                "integer" -> p != null && !p.isString && p.longOrNull != null
                "boolean" -> p != null && !p.isString && p.booleanOrNull != null
                "object" -> value is JsonObject
                "array" -> value is JsonArray
                else -> true
            }
            if (!valid) return "Invalid argument '$name': expected $type"
        }
        return null
    }
}
