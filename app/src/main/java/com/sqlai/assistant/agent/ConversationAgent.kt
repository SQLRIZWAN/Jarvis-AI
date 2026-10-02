package com.sqlai.assistant.agent

import com.sqlai.assistant.SqlAiApp
import com.sqlai.assistant.ai.AiClient
import com.sqlai.assistant.ai.ChatMessage
import com.sqlai.assistant.core.CorePromptBuilder
import com.sqlai.assistant.engine.HistoryStore
import kotlinx.coroutines.flow.first

/**
 * v7 ConversationAgent - the chat brain.
 *
 * Independent of the OperatorAgent: its own [HistoryStore] snapshot + its
 * own model call, so the user gets an INSTANT spoken answer even while a
 * task keeps running in the background (BUG #5). Never touches the device,
 * never pauses Loop B.
 */
object ConversationAgent {

    private const val MAX_HISTORY = 8

    /** Answer one conversational message. Blank only when the model failed. */
    suspend fun respond(text: String): String {
        val settings = SqlAiApp.settings.settings.first()
        if (settings.apiKey.isBlank()) return "Yes?"
        val history = (
            HistoryStore.snapshot().map { ChatMessage(it.first, it.second) } +
                ChatMessage("user", text)
            ).takeLast(MAX_HISTORY)
        return AiClient.complete(
            settings = settings,
            screenContext = null,
            history = history,
            imageJpeg = null,
            systemPromptOverride = CorePromptBuilder.voice(settings)
        ).take(600)
    }
}
