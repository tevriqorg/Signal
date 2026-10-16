package com.wb.signalpro.agent.provider

import kotlinx.coroutines.flow.Flow

enum class AiProviderKind {
    OPENAI_COMPATIBLE,
    CHATGPT_PLAN,
}

data class AiProviderCapabilities(
    val text: Boolean = true,
    val vision: Boolean = false,
    val tools: Boolean = false,
    val streaming: Boolean = true,
)

data class AiModel(
    val id: String,
    val displayName: String = id,
)

enum class AiMessageRole {
    SYSTEM,
    USER,
    ASSISTANT,
    TOOL,
}

data class AiMessage(
    val role: AiMessageRole,
    val content: String,
)

data class AiRequest(
    val conversationId: String,
    val model: String,
    val messages: List<AiMessage>,
    val availableTools: List<String> = emptyList(),
)

sealed interface AiStreamEvent {
    data class TextDelta(val text: String) : AiStreamEvent

    data class ToolCall(
        val callId: String,
        val toolName: String,
        val argumentsJson: String,
    ) : AiStreamEvent

    data class Completed(val responseId: String? = null) : AiStreamEvent

    data class Failed(
        val message: String,
        val retryable: Boolean = false,
    ) : AiStreamEvent
}

sealed interface AiAuthState {
    data object Unconfigured : AiAuthState
    data object Ready : AiAuthState
    data object Authenticating : AiAuthState
    data class Error(val message: String) : AiAuthState
}

/**
 * Stable provider contract for Signal's local Agent Runtime.
 *
 * Concrete HTTP/auth implementations are deliberately deferred to later gates.
 */
interface AiProvider {
    val kind: AiProviderKind

    suspend fun capabilities(): AiProviderCapabilities

    suspend fun listModels(): List<AiModel>

    fun streamResponse(request: AiRequest): Flow<AiStreamEvent>

    suspend fun authState(): AiAuthState
}

/** OpenAI-compatible provider family. Live inference is a G3 concern. */
interface OpenAiCompatibleProvider : AiProvider {
    override val kind: AiProviderKind
        get() = AiProviderKind.OPENAI_COMPATIBLE
}

/** ChatGPT plan / Sign in with ChatGPT provider family. OAuth is a G4 concern. */
interface ChatGptPlanProvider : AiProvider {
    override val kind: AiProviderKind
        get() = AiProviderKind.CHATGPT_PLAN
}
