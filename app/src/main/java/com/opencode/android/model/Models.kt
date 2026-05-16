package com.opencode.android.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class Message(
    val id: String,
    val role: MessageRole,
    val content: String = "",
    val toolCalls: List<ToolCall> = emptyList(),
    val toolResults: List<ToolResult> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
enum class MessageRole { User, Assistant, System, Tool }

@Serializable
data class ToolCall(
    val id: String,
    val toolName: String,
    val arguments: String,
    val status: ToolCallStatus = ToolCallStatus.Pending
)

@Serializable
enum class ToolCallStatus { Pending, Running, Completed, Failed }

@Serializable
data class ToolResult(
    val toolCallId: String,
    val toolName: String,
    val output: String,
    val isError: Boolean = false
)

@Serializable
data class Session(
    val id: String,
    val title: String = "New Session",
    val messages: List<Message> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val projectId: String? = null
)

@Serializable
data class Project(
    val id: String,
    val name: String,
    val rootPath: String,
    val createdAt: Long = System.currentTimeMillis()
)

data class LlmConfig(
    val provider: ProviderType,
    val model: String,
    val apiKey: String,
    val baseUrl: String = provider.defaultBaseUrl,
    val temperature: Double = 0.7,
    val maxTokens: Int = 8192
)

enum class ProviderType(
    val displayName: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val isFree: Boolean,
    val description: String
) {
    Gemini(
        "Google Gemini",
        "https://generativelanguage.googleapis.com/v1beta",
        "gemini-2.0-flash",
        true,
        "Free tier: 60 req/min, no credit card. Get key at aistudio.google.com"
    ),
    GitHubModels(
        "GitHub Models",
        "https://models.inference.ai.azure.com",
        "deepseek/deepseek-v3-0324",
        true,
        "All models FREE with GitHub token. Get token at github.com/settings/tokens"
    ),
    OpenRouter(
        "OpenRouter",
        "https://openrouter.ai/api/v1",
        "openrouter/free",
        true,
        "68+ free models available. Get key at openrouter.ai/keys. Includes deepseek-v4-flash-free!"
    ),
    Cloudflare(
        "Cloudflare Workers AI",
        "https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/v1",
        "@cf/meta/llama-3.3-70b-instruct-fp8-fast",
        true,
        "10k neurals/day free. Needs Account ID + API Token"
    ),
    OpenAI(
        "OpenAI",
        "https://api.openai.com/v1",
        "gpt-4o",
        false,
        "Paid API. Requires OpenAI API key."
    ),
    Anthropic(
        "Anthropic",
        "https://api.anthropic.com/v1",
        "claude-3-5-sonnet-20241022",
        false,
        "Paid API. Requires Anthropic API key."
    ),
    DeepSeek(
        "DeepSeek",
        "https://api.deepseek.com",
        "deepseek-chat",
        false,
        "DeepSeek V4 Flash: $0.14/$0.28 per 1M tokens. Get key at platform.deepseek.com"
    ),
    Custom(
        "Custom (OpenAI-compatible)",
        "",
        "",
        true,
        "Any OpenAI-compatible endpoint (Ollama, LM Studio, etc.)"
    );

    companion object {
        val freeProviders: List<ProviderType> = entries.filter { it.isFree }
    }
}

@Serializable
data class ToolDef(
    val id: String,
    val description: String,
    val parameters: JsonElement,
    val required: List<String> = emptyList()
)

object StreamEvent {
    sealed class Type {
        data class TextDelta(val text: String) : Type()
        data class ReasoningDelta(val text: String) : Type()
        data class ToolCallStart(val toolCall: ToolCall) : Type()
        data class ToolCallDelta(val toolCallId: String, val json: String) : Type()
        data class ToolCallEnd(val toolCall: ToolCall) : Type()
        data class Finish(val reason: String) : Type()
        data class Error(val message: String) : Type()
    }
}

data class ToolContext(
    val sessionId: String,
    val messageId: String,
    val agent: String = "build",
    val callId: String? = null
)

data class ExecuteResult(
    val title: String,
    val output: String,
    val metadata: Map<String, String> = emptyMap(),
    val isError: Boolean = false
)

data class ProviderOption(
    val type: ProviderType,
    val models: List<String>,
    val defaultModel: String
) {
    companion object {
        fun defaults(): List<ProviderOption> = listOf(
            ProviderOption(
                ProviderType.Gemini,
                listOf("gemini-2.0-flash", "gemini-2.0-flash-lite", "gemini-1.5-flash", "gemini-2.5-pro-exp-03-25"),
                "gemini-2.0-flash"
            ),
            ProviderOption(
                ProviderType.GitHubModels,
                listOf(
                    "deepseek/deepseek-v3-0324", "deepseek/deepseek-r1",
                    "deepseek/deepseek-r1-0528",
                    "microsoft/phi-3.5-mini-instruct", "ai21-jamba-1.5-mini"
                ),
                "deepseek/deepseek-v3-0324"
            ),
            ProviderOption(
                ProviderType.OpenRouter,
                listOf(
                    "openrouter/free",
                    "deepseek/deepseek-v4-flash-free",
                    "google/gemma-3-27b-it:free",
                    "meta-llama/llama-3.3-70b-instruct:free",
                    "deepseek/deepseek-r1-distill-llama-70b:free"
                ),
                "deepseek/deepseek-v4-flash-free"
            ),
            ProviderOption(
                ProviderType.Cloudflare,
                listOf(
                    "@cf/meta/llama-3.3-70b-instruct-fp8-fast",
                    "@cf/deepseek-ai/deepseek-r1-distill-qwen-32b",
                    "@cf/mistral/mistral-7b-instruct-v0.3",
                    "@cf/meta/llama-3.2-3b-instruct"
                ),
                "@cf/meta/llama-3.3-70b-instruct-fp8-fast"
            ),
            ProviderOption(
                ProviderType.OpenAI,
                listOf("gpt-4o", "gpt-4o-mini", "gpt-4.1", "gpt-4.1-nano", "gpt-4.1-mini"),
                "gpt-4o"
            ),
            ProviderOption(
                ProviderType.Anthropic,
                listOf("claude-sonnet-4", "claude-haiku-3-5-20241022", "claude-opus-4"),
                "claude-sonnet-4"
            ),
            ProviderOption(
                ProviderType.DeepSeek,
                listOf("deepseek-chat", "deepseek-reasoner", "deepseek-v4-flash", "deepseek-v4-pro"),
                "deepseek-chat"
            ),
        )
    }
}
