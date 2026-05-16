package com.opencode.android.llm

import com.opencode.android.model.LlmConfig
import com.opencode.android.model.Message
import com.opencode.android.model.ProviderType
import com.opencode.android.model.StreamEvent
import com.opencode.android.model.ToolDef
import kotlinx.coroutines.flow.Flow

interface LlmProvider {
    fun stream(
        messages: List<Message>,
        tools: List<ToolDef>,
        config: LlmConfig,
        systemPrompt: String = ""
    ): Flow<StreamEvent.Type>

    suspend fun chat(
        messages: List<Message>,
        tools: List<ToolDef>,
        config: LlmConfig,
        systemPrompt: String = ""
    ): String

    companion object {
        fun create(config: LlmConfig): LlmProvider = when (config.provider) {
            ProviderType.Gemini -> GeminiProvider()
            ProviderType.GitHubModels,
            ProviderType.OpenAI,
            ProviderType.OpenRouter,
            ProviderType.Cloudflare,
            ProviderType.Custom -> OpenAiProvider()
            ProviderType.Anthropic -> AnthropicProvider()
            ProviderType.DeepSeek -> OpenAiProvider()
        }
    }
}
