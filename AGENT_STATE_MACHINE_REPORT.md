# Friday: goal-oriented dynamic tool selection

Updated 24 October 2026 (Asia/Calcutta).

## Result and scope

Friday now stores user-level goals once and chooses exactly one next tool dynamically from the current goal, actual discovered schemas, real recent results and current UI. It does not store a predetermined sequence of future tool calls. The existing FridayAgent, AgentExecutor, AgentStateMachine, ToolProvider/MCP and accessibility-service structure remains.

The external FridayAgent/ToolProvider entry points remain compatible. The model's wire contract intentionally changes: legacy plan/tool_calls/final and undocumented aliases are rejected. Models, parser, executor and bundled prompt use the same typed contract.

## Root causes inspected before editing

1. Optional default fields and permissive unknown-key parsing allowed incompatible JSON to become an empty plan. JSON syntax was being confused with application-schema validity.
2. The old prompt mixed competing response structures and planning with future tool calls. It encouraged speculative tool sequences before observing the actual screen.
3. Executor/state-machine recovery rules could forbid a different corrective tool even after an actual failed result and fresh observation. Conversely, a completion could quote an unrelated node on the wrong screen after a failed launch.
4. Repeated model proposals and repeated successful screen reads could consume requests without verified goal progress. Missing observations, stale IDs/revisions and receipt success needed independent treatment.
5. Fixed 512-token output and missing completion/truncation checks made malformed or partial responses hard to distinguish from valid decisions.
6. Replaying history and duplicating tool information increased prompt work. Bounded historical UI summaries could also omit a current control or answer needed by the next decision.

Inspected the actual ToolRegistry schemas, PhoneControlService tree serialization, MCP content/isError/structuredContent handling, execution policy, state classes and current executor before changing the response contract.

## Execution and evidence

1. Request type=plan: 1-8 goals, each with description and success_condition; optional known expected_ui. Store the complete plan and assign stable s1/s2 IDs.
2. Request one type=act or type=complete decision for the current goal. Supply exact discovered tool descriptions/input schemas, actual recent results and the current tree once. Each request contains three messages; prior conversation and old trees are not replayed.
3. Validate goal ID, tool name, arguments and the response type before any ToolProvider call. Validation covers the actual registry's flat schemas: required properties, types and additionalProperties=false.
4. Execute one real tool. Any UI-changing attempt, including a failed call, enters WAITING_FOR_UI_SETTLE then OBSERVING. Reserve budget for the observation. No model decision or subsequent mutation bypasses it.
5. Validate the actual get_ui_tree result. Blank, malformed, structurally invalid, empty/unusable trees and missing required foreground packages count as failed observations. Retry automatically within the limit, then stop with a clear error.
6. Verify known action predicates against actual parsed package/text/desc/editable/focused nodes. Unknown future UI outcomes are allowed: observe their actual result and decide again. A real screen change after such an action permits continued navigation without completing the goal.
7. Accept goal completion only from a successful real read result or the current fresh UI observation, with matching source ID/revision and an actual quoted excerpt. Mutation receipts cannot prove completion. Frozen goal conditions cannot be weakened; a failed known action condition cannot be waived using an unrelated completion predicate.
8. Advance only after goal completion is accepted. The final answer consists of accepted actual evidence excerpts, without an unverified model-written success summary.

Recovery may select a different corrective action using the actual error and fresh screen, with recovery_reason when a known outcome failed. This changes the old overly restrictive identical-retry-only rule while preserving mandatory observation. Failed unknown actions can retry after fresh evidence; duplicate successful effects and already-open apps are blocked. Repeated proposals, rejected completions, redundant observations, failed attempts, tool count, decision count and total time remain bounded.

## Files changed for this refactor

Paths are relative to this repository.

- app/src/main/java/com/example/phone_agent/Agent/core/AgentExecutor.kt: separate goal planning/action decisions, exact-schema validation, actual-result context, observation/recovery/duplicate limits, bounded malformed-output retries and evidence-only final output.
- app/src/main/java/com/example/phone_agent/Agent/core/AgentStateMachine.kt: stored success conditions, result-versus-effect verification, fresh-screen recovery, source/revision completion guards, failed-action retries and plan/action logging.
- app/src/main/java/com/example/phone_agent/Agent/core/PlanParser.kt: strict typed response parsing, mandatory/mutually exclusive fields, phase validation, nested unknown-key rejection and sanitized parse diagnostics.
- app/src/main/java/com/example/phone_agent/Agent/core/OllamaOutput.kt: new small pure helper for transport validation and phase/retry output budgets; permits additive transport metadata only.
- app/src/main/java/com/example/phone_agent/Agent/core/UiObservation.kt: shared parsed-UI evidence handling and privacy-safe argument logging used by the executor/state machine.
- app/src/main/java/com/example/phone_agent/Agent/model/AgentModels.kt: explicit plan/act/complete/stop response type, goal and completion evidence models, Ollama done_reason.
- app/src/main/java/com/example/phone_agent/Agent/statemachine/AgentState.kt: store each goal's success condition with its existing state/evidence.
- app/src/main/java/com/example/phone_agent/Agent/llm/OllamaLLMClient.kt: reject HTTP errors, empty/truncated/invalid JSON output; phase-specific output limits; timing/server metrics; optional raw-content diagnostics.
- app/src/main/java/com/example/phone_agent/Agent/FridayAgent.kt: retain delegation and consistent FridayAgent logs; avoid duplicating schema listings in the system prompt.
- app/src/main/assets/HardPrompt.txt: one concise documented contract, dynamic selection, actual evidence and recovery rules.
- app/src/test/java/com/example/phone_agent/agent/AgentExecutionTest.kt and UiVerificationTest.kt: migrate 54 regression scenarios to the typed contract and updated recovery/evidence rules.
- app/src/test/java/com/example/phone_agent/agent/FixtureSession.kt: test-only scenario adapter producing the strict new wire format. Production does not accept legacy aliases.
- app/src/test/java/com/example/phone_agent/agent/ResponseContractTest.kt: six response/transport/schema tests.
- app/src/test/java/com/example/phone_agent/agent/DynamicAgentTest.kt: eleven dynamic execution/evidence tests using actual registry schemas.
- AGENT_STATE_MACHINE_REPORT.md: this report.

Earlier workspace changes supporting the executor (factory, status, bounded-history helper, MCP/accessibility outcome handling and registry logging) remain in place. The unrelated UI endpoint edit was preserved. No new dependencies or tool names were added to production.

## Ollama behavior and logging

Plans retain the currently configured output limit of 256 tokens; single decisions retain 128. Output limits do not increase on retry. Schema/parser or truncated-output failure allows at most one retry, even if a caller supplies a larger retry setting; zero retries remains supported. A planning retry uses a compact schema reminder and original command instead of resending the full system prompt, tools or execution state. Its instruction is: "Return only the plan object matching the required schema. Do not repeat the input state." done_reason=length is explicitly logged as TRUNCATED_RESPONSE and consumes the same single retry budget. Unfinished done=false, done_reason=length, empty content, invalid JSON and application-schema errors are distinguished and bounded. Valid JSON such as {} is still rejected as an application response.

Normal logs retain request count/character count, HTTP/body/parse durations, returned numeric server duration/count metrics, plan goals, selected tool and redacted arguments, result metadata, observation freshness, verification/retry decisions and final status. Raw Ollama body/content logs remain available via logResponseContent=true, disabled by default to avoid exposing UI/message content. Results and UI are retained in execution state; normal Logcat logs use metadata instead of dumping sensitive payloads.

The changes remove unnecessary history replay and repeated rejected requests. The earlier reported 15-39 second model latency has not been reproduced or benchmarked against a live Ollama server here; no unsupported hardware/network diagnosis or latency guarantee is claimed. Larger actual screens can still require substantial prompt processing. Full current UI evidence is sent once so important controls/results are not lost to historical truncation.

## Validation performed

Command:

    .\gradlew.bat testDebugUnitTest assembleDebug --offline

Result: BUILD SUCCESSFUL. 78 JVM tests passed, zero failures/errors:

- AgentExecutionTest: 26
- UiVerificationTest: 29
- ResponseContractTest: 9
- DynamicAgentTest: 13
- Existing ExampleUnitTest: 1

The dynamic weather tests adapt to two observed layouts: an address bar requiring tap/type/submit, and a labeled field using type_into_field_near/submit. Another weather test recovers from a failed tap using the real error and newly observed editable field. They assert exact discovered schemas, one stored plan, observation after every mutation, no model call while observation is pending, no redundant tree reads, one Chrome launch and final weather text taken from the actual fixture result.

Additional coverage includes long navigation without predicting future screens, failed generic-action retries only after observation, full current-tree evidence beyond historical summaries, non-UI read completion, fabricated evidence/mutation-receipt rejection, missing/wrong/extra arguments, strict required fields, incompatible response types, empty/truncated Ollama output and bounded retries. Existing tests cover observation failures, transitions, stale IDs/revisions, duplicate normalization, failed verification, frozen conditions, cancellation/timeouts and task completion.

git diff --check passes. Debug APK: app/build/outputs/apk/debug/app-debug.apk.

## Practical limits and manual check

No live phone/Ollama weather run or APK installation was performed for this refactor. Deterministic tests establish controller behavior, not universal completion of arbitrary phone commands. Natural-language relevance is still a model judgment; Kotlin verifies source provenance and observable predicates, not arbitrary intent. Accessibility may omit controls or truncate individual strings; permissions, external dialogs, loading pages and model quality can prevent completion. Cached observations remain current relative to agent actions; asynchronous/external changes require an explained refresh.

For manual validation, install the generated APK, enable/reconnect Phone Control accessibility, start the MCP server, confirm the saved Ollama endpoint is reachable, then request: Open Chrome and check today's weather. Compare the returned values with the actual results page.

Logcat filter: adb logcat -s FridayAgent:D OLLAMA:D '*:S'. Expect FULL_PLAN/PLAN_GOAL, SELECTED_TOOL, WAITING_FOR_UI_SETTLE -> OBSERVING, UI_OBSERVATION, ACTION_VERIFICATION and GOAL_COMPLETION. Failed observations must show bounded retry counts or FINAL_OUTCOME success=false. Never infer live success merely from a tool receipt.

## Follow-up: structured-output schema and executor handoff

The Ollama structured-output format required only type for decisions and allowed action, evidence and reason in the same object. PlanParser correctly required mutually exclusive fields. This meant an output permitted by the generation schema could still be rejected before execution.

Changed OllamaOutput.responseFormat to use explicit oneOf alternatives: plan requires type/goals; act requires type/goal_id/action; complete requires type/goal_id/evidence; stop requires type/reason. Each alternative prohibits unrelated fields. Nested action/evidence objects prohibit unknown fields and require their documented payloads. Expected UI fields are typed consistently and available for goals, actions and completion evidence. Planning also permits the documented stop response. Actual tool arguments continue to be validated against discovered MCP schemas by AgentExecutor; goal IDs and actual evidence remain Kotlin-owned. Single-retry limits and fixed token budgets remain unchanged.

Added two schema contract regressions and one raw Ollama-envelope execution regression. The execution regression sends a real-format plan, open_app decision and completion through OllamaOutput, PlanParser and AgentExecutor to a test ToolProvider using actual ToolRegistry schemas. It asserts exactly one open_app, its following get_ui_tree, no parser/rejection retry, and execution/result/observation/completion log markers.

Changed files in this follow-up: OllamaOutput.kt, ResponseContractTest.kt, DynamicAgentTest.kt and this report. Validation: testDebugUnitTest assembleDebug --offline succeeded; 75 tests passed. No live Ollama constrained-generation or phone run was performed, so the tests establish schema structure and controller handoff rather than universal model/device reliability.

## Follow-up: captured 14:36 post-launch stop

The supplied trace already contained a successful Chrome launch and fresh observation 2 with the editable search field. Its first post-launch response selected stop and placed a long analysis in reason; done_reason=length truncated that response. The single retry returned another stop with analysis instead of a real blocker. No tap or input action was attempted. Goal s1 remained current despite its explicit condition: com.android.chrome is the current foreground package.

Changes:

- AgentStateMachine recognizes only the narrowly defined foreground-package condition above (package varies). It maps that explicit predicate to the existing UiExpectation and can complete that launch-only goal from a freshly observed matching package and verified open_app action. It still calls the existing completion verifier, which enforces successful source, observation freshness and revision. Receipts alone, wrong packages, generic descriptions and compound weather/search goals do not auto-complete. A package-only expected_ui on an otherwise compound goal does not enable automatic completion.
- AgentExecutor checks this foreground completion before the next LLM request, updates the current goal, and preserves the current observed tree for the next decision. Other goals still require model completion decisions verified against actual evidence. It supplies explicit current_goal_detail and completion_guidance for verified actions without assuming the entire user request is done.
- OllamaOutput now constrains action generation to the actual discovered tool names and exact argument schemas, and constrains goal_id to Kotlin's current goal. Runtime validation remains. Stop reasons are constrained to 200 characters. Fixed token budgets, truncation rejection and the single retry limit remain unchanged.
- HardPrompt requires an explicit foreground-package condition for launch-only goals and explains that stop must describe a genuine blocker, not analysis or intended next actions.

Files changed: AgentStateMachine.kt, AgentExecutor.kt, OllamaOutput.kt, HardPrompt.txt, DynamicAgentTest.kt, UiVerificationTest.kt, ResponseContractTest.kt and this report.

Regression coverage adds the captured four-goal command shape (launch/focus/type/submit), requiring that no further model decision for the already verified launch goal occurs and that every subsequent mutation is observed. Additional checks cover receipt-only/wrong-package/compound-goal refusal and discovered-tool/argument/current-goal generation constraints. All 78 JVM tests pass and assembleDebug succeeds with the offline Gradle command above. This is deterministic controller/model-substitute validation, not a live qwen3:4b/Chrome execution. The model can still choose an honest stop; successful real input and submission must be checked on the phone using the newly generated APK.

## Latest context fix status

See diagnostics/CONTEXT_FIX_REPORT.md for current details. Latest offline verification: 96 tests passed, two opt-in tests skipped; APK build passed. Real device runs reached typing and Enter submission but stalled on repeated screen observations. The latest completion-loop change awaits live verification and APK installation because the USB transport stopped responding and then disconnected. Full live acceptance success is not claimed.

