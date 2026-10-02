package com.sqlai.assistant.service

import com.sqlai.assistant.core.LogBus
import com.sqlai.assistant.core.LogLevel
import kotlinx.coroutines.delay

/**
 * FEATURE 1 - self-healing engine: auto-dismiss the dialogs that block
 * automation on real devices.
 *
 * Recognised blockers:
 *  - Runtime permission prompts (tap the POSITIVE option only)
 *  - Battery-optimisation / "restrict background" prompts
 *  - System "App isn't responding" / "keeps stopping" dialogs
 *  - First-run / update / tutorial bottom sheets with OK / Got it / Close
 *  - Stuck soft keyboards (BACK only when the screen is JUST a keyboard -
 *    never fired blindly, the engine's screen analysis handles that)
 *
 * Never taps Deny / Cancel / Don't allow / "Open app settings" style
 * negatives - those are skipped so permissions are not silently lost.
 */
object BlockerSweeper {

    private const val TAG = "BlockerSweeper"

    private val DIALOG_MARKERS = listOf(
        "allow ", "permission", "isn't responding", "keeps stopping",
        "has stopped", "not responding", "battery optimization",
        "background restriction", "restricted", "system UI", "powered by"
    )

    private val POSITIVE_BUTTONS = listOf(
        "While using the app",
        "Only this time",
        "Allow only while using the app",
        "Allow during use",
        "Don't optimize",
        "Keep optimizing",
        "Allow",
        "ALLOW",
        "OK",
        "Ok",
        "Got it",
        "GOT IT",
        "Accept",
        "Agree",
        "Continue",
        "Close app",
        "Wait",
        "Open again",
        "Dismiss"
    )

    private val NEGATIVE_BUTTONS = listOf(
        "Deny", "Don't allow", "Don't allow", "Cancel", "No thanks",
        "Not now", "Block", "Disable", "Force stop"
    )

    /**
     * Look at the current screen; if a recognised blocker dialog is present
     * tap its positive button. Pass the already-captured [screen] to avoid a
     * second accessibility traversal. Returns true when something was dismissed.
     */
    suspend fun sweep(accessibility: SqlAccessibilityService, screen: String? = null): Boolean {
        return try {
            val dump = screen ?: accessibility.captureScreenDetailed(50)
            if (dump.isBlank()) return false

            val lower = dump.lowercase()
            val looksLikeDialog = DIALOG_MARKERS.any { lower.contains(it) } ||
                // A screen with very few elements is usually a dialog overlay.
                (dump.trim().lines().size in 1..6 && lower.contains("app"))
            if (!looksLikeDialog) return false

            // NEGATIVE guard: if the only buttons are negatives, leave it -
            // the agent prompt tells the model what to do instead.
            for (label in POSITIVE_BUTTONS) {
                if (!lower.contains(label.lowercase())) continue
                if (NEGATIVE_BUTTONS.any { it.equals(label, true) }) continue
                if (accessibility.clickText(label)) {
                    LogBus.log("[HEAL] dismissed blocker via \"$label\"", LogLevel.SUCCESS)
                    delay(400)
                    return true
                }
            }
            false
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            LogBus.log("[HEAL] sweep error: ${e.message}", LogLevel.WARN)
            false
        }
    }

    /**
     * Watchdog variant used when a step stalled: sweep first, then press
     * BACK once if the screen still looks stuck (keyboard / dead-end).
     * Returns true if the screen was changed.
     */
    suspend fun recover(accessibility: SqlAccessibilityService): Boolean {
        if (sweep(accessibility)) return true
        val screen = try {
            accessibility.captureScreenText(30)
        } catch (e: Exception) {
            return false
        }
        // ANR of any app -> close it so OUR app never needs a force-stop.
        val lower = screen.lowercase()
        if (lower.contains("isn't responding") || lower.contains("not responding")) {
            if (accessibility.clickText("Close app") || accessibility.clickText("Wait")) {
                LogBus.log("[HEAL] closed ANR dialog", LogLevel.SUCCESS)
                return true
            }
        }
        return false
    }
}
