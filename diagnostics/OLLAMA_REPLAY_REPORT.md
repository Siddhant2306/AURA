# Captured-screen Ollama replay

Read-only diagnostic of the 24 October 2026 14:47 Chrome search-field failure.

The model was queried directly through /api/chat. This script has no MCP client, ToolProvider, Android controls or tool execution. Generated tool calls were parsed as text only. Application code and permanent Ollama settings were not changed.

## Results

| Input context | Request | Correct next tap | Reported prompt tokens |
|---|---|---:|---:|
| Default (4,096 observed after default trials) | original | 0/3 | 2050 |
| Default (4,096 observed after default trials) | compact | 3/3 | 2309 |
| Explicit 8,192 | original | 3/3 | 4513 |
| Explicit 8,192 | compact | 3/3 | 2309 |

All successful responses selected:

```json
{"type":"act","goal_id":"s2","action":{"name":"tap_by_text","arguments":{"query":"Search Google or type URL"}}}
```

The original/default responses all selected stop with an incorrect claim that the request was absent or unclear. No response reached its output-token limit. This reproduces the observed failure with the actual qwen3:4b server.

## Method and controls

- Reconstructed the three captured request messages from Logcat chunks. The reconstructed original is 15,681 Python characters versus 15,683 reported in the log; at most two terminal newline characters were lost in the export. The complete state JSON parses successfully.
- Model: qwen3:4b. Both variants used the same generated response schema, temperature 0.2, repeat_penalty 1.1, repeat_last_n 64, think=false and fixed num_predict=128. No retry or output-budget increase.
- Three paired seeds: 11, 22, 33. Each pair sent the original request then the compact request. Six trials used the normal default context; six explicitly requested num_ctx=8192.
- The original used the captured system instructions, original user command and complete captured state.
- The compact request retained the same command, current goal, all ten actual tool schemas, real recent tool results and all clickable/editable observed controls. It removed unrelated hierarchy containers and other goals, replaced the full planning/decision prompt with a shorter decision-only instruction, and clarified that merely seeing a field is not a completed tap. Its message content totals 7,823 Python characters.
- The response schema mirrors the current Kotlin OllamaOutput decision schema and embeds the captured actual tool schemas. It was supplied unchanged to both variants.
- The loaded-model readout before the explicit-context trials confirmed context_length=4096 after the default trials. Explicit 8192 is a per-request diagnostic override, not a permanent server configuration change.

## Interpretation

The original request reports 4,513 input tokens when given adequate context, exceeding the default 4,096 context even before output space. With the default context it reports only 2,050 tokens and incorrectly stops. Increasing only the input context, while preserving the original messages and fixed output budget, makes all three paired trials select the correct tap. This strongly implicates insufficient input context and discarded task context in this captured failure. The test does not reveal exactly which tokens the server discarded.

Compact requests report 2,309 tokens and choose the correct tap at either tested context. Because compaction changes instructions and representation together, it does not isolate which compaction component helps; the original-request context comparison is the stronger causal control.

Recommended application follow-up: set an explicit input num_ctx with sufficient headroom (8192 worked for this snapshot), use a compact phase-specific decision request that preserves necessary evidence, and monitor prompt size as screens grow. Keep output budgets and retries bounded. Do not replace the MCP tools based on this failure.

This evaluates the next action on one captured screen, three seeds per configuration. It does not establish end-to-end tapping, typing, submission or weather retrieval on the phone.

## Artifacts

- ollama_replay.py: diagnostic only, uses /api/ps and /api/chat.
- ollama_replay_results.json: six default-context results.
- ollama_replay_context8192.json: six explicit-context results.

Reproduce from the project root:

```powershell
python diagnostics/ollama_replay.py --log 'C:\Users\Siddhant\.codex\attachments\5944c53d-3081-45d6-be9d-b5b43e0a5ef4\Pasted text.txt' --output diagnostics/ollama_replay_results.json
python diagnostics/ollama_replay.py --log 'C:\Users\Siddhant\.codex\attachments\5944c53d-3081-45d6-be9d-b5b43e0a5ef4\Pasted text.txt' --output diagnostics/ollama_replay_context8192.json --context 8192
```
