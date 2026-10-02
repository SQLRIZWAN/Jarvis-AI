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
        // G2 F7: NEVER drop progress during a call anymore - the Speaker
        // consumer routes every line onto the call stream while active.
        Speaker.post(text)
        try {
            onProgress?.invoke(text)
        } catch (e: Exception) {
            // Listener errors must never break the workflow.
        }
    }

    /** End the call session cleanly (flushes queued speech via ENDED). */
    private fun endSession() {
        // Best-effort audio cleanup so a cancelled/failed workflow can never
        // leave the mic/audio-focus owned or TTS stuck on the call stream.
        try {
            GeminiLiveAudioEngine.exitCallMode(SqlAiApp.instance)
        } catch (e: Exception) {
            // ignore
        }
        try {
            Speaker.exitCallMode()
        } catch (e: Exception) {
            // ignore
        }
        if (CallStateMachine.current() != CallStateMachine.State.IDLE) {
            CallStateMachine.to(CallStateMachine.State.ENDED)
            CallStateMachine.reset()
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
            CallStateMachine.reset()

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
            com.sqlai.assistant.core.ScreenshotLog.captureAndSave("WhatsApp_Opened")

            // ---- stage 2: search the contact ----------------------------
            announce("Searching for $contact")
            if (!focusSearch(accessibility)) {
                // Already on a chat list without a visible search bar - try
                // direct contact lookup via keyboard.
                LogBus.log("[WA-CALL] Search bar not found - trying direct open", LogLevel.WARN)
            }
            accessibility.typeText(contact)
            delay(1300)
            com.sqlai.assistant.core.ScreenshotLog.captureAndSave("Contact_Typed_$contact")

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
            CallStateMachine.to(CallStateMachine.State.DIALING)
            com.sqlai.assistant.core.ScreenshotLog.captureAndSave("Call_Dialing")

            // ---- stage 5: wait for connection (vision + state machine) --
            // BUG #1: NEVER deliver the message unless CONNECTED is proven.
            val connected = awaitConnected(accessibility)
            if (!connected) {
                endSession()
                announce("The call did not connect")
                return false
            }
            com.sqlai.assistant.core.ScreenshotLog.captureAndSave("Call_Connected")

            // ---- stage 6: talk live over the call -----------------------
            if (!spokenMessage.isNullOrBlank()) {
                val delivered = deliverLive(accessibility, spokenMessage)
                if (!delivered) {
                    // endSession already ran inside deliverLive - this reaches
                    // the normal speaker so the user KNOWS nothing was sent.
                    announce("Call audio route failed - message not delivered")
                    return false
                }
            } else {
                // G2 F1: blank message used to end the session SILENTLY right
                // after connect (no speech, no mic). Now: keep duplex up so
                // the assistant can listen/reply, tell the local user, and
                // hold until the call ends.
                val st = try {
                    SqlAiApp.settings.settings.first()
                } catch (e: Exception) {
                    com.sqlai.assistant.core.AppSettings()
                }
                AudioManagerController.enterCallAudioMode(SqlAiApp.instance, speakerphone = true)
                Speaker.enterCallMode(SqlAiApp.instance, st)
                if (GeminiLiveAudioEngine.isUsable(st)) {
                    GeminiLiveAudioEngine.enterCallMode(SqlAiApp.instance)
                }
                announce("Call is connected. Aap bol sakte hain.")
                holdUntilCallEnds(accessibility, maxMs = 90_000)
            }
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            // BUG #3: propagate cancellation - never swallow it (the engine
            // step watchdog must stay in control, no zombie call workflow).
            endSession()
            // G2 F8: tell the user instead of going dead silent.
            try {
                Speaker.postPriority("Call step timed out - stopping here")
            } catch (ignored: Throwable) {
            }
            throw e
        } catch (e: Exception) {
            // Everything is caught: the voice thread stays alive regardless.
            LogBus.log("WA call failed: ${e.message}", LogLevel.ERROR)
            endSession()
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

    /**
     * FEATURE #4 - score every clickable row against the target contact and
     * tap the best one. The choice + score + reason is logged so on-device
     * runs show exactly why a row was selected.
     */
    private suspend fun pickContact(accessibility: SqlAccessibilityService, contact: String): Boolean {
        // Give the filtered list a moment to render.
        waitForText(accessibility, contact.split(" ").first(), 2500)

        val rows = accessibility.collectClickableTexts(40).ifEmpty {
            // Fallback candidates: individual lines of the screen dump.
            accessibility.captureScreenDetailed(40).lines()
                .map { it.trim() }.filter { it.length in 2..60 }
        }
        if (rows.isEmpty()) return false

        val best = com.sqlai.assistant.device.ContactMatcher.pick(contact, rows, minScore = 65)
        if (best == null) {
            LogBus.log(
                "[WA-CALL] no candidate >= 65 for '$contact' " +
                    "(${rows.size} rows on screen)", LogLevel.WARN
            )
            return false
        }
        LogBus.log(
            "[WA-CALL] selected '${best.label}' score=${best.score} (${best.reason})",
            LogLevel.SUCCESS
        )
        return accessibility.clickText(best.label)
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

    /**
     * FEATURE #3 - vision poll that drives the CallStateMachine:
     *   DIALING -> RINGING ("calling"/"ringing") -> CONNECTED (timer +
     *   in-call controls, and NOT still calling/ringing).
     * Requires the CONNECTED state plus a 2s settle delay before returning
     * true - the message may ONLY be spoken on a proven live call.
     */
    private suspend fun awaitConnected(accessibility: SqlAccessibilityService): Boolean {
        val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val screen = accessibility.captureScreenDetailed(60).lowercase()
            val inCall = screen.contains("speaker") ||
                screen.contains("mute") ||
                screen.contains("end call") ||
                screen.contains("message") ||
                screen.contains("record") ||
                screen.contains("video call") ||
                Regex("\\b\\d{1,2}:\\d{2}\\b").containsMatchIn(screen)
            val stillCalling = screen.contains("calling") ||
                screen.contains("ringing") ||
                screen.contains("placing call")
            val ongoing = screen.contains("ongoing") || screen.contains("in call")

            when {
                (inCall || ongoing) && !stillCalling -> {
                    CallStateMachine.to(CallStateMachine.State.CONNECTED)
                    // Requirement: minimum 2s delay after connection, then
                    // re-verify the call is still alive before ANY speech.
                    delay(2000)
                    if (CallStateMachine.current() != CallStateMachine.State.CONNECTED) return false
                    val recheck = accessibility.captureScreenText(40).lowercase()
                    val dead = recheck.contains("call ended") ||
                        recheck.contains("call rejected") ||
                        recheck.contains("no answer")
                    if (dead) return false
                    LogBus.log("[WA-CALL] CONNECTED confirmed", LogLevel.SUCCESS)
                    return true
                }
                stillCalling -> CallStateMachine.to(CallStateMachine.State.RINGING)
            }
            delay(900)
        }
        return false
    }

    /**
     * BUG #2 / FEATURE #3 - speak [message] on the connected call with
     * VERIFIED routing. Three attempts, each logged with route diagnostics:
     *   1. speakerphone-ON + TTS via the serialized call queue (works with
     *      ANY provider; suspends until onDone = playback proof),
     *   2. Gemini native voice (queue gated so tracks never overlap),
     *   3. forced audio-mode re-switch + TTS retry.
     * Speakerphone is ON from attempt 1 - the far end only hears ACOUSTIC
     * playback (loud speaker -> WhatsApp mic -> uplink), never the earpiece.
     * Returns true ONLY when a playback completed - never claims a silent
     * delivery. After a success it stays duplex while the call lives and
     * logs explicitly when the live mic cannot start ("duplex unavailable").
     */
    private suspend fun deliverLive(
        accessibility: SqlAccessibilityService,
        message: String
    ): Boolean {
        val context = SqlAiApp.instance
        val liveSettings = try {
            SqlAiApp.settings.settings.first()
        } catch (e: Exception) {
            com.sqlai.assistant.core.AppSettings()
        }

        if (CallStateMachine.current() != CallStateMachine.State.CONNECTED) {
            LogBus.log("[WA-CALL] deliver skipped - not CONNECTED", LogLevel.WARN)
            endSession()
            return false
        }

        LogBus.log(
            "[WA-CALL] delivering on-call route=${AudioManagerController.verifyCallRoute(context)}: " +
                "\"${message.take(60)}\"",
            LogLevel.INFO
        )
        var delivered = false
        try {
            CallStateMachine.to(CallStateMachine.State.SPEAKING)

            // G2 F10: speakerphone ON from the very first attempt - the ONLY
            // way the far end physically hears us is acoustically (loud
            // playback -> WhatsApp's mic -> uplink). Old code kept the
            // earpiece, so "DELIVERED" meant local-only silence.
            AudioManagerController.enterCallAudioMode(context, speakerphone = true)
            Speaker.enterCallMode(context, liveSettings)
            LogBus.log(
                "[WA-CALL] route after speakerphone ON: " +
                    AudioManagerController.verifyCallRoute(context),
                LogLevel.INFO
            )

            // ---- attempt 1: TTS through the serialized call queue ---------
            // Works with ANY provider; awaitCallSpeech suspends until onDone
            // (real playback proof) and cannot collide with other speech.
            LogBus.log("[WA-CALL] attempt 1: TTS on call stream", LogLevel.INFO)
            delivered = Speaker.awaitCallSpeech(message, timeoutMs = 25_000)
            if (!delivered) {
                LogBus.log("[WA-CALL] attempt 1 returned EMPTY", LogLevel.WARN)
            }

            // ---- attempt 2: Gemini native voice (queue gated) -------------
            if (!delivered && GeminiLiveAudioEngine.isUsable(liveSettings)) {
                LogBus.log("[WA-CALL] attempt 2: gemini native voice", LogLevel.WARN)
                Speaker.setQueueGate(false)
                try {
                    GeminiLiveAudioEngine.enterCallMode(context)
                    delivered = GeminiLiveAudioEngine.speakText(liveSettings, message)
                } finally {
                    Speaker.setQueueGate(true)
                }
                if (!delivered) {
                    LogBus.log("[WA-CALL] attempt 2 returned EMPTY", LogLevel.WARN)
                }
            }

            // ---- attempt 3: forced mode re-switch + TTS retry --------------
            if (!delivered) {
                LogBus.log("[WA-CALL] attempt 3: forced mode switch + TTS retry", LogLevel.WARN)
                try {
                    GeminiLiveAudioEngine.exitCallMode(context)
                } catch (e: Exception) {
                    // ignore
                }
                AudioManagerController.exitCallAudioMode(context)
                delay(300)
                AudioManagerController.enterCallAudioMode(context, speakerphone = true)
                Speaker.enterCallMode(context, liveSettings)
                delivered = Speaker.awaitCallSpeech(message, timeoutMs = 25_000)
            }

            // G2: arm the duplex mic AFTER delivery so the assistant can
            // listen to the reply (works when provider = Gemini Live).
            if (delivered && GeminiLiveAudioEngine.isUsable(liveSettings)) {
                GeminiLiveAudioEngine.enterCallMode(context)
            }

            if (delivered) {
                LogBus.log(
                    "[WA-CALL] message DELIVERED route=" +
                        AudioManagerController.verifyCallRoute(context),
                    LogLevel.SUCCESS
                )
                // Duplex status: hear the recipient -> reply live.
                if (GeminiLiveAudioEngine.isMicStreaming()) {
                    LogBus.log("[WA-CALL] duplex active (live mic streaming)", LogLevel.SUCCESS)
                } else {
                    LogBus.log(
                        "[WA-CALL] duplex unavailable (mic busy) - one-way message only",
                        LogLevel.WARN
                    )
                }
                // Stay in the call so the recipient can answer back - abort
                // the moment the call really ends (no post-call speech).
                // G2: 120s conversation window (was 45s - cut people off).
                holdUntilCallEnds(accessibility, maxMs = 120_000)
            } else {
                LogBus.log(
                    "[WA-CALL] all route attempts FAILED " +
                        AudioManagerController.verifyCallRoute(context),
                    LogLevel.ERROR
                )
            }
        } finally {
            com.sqlai.assistant.core.ScreenshotLog.captureAndSave(
                if (delivered) "Call_Message_Delivered" else "Call_Message_FAILED"
            )
            endSession()
        }
        return delivered
    }

    /**
     * G2 - poll until the call visibly ends (or [maxMs] passes) while the
     * duplex session stays live. 12s grace so mid-playback screens are not
     * misread as "no call UI".
     */
    private suspend fun holdUntilCallEnds(accessibility: SqlAccessibilityService, maxMs: Long) {
        val start = System.currentTimeMillis()
        val until = start + maxMs
        while (System.currentTimeMillis() < until) {
            delay(1500)
            if (CallStateMachine.current() == CallStateMachine.State.ENDED) break
            val screen = accessibility.captureScreenText(60).lowercase()
            if (screen.contains("call ended") ||
                screen.contains("call rejected") ||
                screen.contains("call failed") ||
                screen.contains("no answer")
            ) break
            if (System.currentTimeMillis() - start > 12_000) {
                val hasCallUi = screen.contains(Regex("\\d:\\d{2}")) ||
                    screen.contains("speaker") ||
                    screen.contains("mute") ||
                    screen.contains("end call") ||
                    screen.contains("message")
                if (!hasCallUi) break
            }
        }
    }

    /** True while the background mic capture of this handler is active. */
    fun isLiveTalking(): Boolean = GeminiLiveAudioEngine.state.value ==
        com.sqlai.assistant.ai.GeminiLiveAudioEngine.LiveState.LISTENING ||
        AudioManagerController.isCallMode()
}
