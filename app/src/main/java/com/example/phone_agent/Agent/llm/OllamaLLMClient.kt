package com.example.phone_agent.agent.llm

import android.util.Log
import com.example.phone_agent.agent.core.InvalidLlmResponseException
import com.example.phone_agent.agent.core.OllamaOutput
import com.example.phone_agent.agent.model.ChatMessage
import com.example.phone_agent.agent.model.OllamaChatRequest
import com.example.phone_agent.agent.model.OllamaOptions
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

class OllamaLLMClient(
    baseUrl: String,
    private val model: String,
    private val httpClient: HttpClient = defaultHttpClient(),
    private val logResponseContent: Boolean = true,
) {
    private val chatUrl = "${normalizeOllamaRootUrl(baseUrl)}/api/chat"

    private val responseJson = Json {
        ignoreUnknownKeys = true
    }

    suspend fun completeJson(messages: List<ChatMessage>): String {
        Log.d(TAG, "========================================")
        Log.d(TAG, "REQUEST START")
        Log.d(TAG, "URL: $chatUrl")
        Log.d(TAG, "Model: $model")
        Log.d(TAG, "Messages: ${messages.size}")
        Log.d(TAG, "Characters: ${messages.sumOf { it.content.length }}")
        Log.d(TAG, "INPUT_CONTEXT: 8192 OUTPUT_LIMIT: ${OllamaOutput.outputTokens(messages)}")

        // Log each conversation message to help diagnose prompt/schema problems.
        // Be careful: messages may contain sensitive UI content.
        if (logResponseContent) {
            messages.forEachIndexed { index, message ->
                Log.d(TAG, "REQUEST MESSAGE [$index] ROLE=${message.role}")
                logLongText("REQUEST MESSAGE [$index] CONTENT", message.content)
            }
        }

        val startTime = System.nanoTime()

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
                    format = OllamaOutput.responseFormat(messages),
                    think = false,
                    options = OllamaOptions(
                        temperature = 0.2,
                        repeat_penalty = 1.1,
                        repeat_last_n = 64,
                        num_predict = OllamaOutput.outputTokens(messages),
                    ),
                )
            )
        }

        val elapsed = (System.nanoTime() - startTime) / 1_000_000

        Log.d(TAG, "HTTP STATUS: ${response.status}")
        Log.d(TAG, "REQUEST TIME: ${elapsed}ms")

        val bodyStart = System.nanoTime()
        val rawBody = response.bodyAsText()
        val bodyMillis = (System.nanoTime() - bodyStart) / 1_000_000

        Log.d(TAG, "RAW BODY LENGTH: ${rawBody.length}")
        Log.d(TAG, "BODY READ TIME: ${bodyMillis}ms")

        // Always print the response body for HTTP failures.
        // This is the most useful information when Ollama returns HTTP 500.
        if (!response.status.isSuccess()) {
            Log.e(TAG, "OLLAMA HTTP ERROR ${response.status.value}")
            Log.e(TAG, "OLLAMA ERROR BODY START")
            logLongText("OLLAMA ERROR BODY", rawBody, isError = true)
            Log.e(TAG, "OLLAMA ERROR BODY END")

            throw LlmClientException(
                "Ollama HTTP ${response.status.value}: " +
                        rawBody.take(MAX_ERROR_LENGTH)
            )
        }

        // Print the complete Ollama API response when enabled.
        if (logResponseContent) {
            Log.d(TAG, "RAW OLLAMA BODY START")
            logLongText("RAW OLLAMA BODY", rawBody)
            Log.d(TAG, "RAW OLLAMA BODY END")
        }

        val parseStart = System.nanoTime()

        val payload = try {
            responseJson.parseToJsonElement(rawBody).jsonObject
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Ollama HTTP response as JSON", e)
            throw InvalidLlmResponseException(
                "Invalid Ollama response JSON: ${e.message}"
            )
        }

        if ((payload["done_reason"] as? JsonPrimitive)?.content == "length") {
            Log.w(TAG, "TRUNCATED_RESPONSE done_reason=length; at most one retry, unchanged token budget")
        }
        val content = try {
            OllamaOutput.parse(rawBody)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract model content from Ollama response", e)
            if (logResponseContent) {
                logLongText("UNPARSED OLLAMA RESPONSE", rawBody, isError = true)
            }
            throw InvalidLlmResponseException(
                "Could not extract Ollama output: ${e.message}"
            )
        }

        val parseMillis = (System.nanoTime() - parseStart) / 1_000_000

        val metrics = payload.filter { (name, value) ->
            (name.endsWith("_duration") || name.endsWith("_count")) &&
                    (value as? JsonPrimitive)?.longOrNull != null
        }

        Log.d(
            TAG,
            "TIMING http_wait_ms=$elapsed " +
                    "body_read_ms=$bodyMillis " +
                    "parse_ms=$parseMillis " +
                    "total_ms=${(System.nanoTime() - startTime) / 1_000_000}"
        )
        Log.d(TAG, "SERVER_METRICS $metrics")
        Log.d(TAG, "CONTENT LENGTH: ${content.length}")

        if (logResponseContent) {
            Log.d(TAG, "MODEL CONTENT START")
            logLongText("MODEL CONTENT", content)
            Log.d(TAG, "MODEL CONTENT END")
        }

        Log.d(TAG, "REQUEST END")
        Log.d(TAG, "========================================")

        return content
    }

    class LlmClientException(message: String) : RuntimeException(message)

    private fun normalizeOllamaRootUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim()

        val withScheme = if (
            trimmed.startsWith("http://") ||
            trimmed.startsWith("https://")
        ) {
            trimmed
        } else {
            "http://$trimmed"
        }

        return withScheme.trimEnd('/')
    }

    private fun logLongText(
        label: String,
        text: String,
        isError: Boolean = false,
    ) {
        // Logcat truncates long messages. Split text into smaller chunks.
        text.chunked(LOG_CHUNK_SIZE).forEachIndexed { index, chunk ->
            val message = "$label [${index + 1}]: $chunk"

            if (isError) {
                Log.e(TAG, message)
            } else {
                Log.d(TAG, message)
            }
        }

        if (text.isEmpty()) {
            if (isError) {
                Log.e(TAG, "$label: <empty>")
            } else {
                Log.d(TAG, "$label: <empty>")
            }
        }
    }

    companion object {
        private const val TAG = "OLLAMA"
        private const val LOG_CHUNK_SIZE = 2500
        private const val MAX_ERROR_LENGTH = 1500
    }
}

private fun defaultHttpClient(): HttpClient =
    HttpClient(CIO) {
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                }
            )
        }

        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 120_000
        }
    }
