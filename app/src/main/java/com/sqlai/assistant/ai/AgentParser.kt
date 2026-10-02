package com.sqlai.assistant.ai

import org.json.JSONObject

/**
 * One iteration of the ReAct agent:
 *   THINK (thought) -> ACT (actions) -> VERIFY (expect) -> next loop.
 */
data class AgentPlan(
    val thought: String,
    val reply: String,
    val done: Boolean,
    val actions: List<Action>,
    val expectType: String,   // text_visible | app_foreground | none
    val expectValue: String
)

object AgentParser {

    fun parse(raw: String): AgentPlan {
        val jsonText = PlanParser.extractJsonObject(raw)
            ?: return AgentPlan(
                thought = raw.take(200),
                reply = "Thinking...",
                done = false,
                actions = emptyList(),
                expectType = "none",
                expectValue = ""
            )

        return try {
            val obj = JSONObject(jsonText)
            val expect = obj.optJSONObject("expect")
            AgentPlan(
                thought = obj.optString("thought").ifBlank { "Analysing screen" },
                reply = obj.optString("reply")
                    .ifBlank { "Working on it" }
                    .replace(Regex("\\s+"), " ")
                    .trim(),
                done = obj.optBoolean("done", false),
                actions = PlanParser.parseActions(obj),
                expectType = expect?.optString("type")?.lowercase()?.ifBlank { "none" } ?: "none",
                expectValue = expect?.optString("value").orEmpty()
            )
        } catch (e: Exception) {
            AgentPlan(
                thought = "Parse error",
                reply = "Working on it",
                done = false,
                actions = emptyList(),
                expectType = "none",
                expectValue = ""
            )
        }
    }
}
