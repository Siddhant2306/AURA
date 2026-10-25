package com.example.phone_agent.mcp

import android.util.Log
import com.example.phone_agent.service.PhoneControlService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

object ToolRegistry {
    val tools: List<ToolDefinition> = listOf(
        tool("find_app", "Find installed launchable apps in the current profile by name/package. For Phone/call app returns the default dialer. Use returned packages; do not guess.", schema(
            properties = buildJsonObject { put("query", stringProperty("User's app name, e.g. Instagram, Spotify, WhatsApp or Phone")) }, required = listOf("query")
        )) { args -> ToolCallResult(service().findApp(args.requireString("query"))) },
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
            description = "Tap a matching text, hint or description; prefer exact editable/control matches over broad ancestor text.",
            inputSchema = schema(
                properties = buildJsonObject {
                    put("query", stringProperty("Case-insensitive text to find on screen."))
                    put("view_id", stringProperty("Optional exact view_id from the latest UI tree to disambiguate controls."))
                    put("bounds", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "integer") }); put("minItems", 4); put("maxItems", 4) })
                    put("prefer_gesture", buildJsonObject { put("type", "boolean"); put("description", "Use a physical tap on a fresh observed target when accessibility click did not change the UI.") })
                },
                required = listOf("query"),
            ),
        ) { args ->
            val bounds = args["bounds"]?.jsonArray?.map { it.jsonPrimitive.intOrNull ?: error("bounds must contain integers") }
            require(bounds == null || bounds.size == 4 && bounds[2] > bounds[0] && bounds[3] > bounds[1]) { "bounds must be [left,top,right,bottom] with positive area" }
            ToolCallResult(service().tapByText(args.requireString("query"), args["view_id"]?.jsonPrimitive?.contentOrNull, bounds,
                args["prefer_gesture"]?.jsonPrimitive?.booleanOrNull ?: false))
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
            description = "Swipe a finger inside the observed screen/scrollable bounds. Left: x1>x2; right: x1<x2; up: y1>y2; down: y1<y2. Avoid system edges. Gesture delivery does not prove content movement.",
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
        Log.i(TAG, "tool_call name=$name")
        val result = try {
            definition.handler(arguments)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) {
            ToolCallResult(e.message ?: e::class.java.simpleName, isError = true)
        }
        Log.i(TAG, "tool_result name=$name isError=${result.isError} chars=${result.text.length}")
        return result
    }

    private fun tool(
        name: String,
        description: String,
        inputSchema: JsonObject,
        handler: suspend (JsonObject) -> ToolCallResult,
    ): ToolDefinition {
        val anchored = name in setOf("tap", "tap_by_text", "type_text", "type_into_field_near", "swipe", "press_enter")
        val effectiveSchema = if (!anchored) inputSchema else JsonObject(inputSchema + ("properties" to JsonObject(
            inputSchema.getValue("properties").jsonObject + ("expected_package" to stringProperty("Kotlin supplies the package from the authoritative UI observation; reject if the foreground changed before this action."))
        )))
        return ToolDefinition(name, description, effectiveSchema) { args ->
            args["expected_package"]?.jsonPrimitive?.contentOrNull?.let { service().requireForegroundPackage(it) }
            handler(args)
        }
    }

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
