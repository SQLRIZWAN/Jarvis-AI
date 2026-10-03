package com.sqlai.assistant.agent

import com.sqlai.assistant.ai.Action
import com.sqlai.assistant.ai.AgentPlan
import java.security.MessageDigest

/**
 * v7 M4 Critic - the pure-logic checker the OperatorAgent runs before it
 * dispatches a plan and after every action. No Android, no coroutines: every
 * rule is unit-testable.
 *
 * Rules:
 *  - risky types (send/delete/pay/call ...) need spoken confirmation first
 *  - a plan is rejected early: no live screen, blank/unknown/malformed action
 *  - screen fingerprints ignore dump ORDER, so re-scans compare cleanly
 *  - a micro-goal is auto-marked only when its label is really on screen
 */
object CriticAgent {

    /** Action types that require a spoken user confirmation before execution (v7 risky set). */
    private val RISKY = setOf(
        "send_message", "wa_send", "delete", "pay", "transfer",
        "uninstall", "settings_reset", "call", "end_call", "wa_call"
    )

    /** Action types the prompt documents and the DeviceController can execute. */
    private val KNOWN = setOf(
        "open_app", "close_app", "tap", "tap_text", "tap_ref", "swipe", "scroll",
        "type_text", "press_key", "set_volume", "volume_up", "volume_down",
        "set_brightness", "toggle_flashlight", "toggle_wifi", "toggle_bluetooth",
        "open_settings", "read_screen", "read_notifications", "wait", "wait_for",
        "wa_call", "voice_note", "speak", "call", "end_call", "answer_call"
    )

    /** Bounds group of one element line: `[left,top WxH]`. */
    private val BOUNDS = Regex("""\[(-?\d+),(-?\d+)\s+(\d+)x(\d+)]""")

    /** Pre-execution verdict for one plan: all problems found plus the risky subset. */
    data class PreCheck(
        val problems: List<String>,
        val confirmNeeded: List<Action>
    )

    /** True when [action] is in the v7 risky set and must be confirmed out loud first. */
    fun needsConfirm(action: Action): Boolean = action.type in RISKY

    /** True when [action] can be dispatched as-is (no validation problems). */
    fun isValid(action: Action): Boolean = problemFor(action) == null

    /**
     * Stable fingerprint of a `captureScreenDetailed()` dump.
     *
     * Every element line contributes one `label|l,t,WxH` token (the label is
     * the text in front of the bounds bracket; the `FOREGROUND_APP=` header has
     * no bracket and is skipped), the tokens are SORTED, joined with `\n` and
     * hashed with SHA-256 as full lowercase hex. Order-independent by design:
     * the same elements dumped in a different order hash identically, while any
     * changed label or coordinate changes the hash.
     */
    fun screenHash(dump: String): String {
        val tokens = dump.lines()
            .mapNotNull { line ->
                val bounds = BOUNDS.find(line) ?: return@mapNotNull null
                val label = line.substring(0, bounds.range.first).trim()
                val g = bounds.groupValues
                "$label|${g[1]},${g[2]},${g[3]}x${g[4]}"
            }
            .sorted()
            .joinToString("\n")

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(tokens.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Pre-execution validation of one plan against the current screen dump.
     *
     * Appends EVERY applicable problem in a fixed order: plan consistency, then
     * screen availability, then one entry per action in list order. Actions
     * that need a spoken confirmation never become a problem - they are only
     * reported through [PreCheck.confirmNeeded].
     */
    fun preValidate(plan: AgentPlan, screen: String): PreCheck {
        val problems = mutableListOf<String>()
        if (plan.done && plan.actions.isNotEmpty()) {
            problems += "done with pending actions"
        }
        if (screen.isBlank() || screen.startsWith("Screen unavailable")) {
            problems += "no live screen"
        }
        for (action in plan.actions) {
            problemFor(action)?.let { problems += it }
        }
        return PreCheck(problems, plan.actions.filter(::needsConfirm))
    }

    /**
     * Deterministic subgoal auto-mark: the FIRST label of [remaining] that is
     * at least 4 characters long AND appears case-insensitively in
     * [screenAfter]; null when nothing matches. First match keeps repeated
     * verifications stable instead of bouncing between candidate labels.
     */
    fun findCompletedSubgoal(remaining: List<String>, screenAfter: String): String? =
        remaining.firstOrNull { it.length >= 4 && screenAfter.contains(it, ignoreCase = true) }

    /** Single problem for [action], or null when the action is dispatchable. */
    private fun problemFor(action: Action): String? {
        val type = action.type
        if (type.isBlank()) return "blank action type"
        if (type !in KNOWN) return "unknown action type $type"
        return when (type) {
            "tap" ->
                if (refValue(action) == null && (action.x == null || action.y == null)) {
                    "tap without ref or coordinates"
                } else {
                    null
                }

            "tap_text" ->
                if (action.text.isNullOrBlank()) "tap_text without text" else null

            "swipe" ->
                if (action.x == null || action.y == null ||
                    action.x2 == null || action.y2 == null
                ) {
                    "swipe without coordinates"
                } else {
                    null
                }

            "scroll" ->
                if (action.direction != "up" && action.direction != "down") {
                    "bad scroll direction"
                } else {
                    null
                }

            else -> null
        }
    }

    /**
     * `Action.ref` element reference, or null when [Action] does not carry that
     * field. Resolved reflectively so this file compiles and behaves the same
     * whether or not the optional field is present - an absent field is exactly
     * `ref == null`, which is what the tap pre-check requires.
     */
    private fun refValue(action: Action): Any? = try {
        val cls = Action::class.java
        val getter = cls.methods.firstOrNull { it.name == "getRef" && it.parameterCount == 0 }
        if (getter != null) {
            getter.invoke(action)
        } else {
            cls.fields.firstOrNull { it.name == "ref" }?.get(action)
        }
    } catch (e: Exception) {
        null
    }
}
