package com.sqlai.assistant.engine

import com.sqlai.assistant.core.AssistantState
import com.sqlai.assistant.core.LogBus
import com.sqlai.assistant.core.LogLevel
import com.sqlai.assistant.core.StateBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * SQLAgentEngine V4 - the DUAL-LOOP parallel agent.
 *
 *  Loop A - Voice Bridge (async worker, Dispatchers.Default + Speaker queue):
 *    keeps a serialized, non-blocking spoken-feedback channel alive. Progress
 *    lines ("Opening WhatsApp now...", "Searching for Mohan...", "Placing the
 *    call...") are emitted into [progress] and consumed by a dedicated bridge
 *    coroutine that pushes them into Speaker.post(). Voice NEVER runs on the
 *    executor thread, so TTS/Gemini round-trips cannot stall automation, and
 *    the wake-word listener (ListeningService) stays armed the whole time.
 *
 *  Loop B - Task Executor (async worker, Dispatchers.IO):
 *    runs the unlimited Observe -> Think -> Act -> Verify loop
 *    ([AgenticLoopEngine]) over Accessibility + vision, fully concurrent with
 *    Loop A. Screenshots/vision parsing stay on IO; state is published via
 *    [taskState] StateFlow.
 *
 *  Cancellation: [cancel] flips a flag polled between steps - the executor
 *    exits cleanly at the next iteration without throwing into callers.
 */
object SQLAgentEngineV4 {

    /** Live task state for the dashboard badge. */
    data class TaskState(
        val running: Boolean = false,
        val task: String = "",
        val startedAt: Long = 0L
    )

    private val _taskState = MutableStateFlow(TaskState())
    val taskState: StateFlow<TaskState> = _taskState.asStateFlow()

    /** Serialized spoken progress - consumed by the voice bridge worker. */
    private val progress = MutableSharedFlow<String>(
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )

    private val runMutex = Mutex()

    @Volatile private var cancelRequested = false

    private val CANCEL_WORDS = setOf(
        "stop", "cancel", "stop it", "cancel it", "ruko", "ruk jao",
        "stop that", "band karo", "abort", "stop the task"
    )

    /** True when [text] is a voice/text command to abort the running task. */
    fun matchesCancel(text: String): Boolean {
        val clean = text.trim().lowercase().trimEnd('.', '!', '?')
        return clean in CANCEL_WORDS || clean.startsWith("stop ") && clean.length < 22
    }

    fun isRunning(): Boolean = _taskState.value.running

    /** Request a graceful stop - the executor exits after its current step. */
    fun cancel() {
        if (!_taskState.value.running) return
        cancelRequested = true
        LogBus.log("Stop requested - finishing current step", LogLevel.WARN)
        Speaker.post("Stopping.")
    }

    /**
     * Run one task to completion (or cancellation). Suspends until Loop B is
     * done while Loop A keeps feeding spoken progress. Single-flight: a second
     * call while one is running is refused.
     */
    suspend fun run(task: String, source: String) {
        if (!runMutex.tryLock()) {
            LogBus.log("Agent busy - ignored: \"$task\"", LogLevel.WARN)
            return
        }
        try {
            cancelRequested = false
            _taskState.value = TaskState(running = true, task = task, startedAt = System.currentTimeMillis())

            coroutineScope {
                // ---- Loop A: voice bridge worker --------------------------
                val voiceBridge = launch(Dispatchers.Default) {
                    progress.collect { line ->
                        Speaker.post(line)          // non-blocking speech queue
                        StateBus.setCommand(line)   // live UI feedback
                    }
                }

                // ---- Loop B: task executor worker -------------------------
                val executor = launch(Dispatchers.IO) {
                    StateBus.setState(AssistantState.PROCESSING)
                    AgenticLoopEngine.runTask(
                        task = task,
                        source = source,
                        announce = { line -> progress.tryEmit(line) },
                        shouldStop = { cancelRequested }
                    )
                }

                executor.join()
                voiceBridge.cancel()
            }
        } finally {
            _taskState.value = TaskState()
            runMutex.unlock()
        }
    }

    fun shutdown() {
        cancelRequested = true
    }
}
