package com.opencode.android.llm

import com.opencode.android.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import kotlin.coroutines.resume

class AnthropicProvider : LlmProvider {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "application/json".toMediaType()

    override fun stream(
        messages: List<Message>,
        tools: List<ToolDef>,
        config: LlmConfig,
        systemPrompt: String
    ): Flow<StreamEvent.Type> = callbackFlow {
        val requestBody = buildJsonObject {
            put("model", config.model)
            put("max_tokens", config.maxTokens)
            put("stream", true)

            if (systemPrompt.isNotBlank()) {
                putJsonArray("system") { addJsonObject { put("type", "text"); put("text", systemPrompt) } }
            }

            putJsonArray("messages") {
                messages.forEach { msg ->
                    when (msg.role) {
                        MessageRole.User -> addJsonObject {
                            put("role", "user")
                            putJsonArray("content") { addJsonObject { put("type", "text"); put("text", msg.content) } }
                        }
                        MessageRole.Assistant -> addJsonObject {
                            put("role", "assistant")
                            putJsonArray("content") {
                                if (msg.content.isNotBlank()) {
                                    addJsonObject { put("type", "text"); put("text", msg.content) }
                                }
                                msg.toolCalls.forEach { tc ->
                                    addJsonObject {
                                        put("type", "tool_use"); put("id", tc.id)
                                        put("name", tc.toolName); put("input", json.parseToJsonElement(tc.arguments))
                                    }
                                }
                            }
                        }
                        MessageRole.Tool -> addJsonObject {
                            put("role", "user")
                            putJsonArray("content") {
                                msg.toolResults.forEach { tr ->
                                    addJsonObject { put("type", "tool_result"); put("tool_use_id", tr.toolCallId); put("content", tr.output) }
                                }
                            }
                        }
                        else -> {}
                    }
                }
            }

            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    tools.forEach { tool ->
                        addJsonObject {
                            put("name", tool.id); put("description", tool.description)
                            put("input_schema", tool.parameters)
                        }
                    }
                }
            }
        }

        val request = Request.Builder()
            .url("${config.baseUrl}/messages")
            .addHeader("x-api-key", config.apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody(mediaType))
            .build()

        EventSources.createFactory(client).newEventSource(request, object : EventSourceListener() {
            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (type == "ping" || data == "[DONE]") return
                try {
                    val parsed = json.parseToJsonElement(data).jsonObject
                    when (parsed["type"]?.jsonPrimitive?.contentOrNull) {
                        "content_block_start" -> {
                            val block = parsed["content_block"]?.jsonObject ?: return
                            when (block["type"]?.jsonPrimitive?.contentOrNull) {
                                "text" -> {}
                                "tool_use" -> {
                                    val toolCall = ToolCall(
                                        id = block["id"]?.jsonPrimitive?.contentOrNull ?: "",
                                        toolName = block["name"]?.jsonPrimitive?.contentOrNull ?: "",
                                        arguments = ""
                                    )
                                    trySend(StreamEvent.Type.ToolCallStart(toolCall))
                                }
                            }
                        }
                        "content_block_delta" -> {
                            val delta = parsed["delta"]?.jsonObject ?: return
                            when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                                "text_delta" -> {
                                    val text = delta["text"]?.jsonPrimitive?.contentOrNull ?: ""
                                    trySend(StreamEvent.Type.TextDelta(text))
                                }
                                "input_json_delta" -> {
                                    val partial = delta["partial_json"]?.jsonPrimitive?.contentOrNull ?: ""
                                    trySend(StreamEvent.Type.ToolCallDelta("", partial))
                                }
                                "thinking_delta" -> {
                                    val text = delta["thinking"]?.jsonPrimitive?.contentOrNull ?: ""
                                    trySend(StreamEvent.Type.ReasoningDelta(text))
                                }
                            }
                        }
                        "message_stop" -> {
                            trySend(StreamEvent.Type.Finish("stop"))
                        }
                    }
                } catch (_: Exception) { }
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                trySend(StreamEvent.Type.Error(t?.message ?: "Anthropic stream failed"))
                trySend(StreamEvent.Type.Finish("error"))
                channel.close()
            }

            override fun onClosed(eventSource: EventSource) {
                trySend(StreamEvent.Type.Finish("stop"))
                channel.close()
            }
        })

        awaitClose { }
    }.flowOn(Dispatchers.IO)

    override suspend fun chat(
        messages: List<Message>,
        tools: List<ToolDef>,
        config: LlmConfig,
        systemPrompt: String
    ): String {
        val requestBody = buildJsonObject {
            put("model", config.model)
            put("max_tokens", config.maxTokens)
            put("stream", false)
            if (systemPrompt.isNotBlank()) {
                putJsonArray("system") { addJsonObject { put("type", "text"); put("text", systemPrompt) } }
            }
            putJsonArray("messages") {
                messages.forEach { msg ->
                    when (msg.role) {
                        MessageRole.User -> addJsonObject {
                            put("role", "user")
                            putJsonArray("content") { addJsonObject { put("type", "text"); put("text", msg.content) } }
                        }
                        MessageRole.Assistant -> addJsonObject { put("role", "assistant"); put("content", msg.content) }
                        MessageRole.Tool -> addJsonObject {
                            put("role", "user")
                            putJsonArray("content") {
                                msg.toolResults.forEach { tr ->
                                    addJsonObject { put("type", "tool_result"); put("tool_use_id", tr.toolCallId); put("content", tr.output) }
                                }
                            }
                        }
                        else -> {}
                    }
                }
            }
        }

        val request = Request.Builder()
            .url("${config.baseUrl}/messages")
            .addHeader("x-api-key", config.apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .post(requestBody.toString().toRequestBody(mediaType))
            .build()

        return suspendCancellableCoroutine<String> { continuation ->
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    continuation.resume("Error: ${e.message}")
                }
                override fun onResponse(call: Call, response: Response) {
                    val body = response.body?.string() ?: ""
                    val result = try {
                        json.parseToJsonElement(body).jsonObject["content"]
                            ?.jsonArray?.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: "" }
                    } catch (_: Exception) { body }
                    continuation.resume(result ?: body)
                }
            })
        }
    }
}
