package com.sqlai.assistant.agent

import com.sqlai.assistant.engine.SQLAgentEngineV4

/**
 * v7 pure intent routing - decides what one utterance means given the
 * OperatorAgent's run-state. No Android, no coroutines: unit-testable.
 *
 * Rules:
 *  - nothing running        -> RunTask (the operator handles chat + tasks)
 *  - running + cancel word  -> Cancel
 *  - running + question     -> Chat (task NEVER pauses - BUG #5)
 *  - running + task-like    -> QueueTask (auto-run when the current one ends)
 *  - running + anything else-> Chat
 */
object IntentRouter {

    sealed class Route {
        object Cancel : Route()
        data class Chat(val text: String) : Route()
        data class RunTask(val text: String) : Route()
        data class QueueTask(val text: String) : Route()
    }

    private val QUESTION = Regex(
        "(?i)^\\s*(kya|kyun|kyon|kaise|kaisa|kaisi|kaun|kaunsa|kaunsi|kitna|kab|" +
            "what|why|who|when|where|which|how|explain|tell me|batao|bataiye|" +
            "samjhao|hello|hi|hey|namaste|aur batao)\\b"
    )

    private val TASKY = Regex(
        "(?i)\\b(open|launch|close|send|message|call|play|pause|set|turn|scroll|" +
            "swipe|tap|type|toggle|switch|search|find|increase|decrease|install|" +
            "uninstall|delete|clear|dial|ring|answer|follow|like|unlike|mute|" +
            "unmute|wifi|flash|flashlight|torch|bluetooth|volume|brightness|" +
            "whatsapp|instagram|youtube|facebook|settings|gallery|camera|" +
            "contacts|messages|gmail|maps|gpay|phonepe|kholo|khol|bhejo|bhej|" +
            "chalao|chala|karo|kar do|jaao|jao|dikhao|likho|banao|band karo|" +
            "aage|peeche|upar|neeche|next|back|home|recents|reboot)\\b"
    )

    fun route(text: String, taskRunning: Boolean): Route {
        val clean = text.trim()
        if (!taskRunning) return Route.RunTask(clean)
        // Wake-word prefix: "SQL stop" must cancel like plain "stop".
        val cancelCandidate = if (clean.startsWith("sql ", ignoreCase = true)) {
            clean.substring(4).trim()
        } else {
            clean
        }
        if (SQLAgentEngineV4.matchesCancel(cancelCandidate)) return Route.Cancel
        if (QUESTION.containsMatchIn(clean)) return Route.Chat(clean)
        if (TASKY.containsMatchIn(clean)) return Route.QueueTask(clean)
        return Route.Chat(clean)
    }

    fun looksLikeTask(text: String): Boolean = TASKY.containsMatchIn(text.trim())
}
