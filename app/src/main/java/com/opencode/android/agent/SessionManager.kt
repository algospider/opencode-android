package com.opencode.android.agent

import com.opencode.android.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class SessionManager {
    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    fun createSession(projectId: String? = null): Session {
        val session = Session(
            id = generateId(),
            projectId = projectId
        )
        _sessions.value = _sessions.value + session
        _currentSessionId.value = session.id
        return session
    }

    fun getSession(id: String): Session? = _sessions.value.find { it.id == id }

    fun getCurrentSession(): Session? = _currentSessionId.value?.let { getSession(it) }

    fun setCurrentSession(id: String) {
        _currentSessionId.value = id
    }

    fun addMessage(sessionId: String, message: Message) {
        _sessions.value = _sessions.value.map { session ->
            if (session.id == sessionId) {
                session.copy(
                    messages = session.messages + message,
                    updatedAt = System.currentTimeMillis()
                )
            } else session
        }
    }

    fun updateLastAssistantMessage(sessionId: String, content: String, toolCalls: List<ToolCall> = emptyList()) {
        _sessions.value = _sessions.value.map { session ->
            if (session.id == sessionId && session.messages.isNotEmpty()) {
                val msgs = session.messages.toMutableList()
                val last = msgs.last()
                if (last.role == MessageRole.Assistant) {
                    msgs[msgs.size - 1] = last.copy(
                        content = if (last.content.isNotEmpty() && content.isNotEmpty()) last.content else (last.content + content),
                        toolCalls = last.toolCalls + toolCalls
                    )
                }
                session.copy(messages = msgs, updatedAt = System.currentTimeMillis())
            } else session
        }
    }

    fun addToolResults(sessionId: String, results: List<ToolResult>) {
        _sessions.value = _sessions.value.map { session ->
            if (session.id == sessionId) {
                val lastAssistant = session.messages.lastOrNull { it.role == MessageRole.Assistant }
                session.copy(
                    messages = session.messages + Message(
                        id = generateId(),
                        role = MessageRole.Tool,
                        toolResults = results
                    ),
                    updatedAt = System.currentTimeMillis()
                )
            } else session
        }
    }

    fun updateSessionTitle(sessionId: String, title: String) {
        _sessions.value = _sessions.value.map { session ->
            if (session.id == sessionId) session.copy(title = title, updatedAt = System.currentTimeMillis())
            else session
        }
    }

    fun getMessages(sessionId: String): List<Message> {
        return getSession(sessionId)?.messages ?: emptyList()
    }

    private fun generateId(): String {
        val timestamp = System.currentTimeMillis()
        val random = UUID.randomUUID().toString().take(8)
        return "sess_${timestamp}_${random}"
    }

    companion object {
        @Volatile
        private var instance: SessionManager? = null

        fun getInstance(): SessionManager {
            return instance ?: synchronized(this) {
                instance ?: SessionManager().also { instance = it }
            }
        }
    }
}
