package com.example.phone_agent.mcp

import android.util.Log
import com.example.phone_agent.service.PhoneControlService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

object ToolRegistry {
    val tools: List<ToolDefinition> = listOf(
        tool(
            name = "open_app",
            description = "Open an installed Android app by package name.",
            inputSchema = schema(
                properties = buildJsonObject {
                    put("package_name", stringProperty("Android package name, for example com.android.chrome."))
                },
                required = listOf("package_name"),
            ),
        ) { args ->
            ToolCallResult(service().openApp(args.requireString("package_name")))
        },
        tool(
            name = "get_ui_tree",
            description = "Return a compact JSON accessibility tree for the current screen.",
            inputSchema = schema(),
        ) {
            ToolCallResult(service().getUiTree())
        },
        tool(
            name = "tap",
            description = "Tap an absolute screen coordinate.",
            inputSchema = schema(
                properties = buildJsonObject {
                    put("x", numberProperty("X coordinate in physical screen pixels."))
                    put("y", numberProperty("Y coordinate in physical screen pixels."))
                },
                required = listOf("x", "y"),
            ),
        ) { args ->
            ToolCallResult(service().tap(args.requireFloat("x"), args.requireFloat("y")))
        },
        tool(
            name = "tap_by_text",
            description = "Tap the first clickable element whose text or content-description contains a query.",
            inputSchema = schema(
                properties = buildJsonObject {
                    put("query", stringProperty("Case-insensitive text to find on screen."))
                },
                required = listOf("query"),
            ),
        ) { args ->
            ToolCallResult(service().tapByText(args.requireString("query")))
        },
        tool(
            name = "type_text",
            description = "Append text to the currently focused editable field.",
            inputSchema = schema(
                properties = buildJsonObject {
                    put("text", stringProperty("Text to append to the focused field."))
                },
                required = listOf("text"),
            ),
        ) { args ->
            ToolCallResult(service().typeText(args.requireString("text")))
        },
        tool(
            name = "type_into_field_near",
            description = "Find an editable field by nearby label, hint, text, or description, then set its text.",
            inputSchema = schema(
                properties = buildJsonObject {
                    put("query", stringProperty("Nearby label, hint, text, or content-description."))
                    put("text", stringProperty("Text to place into the matched field."))
                },
                required = listOf("query", "text"),
            ),
        ) { args ->
            ToolCallResult(
                service().typeIntoFieldNear(
                    query = args.requireString("query"),
                    text = args.requireString("text"),
                ),
            )
        },
        tool(
            name = "swipe",
            description = "Swipe from one absolute screen coordinate to another.",
            inputSchema = schema(
                properties = buildJsonObject {
                    put("x1", numberProperty("Start X coordinate."))
                    put("y1", numberProperty("Start Y coordinate."))
                    put("x2", numberProperty("End X coordinate."))
                    put("y2", numberProperty("End Y coordinate."))
                },
                required = listOf("x1", "y1", "x2", "y2"),
            ),
        ) { args ->
            ToolCallResult(
                service().swipe(
                    x1 = args.requireFloat("x1"),
                    y1 = args.requireFloat("y1"),
                    x2 = args.requireFloat("x2"),
                    y2 = args.requireFloat("y2"),
                ),
            )
        },
        tool(
            name = "go_home",
            description = "Press Android HOME.",
            inputSchema = schema(),
        ) {
            ToolCallResult(service().goHome())
        },
        tool(
            name = "go_back",
            description = "Press Android BACK.",
            inputSchema = schema(),
        ) {
            ToolCallResult(service().goBack())
        },
        tool(
            name = "press_enter",
            description = "Submit or search the currently focused editable field (IME enter/go).",
            inputSchema = schema(),
        ) {
            ToolCallResult(service().pressEnter())
        },
    )

    suspend fun call(
        name: String,
        arguments: JsonObject,
    ): ToolCallResult {
        val definition = tools.firstOrNull { it.name == name } ?: throw UnknownToolException(name)
        Log.i(TAG, "tool_call name=$name args=$arguments")
        val result = try {
            definition.handler(arguments)
        } catch (e: Exception) {
            ToolCallResult(e.message ?: e::class.java.simpleName, isError = true)
        }
        Log.i(TAG, "tool_result name=$name isError=${result.isError} result=${result.text.take(LOG_RESULT_LIMIT)}")
        return result
    }

    private fun tool(
        name: String,
        description: String,
        inputSchema: JsonObject,
        handler: suspend (JsonObject) -> ToolCallResult,
    ): ToolDefinition = ToolDefinition(name, description, inputSchema, handler)

    private fun service(): PhoneControlService =
        PhoneControlService.instance
            ?: throw IllegalStateException("Accessibility service is not enabled or connected")

    private fun schema(
        properties: JsonObject = buildJsonObject { },
        required: List<String> = emptyList(),
    ): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", properties)
        put("required", buildJsonArray {
            required.forEach { add(JsonPrimitive(it)) }
        })
        put("additionalProperties", false)
    }

    private fun stringProperty(description: String): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", description)
    }

    private fun numberProperty(description: String): JsonObject = buildJsonObject {
        put("type", "number")
        put("description", description)
    }

    private fun JsonObject.requireString(name: String): String {
        val value = get(name)?.jsonPrimitive?.contentOrNull
        require(!value.isNullOrBlank()) { "Missing required string parameter '$name'" }
        return value
    }

    private fun JsonObject.requireFloat(name: String): Float {
        val value = get(name)?.jsonPrimitive?.floatOrNull
        require(value != null) { "Missing required number parameter '$name'" }
        return value
    }

    private const val TAG = "ToolRegistry"
    private const val LOG_RESULT_LIMIT = 1000
}

data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val handler: suspend (JsonObject) -> ToolCallResult,
)

data class ToolCallResult(
    val text: String,
    val isError: Boolean = false,
)

class UnknownToolException(name: String) : IllegalArgumentException("Unknown tool '$name'")
