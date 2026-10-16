package com.wb.signalpro.agent.tools

@JvmInline
value class SignalToolId(val value: String) {
    init {
        require(value.startsWith("signal.")) { "Signal tool ids must use the signal.* namespace" }
    }
}

data class SignalToolDescriptor(
    val id: SignalToolId,
    val description: String,
    val inputSchemaJson: String,
    val outputSchemaJson: String? = null,
)

data class SignalToolContext(
    val conversationId: String,
    val requestId: String,
)

sealed interface SignalToolResult<out T> {
    data class Success<T>(val value: T) : SignalToolResult<T>

    data class Rejected(
        val reason: String,
        val code: String? = null,
    ) : SignalToolResult<Nothing>

    data class Failure(
        val message: String,
        val retryable: Boolean = false,
    ) : SignalToolResult<Nothing>
}

/**
 * Typed boundary between the Agent Runtime and Signal data/features.
 *
 * A concrete tool owns authorization, redaction, limits and evidence references.
 * The Agent must not bypass tools to query persistence directly.
 */
interface SignalTool<Input : Any, Output : Any> {
    val descriptor: SignalToolDescriptor

    suspend fun execute(
        input: Input,
        context: SignalToolContext,
    ): SignalToolResult<Output>
}

/**
 * Registry surface used by the Agent Runtime. Type erasure occurs only at lookup;
 * concrete SignalTool implementations remain strongly typed.
 */
interface SignalToolCatalog {
    fun all(): List<SignalTool<*, *>>

    fun find(id: SignalToolId): SignalTool<*, *>?
}
