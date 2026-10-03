package com.sqlai.assistant.device

import com.sqlai.assistant.SqlAiApp
import com.sqlai.assistant.ai.Action
import com.sqlai.assistant.core.LogBus
import com.sqlai.assistant.core.LogLevel
import com.sqlai.assistant.service.SqlAccessibilityService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Deterministic, LLM-free device flows (v7 M4).
 *
 * A [Macro] is a fixed list of [Step]s picked either by its trigger phrases
 * (see [match]) or by the exact `flow <name>` form - no model round-trip and
 * no guessing. [run] executes the steps strictly in order: every device
 * mutation goes through [DeviceController.execute] (one UI mutex for the whole
 * app) and `wait_for` polls the accessibility screen dump every 100 ms. A
 * failed step aborts the flow with `false` so the caller can fall back to the
 * LLM loop.
 */
object FlowMacros {

    /** One flow step. x/y are pixels; for "tap_pct" they are percentages 0..100. */
    data class Step(
        val type: String,
        val text: String? = null,
        val x: Int? = null,
        val y: Int? = null,
        val ms: Long? = null,
        val direction: String? = null
    )

    /** A named flow: the trigger phrases that select it plus the steps it runs. */
    data class Macro(val name: String, val triggers: List<String>, val steps: List<Step>)

    /** A macro resolved from task text together with its bound `{param}` values. */
    data class Match(val macro: Macro, val params: Map<String, String>)

    private const val FLOW_TIMEOUT_MS = 60_000L
    private const val POLL_INTERVAL_MS = 100L
    private const val DEFAULT_WAIT_MS = 500L
    private const val DEFAULT_WAIT_FOR_MS = 5_000L
    private const val SWIPE_DISTANCE_PX = 400

    private val OPEN_APP_REGEX = Regex("^open ([a-z0-9][a-z0-9.\\- ]*) app$")
    private val FLOW_APP_OPEN_REGEX = Regex("^flow app_open (.+)$")

    /**
     * Built-in flows: settings_wifi, wa_open_chat, reel_like, app_open (exact names).
     *
     * Steps stay conservative on purpose - a failed step means the caller falls
     * back to the LLM loop instead of a flow drifting into wrong taps.
     */
    fun builtins(): List<Macro> = listOf(
        Macro(
            name = "settings_wifi",
            triggers = listOf(
                "wifi on karo", "wifi chalu karo", "turn on wifi", "wifi on", "wi-fi on"
            ),
            steps = listOf(
                Step(type = "open_settings", text = "wifi"),
                Step(type = "wait", ms = 1500L),
                Step(type = "wait_for", text = "Wi-Fi", ms = 4000L),
                Step(type = "tap_text", text = "Wi-Fi")
            )
        ),
        Macro(
            name = "wa_open_chat",
            triggers = listOf(
                "whatsapp chat kholo", "open whatsapp chat", "whatsapp pe chat kholo"
            ),
            steps = listOf(
                Step(type = "open_app", text = "whatsapp"),
                Step(type = "wait", ms = 1500L),
                Step(type = "wait_for", text = "WhatsApp", ms = 5000L)
            )
        ),
        Macro(
            name = "reel_like",
            triggers = listOf(
                "reel like karo", "like this reel", "insta reel like", "reel ko like karo"
            ),
            steps = listOf(
                Step(type = "open_app", text = "instagram"),
                Step(type = "wait", ms = 2000L),
                Step(type = "wait_for", text = "Reels", ms = 5000L),
                Step(type = "tap_pct", x = 8, y = 78)
            )
        ),
        Macro(
            name = "app_open",
            triggers = emptyList(),
            steps = listOf(Step(type = "open_app", text = "{app}"))
        )
    )

    /**
     * Task text -> matching macro (case-insensitive, word-aware), or null.
     *
     * A leading `sql ` is stripped, then the exact `flow <builtin name>` form,
     * the built-in trigger phrases (substring with word boundaries) and finally
     * the `app_open` regex forms are checked, in that order.
     */
    fun match(task: String): Match? {
        var normalized = task.trim().lowercase()
        if (normalized.startsWith("sql ")) normalized = normalized.substring(4).trim()
        if (normalized.isEmpty()) return null

        val macros = builtins()

        for (macro in macros) {
            if (normalized == "flow ${macro.name}") return Match(macro, emptyMap())
        }

        for (macro in macros) {
            for (trigger in macro.triggers) {
                if (containsWordBoundary(normalized, trigger)) return Match(macro, emptyMap())
            }
        }

        val appOpen = macros.firstOrNull { it.name == "app_open" } ?: return null
        OPEN_APP_REGEX.find(normalized)?.let { found ->
            val app = found.groupValues[1].trim()
            if (app.isNotEmpty()) return Match(appOpen, mapOf("app" to app))
        }
        FLOW_APP_OPEN_REGEX.find(normalized)?.let { found ->
            val app = found.groupValues[1].trim()
            if (app.isNotEmpty()) return Match(appOpen, mapOf("app" to app))
        }
        return null
    }

    /**
     * Parse a JSON array of steps -> List<Step> (null when invalid JSON/not an
     * array/blank type anywhere). Strict: one malformed element rejects the
     * whole document.
     */
    fun parseSteps(json: String): List<Step>? {
        if (json.isBlank()) return null
        return try {
            val arr = JSONArray(json)
            val steps = mutableListOf<Step>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: return null
                val type = obj.optString("type").trim().lowercase()
                if (type.isEmpty()) return null
                steps.add(
                    Step(
                        type = type,
                        text = obj.stepString("text"),
                        x = obj.stepInt("x"),
                        y = obj.stepInt("y"),
                        ms = obj.stepLong("ms"),
                        direction = obj.stepString("direction")
                    )
                )
            }
            steps
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Replace "{name}" placeholders in a step's text using [params]
     * (missing params leave the placeholder as-is).
     */
    fun substitute(step: Step, params: Map<String, String>): Step {
        val template = step.text ?: return step
        if (params.isEmpty()) return step
        var result = template
        for ((name, value) in params) {
            result = result.replace("{$name}", value)
        }
        return step.copy(text = result)
    }

    /**
     * Execute the flow step-by-step; false = abort early (failed wait_for /
     * exception / cancelled). The whole run is capped at 60 s.
     *
     * @param announce receives the per-step progress lines and the final status.
     */
    suspend fun run(match: Match, announce: (String) -> Unit = {}): Boolean {
        val macro = match.macro
        val steps = macro.steps
        LogBus.log("Flow ${macro.name}: running ${steps.size} step(s)")
        val result = withTimeoutOrNull(FLOW_TIMEOUT_MS) {
            for (index in steps.indices) {
                val raw = steps[index]
                announce("Flow step ${index + 1}/${steps.size}: ${raw.type}")
                val step = substitute(raw, match.params)
                if (!executeStep(step, announce)) return@withTimeoutOrNull false
            }
            announce("Flow complete")
            true
        }
        if (result == null) {
            LogBus.log("Flow ${macro.name} timed out after ${FLOW_TIMEOUT_MS}ms", LogLevel.WARN)
            announce("Flow timed out")
            return false
        }
        if (result) LogBus.log("Flow ${macro.name} complete", LogLevel.SUCCESS)
        return result
    }

    /** Run one substituted step; cancellations always propagate. */
    private suspend fun executeStep(step: Step, announce: (String) -> Unit): Boolean {
        return try {
            when (step.type) {

                "wait" -> delay(step.ms ?: DEFAULT_WAIT_MS)

                "wait_for" -> {
                    val target = step.text.orEmpty()
                    val found = waitForText(target, step.ms ?: DEFAULT_WAIT_FOR_MS)
                    if (!found) {
                        announce("Flow timeout waiting for \"$target\"")
                        LogBus.log("Flow wait_for timeout: \"$target\"", LogLevel.WARN)
                        return false
                    }
                }

                else -> {
                    val action = toAction(step)
                    if (action == null) {
                        announce("Flow step failed: unsupported step \"${step.type}\"")
                        LogBus.log("Flow unsupported step: ${step.type}", LogLevel.WARN)
                        return false
                    }
                    DeviceController.execute(listOf(action))
                }
            }
            true
        } catch (e: CancellationException) {
            // Agent stop / overall timeout - never swallow the cancel.
            throw e
        } catch (e: Exception) {
            announce("Flow step failed: ${e.message ?: e.javaClass.simpleName}")
            LogBus.log("Flow step ${step.type} failed: ${e.message}", LogLevel.ERROR)
            false
        }
    }

    /** Poll the same accessibility dump DeviceController reads until text or timeout. */
    private suspend fun waitForText(text: String, timeoutMs: Long): Boolean {
        if (text.isBlank()) return false
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val dump = SqlAccessibilityService.instance?.captureScreenText(80).orEmpty()
            if (dump.contains(text, ignoreCase = true)) return true
            delay(POLL_INTERVAL_MS)
        }
        return false
    }

    /** Map a non wait/wait_for step onto a single device action (null = invalid). */
    private fun toAction(step: Step): Action? {
        return when (step.type) {

            "open_app" -> Action(type = "open_app", app = step.text)

            "open_settings" -> Action(type = "open_settings", item = step.text)

            "tap" -> Action(type = "tap", x = step.x, y = step.y)

            "tap_pct" -> {
                val pctX = step.x
                val pctY = step.y
                if (pctX == null || pctY == null) return null
                val metrics = SqlAiApp.instance.resources.displayMetrics
                Action(
                    type = "tap",
                    x = pctX * metrics.widthPixels / 100,
                    y = pctY * metrics.heightPixels / 100
                )
            }

            "tap_text" -> Action(type = "tap_text", text = step.text)

            "swipe" -> Action(
                type = "swipe",
                x = step.x,
                y = step.y,
                x2 = step.x,
                y2 = step.y?.minus(SWIPE_DISTANCE_PX)
            )

            "back" -> Action(type = "press_key", key = "back")

            "home" -> Action(type = "press_key", key = "home")

            else -> null
        }
    }

    /** True when [needle] occurs in [haystack] on both-side word boundaries. */
    private fun containsWordBoundary(haystack: String, needle: String): Boolean {
        if (needle.isEmpty()) return false
        var start = haystack.indexOf(needle)
        while (start >= 0) {
            val end = start + needle.length
            val beforeOk = start == 0 || !haystack[start - 1].isLetterOrDigit()
            val afterOk = end == haystack.length || !haystack[end].isLetterOrDigit()
            if (beforeOk && afterOk) return true
            start = haystack.indexOf(needle, start + 1)
        }
        return false
    }

    private fun JSONObject.stepString(key: String): String? =
        if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotBlank() } else null

    private fun JSONObject.stepInt(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    private fun JSONObject.stepLong(key: String): Long? =
        if (has(key) && !isNull(key)) optLong(key) else null
}
