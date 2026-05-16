package com.opencode.android.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opencode.android.agent.SessionManager
import com.opencode.android.agent.SessionProcessor
import com.opencode.android.model.LlmConfig
import com.opencode.android.model.MessageRole
import com.opencode.android.model.ProviderType
import com.opencode.android.tool.QuestionRequest
import com.opencode.android.tool.QuestionResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isProcessing: Boolean = false,
    val inputText: String = "",
    val isConfigured: Boolean = false,
    val error: String? = null,
    val pendingQuestion: QuestionRequest? = null
)

data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    val toolCalls: List<String> = emptyList(),
    val isStreaming: Boolean = false
)

class ChatViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val sessionManager = SessionManager.getInstance()
    private var sessionProcessor: SessionProcessor? = null

    private val _config = MutableStateFlow<LlmConfig?>(null)

    init {
        viewModelScope.launch {
            sessionManager.currentSessionId.collect { sessionId ->
                if (sessionId != null) {
                    refreshMessages()
                }
            }
        }
    }

    fun configure(apiKey: String, provider: ProviderType = ProviderType.Gemini, model: String = provider.defaultModel, baseUrl: String? = null) {
        val config = LlmConfig(
            provider = provider,
            model = model,
            apiKey = apiKey,
            baseUrl = baseUrl ?: provider.defaultBaseUrl
        )
        _config.value = config
        _uiState.value = _uiState.value.copy(isConfigured = true)

        sessionProcessor = SessionProcessor.create(config)
        sessionProcessor?.let { processor ->
            viewModelScope.launch {
                processor.events.collect { event ->
                    when (event) {
                        is SessionProcessor.SessionEvent.StreamText -> {
                            val messages = sessionManager.getMessages(sessionManager.currentSessionId.value ?: return@collect)
                            val lastAssistant = messages.lastOrNull { it.role == MessageRole.Assistant }
                            if (lastAssistant != null) {
                                _uiState.value = _uiState.value.copy(
                                    messages = _uiState.value.messages.map {
                                        if (it.id == lastAssistant.id) it.copy(content = lastAssistant.content, isStreaming = true)
                                        else it
                                    }
                                )
                            }
                        }
                        is SessionProcessor.SessionEvent.ToolCallStarted -> {
                            val label = when (event.toolCall.toolName) {
                                "websearch" -> "🔍 Searching the web..."
                                "webfetch" -> "🌐 Fetching web page..."
                                "bash" -> "💻 Running command..."
                                "git_init" -> "📦 Initializing git repo..."
                                "repo_clone" -> "📦 Cloning repository..."
                                "git_status" -> "📊 Checking git status..."
                                "repo_overview" -> "📋 Analyzing repository..."
                                "read" -> "📖 Reading file..."
                                "write" -> "✏️ Writing file..."
                                "edit" -> "✏️ Editing file..."
                                "glob" -> "🔎 Finding files..."
                                "grep" -> "🔎 Searching contents..."
                                "apply_patch" -> "📝 Applying patch..."
                                "question" -> "❓ Asking you a question..."
                                else -> "🔧 Running ${event.toolCall.toolName}..."
                            }
                            _uiState.value = _uiState.value.copy(
                                messages = _uiState.value.messages + ChatMessage(
                                    id = event.toolCall.id,
                                    role = MessageRole.Assistant,
                                    content = label,
                                    isStreaming = true
                                )
                            )
                        }
                        is SessionProcessor.SessionEvent.ToolCallCompleted -> {
                            _uiState.value = _uiState.value.copy(
                                messages = _uiState.value.messages.map {
                                    if (it.id == event.toolCallId) it.copy(isStreaming = false)
                                    else it
                                }
                            )
                        }
                        is SessionProcessor.SessionEvent.ToolCallFailed -> {
                            _uiState.value = _uiState.value.copy(
                                messages = _uiState.value.messages.map {
                                    if (it.id == event.toolCallId) it.copy(content = "❌ ${it.content}: ${event.error}", isStreaming = false)
                                    else it
                                }
                            )
                        }
                        is SessionProcessor.SessionEvent.MessageComplete -> {
                            refreshMessages()
                        }
                        is SessionProcessor.SessionEvent.Error -> {
                            _uiState.value = _uiState.value.copy(error = event.message)
                        }
                        is SessionProcessor.SessionEvent.ProcessingStarted -> {
                            _uiState.value = _uiState.value.copy(isProcessing = true)
                        }
                        is SessionProcessor.SessionEvent.ProcessingComplete -> {
                            _uiState.value = _uiState.value.copy(isProcessing = false)
                            refreshMessages()
                        }
                        is SessionProcessor.SessionEvent.StreamReasoning -> { }
                        is SessionProcessor.SessionEvent.QuestionReceived -> {
                            _uiState.value = _uiState.value.copy(pendingQuestion = event.request)
                        }
                        is SessionProcessor.SessionEvent.QuestionTimeout -> {
                            _uiState.value = _uiState.value.copy(error = event.message, pendingQuestion = null)
                        }
                    }
                }
            }
        }

        sessionManager.createSession()
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        _uiState.value = _uiState.value.copy(inputText = "")
        sessionProcessor?.sendMessage(text)
    }

    fun answerQuestion(answers: List<String>) {
        val request = _uiState.value.pendingQuestion ?: return
        _uiState.value = _uiState.value.copy(pendingQuestion = null)
        sessionProcessor?.answerQuestion(QuestionResponse(request.id, answers))
    }

    fun dismissQuestion() {
        _uiState.value = _uiState.value.copy(pendingQuestion = null)
        // Continue processing - the LLM will handle the cancelled question
        sessionProcessor?.sendMessage("@continue")
    }

    fun updateInputText(text: String) {
        _uiState.value = _uiState.value.copy(inputText = text)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun cancelProcessing() {
        sessionProcessor?.cancelProcessing()
    }

    private fun refreshMessages() {
        val sid = sessionManager.currentSessionId.value ?: return
        val messages = sessionManager.getMessages(sid)
        _uiState.value = _uiState.value.copy(
            messages = messages.mapNotNull { msg ->
                when (msg.role) {
                    MessageRole.User -> ChatMessage(
                        id = msg.id,
                        role = msg.role,
                        content = msg.content
                    )
                    MessageRole.Assistant -> ChatMessage(
                        id = msg.id,
                        role = msg.role,
                        content = msg.content,
                        toolCalls = msg.toolCalls.map { "${it.toolName}(${it.arguments.take(100)})" }
                    )
                    MessageRole.Tool -> null // Skip tool result messages in UI
                    MessageRole.System -> null
                }
            }
        )
    }

    override fun onCleared() {
        super.onCleared()
        sessionProcessor?.destroy()
    }
}
