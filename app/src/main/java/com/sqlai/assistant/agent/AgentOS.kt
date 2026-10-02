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
