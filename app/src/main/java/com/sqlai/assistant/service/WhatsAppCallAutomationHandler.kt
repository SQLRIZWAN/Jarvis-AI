package com.sqlai.assistant.service

import com.sqlai.assistant.SqlAiApp
import com.sqlai.assistant.ai.GeminiLiveAudioEngine
import com.sqlai.assistant.core.AudioManagerController
import com.sqlai.assistant.core.LogBus
import com.sqlai.assistant.core.LogLevel
import com.sqlai.assistant.device.DeviceController
import com.sqlai.assistant.engine.Speaker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * End-to-end WhatsApp CALL automation over vision + accessibility:
 *
 *   open WhatsApp -> focus search -> type contact -> open chat ->
 *   tap Voice call -> wait for connection (vision: in-call controls / timer)
 *   -> speak the spoken message over the call audio -> live duplex talk-back.
 *
 * Every stage:
 *  - announces progress orally ("Opening WhatsApp now...", "Searching for
 *    Mohan...", "Placing the call...") through the non-blocking speech queue,
 *  - retries via FRESH screen parsing with alternative labels / content
 *    descriptions (never crashes, never freezes the voice thread),
 *  - overall hard timeout so a missing button degrades to a spoken error
 *    instead of an app hang.
 */
object WhatsAppCallAutomationHandler {

    private const val TAG = "WaCallAuto"
    private const val STAGE_TIMEOUT_MS = 45_000L
    private const val CONNECT_TIMEOUT_MS = 18_000L

    /** Progress callback (usually SQLAgentEngineV4 announce -> speech queue). */
    var onProgress: ((String) -> Unit)? = null

    private fun announce(text: String) {
        LogBus.log("[WA-CALL] $text", LogLevel.INFO)
        Speaker.post(text)
        try {
            onProgress?.invoke(text)
        } catch (e: Exception) {
            // Listener errors must never break the workflow.
        }
    }

    /**
     * Full workflow: place a WhatsApp voice call to [contact] and (optionally)
     * deliver [spokenMessage] live over the connected call.
     *
     * @return true when the call was placed (and message delivered, if given).
     */
    suspend fun placeCall(contact: String, spokenMessage: String? = null): Boolean {
        if (contact.isBlank()) {
            announce("Which contact should I call?")
            return false
        }
        val accessibility = SqlAccessibilityService.instance
        if (accessibility == null) {
            announce("I need the accessibility service to do this.")
            return false
        }
        val started = System.currentTimeMillis()
        return try {
            // ---- stage 1: open WhatsApp ---------------------------------
            announce("Opening WhatsApp now")
            val pkg = DeviceController.resolvePackage("whatsapp") ?: run {
                announce("WhatsApp is not installed")
                return false
            }
            if (!DeviceController.launchPackage(pkg)) {
                announce("Could not open WhatsApp")
                return false
            }
            delay(1800)

            // ---- stage 2: search the contact ----------------------------
            announce("Searching for $contact")
            if (!focusSearch(accessibility)) {
                // Already on a chat list without a visible search bar - try
                // direct contact lookup via keyboard.
                LogBus.log("[WA-CALL] Search bar not found - trying direct open", LogLevel.WARN)
            }
            accessibility.typeText(contact)
            delay(1300)

            // ---- stage 3: open the contact row --------------------------
            val picked = pickContact(accessibility, contact)
            if (!picked) {
                if (System.currentTimeMillis() - started > STAGE_TIMEOUT_MS) {
                    announce("Contact $contact not found")
                    return false
                }
                // Vision retry: clear + retype with the first name only.
                accessibility.pressEnter()
                delay(600)
                accessibility.typeText(contact.split(" ").first())
                delay(1300)
                if (!pickContact(accessibility, contact)) {
                    announce("I could not find $contact on screen")
                    return false
                }
            }
            delay(1100)

            // ---- stage 4: tap the voice-call button ---------------------
            announce("Placing the call")
            if (!tapVoiceCall(accessibility)) {
                // Retry once with a fresh screen parse + alt labels.
                delay(900)
                if (!tapVoiceCall(accessibility)) {
                    announce("Voice call button not found")
                    return false
                }
            }

            // ---- stage 5: wait for connection (vision) ------------------
            val connected = awaitConnected(accessibility)
            if (!connected) {
                announce("The call does not seem to be connecting")
                // Keep going - some devices simply don't show the indicators.
            } else {
                announce("Call connected")
            }

            // ---- stage 6: talk live over the call -----------------------
            if (!spokenMessage.isNullOrBlank()) {
                deliverLive(accessibility, spokenMessage)
            }
            true
        } catch (e: Exception) {
            // Everything is caught: the voice thread stays alive regardless.
            LogBus.log("WA call failed: ${e.message}", LogLevel.ERROR)
            announce("Sorry, the WhatsApp call failed")
            false
        }
    }

    // --------------------------------------------------------------- stages

    private suspend fun focusSearch(accessibility: SqlAccessibilityService): Boolean {
        val labels = listOf("Search", "Search contacts", "खोजें", "संपर्क खोजें")
        for (attempt in 0 until 2) {
            for (label in labels) {
                if (accessibility.clickText(label)) {
                    delay(500)
                    return true
                }
            }
            // Fresh screen parse between attempts (UI may still be animating).
            delay(800)
            val screen = accessibility.captureScreenText(60)
            if (screen.contains("search", ignoreCase = true)) {
                // Node exists but exact label missed - try the lowercased form.
                if (accessibility.clickText("search")) {
                    delay(500)
                    return true
                }
            }
            if (attempt == 0) delay(600)
        }
        return false
    }

    /** Poll [captureScreenText] until [label] appears or [timeoutMs] passes. */
    private suspend fun waitForText(
        accessibility: SqlAccessibilityService,
        label: String,
        timeoutMs: Long
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (accessibility.captureScreenText(50).contains(label, ignoreCase = true)) return true
            delay(400)
        }
        return false
    }

    private suspend fun pickContact(accessibility: SqlAccessibilityService, contact: String): Boolean {
        val candidates = listOf(
            contact,
            contact.trim(),
            contact.split(" ").first(),
            contact.split(" ").lastOrNull().orEmpty()
        ).distinct().filter { it.isNotBlank() }
        for (label in candidates) {
            if (waitForText(accessibility, label, 2500) && accessibility.clickText(label)) {
                return true
            }
        }
        return false
    }

    private suspend fun tapVoiceCall(accessibility: SqlAccessibilityService): Boolean {
        // clickText matches BOTH on-screen text and contentDescription, so the
        // phone icon's "Voice call" label is covered.
        val labels = listOf(
            "Voice call",
            "Audio call",
            "Voice Call",
            "Call",
            "वॉइस कॉल",
            "कॉल"
        )
        for (label in labels) {
            if (accessibility.clickText(label)) {
                delay(1200)
                return true
            }
        }
        return false
    }

    /** Vision poll: in-call controls, timer or disappearance of "Calling". */
    private suspend fun awaitConnected(accessibility: SqlAccessibilityService): Boolean {
        val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val screen = accessibility.captureScreenDetailed(60).lowercase()
            val inCall = screen.contains("speaker") ||
                screen.contains("mute") ||
                screen.contains("end call") ||
                Regex("\\b\\d{1,2}:\\d{2}\\b").containsMatchIn(screen)
            val stillCalling = screen.contains("calling") ||
                screen.contains("ringing") ||
                screen.contains("placing call")
            if (inCall && !stillCalling) return true
            delay(900)
        }
        return false
    }

    /**
     * Speak the message over the call audio. Prefers Gemini Live duplex (the
     * caller can talk back and the assistant answers live); falls back to
     * TTS routed through the call stream.
     */
    private suspend fun deliverLive(accessibility: SqlAccessibilityService, message: String) {
        val context = SqlAiApp.instance
        val liveSettings = try {
            SqlAiApp.settings.settings.first()
        } catch (e: Exception) {
            com.sqlai.assistant.core.AppSettings()
        }

        announce("Telling them now")
        if (GeminiLiveAudioEngine.isUsable(liveSettings)) {
            GeminiLiveAudioEngine.enterCallMode(context)
            GeminiLiveAudioEngine.speakText(liveSettings, message)
            // Stay duplex for a while so the recipient can answer back.
            val until = System.currentTimeMillis() + 45_000
            while (System.currentTimeMillis() < until) {
                val gone = !accessibility.captureScreenText(40)
                    .contains(Regex(".*(calling|ringing|\\d:\\d{2}).*"))
                if (gone) break
                delay(1500)
            }
            GeminiLiveAudioEngine.exitCallMode(context)
        } else {
            val app = SqlAiApp.instance
            Speaker.enterCallMode(app, liveSettings)
            Speaker.speakOnCall(message, liveSettings)
            Speaker.exitCallMode()
        }
    }

    /** True while the background mic capture of this handler is active. */
    fun isLiveTalking(): Boolean = GeminiLiveAudioEngine.state.value ==
        com.sqlai.assistant.ai.GeminiLiveAudioEngine.LiveState.LISTENING ||
        AudioManagerController.isCallMode()
}
