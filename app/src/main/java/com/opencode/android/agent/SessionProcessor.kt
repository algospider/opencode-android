package com.opencode.android.agent

import com.opencode.android.llm.LlmProvider
import com.opencode.android.model.*
import com.opencode.android.permission.PermissionSystem
import com.opencode.android.tool.QuestionRequest
import com.opencode.android.tool.QuestionResponse
import com.opencode.android.tool.QuestionTool
import com.opencode.android.tool.ToolRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

class SessionProcessor(
    private val llmConfig: LlmConfig,
    private val systemPrompt: String = """You are OpenCode, an AI coding assistant that runs on Android. You help users with software development tasks.

You have access to these tools:
- read/write/edit: File operations (read files, create new files, make targeted edits)
- glob/grep: File search (find files by name patterns, search file contents with regex)
- bash: Execute shell commands in the terminal
- webfetch: Fetch and read web pages
- websearch: Search the web for real-time information
- git_init/repo_clone/git_status/repo_overview: Git operations and repository management
- apply_patch: Apply unified diff patches to files
- question: Ask the user structured questions

Rules:
1. Read files before editing them to understand their contents
2. Use edit for surgical changes, write for new files
3. Always explain what you're doing
4. When in doubt, use question tool to ask the user
5. Search the web for documentation when needed"""
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessionManager = SessionManager.getInstance()
    private val toolRegistry = ToolRegistry.getInstance()
    private val llmProvider = LlmProvider.create(llmConfig)
    private val permissionSystem = PermissionSystem()

    private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 128)
    val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private var currentJob: Job? = null

    sealed class SessionEvent {
        data class StreamText(val text: String) : SessionEvent()
        data class StreamReasoning(val text: String) : SessionEvent()
        data class ToolCallStarted(val toolCall: ToolCall) : SessionEvent()
        data class ToolCallCompleted(val toolCallId: String, val result: ExecuteResult) : SessionEvent()
        data class ToolCallFailed(val toolCallId: String, val error: String) : SessionEvent()
        data class MessageComplete(val message: Message) : SessionEvent()
        data class Error(val message: String) : SessionEvent()
        data object ProcessingStarted : SessionEvent()
        data object ProcessingComplete : SessionEvent()
        data class QuestionReceived(val request: QuestionRequest) : SessionEvent()
        data class QuestionTimeout(val message: String) : SessionEvent()
    }

    private var pendingQuestion: QuestionRequest? = null
    private var questionContinuation: CancellableContinuation<QuestionResponse>? = null

    fun sendMessage(userContent: String, sessionId: String? = null) {
        val sid = sessionId ?: sessionManager.currentSessionId.value
        if (sid == null || _isProcessing.value) return

        currentJob?.cancel()
        currentJob = scope.launch {
            _isProcessing.value = true
            _events.emit(SessionEvent.ProcessingStarted)

            try {
                val userMessage = Message(
                    id = generateMsgId(),
                    role = MessageRole.User,
                    content = userContent
                )
                sessionManager.addMessage(sid, userMessage)

                var sessionLoopCount = 0
                var shouldContinue = true

                while (shouldContinue && sessionLoopCount < 10) {
                    sessionLoopCount++
                    shouldContinue = false

                    val messages = sessionManager.getMessages(sid)
                    val tools = toolRegistry.getToolDefs()

                    val assistantId = generateMsgId()
                    val assistantMessage = Message(
                        id = assistantId,
                        role = MessageRole.Assistant
                    )
                    sessionManager.addMessage(sid, assistantMessage)

                    val toolCalls = mutableListOf<ToolCall>()

                    llmProvider.stream(messages, tools, llmConfig, systemPrompt).collect { event ->
                        when (event) {
                            is StreamEvent.Type.TextDelta -> {
                                sessionManager.updateLastAssistantMessage(sid, event.text)
                                _events.emit(SessionEvent.StreamText(event.text))
                            }
                            is StreamEvent.Type.ReasoningDelta -> {
                                _events.emit(SessionEvent.StreamReasoning(event.text))
                            }
                            is StreamEvent.Type.ToolCallStart -> {
                                val tc = event.toolCall
                                toolCalls.add(tc)
                                sessionManager.updateLastAssistantMessage(sid, "", listOf(tc))
                                _events.emit(SessionEvent.ToolCallStarted(tc))
                            }
                            is StreamEvent.Type.ToolCallDelta -> {
                                val existing = toolCalls.find { it.id == event.toolCallId }
                                if (existing != null) {
                                    val idx = toolCalls.indexOf(existing)
                                    toolCalls[idx] = existing.copy(arguments = existing.arguments + event.json)
                                }
                            }
                            is StreamEvent.Type.ToolCallEnd -> {
                                val existing = toolCalls.find { it.id == event.toolCall.id }
                                if (existing != null) {
                                    val idx = toolCalls.indexOf(existing)
                                    toolCalls[idx] = event.toolCall.copy(arguments = existing.arguments.ifEmpty { event.toolCall.arguments })
                                }
                            }
                            is StreamEvent.Type.Finish -> {}
                            is StreamEvent.Type.Error -> {
                                _events.emit(SessionEvent.Error(event.message))
                            }
                        }
                    }

                    // Update assistant message with final tool calls
                    if (toolCalls.isNotEmpty()) {
                        val lastMsg = sessionManager.getMessages(sid).lastOrNull { it.role == MessageRole.Assistant }
                        if (lastMsg != null) {
                            sessionManager.updateLastAssistantMessage(sid, lastMsg.content, emptyList())
                            val finalMsg = lastMsg.copy(toolCalls = toolCalls)
                            sessionManager.addMessage(sid, finalMsg)
                        }

                        // Execute tool calls
                        val toolResults = mutableListOf<ToolResult>()

                        for (tc in toolCalls) {
                            val tool = toolRegistry.get(tc.toolName)

                            if (tool == null) {
                                toolResults.add(ToolResult(tc.id, tc.toolName, "Tool not found: ${tc.toolName}", isError = true))
                                _events.emit(SessionEvent.ToolCallFailed(tc.id, "Tool not found: ${tc.toolName}"))
                                continue
                            }

                            // Permission check
                            val permission = permissionSystem.evaluate(tc.toolName, "")
                            if (permission.action == com.opencode.android.permission.PermissionAction.Deny) {
                                toolResults.add(ToolResult(tc.id, tc.toolName, "Permission denied: ${tc.toolName}", isError = true))
                                _events.emit(SessionEvent.ToolCallFailed(tc.id, "Permission denied"))
                                continue
                            }

                            // Parse arguments
                            val args = try {
                                val json = Json { ignoreUnknownKeys = true }
                                val element = json.parseToJsonElement(tc.arguments).jsonObject
                                element.toMap()
                            } catch (_: Exception) {
                                emptyMap()
                            }

                            val ctx = ToolContext(
                                sessionId = sid,
                                messageId = assistantId,
                                callId = tc.id
                            )

                            // Handle question tool specially
                            if (tool is com.opencode.android.tool.QuestionTool) {
                                val qRequest = tool.requests
                                // Collect the question request
                                val result = suspendCancellableCoroutine<ExecuteResult> { cont ->
                                    scope.launch {
                                        tool.requests.collect { request ->
                                            pendingQuestion = request
                                            _events.emit(SessionEvent.QuestionReceived(request))
                                            // Wait for response via continuation
                                        }
                                    }
                                    // Store continuation for response
                                    cont.invokeOnCancellation { }
                                }
                                toolResults.add(ToolResult(tc.id, tc.toolName, "Awaiting user response..."))
                                // Don't continue loop yet - we need the response
                                break
                            } else {
                                val result = tool.execute(args, ctx)
                                toolResults.add(ToolResult(tc.id, tc.toolName, result.output, isError = result.isError))

                                if (result.isError) {
                                    _events.emit(SessionEvent.ToolCallFailed(tc.id, result.output))
                                } else {
                                    _events.emit(SessionEvent.ToolCallCompleted(tc.id, result))
                                }
                            }
                        }

                        if (toolResults.isNotEmpty()) {
                            sessionManager.addToolResults(sid, toolResults)

                            val hasQuestion = toolCalls.any { it.toolName == "question" }
                            if (!hasQuestion) {
                                shouldContinue = true
                            }
                        }
                    } else {
                        val finalAssistant = sessionManager.getMessages(sid).lastOrNull { it.role == MessageRole.Assistant }
                        if (finalAssistant != null) {
                            _events.emit(SessionEvent.MessageComplete(finalAssistant))
                        }
                    }
                }

            } catch (e: CancellationException) {
                // Normal cancellation, no error
            } catch (e: Exception) {
                _events.emit(SessionEvent.Error(e.message ?: "Unknown error in processing"))
            } finally {
                _isProcessing.value = false
                _events.emit(SessionEvent.ProcessingComplete)
            }
        }
    }

    fun answerQuestion(response: QuestionResponse) {
        pendingQuestion = null
        scope.launch {
            // Add the response as a user message
            val sid = sessionManager.currentSessionId.value ?: return@launch
            val answerMsg = Message(
                id = generateMsgId(),
                role = MessageRole.User,
                content = "Answer: ${response.answers.joinToString(", ")}"
            )
            sessionManager.addMessage(sid, answerMsg)
            _events.emit(SessionEvent.StreamText("\n[User answered: ${response.answers.joinToString(", ")}]\n"))

            // Continue processing with the answer
            sendMessage("@continue", sid)
        }
    }

    fun cancelProcessing() {
        currentJob?.cancel()
        _isProcessing.value = false
        pendingQuestion = null
        scope.launch { _events.emit(SessionEvent.ProcessingComplete) }
    }

    fun destroy() {
        currentJob?.cancel()
        scope.cancel()
    }

    private fun generateMsgId(): String {
        val timestamp = System.currentTimeMillis()
        val random = (1000..9999).random()
        return "msg_${timestamp}_${random}"
    }

    private fun JsonObject.toMap(): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        for ((key, value) in this) {
            map[key] = value.toKotlinValue()
        }
        return map
    }

    private fun JsonElement.toKotlinValue(): Any? = when (this) {
        is JsonPrimitive -> contentOrNull
        is JsonArray -> map { it.toKotlinValue() }
        is JsonObject -> mapValues { it.value.toKotlinValue() }
        is JsonNull -> null
    }

    companion object {
        fun create(config: LlmConfig, systemPrompt: String = ""): SessionProcessor {
            return SessionProcessor(config, systemPrompt)
        }
    }
}
