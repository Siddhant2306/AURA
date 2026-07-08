package com.example.phone_agent.mcp

import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

class McpHttpServer(
    private val bearerToken: String,
    private val port: Int = DEFAULT_PORT,
    private val host: String = DEFAULT_HOST,
) {
    private val rpcJson = Json {
        ignoreUnknownKeys = true
    }
    private val running = AtomicBoolean(false)

    @Volatile
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    fun start() {
        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "MCP HTTP server already running")
            return
        }

        // Remote tunnels can be layered in front of this LAN listener later, but should add
        // their own authentication and HTTPS. This base pass intentionally does not start one.
        server = embeddedServer(CIO, host = host, port = port) {
            configureApplication()
        }
        server?.start(wait = false)
        Log.i(TAG, "MCP HTTP server listening on $host:$port")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        server?.stop(gracePeriodMillis = 1000, timeoutMillis = 5000)
        server = null
        Log.i(TAG, "MCP HTTP server stopped")
    }

    fun isRunning(): Boolean = running.get()

    private fun Application.configureApplication() {
        install(ContentNegotiation) {
            json(rpcJson)
        }

        routing {
            get("/health") {
                call.respondText(
                    buildJsonObject {
                        put("status", "ok")
                        put("mcp", running.get())
                    }.toString(),
                    ContentType.Application.Json,
                    HttpStatusCode.OK,
                )
            }

            post("/mcp") {
                if (!call.isAuthorized()) {
                    call.respondText(
                        buildJsonObject {
                            put("error", "unauthorized")
                            put("message", "Bearer token required")
                        }.toString(),
                        ContentType.Application.Json,
                        HttpStatusCode.Unauthorized,
                    )
                    return@post
                }

                val response = handleJsonRpc(call.receiveText())
                if (response == null) {
                    call.respond(HttpStatusCode.NoContent)
                } else {
                    call.respondText(response.toString(), ContentType.Application.Json, HttpStatusCode.OK)
                }
            }
        }
    }

    private suspend fun handleJsonRpc(body: String): JsonObject? =
        try {
            val request = rpcJson.parseToJsonElement(body).jsonObject
            val id = request["id"]
            val method = request["method"]?.jsonPrimitive?.contentOrNull
                ?: return errorResponse(id ?: JsonNull, INVALID_REQUEST, "Missing JSON-RPC method")

            if (id == null) {
                handleNotification(method, request["params"] as? JsonObject)
                null
            } else {
                successResponse(id, dispatch(method, request["params"] as? JsonObject))
            }
        } catch (e: RpcException) {
            errorResponse(e.id, e.code, e.message.orEmpty())
        } catch (e: SerializationException) {
            errorResponse(JsonNull, PARSE_ERROR, "Invalid JSON: ${e.message}")
        } catch (e: IllegalArgumentException) {
            errorResponse(JsonNull, INVALID_REQUEST, e.message ?: "Invalid request")
        }

    private suspend fun dispatch(
        method: String,
        params: JsonObject?,
    ): JsonObject =
        when (method) {
            "initialize" -> initializeResult()
            "tools/list" -> toolsListResult()
            "tools/call" -> callToolResult(params ?: throw RpcException(JsonNull, INVALID_PARAMS, "Missing params"))
            else -> throw RpcException(JsonNull, METHOD_NOT_FOUND, "Unknown method '$method'")
        }

    private suspend fun handleNotification(
        method: String,
        params: JsonObject?,
    ) {
        if (method == "notifications/initialized") return
        dispatch(method, params)
    }

    private fun initializeResult(): JsonObject = buildJsonObject {
        put("protocolVersion", MCP_PROTOCOL_VERSION)
        put("capabilities", buildJsonObject {
            put("tools", buildJsonObject {
                put("listChanged", false)
            })
        })
        put("serverInfo", buildJsonObject {
            put("name", "android-phone-control")
            put("version", "0.1.0")
        })
    }

    private fun toolsListResult(): JsonObject = buildJsonObject {
        put("tools", buildJsonArray {
            ToolRegistry.tools.forEach { tool ->
                add(
                    buildJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("inputSchema", tool.inputSchema)
                    },
                )
            }
        })
    }

    private suspend fun callToolResult(params: JsonObject): JsonObject {
        val name = params["name"]?.jsonPrimitive?.contentOrNull
            ?: throw RpcException(JsonNull, INVALID_PARAMS, "Missing tool name")
        val arguments = params["arguments"] as? JsonObject ?: buildJsonObject { }
        val result = try {
            ToolRegistry.call(name, arguments)
        } catch (e: UnknownToolException) {
            throw RpcException(JsonNull, INVALID_PARAMS, e.message.orEmpty())
        }

        return buildJsonObject {
            put("content", buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "text")
                        put("text", result.text)
                    },
                )
            })
            if (result.isError) put("isError", true)
        }
    }

    private fun successResponse(id: JsonElement, result: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }

    private fun errorResponse(
        id: JsonElement,
        code: Int,
        message: String,
    ): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
        })
    }

    private fun ApplicationCall.isAuthorized(): Boolean {
        val header = request.headers[HttpHeaders.Authorization] ?: return false
        if (!header.startsWith(BEARER_PREFIX, ignoreCase = true)) return false
        val providedToken = header.substring(BEARER_PREFIX.length).trim()
        return constantTimeEquals(bearerToken, providedToken)
    }

    private fun constantTimeEquals(
        expected: String,
        provided: String,
    ): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        return MessageDigest.isEqual(
            digest.digest(expected.toByteArray(Charsets.UTF_8)),
            digest.digest(provided.toByteArray(Charsets.UTF_8)),
        )
    }

    private class RpcException(
        val id: JsonElement,
        val code: Int,
        override val message: String,
    ) : Exception(message)

    companion object {
        const val DEFAULT_PORT = 8080
        const val DEFAULT_HOST = "0.0.0.0"
        const val MCP_PROTOCOL_VERSION = "2025-06-18"
        private const val TAG = "McpHttpServer"
        private const val BEARER_PREFIX = "Bearer "
        private const val PARSE_ERROR = -32700
        private const val INVALID_REQUEST = -32600
        private const val METHOD_NOT_FOUND = -32601
        private const val INVALID_PARAMS = -32602
    }
}
