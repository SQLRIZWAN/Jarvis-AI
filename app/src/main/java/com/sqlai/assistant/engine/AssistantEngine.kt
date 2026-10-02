package com.sqlai.assistant.engine

import com.sqlai.assistant.agent.AgentOS

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
 * v7 compatibility facade: the listening + assist services keep calling
 * [execute], but every utterance now flows through [AgentOS.dispatchText]
 * -> IntentRouter -> ConversationAgent || OperatorAgent. The old
 * single-flight drop (`Engine busy - ignored`) and the pause-on-speech path
 * are GONE: chat answers while the task keeps running (BUG #5).
 */
object AssistantEngine {

    /** Fire-and-forget entry point used by the listening + assist services. */
    fun execute(command: String, source: String) {
        AgentOS.dispatchText(command, source)
    }

    /** Suspend entry point (used by the listening + assist services). */
    suspend fun executeBlocking(command: String, source: String) {
        AgentOS.routeText(command, source)
    }
}
