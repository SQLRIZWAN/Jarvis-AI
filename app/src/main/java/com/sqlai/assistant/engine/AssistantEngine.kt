package com.sqlai.assistant.engine

import com.sqlai.assistant.SqlAiApp
import com.sqlai.assistant.ai.AiClient
import com.sqlai.assistant.ai.ChatMessage
import com.sqlai.assistant.ai.PlanParser
import com.sqlai.assistant.core.AssistantState
import com.sqlai.assistant.core.LogBus
import com.sqlai.assistant.core.LogLevel
import com.sqlai.assistant.core.StateBus
import com.sqlai.assistant.device.DeviceController
import com.sqlai.assistant.service.SqlAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** Rolling conversation memory so follow-up commands keep context. */
object HistoryStore {

    private const val MAX_TURNS = 16
    private val lock = Any()
    private val messages = ArrayDeque<ChatMessage>()

    fun addUser(text: String) = synchronized(lock) {
        messages.addLast(ChatMessage("user", text))
        trim()
    }

    fun addAssistant(text: String) = synchronized(lock) {
        messages.addLast(ChatMessage("assistant", text.take(600)))
        trim()
    }

    fun snapshot(): List<ChatMessage> = synchronized(lock) { messages.toList() }

    fun clear() = synchronized(lock) { messages.clear() }

    private fun trim() {
        while (messages.size > MAX_TURNS) messages.removeFirst()
    }
}

/**
 * Brain of SQL AI: transcript -> LLM plan -> spoken reply -> device actions.
 */
object AssistantEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    /** Fire-and-forget entry point used by the listening + assist services. */
    fun execute(command: String, source: String) {
        val job = scope.launch { run(command, source) }
        job.invokeOnCompletion { throwable ->
            if (throwable != null) {
                LogBus.log("Engine crashed: ${throwable.message}", LogLevel.ERROR)
                StateBus.setState(AssistantState.IDLE)
            }
        }
    }

    /** Suspend entry point (used by tests / manual flows). */
    suspend fun executeBlocking(command: String, source: String) = run(command, source)

    private suspend fun run(command: String, source: String) {
        if (!mutex.tryLock()) {
            LogBus.log("Engine busy - ignored: \"$command\"", LogLevel.WARN)
            return
        }
        try {
            StateBus.setState(AssistantState.PROCESSING)
            StateBus.setCommand(command)
            LogBus.log("[$source] Thinking about: \"$command\"")

            val settings = SqlAiApp.settings.settings.first()

            val screenContext = if (settings.screenContextEnabled) {
                SqlAccessibilityService.instance?.captureScreenText(40)
            } else null

            HistoryStore.addUser(command)

            val rawReply = try {
                AiClient.complete(settings, screenContext, HistoryStore.snapshot())
            } catch (e: Exception) {
                LogBus.log("AI request failed: ${e.message}", LogLevel.ERROR)
                Speaker.speak("Sorry, I could not reach the AI service.")
                HistoryStore.addAssistant("[error] ${e.message}")
                StateBus.setState(AssistantState.IDLE)
                return
            }

            HistoryStore.addAssistant(rawReply)
            val plan = PlanParser.parse(rawReply)
            LogBus.log("Reply: ${plan.reply}", LogLevel.SUCCESS)

            Speaker.speak(plan.reply)

            withContext(Dispatchers.IO) {
                DeviceController.execute(plan.actions)
            }

            StateBus.setState(AssistantState.IDLE)
            LogBus.log("Done (${plan.actions.size} action(s))", LogLevel.SUCCESS)
        } finally {
            mutex.unlock()
        }
    }
}
