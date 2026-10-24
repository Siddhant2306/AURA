package com.example.phone_agent.agent.core

import com.example.phone_agent.agent.model.*
import kotlinx.serialization.json.*

class InvalidLlmResponseException(message: String) : RuntimeException(message)

/** Transport envelope permits additive server metadata; application responses use a separate strict parser. */
object OllamaOutput {
    private val json = Json { ignoreUnknownKeys = true }
    fun parse(rawBody: String): String {
        val response = try { json.decodeFromString(OllamaChatResponse.serializer(), rawBody) }
        catch (_: IllegalArgumentException) { throw InvalidLlmResponseException("Invalid Ollama response JSON") }
        if (response.doneReason == "length") throw InvalidLlmResponseException("Truncated Ollama response: done_reason=length")
        if (!response.done) throw InvalidLlmResponseException("Truncated or unfinished Ollama output")
        val text = response.message?.content?.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "")?.trim()
        if (text.isNullOrBlank()) throw InvalidLlmResponseException("Ollama returned empty content")
        if (text.contains("<think>")) throw InvalidLlmResponseException("Truncated thinking block")
        try { Json.parseToJsonElement(text) }
        catch (_: IllegalArgumentException) { throw InvalidLlmResponseException("Invalid or truncated JSON content") }
        return text // Syntax validity alone does not establish the application's response contract.
    }

    fun outputTokens(messages: List<ChatMessage>): Int {
        val state = runCatching {
            Json.parseToJsonElement(messages.last().content).jsonObject
        }.getOrNull()

        val planning =
            (state?.get("request_phase") as? JsonPrimitive)
                ?.contentOrNull == "plan"

        val baseTokens = if (planning) 256 else 128
        return baseTokens
    }


    /** Each alternative has the exact fields accepted by PlanParser, including nested metadata. */
    fun responseFormat(messages: List<ChatMessage>): JsonElement {
        val state = runCatching { Json.parseToJsonElement(messages.last().content).jsonObject }.getOrNull()
        val planning = (state?.get("request_phase") as? JsonPrimitive)?.contentOrNull == "plan"
        fun string(maxLength: Int? = null) = buildJsonObject {
            put("type", "string"); put("minLength", 1)
            maxLength?.let { put("maxLength", it) }
        }
        fun obj(properties: JsonObject, vararg required: String) = buildJsonObject {
            put("type", "object"); put("properties", properties)
            put("required", buildJsonArray { required.forEach { add(it) } })
            put("additionalProperties", false)
        }
        fun tag(type: String) = buildJsonObject { put("type", "string"); put("enum", buildJsonArray { add(type) }) }
        val expectedUi = obj(buildJsonObject {
            put("package_name", string()); put("text", string())
            put("editable_text", buildJsonObject { put("type", "string") })
            put("focused_editable", buildJsonObject { put("type", "boolean") })
        }).let { JsonObject(it + ("minProperties" to JsonPrimitive(1))) }
        val stop = obj(buildJsonObject { put("type", tag("stop")); put("reason", string(200)) }, "type", "reason")
        val currentGoal = (state?.get("current_goal") as? JsonPrimitive)?.contentOrNull
        val goalId = currentGoal?.let { tag(it) } ?: string()
        val expectation = (state?.get("current_goal_detail") as? JsonObject)?.get("expected_ui") as? JsonObject
        val focusGoal = (expectation?.get("focused_editable") as? JsonPrimitive)?.booleanOrNull == true
        val actualTree = (state?.get("ui_observation") as? JsonObject)?.get("tree")
        val inputTargets = if (focusGoal) actualTree?.let { UiObservation.parse(it.toString()) }?.nodes.orEmpty()
            .filter { (it["editable"] as? JsonPrimitive)?.booleanOrNull == true } else emptyList()
        val labels = inputTargets.flatMap { node -> listOf("text", "desc", "hint").mapNotNull {
            (node[it] as? JsonPrimitive)?.contentOrNull?.removeSuffix("...")?.take(64)?.takeIf(String::isNotBlank)
        } }.distinct()
        val desiredInput = (expectation?.get("editable_text") as? JsonPrimitive)?.contentOrNull
        val beforeInput = actualTree?.let { UiObservation.parse(it.toString())?.inputText() }
        fun actionSchema(name: JsonObject, arguments: JsonObject, requireRecovery: Boolean = false) = obj(buildJsonObject {
            put("name", name); put("arguments", arguments)
            put("expected_ui", expectedUi); put("recovery_reason", string(200))
        }, *(if (requireRecovery) arrayOf("name", "arguments", "recovery_reason") else arrayOf("name", "arguments")))
        val discoveredActions = (state?.get("available_tools") as? JsonArray).orEmpty().mapNotNull { entry ->
            val tool = entry as? JsonObject ?: return@mapNotNull null
            val name = (tool["name"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            var schema = tool["input_schema"] as? JsonObject ?: return@mapNotNull null
            // Narrow generation to real editable targets for an explicitly declared input goal.
            // These remain valid calls under the unchanged discovered MCP schema.
            if (labels.isNotEmpty() && name in setOf("tap_by_text", "type_into_field_near")) {
                val properties = (schema["properties"] as? JsonObject).orEmpty().toMutableMap()
                val query = (properties["query"] as? JsonObject).orEmpty()
                properties["query"] = JsonObject(query + ("enum" to JsonArray(labels.map(::JsonPrimitive))))
                schema = JsonObject(schema + ("properties" to JsonObject(properties)))
            }
            if (desiredInput != null && name in setOf("type_text", "type_into_field_near")) {
                val value = if (name == "type_text" && beforeInput != null && desiredInput.startsWith(beforeInput)) desiredInput.removePrefix(beforeInput) else desiredInput
                val properties = (schema["properties"] as? JsonObject).orEmpty().toMutableMap()
                val text = (properties["text"] as? JsonObject).orEmpty()
                properties["text"] = JsonObject(text + ("enum" to buildJsonArray { add(value) }))
                schema = JsonObject(schema + ("properties" to JsonObject(properties)))
            }
            val currentObservation = state?.get("ui_observation") as? JsonObject
            val recovering = (state?.get("recovery_required") as? JsonPrimitive)?.booleanOrNull == true
            val result = actionSchema(tag(name), schema, recovering || name == "get_ui_tree" && (currentObservation?.get("fresh") as? JsonPrimitive)?.booleanOrNull == true)
            if (name == "get_ui_tree" && (state?.get("observation_unchanged") as? JsonPrimitive)?.booleanOrNull == true) {
                val props = result.getValue("properties").jsonObject
                JsonObject(result + ("properties" to JsonObject(props + ("recovery_reason" to tag("expand_cached_observation")))))
            } else result
        }
        val action = if (discoveredActions.isEmpty()) actionSchema(string(), buildJsonObject { put("type", "object") })
            else buildJsonObject { put("oneOf", JsonArray(discoveredActions)) }
        val alternatives = if (planning) listOf(
            obj(buildJsonObject {
                put("type", tag("plan"))
                put("goals", buildJsonObject {
                    put("type", "array"); put("minItems", 1); put("maxItems", 8)
                    put("items", obj(buildJsonObject {
                        put("description", string(500)); put("success_condition", string(500)); put("expected_ui", expectedUi)
                    }, "description", "success_condition"))
                })
            }, "type", "goals"), stop,
        ) else listOf(
            obj(buildJsonObject {
                put("type", tag("act")); put("goal_id", goalId)
                put("action", action)
            }, "type", "goal_id", "action"),
            obj(buildJsonObject {
                put("type", tag("complete")); put("goal_id", goalId)
                put("evidence", obj(buildJsonObject {
                    put("action_id", buildJsonObject { put("type", "integer"); put("minimum", 1) })
                    put("text", string(2_000)); put("expected_ui", expectedUi)
                }, "action_id", "text"))
            }, "type", "goal_id", "evidence"), stop,
        )
        val ordered = if (!planning && (state?.get("observation_unchanged") as? JsonPrimitive)?.booleanOrNull == true)
            listOf(alternatives[1], alternatives[0], alternatives[2]) else alternatives
        val permitted = if ((state?.get("final_assessment") as? JsonPrimitive)?.booleanOrNull == true)
            ordered.filter { it.getValue("properties").jsonObject.getValue("type").jsonObject.getValue("enum").jsonArray.single().jsonPrimitive.content != "act" }
        else if ((state?.get("completion_blocked_reason") as? JsonPrimitive)?.contentOrNull != null)
            ordered.filter { it.getValue("properties").jsonObject.getValue("type").jsonObject.getValue("enum").jsonArray.single().jsonPrimitive.content != "complete" }
        else ordered
        return buildJsonObject { put("oneOf", JsonArray(permitted)) }
    }
}
