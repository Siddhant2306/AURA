# Friday execution validation — 24 October 2026

## Current result

Offline verification passed: 96 JVM tests passed, two opt-in live tests skipped, zero failures. `testDebugUnitTest assembleDebug --offline` succeeded. Latest APK: app/build/outputs/apk/debug/app-debug.apk.

Actual phone tests executed Chrome launch, input focus, query entry and Enter through production AgentExecutor, live Ollama and the phone app's real MCP/accessibility service. The last completed sequence then stalled: the model repeatedly requested observations instead of deciding completion. A fully successful live acceptance test is NOT yet established.

The newest completion-loop changes pass local tests but could not be retested on the phone. After reconnection the MCP server was stopped; while restarting it, ADB shell commands hung. `adb reconnect` closed those commands; the subsequent device list was empty. The newest APK is not installed. Earlier targeting-service changes were installed during prior validation.

## Root causes and fixes

- Requests exceeded the default 4096-token input window. num_ctx=8192 is explicit; separate planning/decision prompts retain the command, current goal, exact discovered schemas, four recent results and actual UI evidence.
- Large results pages still exceeded context. UiObservation supplies ranked pages bounded to 7000 characters; cached detail remains accessible, and Kotlin verifies the full original tree.
- Generation schema/parser disagreement rejected valid-looking actions. Explicit plan/act/complete/stop contracts now agree; runtime argument validation remains. Kotlin assigns goal IDs. Schema/truncation failures permit at most one retry with fixed budgets.
- Broad text matching selected aggregate ancestors instead of inputs. TextTargetRanking prioritizes exact controls/editables. PhoneControlService uses observed bounds and a gesture to focus Chrome editable nodes whose ACTION_CLICK acknowledged success without actual focus.
- Primitive goals stalled after their effects were visible. Narrow foreground/focus/literal-input conditions complete through existing provenance/freshness checks. Already-correct input is idempotent; append generation uses only the missing suffix. Results goals never complete from query visibility or a submission receipt.
- Input/Enter repeated on unfocused pages. ToolExecutionPolicy exposes and enforces focus/input preconditions. Recent state distinguishes observed-but-unassessed effects from failed expected effects.
- Latest live stall: repeated screen refreshes after submission. HardPrompt now assesses success before choosing an action. Consecutive identical observations are identified; generation allows cached detail instead of another identical phone read. Bounded runtime limits remain. This newest model-behavior change awaits live verification.

## Files involved

Production: Agent/core/AgentExecutor.kt, AgentStateMachine.kt, UiObservation.kt, ToolExecutionPolicy.kt, OllamaOutput.kt, AgentPrompt.kt, PlanParser.kt; Agent/model/AgentModels.kt; Agent/llm/OllamaLLMClient.kt; assets/HardPrompt.txt; service/PhoneControlService.kt and TextTargetRanking.kt; mcp/ToolRegistry.kt.

Tests: AgentContextTest, AgentExecutionTest, DynamicAgentTest, ResponseContractTest, UiVerificationTest, TextTargetRankingTest, and opt-in LiveAgentValidationTest. Unrelated existing working-tree edits were preserved.

## Evidence and scope

Earlier production-request replay selected the observed search input in three live model trials: production_replay_trace.log. Saved live attempt logs record actual mutation/observation order. Latest connection failures: live_validation_trace.log.

I initiated the live tests; production Kotlin AgentExecutor selected calls and your app's MCP/accessibility service performed the Chrome actions. The harness runs the controller from a JVM test; it does not exercise Compose command entry or establish arbitrary-command reliability.

Resume with an unlocked stable USB connection and running MCP server:

    $env:FRIDAY_LIVE_MODE = 'phone'
    .\gradlew.bat testDebugUnitTest --tests '*LiveAgentValidationTest.runProductionExecutorOnConnectedPhone' --offline

The test requires observed focused query text, real Enter execution, successful controller completion and weather-related content outside editable fields. Then install the newest APK and validate the app entry path. A build or tool receipt alone does not prove task success.
