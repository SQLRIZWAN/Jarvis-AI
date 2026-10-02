package com.sqlai.assistant.engine

import com.sqlai.assistant.core.AssistantState
import com.sqlai.assistant.core.LogBus
import com.sqlai.assistant.core.LogLevel
import com.sqlai.assistant.core.StateBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

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

    /** Suspend entry point (used by the listening + assist services). */
    suspend fun executeBlocking(command: String, source: String) = run(command, source)

    private suspend fun run(command: String, source: String) {
        // Voice/text stop command -> abort the running V4 task (graceful).
        if (SQLAgentEngineV4.isRunning() && SQLAgentEngineV4.matchesCancel(command)) {
            HistoryStore.addUser(command)
            SQLAgentEngineV4.cancel()
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
}
