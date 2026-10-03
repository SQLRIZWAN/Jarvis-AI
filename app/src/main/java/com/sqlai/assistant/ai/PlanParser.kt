package com.sqlai.assistant.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * One concrete device operation produced by the LLM.
 * Only the fields relevant to [type] are populated.
 */
data class Action(
    val type: String,
    val app: String? = null,
    val text: String? = null,
    val x: Int? = null,
    val y: Int? = null,
    val x2: Int? = null,
    val y2: Int? = null,
    val durationMs: Int? = null,
    val direction: String? = null,
    val value: Int? = null,
    val key: String? = null,
    val on: Boolean? = null,
    val item: String? = null,
    val ms: Int? = null,
    val message: String? = null,
    /** v7 M4: stable screen element id ("r0".."rN") from the LIVE SCREEN dump. */
    val ref: String? = null
)

data class Plan(
    val reply: String,
    val actions: List<Action>
)

object PlanParser {

    fun parse(raw: String): Plan {
        val jsonText = extractJsonObject(raw)
            ?: return Plan(reply = raw.take(120).ifBlank { "Done." }, actions = emptyList())

        return try {
            val obj = JSONObject(jsonText)
            val reply = obj.optString("reply")
                .ifBlank { "Done." }
                .replace(Regex("\\s+"), " ")
                .trim()

            Plan(reply = reply, actions = parseActions(obj))
        } catch (e: Exception) {
            Plan(reply = "Done.", actions = emptyList())
        }
    }

    internal fun parseActions(obj: JSONObject): List<Action> {
        val actions = mutableListOf<Action>()
        val arr: JSONArray = obj.optJSONArray("actions") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val a = arr.optJSONObject(i) ?: continue
            val type = a.optString("type").lowercase().trim()
            if (type.isEmpty()) continue
            actions.add(
                Action(
                    type = type,
                    app = a.optStringOrNull("app") ?: a.optStringOrNull("package"),
                    text = a.optStringOrNull("text") ?: a.optStringOrNull("message"),
                    // G1: prompt documents swipe {x1,y1,x2,y2} but the model's
                    // x1/y1 were dropped -> swipe silently did nothing.
                    x = a.optIntOrNull("x") ?: a.optIntOrNull("x1"),
                    y = a.optIntOrNull("y") ?: a.optIntOrNull("y1"),
                    x2 = a.optIntOrNull("x2"),
                    y2 = a.optIntOrNull("y2"),
                    durationMs = a.optIntOrNull("duration_ms") ?: a.optIntOrNull("duration"),
                    direction = a.optStringOrNull("direction"),
                    value = a.optIntOrNull("value"),
                    key = a.optStringOrNull("key"),
                    on = a.optBooleanOrNull("on"),
                    item = a.optStringOrNull("item"),
                    ms = a.optIntOrNull("ms"),
                    message = a.optStringOrNull("message"),
                    ref = a.optStringOrNull("ref")
                )
            )
        }
        return actions
    }

    /** Pull the first balanced {...} block, tolerating markdown fences and chatter. */
    internal fun extractJsonObject(raw: String): String? {
        val cleaned = raw.replace("```json", "").replace("```", "")
        val start = cleaned.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until cleaned.length) {
            val c = cleaned[i]
            if (escape) {
                escape = false
                continue
            }
            when (c) {
                '\\' -> escape = true
                '"' -> inString = !inString
                '{' -> if (!inString) depth++
                '}' -> {
                    if (!inString) {
                        depth--
                        if (depth == 0) return cleaned.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }

    internal fun JSONObject.optStringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null

    internal fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    internal fun JSONObject.optBooleanOrNull(key: String): Boolean? =
        if (has(key) && !isNull(key)) optBoolean(key) else null
}
