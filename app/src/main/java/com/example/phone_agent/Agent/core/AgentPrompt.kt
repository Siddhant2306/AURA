package com.example.phone_agent.agent.core

/** Phase sections keep planning instructions out of action decisions. Custom unsectioned prompts still work. */
internal object AgentPrompt {
    fun forPhase(source: String, planning: Boolean): String {
        if (!source.contains("[PLANNING]") || !source.contains("[DECISION]")) return source
        val common = source.substringBefore("[PLANNING]").trim()
        val phase = if (planning) source.substringAfter("[PLANNING]").substringBefore("[DECISION]")
            else source.substringAfter("[DECISION]")
        return common + "\n\n" + phase.trim()
    }
}
