package com.example.phone_agent.agent.core

/**
 * Keeps message history within practical limits for local LLMs.
 */
object ContextManager {
    private const val MAX_UI_TREE_CHARS = 6_000
    private const val MAX_TOOL_RESULT_CHARS = 8_000

    fun truncateToolResult(toolName: String, result: String): String {
        val limit = if (toolName == "get_ui_tree") MAX_UI_TREE_CHARS else MAX_TOOL_RESULT_CHARS
        return if (result.length <= limit) {
            result
        } else {
            result.take(limit) + "\n...[truncated ${result.length - limit} chars]"
        }
    }
}
