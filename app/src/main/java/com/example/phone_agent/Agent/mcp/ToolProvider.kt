package com.example.phone_agent.agent.mcp

import com.example.phone_agent.agent.model.McpTool
import com.example.phone_agent.agent.model.McpToolResult
import kotlinx.serialization.json.JsonObject

/**
 * Abstraction for tool discovery and execution. MCP today; other agents or sandboxes later.
 */
interface ToolProvider {
    suspend fun initialize()
    suspend fun listTools(): List<McpTool>
    suspend fun callTool(name: String, arguments: JsonObject): McpToolResult
}
