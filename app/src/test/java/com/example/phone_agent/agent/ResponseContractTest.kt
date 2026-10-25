package com.example.phone_agent.agent

import com.example.phone_agent.agent.core.*
import com.example.phone_agent.agent.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ResponseContractTest {
    @Test fun finalAssessmentOffersCompletionOrHonestStopInsteadOfMoreTools() {
        val state = """{"request_phase":"decision","final_assessment":true}"""
        val branches = OllamaOutput.responseFormat(listOf(ChatMessage("user", state))).jsonObject.getValue("oneOf").jsonArray
        val types = branches.map { it.jsonObject.getValue("properties").jsonObject.getValue("type").jsonObject.getValue("enum").jsonArray.single().jsonPrimitive.content }
        assertEquals(listOf("complete", "stop"), types)
    }
    @Test fun recoveryReasonIsRequiredWhenObservedActionFailedVerification() {
        val tool = com.example.phone_agent.mcp.ToolRegistry.tools.first { it.name == "press_enter" }
        val state = buildJsonObject {
            put("request_phase", "decision"); put("recovery_required", true)
            put("available_tools", buildJsonArray { add(buildJsonObject { put("name", tool.name); put("input_schema", tool.inputSchema) }) })
        }
        val action = OllamaOutput.responseFormat(listOf(ChatMessage("user", state.toString()))).jsonObject.getValue("oneOf").jsonArray[0]
            .jsonObject.getValue("properties").jsonObject.getValue("action").jsonObject.getValue("oneOf").jsonArray.single().jsonObject
        assertTrue("recovery_reason" in required(action))
    }
    @Test fun blockedCompletionIsNotOfferedByGenerationSchema() {
        val state = """{"request_phase":"decision","completion_blocked_reason":"Search is still being edited"}"""
        val branches = OllamaOutput.responseFormat(listOf(ChatMessage("user", state))).jsonObject.getValue("oneOf").jsonArray
        val types = branches.map { it.jsonObject.getValue("properties").jsonObject.getValue("type").jsonObject.getValue("enum").jsonArray.single().jsonPrimitive.content }
        assertEquals(listOf("act", "stop"), types)
    }
    @Test fun unchangedObservationOffersCachedInspectionInsteadOfAnotherPhoneRead() {
        val tool = com.example.phone_agent.mcp.ToolRegistry.tools.first { it.name == "get_ui_tree" }
        val state = buildJsonObject {
            put("request_phase", "decision"); put("current_goal", "s4"); put("observation_unchanged", true)
            put("ui_observation", buildJsonObject { put("fresh", true) })
            put("available_tools", buildJsonArray { add(buildJsonObject { put("name", tool.name); put("input_schema", tool.inputSchema) }) })
        }
        val branches = OllamaOutput.responseFormat(listOf(ChatMessage("user", state.toString()))).jsonObject.getValue("oneOf").jsonArray
        assertEquals("complete", branches[0].jsonObject.getValue("properties").jsonObject.getValue("type").jsonObject.getValue("enum").jsonArray.single().jsonPrimitive.content)
        val action = branches[1]
            .jsonObject.getValue("properties").jsonObject.getValue("action").jsonObject.getValue("oneOf").jsonArray.single().jsonObject
        assertTrue("recovery_reason" in required(action))
        assertEquals(listOf(JsonPrimitive("expand_cached_observation")), action.getValue("properties").jsonObject
            .getValue("recovery_reason").jsonObject.getValue("enum").jsonArray)
    }
    @Test fun freshObservationGenerationRequiresAReasonForAnotherRead() {
        val tool = com.example.phone_agent.mcp.ToolRegistry.tools.first { it.name == "get_ui_tree" }
        val state = buildJsonObject {
            put("request_phase", "decision"); put("current_goal", "s4")
            put("ui_observation", buildJsonObject { put("fresh", true) })
            put("available_tools", buildJsonArray { add(buildJsonObject { put("name", tool.name); put("input_schema", tool.inputSchema) }) })
        }
        val action = OllamaOutput.responseFormat(listOf(ChatMessage("user", state.toString()))).jsonObject.getValue("oneOf").jsonArray[0]
            .jsonObject.getValue("properties").jsonObject.getValue("action").jsonObject.getValue("oneOf").jsonArray.single().jsonObject
        assertEquals(setOf("name", "arguments", "recovery_reason"), required(action))
    }
    @Test fun appendGenerationUsesOnlyTheMissingSuffixOfTheDeclaredInput() {
        val tool = com.example.phone_agent.mcp.ToolRegistry.tools.first { it.name == "type_text" }
        val state = buildJsonObject {
            put("request_phase", "decision"); put("current_goal", "s3")
            put("current_goal_detail", buildJsonObject { put("expected_ui", buildJsonObject { put("editable_text", "weather"); put("focused_editable", true) }) })
            put("ui_observation", buildJsonObject { put("tree", Json.parseToJsonElement("""{"tree":{"text":"wea","editable":true,"focused":true}}""")) })
            put("available_tools", buildJsonArray { add(buildJsonObject { put("name", tool.name); put("input_schema", tool.inputSchema) }) })
        }
        val action = OllamaOutput.responseFormat(listOf(ChatMessage("user", state.toString()))).jsonObject.getValue("oneOf").jsonArray[0]
            .jsonObject.getValue("properties").jsonObject.getValue("action").jsonObject.getValue("oneOf").jsonArray.single().jsonObject
        val values = action.getValue("properties").jsonObject.getValue("arguments").jsonObject.getValue("properties").jsonObject
            .getValue("text").jsonObject.getValue("enum").jsonArray
        assertEquals(listOf(JsonPrimitive("ther")), values)
    }
    @Test fun inputGoalTargetsActualEditableFieldRatherThanSearchLogo() {
        val state = buildJsonObject {
            put("request_phase", "decision"); put("current_goal", "s2")
            put("current_goal_detail", buildJsonObject { put("expected_ui", buildJsonObject { put("focused_editable", true) }) })
            put("ui_observation", buildJsonObject { put("tree", Json.parseToJsonElement("""{"tree":{"children":[{"desc":"Google Search","clickable":true},{"text":"google.com/search?q=weather","editable":true}]}}""")) })
            put("available_tools", buildJsonArray {
                com.example.phone_agent.mcp.ToolRegistry.tools.filter { it.name == "tap_by_text" }.forEach { tool -> add(buildJsonObject {
                    put("name", tool.name); put("input_schema", tool.inputSchema)
                }) }
            })
        }
        val schema = OllamaOutput.responseFormat(listOf(ChatMessage("user", state.toString())))
        val action = schema.jsonObject.getValue("oneOf").jsonArray[0].jsonObject.getValue("properties").jsonObject
            .getValue("action").jsonObject.getValue("oneOf").jsonArray.single().jsonObject
        val query = action.getValue("properties").jsonObject.getValue("arguments").jsonObject
            .getValue("properties").jsonObject.getValue("query").jsonObject.getValue("enum").jsonArray
        assertEquals(listOf(JsonPrimitive("google.com/search?q=weather")), query)
    }
    private val parser = PlanParser()
    private val plan = """{"type":"plan","goals":[{"description":"Read weather","success_condition":"Today's temperature and location are observed"}]}"""
    private val act = """{"type":"act","goal_id":"s1","action":{"name":"get_ui_tree","arguments":{}}}"""
    private val complete = """{"type":"complete","goal_id":"s1","evidence":{"action_id":1,"text":"Today 24 C"}}"""
    @Test fun documentedResponsesRoundTripWithExplicitTypes() {
        for ((raw, planning) in listOf(plan to true, act to false, complete to false, """{"type":"stop","reason":"Access is unavailable"}""" to false)) {
            val parsed = parser.parse(raw, planning)
            assertNull(parsed.error)
            assertNotNull(parser.parse(wire(parsed.response!!), planning).response)
        }
    }
    @Test fun legacyMixedAndWrongPhaseStructuresAreRejected() {
        for (raw in listOf("{}", "[]", "null", "not json", "```json\n$plan\n```", """{"complete":true}""",
            """{"next_actions":[]}""", """{"current_action":{}}""", """{"tool_calls":[]}""",
            act.dropLast(1) + ",\"completion\":{}}", """{"type":"unknown"}""")) {
            assertNotNull(raw, parser.parse(raw, false).error)
        }
        assertNotNull(parser.parse(act, true).error)
        assertNotNull(parser.parse(complete, true).error)
        assertNotNull(parser.parse(plan, false).error)
    }
    @Test fun requiredFieldsAndNestedUnknownFieldsAreStrict() {
        val malformed = listOf(
            plan.replace("\"type\":\"plan\",", ""),
            plan.replace(",\"success_condition\":\"Today's temperature and location are observed\"", ""),
            plan.replace("\"Read weather\"", "\" \""),
            plan.replace("\"goals\":[", "\"extra\":true,\"goals\":["),
            plan.replace("\"description\":", "\"tool_calls\":[],\"description\":"),
            act.replace("\"goal_id\":\"s1\",", ""),
            act.replace(",\"arguments\":{}", ""),
            act.replace("\"name\":\"get_ui_tree\",", ""),
            act.replace("\"arguments\":{}", "\"arguments\":[]"),
            act.replace("\"arguments\":{}", "\"arguments\":{},\"invented\":true"),
            complete.replace("\"action_id\":1,", ""),
            complete.replace("\"action_id\":1", "\"action_id\":0"),
            complete.replace(",\"text\":\"Today 24 C\"", ""),
            complete.replace("\"text\":\"Today 24 C\"", "\"text\":\"\""),
        )
        for (raw in malformed) assertNotNull(raw, parser.parse(raw, raw.contains("\"type\":\"plan\"")).error)
    }
    private fun envelope(content: String, done: Boolean = true, reason: String = "stop") = buildJsonObject {
        put("message", buildJsonObject { put("role", "assistant"); put("content", content) })
        put("done", done); put("done_reason", reason); put("new_server_metadata", 42)
    }.toString()
    @Test fun transportValidityDoesNotImplyApplicationSchemaValidity() {
        assertEquals(plan, OllamaOutput.parse(envelope(plan)))
        assertEquals("{}", OllamaOutput.parse(envelope("{}")))
        assertNotNull(parser.parse(OllamaOutput.parse(envelope("{}")), true).error)
        assertEquals(plan, OllamaOutput.parse(envelope("<think>private reasoning</think> $plan")))
    }
    @Test fun emptyTruncatedAndMalformedOllamaOutputsAreRejected() {
        for (raw in listOf("", "{}", "[]", "not json", envelope(plan).replace(",\"done\":true", ""), envelope(""), envelope("  "), envelope(plan, false),
            envelope(plan, reason = "length"), envelope("{\"type\":"), envelope("<think>unfinished"))) {
            try { OllamaOutput.parse(raw); fail("Accepted $raw") } catch (_: InvalidLlmResponseException) {}
        }
    }
    @Test fun outputBudgetsStayFixedOnRetries() {
        fun tokens(phase: String, retry: Int) = OllamaOutput.outputTokens(listOf(ChatMessage("user", """{"request_phase":"$phase","response_retry":$retry}""")))
        assertEquals(256, tokens("plan", 0)); assertEquals(128, tokens("decision", 0))
        assertEquals(256, tokens("plan", 1)); assertEquals(128, tokens("decision", 1))
        assertEquals(128, tokens("decision", 100))
    }
    private fun format(phase: String) = OllamaOutput.responseFormat(listOf(ChatMessage("user", """{"request_phase":"$phase"}""")))
        .jsonObject.getValue("oneOf").jsonArray.map { it.jsonObject }
    private fun required(schema: JsonObject) = schema.getValue("required").jsonArray.map { it.jsonPrimitive.content }.toSet()
    @Test fun generatedDecisionSchemaRequiresExclusivePayloadForEachResponseType() {
        val branches = format("decision").associateBy {
            it.getValue("properties").jsonObject.getValue("type").jsonObject.getValue("enum").jsonArray.single().jsonPrimitive.content
        }
        assertEquals(setOf("act", "complete", "stop"), branches.keys)
        for ((type, keys) in mapOf("act" to setOf("type", "goal_id", "action"), "complete" to setOf("type", "goal_id", "evidence"), "stop" to setOf("type", "reason"))) {
            val branch = branches.getValue(type)
            assertEquals(keys, required(branch))
            assertEquals(keys, branch.getValue("properties").jsonObject.keys)
            assertEquals(false, branch.getValue("additionalProperties").jsonPrimitive.boolean)
        }
        val action = branches.getValue("act").getValue("properties").jsonObject.getValue("action").jsonObject
        assertEquals(setOf("name", "arguments"), required(action))
        assertEquals(false, action.getValue("additionalProperties").jsonPrimitive.boolean)
        assertNotNull(parser.parse(act.dropLast(1) + ",\"reason\":\"unused\"}", false).error)
    }
    @Test fun generatedSchemaSupportsDocumentedExpectationsAndPlanningStop() {
        val goals = format("plan").first().getValue("properties").jsonObject.getValue("goals").jsonObject.getValue("items").jsonObject
        assertEquals(setOf("description", "success_condition"), required(goals))
        val expectation = goals.getValue("properties").jsonObject.getValue("expected_ui").jsonObject
        assertEquals(setOf("package_name", "text", "editable_text", "focused_editable", "selected_text"), expectation.getValue("properties").jsonObject.keys)
        assertEquals(1, expectation.getValue("minProperties").jsonPrimitive.int)
        assertEquals(setOf("type", "reason"), required(format("plan").last()))
        val evidence = format("decision")[1].getValue("properties").jsonObject.getValue("evidence").jsonObject
        assertEquals(setOf("action_id", "text"), required(evidence))
        assertEquals(expectation, evidence.getValue("properties").jsonObject.getValue("expected_ui"))
        assertNull(parser.parse("""{"type":"stop","reason":"No available tools"}""", true).error)
    }
    @Test fun actionGenerationUsesDiscoveredSchemasAndKotlinAssignedCurrentGoal() {
        val tool = com.example.phone_agent.mcp.ToolRegistry.tools.first { it.name == "type_into_field_near" }
        val state = buildJsonObject {
            put("request_phase", "decision"); put("current_goal", "s2")
            put("available_tools", buildJsonArray { add(buildJsonObject {
                put("name", tool.name); put("input_schema", tool.inputSchema)
            }) })
        }
        val schema = OllamaOutput.responseFormat(listOf(ChatMessage("user", state.toString()))).jsonObject.getValue("oneOf").jsonArray
        val actionProps = schema[0].jsonObject.getValue("properties").jsonObject
        assertEquals(listOf(JsonPrimitive("s2")), actionProps.getValue("goal_id").jsonObject.getValue("enum").jsonArray)
        val toolProps = actionProps.getValue("action").jsonObject.getValue("oneOf").jsonArray.single().jsonObject.getValue("properties").jsonObject
        assertEquals(tool.inputSchema, toolProps.getValue("arguments"))
        assertEquals(listOf(JsonPrimitive(tool.name)), toolProps.getValue("name").jsonObject.getValue("enum").jsonArray)
        assertEquals(200, schema.last().jsonObject.getValue("properties").jsonObject.getValue("reason").jsonObject.getValue("maxLength").jsonPrimitive.int)
    }
}
