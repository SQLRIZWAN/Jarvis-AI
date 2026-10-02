package com.sqlai.assistant.agent

/**
 * v7 Blackboard - the single source of truth shared by the independent
 * agents. ConversationAgent, OperatorAgent, GuardAgent and the UI all read
 * these flags instead of poking each other, so chat can answer instantly
 * while a task keeps running.
 */
object Blackboard {

    /** Original task text of the currently active OperatorAgent run. */
    @Volatile
    var currentTask: String? = null

    /** Hash of the last observed screen - Critic/Critic reuse it to detect UI delta. */
    @Volatile
    var lastScreenHash: String? = null

    /** Which voice pipeline owns the mic right now (none|stt|vosk|gemini_live|call|voice_note). */
    @Volatile
    var micOwner: String = "none"

    /** True while an OperatorAgent task is executing. */
    @Volatile
    var taskRunning: Boolean = false

    /** True while the ConversationAgent is waiting on a model reply. */
    @Volatile
    var chatBusy: Boolean = false

    /** Immutable snapshot for status events / dashboard. */
    data class State(
        val currentTask: String?,
        val lastScreenHash: String?,
        val micOwner: String,
        val taskRunning: Boolean,
        val chatBusy: Boolean
    )

    fun state(): State = State(
        currentTask = currentTask,
        lastScreenHash = lastScreenHash,
        micOwner = micOwner,
        taskRunning = taskRunning,
        chatBusy = chatBusy
    )
}
