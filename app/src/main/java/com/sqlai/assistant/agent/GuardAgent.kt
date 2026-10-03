package com.sqlai.assistant.agent

import java.util.concurrent.atomic.AtomicInteger

/**
 * v7 M7 GuardAgent - prompt-injection defence (BUG #9). Pure logic: no
 * Android, no coroutines - every rule is unit-testable on the JVM.
 *
 * Threat model: ANY text that reaches the model (live screen dumps, user
 * chat input) may contain instructions written by someone ELSE - a malicious
 * app overlay, a WhatsApp message, a web page. [scan] detects the classic
 * rewrite-your-role attacks; [guard] additionally counts detections for the
 * dashboard. Detected LINES are neutralized in the output - the model still
 * sees the rest of the screen, never the injected command.
 */
object GuardAgent {

    /** Verdict of one scan: match info plus the line-stripped output. */
    data class Verdict(
        val blocked: Boolean,
        val matched: String?,
        val output: String
    )

    /** Lines that try to rewrite OUR instructions (kept lowercase regexes). */
    private val INJECTION = listOf(
        "ignore-instructions" to Regex(
            """(?i)ignore\s+(?:all\s+|any\s+|previous|prior|above|earlier|your)?\s*"""
                + """(?:instructions?|prompts?|rules?|guidelines?)"""
        ),
        "disregard-instructions" to Regex(
            """(?i)disregard\s+(?:all\s+|any\s+|previous|prior|your)?\s*"""
                + """(?:instructions?|prompts?|rules?)"""
        ),
        "forget-everything" to Regex(
            """(?i)forget\s+(?:everything|all\s+previous|your\s+instructions|the\s+system)"""
        ),
        "role-reassign" to Regex(
            """(?i)you\s+are\s+now\s+(?:a|an|the)\s+\w+"""
        ),
        "system-prefix" to Regex(
            """(?i)system\s*[:=-]\s*(?:you|act|role|new)"""
        ),
        "new-instructions" to Regex(
            """(?i)new\s+instructions?\s*[:=-]"""
        ),
        "override-original" to Regex(
            """(?i)do\s+not\s+follow\s+(?:the\s+)?(?:original|system|previous)"""
        ),
        "jailbreak" to Regex(
            """(?i)\b(?:jailbreak|dan\s+mode|developer\s+mode|god\s+mode)\b"""
        ),
        "reveal-prompt" to Regex(
            """(?i)reveal\s+(?:your|the|this)\s+(?:system\s+prompt|instructions|hidden\s+rules)"""
        ),
        "no-rules-pretend" to Regex(
            """(?i)pretend\s+(?:you\s+have\s+|there\s+are\s+)?no\s+(?:rules|restrictions|guidelines)"""
        ),
        "override-safety" to Regex(
            """(?i)bypass\s+(?:your\s+|the\s+)?(?:safety|security|content\s+policies)"""
        )
    )

    /** Detections since process start (shown on the dashboard). */
    val blockedCount = AtomicInteger(0)

    /** True when [text] contains any known injection pattern. */
    fun isInjected(text: String): Boolean = INJECTION.any { it.second.containsMatchIn(text) }

    /**
     * Scan [text]: blocked=true when a pattern matches; output keeps every
     * line EXCEPT the suspicious ones (replaced by a marker), so the model
     * never receives the injected instruction. Does NOT touch [blockedCount]
     * (tests and hot loops stay side-effect free).
     */
    fun scan(text: String): Verdict {
        val hit = INJECTION.firstOrNull { it.second.containsMatchIn(text) }
            ?: return Verdict(blocked = false, matched = null, output = text)
        val output = text.lineSequence()
            .joinToString("\n") { line ->
                if (INJECTION.any { it.second.containsMatchIn(line) }) {
                    "[guard: suspicious line removed (${hit.first})]"
                } else {
                    line
                }
            }
        return Verdict(blocked = true, matched = hit.first, output = output)
    }

    /**
     * Production entry: same as [scan] but counts every blocked verdict for
     * the dashboard. Use this on real model inputs (screen, user chat).
     */
    fun guard(text: String): Verdict {
        val verdict = scan(text)
        if (verdict.blocked) {
            blockedCount.incrementAndGet()
        }
        return verdict
    }

    /** Convenience for live screen dumps (no counting side effect). */
    fun sanitizeScreen(dump: String): String = scan(dump).output
}
