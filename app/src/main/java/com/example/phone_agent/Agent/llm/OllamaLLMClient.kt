package com.example.phone_agent.agent.llm

import com.example.phone_agent.agent.model.ChatMessage
import com.example.phone_agent.agent.model.OllamaChatRequest
import com.example.phone_agent.agent.model.OllamaChatResponse
import com.example.phone_agent.agent.model.OllamaOptions
import io.ktor.client.HttpClient
//import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import android.util.Log
import io.ktor.client.statement.bodyAsText


class OllamaLLMClient(
    baseUrl: String,
    private val model: String,
    private val httpClient: HttpClient = defaultHttpClient(),
) {
    private val chatUrl = "${normalizeOllamaRootUrl(baseUrl)}/api/chat"

    suspend fun completeJson(messages: List<ChatMessage>): String {

        Log.d("OLLAMA", "========================================")
        Log.d("OLLAMA", "REQUEST START")
        Log.d("OLLAMA", "URL: $chatUrl")
        Log.d("OLLAMA", "Model: $model")
        Log.d("OLLAMA", "Messages: ${messages.size}")
        Log.d("OLLAMA", "Characters: ${messages.sumOf { it.content.length }}")

        val startTime = System.currentTimeMillis()

        val response = httpClient.post(chatUrl) {
            header(
                HttpHeaders.ContentType,
                ContentType.Application.Json.toString()
            )

            setBody(
                OllamaChatRequest(
                    model = model,
                    messages = messages,
                    stream = false,
                    format = "json",
                    think = false,
                    options = OllamaOptions(
                        num_predict =  512
                    ),
                )
            )
        }

        val elapsed = System.currentTimeMillis() - startTime

        Log.d("OLLAMA", "HTTP STATUS: ${response.status}")
        Log.d("OLLAMA", "REQUEST TIME: ${elapsed}ms")

        val rawBody = response.bodyAsText()

        Log.d("OLLAMA", "RAW BODY LENGTH: ${rawBody.length}")
        Log.d("OLLAMA", "RAW BODY:")
        Log.d("OLLAMA", rawBody)

        val parsed = Json {
            ignoreUnknownKeys = true
        }.decodeFromString<OllamaChatResponse>(rawBody)

        Log.d("OLLAMA", "MESSAGE: ${parsed.message}")
        Log.d("OLLAMA", "CONTENT: ${parsed.message?.content}")
        Log.d("OLLAMA", "DONE: ${parsed.done}")

        return parsed.message?.content
            ?.removeThinkBlocks()
            ?.trim()
            ?: throw LlmClientException(
                "Ollama returned empty content. HTTP=${response.status}"
            )
    }

    class LlmClientException(message: String) : RuntimeException(message)

    fun normalizeOllamaRootUrl(baseUrl: String): String {
        val withScheme = if (baseUrl.startsWith("http://") || baseUrl.startsWith("https://")) {
            baseUrl
        } else {
            "http://$baseUrl"
        }
        return withScheme.trim().trimEnd('/')
    }

    private fun String.removeThinkBlocks(): String =
        replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "").trim()

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
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 120_000
        }
    }

