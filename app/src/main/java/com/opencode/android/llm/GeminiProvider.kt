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
import java.util.UUID
import kotlin.coroutines.resume

class GeminiProvider : LlmProvider {
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
        val contents = buildJsonArray {
            messages.filter { it.role != MessageRole.System }.forEach { msg ->
                add(buildGeminiContent(msg))
            }
        }

        val requestBodyObj = buildJsonObject {
            put("contents", contents)

            if (systemPrompt.isNotBlank()) {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") { addJsonObject { put("text", systemPrompt) } }
                }
            }

            putJsonObject("generationConfig") {
                put("temperature", config.temperature)
                put("maxOutputTokens", config.maxTokens)
            }

            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    addJsonObject {
                        putJsonArray("functionDeclarations") {
                            tools.forEach { tool ->
                                addJsonObject {
                                    put("name", tool.id); put("description", tool.description)
                                    put("parameters", tool.parameters)
                                }
                            }
                        }
                    }
                }
            }
        }

        val modelPart = config.model.removePrefix("models/")
        val url = "${config.baseUrl}/models/${modelPart}:streamGenerateContent?alt=sse&key=${config.apiKey}"

        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(requestBodyObj.toString().toRequestBody(mediaType))
            .build()

        EventSources.createFactory(client).newEventSource(request, object : EventSourceListener() {
            private val textBuilder = StringBuilder()

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                try {
                    val parsed = json.parseToJsonElement(data).jsonObject
                    val candidates = parsed["candidates"]?.jsonArray ?: return
                    val content = candidates.firstOrNull()?.jsonObject?.get("content")?.jsonObject ?: return
                    val parts = content["parts"]?.jsonArray ?: return

                    parts.forEach { partObj ->
                        val part = partObj.jsonObject
                        part["text"]?.jsonPrimitive?.contentOrNull?.let { text ->
                            textBuilder.append(text)
                            trySend(StreamEvent.Type.TextDelta(text))
                        }
                        part["functionCall"]?.jsonObject?.let { fc ->
                            val name = fc["name"]?.jsonPrimitive?.contentOrNull ?: ""
                            val args = fc["args"]?.jsonObject?.toString() ?: "{}"
                            val id = "fc_${UUID.randomUUID().toString().take(8)}"
                            trySend(StreamEvent.Type.ToolCallStart(ToolCall(id, name, args)))
                            trySend(StreamEvent.Type.ToolCallEnd(ToolCall(id, name, args)))
                        }
                    }
                } catch (_: Exception) { }
            }

            override fun onClosed(eventSource: EventSource) {
                trySend(StreamEvent.Type.Finish("stop"))
                channel.close()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                val errorBody = response?.body?.string()
                val msg = when {
                    t != null -> "Connection error: ${t.message}"
                    errorBody != null -> {
                        try {
                            json.parseToJsonElement(errorBody).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                                ?: "API error: $errorBody"
                        } catch (_: Exception) { "API error: $errorBody" }
                    }
                    else -> "Unknown error"
                }
                trySend(StreamEvent.Type.Error(msg))
                trySend(StreamEvent.Type.Finish("error"))
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
        val contents = buildJsonArray {
            messages.filter { it.role != MessageRole.System }.forEach { msg ->
                add(buildGeminiContent(msg))
            }
        }

        val requestBodyObj = buildJsonObject {
            put("contents", contents)
            if (systemPrompt.isNotBlank()) {
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") { addJsonObject { put("text", systemPrompt) } }
                }
            }
        }

        val modelPart = config.model.removePrefix("models/")
        val url = "${config.baseUrl}/models/${modelPart}:generateContent?key=${config.apiKey}"

        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(requestBodyObj.toString().toRequestBody(mediaType))
            .build()

        return suspendCancellableCoroutine<String> { continuation ->
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    continuation.resume("Error: ${e.message}")
                }
                override fun onResponse(call: Call, response: Response) {
                    val body = response.body?.string() ?: ""
                    val text = try {
                        json.parseToJsonElement(body).jsonObject["candidates"]
                            ?.jsonArray?.firstOrNull()?.jsonObject?.get("content")?.jsonObject?.get("parts")
                            ?.jsonArray?.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: "" }
                    } catch (_: Exception) { null }
                    continuation.resume(text ?: body)
                }
            })
        }
    }

    private fun buildGeminiContent(msg: Message): JsonElement = buildJsonObject {
        when (msg.role) {
            MessageRole.User -> {
                put("role", "user")
                putJsonArray("parts") { addJsonObject { put("text", msg.content) } }
            }
            MessageRole.Assistant -> {
                put("role", "model")
                putJsonArray("parts") {
                    if (msg.content.isNotBlank()) {
                        addJsonObject { put("text", msg.content) }
                    }
                    msg.toolCalls.forEach { tc ->
                        addJsonObject {
                            putJsonObject("functionCall") {
                                put("name", tc.toolName)
                                put("args", json.parseToJsonElement(tc.arguments).jsonObject)
                            }
                        }
                    }
                }
            }
            MessageRole.Tool -> {
                put("role", "function")
                putJsonArray("parts") {
                    msg.toolResults.forEach { tr ->
                        addJsonObject {
                            putJsonObject("functionResponse") {
                                put("name", tr.toolName)
                                putJsonObject("response") { put("output", tr.output) }
                            }
                        }
                    }
                }
            }
            MessageRole.System -> {
                put("role", "user")
                putJsonArray("parts") { addJsonObject { put("text", msg.content) } }
            }
        }
    }
}
