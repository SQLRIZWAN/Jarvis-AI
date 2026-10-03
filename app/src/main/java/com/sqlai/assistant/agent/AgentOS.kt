package com.sqlai.assistant.agent

import com.sqlai.assistant.core.AppCrashHandler
import com.sqlai.assistant.core.AssistantState
import com.sqlai.assistant.core.LogBus
import com.sqlai.assistant.core.LogLevel
import com.sqlai.assistant.core.StateBus
import com.sqlai.assistant.engine.HistoryStore
import com.sqlai.assistant.engine.SQLAgentEngineV4
import com.sqlai.assistant.engine.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/**
 * v7 AgentOS - the multi-agent orchestrator.
 *
 * One CoroutineScope (SupervisorJob + AppCrashHandler.coroutineHandler), one
 * EventBus ([events]) and one [uiMutex] that serializes DeviceController UI
 * actions (ek time pe ek tap). [dispatch] routes the four intents:
 *
 *   Chat(text)  - conversational answer, NEVER blocks a running task
 *   Task(text)  - OperatorAgent run; while one runs, new tasks QUEUE
 *   Stop        - graceful cancel + queue clear
 *   Status      - Blackboard snapshot on the bus
 *
 * [dispatchText] classifies one utterance via [IntentRouter] first, so a
 * user speaking mid-task gets an instant chat reply (task keeps going) and
 * a new task is queued behind the current one (BUG #5).
 */
object AgentOS {

    /**
     * Accept words for the risky-action voice gate (lowercase, punctuation
     * stripped). Kept clear of IntentRouter's TASKY/cancel vocabulary so a
     * plain "haan"/"yes" always lands here as [Intent.Chat] instead of being
     * queued as a new task or treated as a stop.
     */
    private val YES_WORDS = setOf(
        "haan", "ha", "han", "ha ji", "ji haan", "yes", "yep", "yeah", "sure",
        "ok", "okay", "confirm", "do it", "go ahead"
    )

    /** Decline words - same TASKY-avoidance rule as [YES_WORDS]. */
    private val NO_WORDS = setOf(
        "nahi", "na", "nahi nahi", "no", "nope", "skip", "rehne do",
        "don't", "do not"
    )

    /** Everything the outside world can ask the agent cluster to do. */
    sealed class Intent {
        data class Chat(val text: String) : Intent()
        data class Task(val text: String) : Intent()
        object Stop : Intent()
        object Status : Intent()
    }

    /** Events emitted on the bus for the UI / voice bridge. */
    sealed class AgentEvent {
        data class ChatReply(val text: String) : AgentEvent()
        data class TaskStarted(val task: String) : AgentEvent()
        data class TaskFinished(val task: String) : AgentEvent()
        data class Stopped(val task: String?) : AgentEvent()
        data class Queued(val task: String) : AgentEvent()
        data class StatusReport(val state: Blackboard.State) : AgentEvent()
    }

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + AppCrashHandler.coroutineHandler
    )

    private val _events = MutableSharedFlow<AgentEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** EventBus - hot, never suspends the emitter, oldest drop first. */
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    /** Serializes every DeviceController UI action across all agents. */
    val uiMutex = Mutex()

    /** One queued task - auto-runs when the current OperatorAgent run ends. */
    @Volatile
    var pendingTask: String? = null

    /** Pending risky-action confirmation latch (v7 M4 voice gate). */
    @Volatile
    private var pendingConfirm: kotlinx.coroutines.CompletableDeferred<Boolean>? = null

    init {
        // Mirror the OperatorAgent run-state into the Blackboard and consume
        // the queue the moment the current run finishes.
        scope.launch {
            var wasRunning = false
            SQLAgentEngineV4.taskState.collect { s ->
                Blackboard.taskRunning = s.running
                Blackboard.currentTask = s.task.ifBlank { null }
                if (!wasRunning && s.running) {
                    _events.emit(AgentEvent.TaskStarted(s.task))
                } else if (wasRunning && !s.running) {
                    _events.emit(
                        AgentEvent.TaskFinished(s.task.ifBlank { Blackboard.currentTask.orEmpty() })
                    )
                    val queued = pendingTask
                    if (queued != null) {
                        pendingTask = null
                        LogBus.log("[AgentOS] auto-running queued task: $queued", LogLevel.INFO)
                        dispatch(Intent.Task(queued), "queue")
                    }
                }
                wasRunning = s.running
            }
        }
    }

    /** Route one intent - non-blocking, failures land on the crash handler. */
    fun dispatch(intent: Intent, source: String = "agentos") {
        scope.launch { runSafely(intent, source) }
    }

    /**
     * v7 M4 risky-action gate: speak [question] and suspend until the user
     * says haan/nahi (routed as [Intent.Chat]) or [timeoutMs] elapses.
     * One latch at a time - a newer question supersedes the older one.
     * Returns false on timeout / cancellation (never swallows cancel).
     */
    suspend fun askConfirm(question: String, timeoutMs: Long = 8_000L): Boolean {
        val latch = kotlinx.coroutines.CompletableDeferred<Boolean>()
        pendingConfirm = latch
        try {
            Speaker.post(question)
            LogBus.log("[AgentOS] confirm: $question", LogLevel.WARN)
            return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { latch.await() } ?: false
        } finally {
            if (pendingConfirm === latch) pendingConfirm = null
        }
    }

    /** Classify one utterance, then dispatch it (voice/typed entry point). */
    fun dispatchText(text: String, source: String) {
        scope.launch { routeText(text, source) }
    }

    /** Suspend classification + dispatch (facade for AssistantEngine). */
    suspend fun routeText(text: String, source: String) {
        when (IntentRouter.route(text, SQLAgentEngineV4.isRunning())) {
            IntentRouter.Route.Cancel -> runSafely(Intent.Stop, source)
            is IntentRouter.Route.Chat -> runSafely(Intent.Chat(text), source)
            else -> runSafely(Intent.Task(text), source)
        }
    }

    private suspend fun runSafely(intent: Intent, source: String) {
        try {
            handle(intent, source)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            LogBus.log("[AgentOS] intent failed: ${t.message}", LogLevel.ERROR)
            StateBus.setState(AssistantState.IDLE)
        }
    }

    private suspend fun handle(intent: Intent, source: String) {
        when (intent) {
            is Intent.Chat -> {
                // v7 M6 barge-in: the user just spoke/typed - stale assistant
                // speech must never talk over the new turn.
                Speaker.interruptPlayback()
                // v7 M4: a pending risky-action confirmation latches FIRST -
                // plain words like "haan"/"nahi" answer the gate, not the LLM.
                val confirm = pendingConfirm
                if (confirm != null && !confirm.isCompleted) {
                    val text = intent.text.trim().lowercase()
                        .removeSuffix(".").removeSuffix("!").trim()
                    when {
                        YES_WORDS.contains(text) -> {
                            confirm.complete(true)
                            Speaker.post("Okay, doing it.")
                        }

                        NO_WORDS.contains(text) -> {
                            confirm.complete(false)
                            Speaker.post("Skipping that step.")
                        }

                        else -> Speaker.post("Say haan to confirm, nahi to skip.")
                    }
                    return
                }
                Blackboard.chatBusy = true
                HistoryStore.addUser(intent.text)
                try {
                    val reply = ConversationAgent.respond(intent.text)
                    if (reply.isNotBlank()) {
                        HistoryStore.addAssistant(reply)
                        Speaker.post(reply)
                        _events.emit(AgentEvent.ChatReply(reply))
                    }
                } finally {
                    Blackboard.chatBusy = false
                }
            }

            is Intent.Task -> {
                Blackboard.currentTask = intent.text
                if (SQLAgentEngineV4.isRunning()) {
                    // BUG #5: never drop, never pause - queue behind the run.
                    pendingTask = intent.text
                    _events.emit(AgentEvent.Queued(intent.text))
                    Speaker.post("Abhi ek task chal raha hai, boliye SQL stop pehle.")
                    LogBus.log("[AgentOS] queued: ${intent.text}", LogLevel.INFO)
                    return
                }
                StateBus.setState(AssistantState.PROCESSING)
                StateBus.setCommand(intent.text)
                // v7 M6: a fresh task starts in silence.
                Speaker.interruptPlayback()
                HistoryStore.addUser(intent.text)
                try {
                    SQLAgentEngineV4.run(intent.text, source)
                    HistoryStore.addAssistant("completed: ${intent.text}")
                } finally {
                    StateBus.setState(AssistantState.IDLE)
                }
            }

            Intent.Stop -> {
                LogBus.log("[AgentOS] stop", LogLevel.WARN)
                // v7 M6: stop means silence too.
                Speaker.interruptPlayback()
                pendingTask = null
                if (SQLAgentEngineV4.isRunning()) {
                    SQLAgentEngineV4.cancel()
                }
                _events.emit(AgentEvent.Stopped(Blackboard.currentTask))
            }

            Intent.Status -> {
                _events.emit(AgentEvent.StatusReport(Blackboard.state()))
            }
        }
    }
}
