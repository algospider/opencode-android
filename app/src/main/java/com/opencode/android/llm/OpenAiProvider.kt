package com.opencode.android.llm

import com.opencode.android.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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

class OpenAiProvider : LlmProvider {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val mediaType = "application/json".toMediaType()

    override fun stream(
        messages: List<Message>,
        tools: List<ToolDef>,
        config: LlmConfig,
        systemPrompt: String
    ): Flow<StreamEvent.Type> = flow {
        val requestBody = buildJsonObject {
            put("model", config.model)
            put("temperature", config.temperature)
            put("max_tokens", config.maxTokens)
            put("stream", true)

            putJsonArray("messages") {
                if (systemPrompt.isNotBlank()) {
                    addJsonObject {
                        put("role", "system")
                        put("content", systemPrompt)
                    }
                }
                messages.forEach { msg ->
                    when (msg.role) {
                        MessageRole.System -> addJsonObject {
                            put("role", "system"); put("content", msg.content)
                        }
                        MessageRole.User -> addJsonObject {
                            put("role", "user"); put("content", msg.content)
                        }
                        MessageRole.Assistant -> {
                            addJsonObject {
                                put("role", "assistant")
                                put("content", msg.content)
                                if (msg.toolCalls.isNotEmpty()) {
                                    putJsonArray("tool_calls") {
                                        msg.toolCalls.forEach { tc ->
                                            addJsonObject {
                                                put("id", tc.id)
                                                put("type", "function")
                                                putJsonObject("function") {
                                                    put("name", tc.toolName)
                                                    put("arguments", tc.arguments)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        MessageRole.Tool -> addJsonObject {
                            put("role", "tool")
                            put("tool_call_id", msg.toolResults.firstOrNull()?.toolCallId ?: "")
                            put("content", msg.toolResults.firstOrNull()?.output ?: msg.content)
                        }
                    }
                }
            }

            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    tools.forEach { tool ->
                        addJsonObject {
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", tool.id)
                                put("description", tool.description)
                                put("parameters", tool.parameters)
                            }
                        }
                    }
                }
            }
        }

        val request = Request.Builder()
            .url("${config.baseUrl}/chat/completions")
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody(mediaType))
            .build()

        val result = suspendCancellableCoroutine<JsonElement> { continuation ->
            val listener = object : EventSourceListener() {
                private val textBuilder = StringBuilder()
                private var currentToolCall: ToolCallBuilder? = null
                private val toolCallBuilders = mutableMapOf<String, ToolCallBuilder>()

                override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                    if (data == "[DONE]") return
                    try {
                        val parsed = json.parseToJsonElement(data).jsonObject
                        val choices = parsed["choices"]?.jsonArray
                        val delta = choices?.firstOrNull()?.jsonObject?.get("delta")?.jsonObject ?: return

                        delta["content"]?.jsonPrimitive?.contentOrNull?.let { text ->
                            textBuilder.append(text)
                            trySendBlocking(StreamEvent.Type.TextDelta(text))
                        }

                        delta["reasoning_content"]?.jsonPrimitive?.contentOrNull?.let { text ->
                            trySendBlocking(StreamEvent.Type.ReasoningDelta(text))
                        }

                        delta["tool_calls"]?.jsonArray?.forEach { tcElement ->
                            val tc = tcElement.jsonObject
                            val id = tc["id"]?.jsonPrimitive?.contentOrNull
                            val index = tc["index"]?.jsonPrimitive?.int ?: 0
                            val function = tc["function"]?.jsonObject

                            if (id != null && currentToolCall?.id != id) {
                                currentToolCall?.let { finishToolCall(it) }
                                currentToolCall = ToolCallBuilder(id = id, toolName = function?.get("name")?.jsonPrimitive?.contentOrNull ?: "")
                                toolCallBuilders[id] = currentToolCall!!
                                trySendBlocking(StreamEvent.Type.ToolCallStart(
                                    ToolCall(id, currentToolCall!!.toolName, "")
                                ))
                            }

                            function?.get("arguments")?.jsonPrimitive?.contentOrNull?.let { arg ->
                                currentToolCall?.let { builder ->
                                    builder.arguments.append(arg)
                                    trySendBlocking(StreamEvent.Type.ToolCallDelta(id ?: "", arg))
                                }
                            }

                            val finishReason = choices.firstOrNull()?.jsonObject?.get("finish_reason")?.jsonPrimitive?.contentOrNull
                            if (finishReason == "tool_calls") {
                                finishAllToolCalls()
                            }
                        }

                    } catch (_: Exception) { }
                }

                override fun onClosed(eventSource: EventSource) {
                    finishAllToolCalls()
                    continuation.resume(JsonPrimitive(textBuilder.toString()))
                }

                override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                    val errorMsg = t?.message ?: response?.body?.string() ?: "Unknown error"
                    trySendBlocking(StreamEvent.Type.Error(errorMsg))
                    continuation.resume(JsonPrimitive(textBuilder.toString()))
                }

                private fun finishToolCall(builder: ToolCallBuilder) {
                    trySendBlocking(StreamEvent.Type.ToolCallEnd(
                        ToolCall(builder.id, builder.toolName, builder.arguments.toString())
                    ))
                }

                private fun finishAllToolCalls() {
                    toolCallBuilders.values.forEach { finishToolCall(it) }
                    toolCallBuilders.clear()
                    currentToolCall = null
                }
            }

            EventSources.createFactory(client).newEventSource(request, listener)
        }

        emit(StreamEvent.Type.Finish("stop"))
    }.flowOn(Dispatchers.IO)

    override suspend fun chat(
        messages: List<Message>,
        tools: List<ToolDef>,
        config: LlmConfig,
        systemPrompt: String
    ): String {
        val requestBody = buildJsonObject {
            put("model", config.model)
            put("temperature", config.temperature)
            put("max_tokens", config.maxTokens)
            put("stream", false)
            putJsonArray("messages") {
                if (systemPrompt.isNotBlank()) {
                    addJsonObject { put("role", "system"); put("content", systemPrompt) }
                }
                messages.forEach { msg ->
                    when (msg.role) {
                        MessageRole.System -> addJsonObject { put("role", "system"); put("content", msg.content) }
                        MessageRole.User -> addJsonObject { put("role", "user"); put("content", msg.content) }
                        MessageRole.Assistant -> addJsonObject { put("role", "assistant"); put("content", msg.content) }
                        MessageRole.Tool -> addJsonObject { put("role", "tool"); put("tool_call_id", msg.toolResults.firstOrNull()?.toolCallId ?: ""); put("content", msg.toolResults.firstOrNull()?.output ?: msg.content) }
                    }
                }
            }
        }

        val request = Request.Builder()
            .url("${config.baseUrl}/chat/completions")
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .post(requestBody.toString().toRequestBody(mediaType))
            .build()

        return suspendCancellableCoroutine { continuation ->
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    continuation.resume("Error: ${e.message}")
                }
                override fun onResponse(call: Call, response: Response) {
                    val body = response.body?.string() ?: ""
                    val result = try {
                        json.parseToJsonElement(body).jsonObject["choices"]
                            ?.jsonArray?.firstOrNull()?.jsonObject?.get("message")
                            ?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull ?: ""
                    } catch (_: Exception) { body }
                    continuation.resume(result)
                }
            })
        }
    }

    private data class ToolCallBuilder(
        val id: String,
        val toolName: String,
        val arguments: StringBuilder = StringBuilder()
    )
}
