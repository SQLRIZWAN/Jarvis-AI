package com.sqlai.assistant.engine

import com.sqlai.assistant.SqlAiApp
import com.sqlai.assistant.ai.AiClient
import com.sqlai.assistant.ai.ChatMessage
import com.sqlai.assistant.core.AppCrashHandler
import com.sqlai.assistant.core.AssistantState
import com.sqlai.assistant.core.LogBus
import com.sqlai.assistant.core.LogLevel
import com.sqlai.assistant.core.StateBus
import com.sqlai.assistant.service.SqlAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Rolling conversation memory so follow-up commands keep context. */
object HistoryStore {

    private const val MAX_TURNS = 16
    private val lock = Any()
    private val messages = ArrayDeque<Pair<String, String>>()

    fun addUser(text: String) = synchronized(lock) {
        messages.addLast("user" to text)
        trim()
    }

    fun addAssistant(text: String) = synchronized(lock) {
        messages.addLast("assistant" to text.take(600))
        trim()
    }

    fun snapshot(): List<Pair<String, String>> = synchronized(lock) { messages.toList() }

    fun clear() = synchronized(lock) { messages.clear() }

    private fun trim() {
        while (messages.size > MAX_TURNS) messages.removeFirst()
    }
}

/**
 * Entry point of the assistant: keeps the state machine, single-flight mutex
 * and hands the actual work to the dual-loop parallel [SQLAgentEngineV4]
 * (Loop A: voice bridge / Loop B: task executor).
 */
object AssistantEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + AppCrashHandler.coroutineHandler)
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

    /** Suspend entry point (used by the listening + assist services). */
    suspend fun executeBlocking(command: String, source: String) = run(command, source)

    private const val INTERRUPT_PROMPT =
        "The user just INTERRUPTED a task that is running in the background. " +
            "Answer their message naturally and briefly (max 2 sentences, in their language), " +
            "as a helpful assistant who paused to listen. Do NOT describe actions or JSON. " +
            "If they seem to be giving a BRAND-NEW task, tell them gently: " +
            "\"Say SQL stop first, then repeat that.\" The paused task will resume after you answer."

    private suspend fun run(command: String, source: String) {
        // Voice/text stop command -> abort the running V4 task (graceful).
        if (SQLAgentEngineV4.isRunning() && SQLAgentEngineV4.matchesCancel(command)) {
            HistoryStore.addUser(command)
            SQLAgentEngineV4.cancel()
            return
        }

        // INTERRUPT: user spoke while Loop B was working - freeze execution,
        // reply naturally, then resume the task exactly where it stopped.
        if (SQLAgentEngineV4.isRunning()) {
            HistoryStore.addUser(command)
            SQLAgentEngineV4.pause()
            try {
                delay(300) // let the current step reach a clean boundary
                val reply = interruptReply(command)
                if (reply.isNotBlank()) {
                    HistoryStore.addAssistant(reply)
                    Speaker.post(reply)
                }
            } finally {
                SQLAgentEngineV4.resume()
            }
            return
        }

        if (!mutex.tryLock()) {
            LogBus.log("Engine busy - ignored: \"$command\"", LogLevel.WARN)
            return
        }
        try {
            StateBus.setState(AssistantState.PROCESSING)
            StateBus.setCommand(command)
            HistoryStore.addUser(command)

            // Dual-loop: V4 suspends until Loop B finishes while Loop A keeps
            // feeding spoken progress from a separate worker.
            SQLAgentEngineV4.run(command, source)

            HistoryStore.addAssistant("completed: $command")
            StateBus.setState(AssistantState.IDLE)
        } catch (e: Exception) {
            LogBus.log("Engine error: ${e.message}", LogLevel.ERROR)
            StateBus.setState(AssistantState.IDLE)
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Quick conversational answer while Loop B is paused: recent chat memory
     * + live screen so the reply is contextual ("what's on screen" works).
     */
    private suspend fun interruptReply(command: String): String {
        return try {
            val settings = SqlAiApp.settings.settings.first()
            if (settings.apiKey.isBlank()) return "Yes?"
            val screen = withTimeoutOrNull(4000) {
                withContext(Dispatchers.IO) {
                    SqlAccessibilityService.instance?.captureScreenText(50)
                }
            }.orEmpty()
            val history = (HistoryStore.snapshot().map { ChatMessage(it.first, it.second) } +
                ChatMessage("user", command)).takeLast(8)
            AiClient.complete(
                settings = settings,
                screenContext = screen.ifBlank { null },
                history = history,
                imageJpeg = null,
                systemPromptOverride = INTERRUPT_PROMPT
            ).take(400)
        } catch (e: Exception) {
            LogBus.log("Interrupt reply failed: ${e.message}", LogLevel.WARN)
            "Yes?"
        }
    }
}
