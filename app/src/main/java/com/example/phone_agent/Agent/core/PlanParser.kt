package com.example.phone_agent.agent.core

import com.example.phone_agent.agent.model.AgentPlan
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class PlanParser(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    },
) {
    fun parse(raw: String): AgentPlan {
        val jsonObject = extractJsonObject(raw)
        return try {
            json.decodeFromString(AgentPlan.serializer(), jsonObject)
        } catch (_: SerializationException) {
            AgentPlan(final = null, toolCalls = emptyList(), parseError = true)
        } catch (_: IllegalArgumentException) {
            AgentPlan(final = null, toolCalls = emptyList(), parseError = true)
        }
    }

    private fun extractJsonObject(raw: String): String {
        val cleaned = raw
            .trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')

        return if (start >= 0 && end > start) {
            cleaned.substring(start, end + 1)
        } else {
            cleaned
        }
    }
}
