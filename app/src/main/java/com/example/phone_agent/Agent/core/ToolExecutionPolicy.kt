package com.example.phone_agent.agent.core

import kotlinx.coroutines.delay

/**
 * Rules for sequential vs batched tool execution and UI settle delays.
 */
object ToolExecutionPolicy {
    private val uiChangingTools = setOf(
        "open_app",
        "tap",
        "tap_by_text",
        "type_text",
        "type_into_field_near",
        "swipe",
        "go_home",
        "go_back",
        "press_enter",
    )

    private val settleDelayMs = mapOf(
        "open_app" to 1_200L,
        "tap" to 600L,
        "tap_by_text" to 600L,
        "type_text" to 400L,
        "type_into_field_near" to 400L,
        "swipe" to 700L,
        "go_home" to 800L,
        "go_back" to 600L,
        "press_enter" to 800L,
    )

    fun isUiChanging(toolName: String): Boolean = toolName in uiChangingTools

    suspend fun waitForUiSettle(toolName: String) {
        val delayMs = settleDelayMs[toolName] ?: return
        delay(delayMs)
    }

    /**
     * Returns true when the LLM batched tools that should run one-at-a-time with UI inspection between.
     */
    fun hasUnsafeBatch(toolNames: List<String>): Boolean {
        if (toolNames.size <= 1) return false
        var sawUiChange = false
        for (name in toolNames) {
            if (isUiChanging(name)) {
                if (sawUiChange) return true
                sawUiChange = true
            }
        }
        return false
    }
}
