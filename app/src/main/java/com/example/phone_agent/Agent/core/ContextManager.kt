package com.example.phone_agent.agent.core

import kotlinx.serialization.json.*

/**
 * Keeps message history within practical limits for local LLMs.
 */
object ContextManager {
    private const val MAX_UI_TREE_CHARS = 6_000
    private const val MAX_TOOL_RESULT_CHARS = 8_000

    fun truncateToolResult(toolName: String, result: String): String {
        val limit = if (toolName == "get_ui_tree") MAX_UI_TREE_CHARS else MAX_TOOL_RESULT_CHARS
        // Preserve valid JSON and the foreground package even for a large screen.
        if (toolName == "get_ui_tree" && result.length > limit) {
            val screen = runCatching { Json.parseToJsonElement(result).jsonObject }.getOrNull()
            if (screen != null) {
                var budget = limit - 300
                val nodes = mutableListOf<JsonElement>()
                fun visit(element: JsonElement) {
                    if (budget <= 0) return
                    when (element) {
                        is JsonObject -> {
                            val node = JsonObject(element.filterKeys { it != "children" })
                            val size = node.toString().length + 1
                            if (size <= budget) { nodes += node; budget -= size }
                            (element["children"] as? JsonArray)?.forEach { visit(it) }
                        }
                        is JsonArray -> element.forEach { visit(it) }
                        else -> Unit
                    }
                }
                screen["tree"]?.let { visit(it) }
                return buildJsonObject {
                    screen["package"]?.let { put("package", it) }
                    put("tree", buildJsonObject { put("children", JsonArray(nodes)) })
                    put("truncated", true)
                }.toString()
            }
        }
        return if (result.length <= limit) {
            result
        } else {
            result.take(limit) + "\n...[truncated ${result.length - limit} chars]"
        }
    }
}
