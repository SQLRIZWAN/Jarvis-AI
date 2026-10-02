package com.sqlai.assistant.agent

import com.sqlai.assistant.core.AppCrashHandler
import com.sqlai.assistant.core.LogBus
import com.sqlai.assistant.core.LogLevel
import com.sqlai.assistant.engine.AssistantEngine
import com.sqlai.assistant.engine.SQLAgentEngineV4
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
 *   Task(text)  - OperatorAgent run (queued by the caller until free)
 *   Stop        - graceful cancel of the running task
 *   Status      - Blackboard snapshot on the bus
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

    init {
        // Mirror the OperatorAgent run-state into the Blackboard so every
        // agent (and the dashboard) sees one consistent truth.
        scope.launch {
            var wasRunning = false
            SQLAgentEngineV4.taskState.collect { s ->
                Blackboard.taskRunning = s.running
                Blackboard.currentTask = s.task.ifBlank { null }
                if (!wasRunning && s.running) {
                    _events.emit(AgentEvent.TaskStarted(s.task))
                } else if (wasRunning && !s.running) {
                    _events.emit(AgentEvent.TaskFinished(s.task.ifBlank { Blackboard.currentTask.orEmpty() }))
                }
                wasRunning = s.running
            }
        }
    }

    /** Route one intent - non-blocking, failures land on the crash handler. */
    fun dispatch(intent: Intent) {
        scope.launch { handle(intent) }
    }

    private suspend fun handle(intent: Intent) {
        when (intent) {
            is Intent.Chat -> {
                // M1 routing: legacy single path; M2 swaps in ConversationAgent
                // so this NEVER pauses the running task.
                LogBus.log("[AgentOS] chat: ${intent.text}", LogLevel.INFO)
                AssistantEngine.execute(intent.text, "agentos-chat")
            }

            is Intent.Task -> {
                LogBus.log("[AgentOS] task: ${intent.text}", LogLevel.INFO)
                Blackboard.currentTask = intent.text
                AssistantEngine.execute(intent.text, "agentos-task")
            }

            Intent.Stop -> {
                LogBus.log("[AgentOS] stop", LogLevel.WARN)
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
