package com.example.phone_agent.agent

import android.content.Context
import com.example.phone_agent.agent.llm.OllamaLLMClient
import com.example.phone_agent.agent.mcp.MCPClient

data class FridayAgentConfig(
    val ollamaBaseUrl: String,
    val ollamaModel: String,
    val mcpBaseUrl: String,
    val mcpBearerToken: String,
    val maxSteps: Int = 12,
)

object FridayAgentFactory {
    fun create(context: Context, config: FridayAgentConfig): FridayAgent =
        FridayAgent(
            context = context,
            ollamaLLMClient = OllamaLLMClient(
                baseUrl = config.ollamaBaseUrl,
                model = config.ollamaModel,
            ),
            toolProvider = MCPClient(
                baseUrl = config.mcpBaseUrl,
                bearerToken = config.mcpBearerToken,
            ),
            maxSteps = config.maxSteps,
        )
}
