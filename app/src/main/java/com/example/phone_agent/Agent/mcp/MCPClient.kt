package com.example.phone_agent.agent.mcp

import com.example.phone_agent.agent.mcp.ToolProvider
import com.example.phone_agent.agent.model.McpRpcRequest
import com.example.phone_agent.agent.model.McpTool
import com.example.phone_agent.agent.model.McpToolResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicInteger
import android.util.Log

class MCPClient(
    baseUrl: String,
    private val bearerToken: String,
    private val httpClient: HttpClient = defaultHttpClient(),
) : ToolProvider {
    private val requestIds = AtomicInteger(1)
    private val rootUrl = normalizeMcpRootUrl(baseUrl)
    private val mcpUrl = "$rootUrl/mcp"

    override suspend fun initialize() {
        rpc(
            method = "initialize",
            params = buildJsonObject {
                put("protocolVersion", "2025-06-18")
                put("capabilities", buildJsonObject { })
                put(
                    "clientInfo",
                    buildJsonObject {
                        put("name", "phone-agent-friday")
                        put("version", "0.1.0")
                    },
                )
            },
        )
        notifyInitialized()
    }

    private suspend fun notifyInitialized() {
        Log.d("MCP", "Sending notification: notifications/initialized")

        val request = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "notifications/initialized")
            put("params", buildJsonObject { })
        }

        val response = httpClient.post(mcpUrl) {
            bearerAuth(bearerToken)
            header(
                HttpHeaders.ContentType,
                ContentType.Application.Json.toString(),
            )
            setBody(request)
        }

        Log.d("MCP", "Initialized notification sent: ${response.status}")
    }

    override suspend fun listTools(): List<McpTool> {
        val result = rpc("tools/list")
        val tools = result["tools"]?.jsonArray ?: JsonArray(emptyList())
        return tools.mapNotNull { element ->
            val tool = element.jsonObject
            val name = tool["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            McpTool(
                name = name,
                description = tool["description"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                inputSchema = tool["inputSchema"]?.jsonObject ?: buildJsonObject { put("type", "object") },
            )
        }
    }

    override suspend fun callTool(
        name: String,
        arguments: JsonObject,
    ): McpToolResult {
        Log.d("MCP", "Calling tool: $name")
        Log.d("MCP", "Arguments: $arguments")
        val result = rpc(
            method = "tools/call",
            params = buildJsonObject {
                put("name", name)
                put("arguments", arguments)
            },
        )
        Log.d("MCP", "Tool result: $result")
        val contentText = result["content"]
            ?.jsonArray
            ?.joinToString(separator = "\n") { item ->
                item.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
            }
            .orEmpty()
        return McpToolResult(
            text = contentText.ifBlank { result.toString() },
            isError = result["isError"]?.jsonPrimitive?.booleanOrNull == true,
            rawJson = result,
        )
    }

    private suspend fun rpc(
        method: String,
        params: JsonObject = buildJsonObject { },
    ): JsonObject {
        Log.d("MCP", "Sending request: $method")
        if (bearerToken.isBlank()) {
            throw McpClientException("Missing MCP bearer token. Start the server and use the token shown in the app.")
        }

        val request = McpRpcRequest(
            id = requestIds.getAndIncrement(),
            method = method,
            params = params,
        )
        val response = httpClient.post(mcpUrl) {
            bearerAuth(bearerToken)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody(request)
        }

        Log.d("MCP", "Response received: ${response.status}")
        val body: JsonObject = response.body()
        Log.d("MCP", "Response body: $body")
        if (!response.status.isSuccess()) {
            throw McpClientException("MCP HTTP ${response.status.value}: $body")
        }

        body["error"]?.let { error ->
            throw McpClientException(formatRpcError(error))
        }
        return body["result"]?.jsonObject
            ?: throw McpClientException("MCP response did not include a JSON object result: $body")
    }

    private fun formatRpcError(error: JsonElement): String {
        val objectError = error as? JsonObject ?: return "MCP RPC error: $error"
        val code = objectError["code"]?.jsonPrimitive?.intOrNull
        val message = objectError["message"]?.jsonPrimitive?.contentOrNull
        return "MCP RPC error ${code ?: "unknown"}: ${message ?: objectError}"
    }
}

class McpClientException(message: String) : RuntimeException(message)

fun normalizeMcpRootUrl(baseUrl: String): String {
    val withScheme = if (baseUrl.startsWith("http://") || baseUrl.startsWith("https://")) {
        baseUrl
    } else {
        "http://$baseUrl"
    }
    return withScheme.trim().trimEnd('/').removeSuffix("/mcp")
}

private fun defaultHttpClient(): HttpClient =
    HttpClient(CIO) {
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                },
            )
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 60_000
        }
    }
