package com.sqlai.assistant.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import com.sqlai.assistant.R
import com.sqlai.assistant.SqlAiApp
import com.sqlai.assistant.ai.AiClient
import com.sqlai.assistant.ai.ChatMessage
import com.sqlai.assistant.ai.GeminiLiveAudioEngine
import com.sqlai.assistant.core.AppCrashHandler
import com.sqlai.assistant.core.AppSettings
import com.sqlai.assistant.core.AudioManagerController
import com.sqlai.assistant.core.LogBus
import com.sqlai.assistant.core.LogLevel
import com.sqlai.assistant.core.StateBus
import com.sqlai.assistant.engine.Speaker
import com.sqlai.assistant.engine.SpeechCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * v6.0 - AUTONOMOUS CALL BRIDGE.
 *
 * Spec: once a WhatsApp call connects (blank call, or after our message was
 * delivered), the agent must NOT hand control back to the local user and it
 * must NEVER abandon the call after a fixed 10-15s window. This service
 * takes the call over permanently (until the call visibly ends, or the hard
 * caps) and runs the live conversation itself:
 *
 *   - preferred: Gemini Live duplex - far-end audio comes in over the
 *     VOICE_COMMUNICATION mic (acoustic coupling via loudspeaker ON), the
 *     model replies through the call stream autonomously. The mic is healed
 *     if the stream drops mid-call.
 *   - fallback (provider not Gemini): turn-based loop - SpeechCapture hears
 *     the caller, AiClient drafts a short spoken reply, TTS sends it over
 *     the call stream (same proven pattern as SqlInCallService).
 *
 * Audio: speakerphone ON for the whole session (the far end only hears us
 * acoustically). On exit everything is restored: normal audio mode,
 * speaker off, duplex closed, queue gate re-opened, state machine reset.
 *
 * Lifecycle: startForeground with the microphone FGS type, PARTIAL wake
 * lock, 30 hard cap (10 min when the screen/vision is unavailable), duplicate
 * starts ignored, cleanup also runs from onDestroy (crash-safe).
 */
class AutonomousCallBridgeService : Service() {

    private var scope: CoroutineScope? = null
    private var job: kotlinx.coroutines.Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Main + AppCrashHandler.coroutineHandler
        )
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } catch (t: Throwable) {
            LogBus.log("[BRIDGE] startForeground failed: ${t.message}", LogLevel.ERROR)
        }
        acquireWakeLock()
        LogBus.log("[BRIDGE] autonomous call bridge started", LogLevel.SUCCESS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Duplicate start while a session is live = ignore (idempotent).
        if (job?.isActive == true) return START_NOT_STICKY
        val sc = scope ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        job = sc.launch { bridgeLoop(this) }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        isRunning = false
        try {
            job?.cancel()
        } catch (t: Throwable) {
            // Ignore.
        }
        try {
            scope?.cancel()
        } catch (t: Throwable) {
            // Ignore.
        }
        scope = null
        job = null
        teardown()
        releaseWakeLock()
        LogBus.log("[BRIDGE] call bridge stopped", LogLevel.INFO)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- loop

    private suspend fun bridgeLoop(coroutine: CoroutineScope) {
        val startedAt = System.currentTimeMillis()
        val st = try {
            SqlAiApp.settings.settings.first()
        } catch (e: Exception) {
            AppSettings()
        }

        // Speakerphone ON from second one - acoustic coupling both ways.
        AudioManagerController.enterCallAudioMode(this, speakerphone = true)
        Speaker.enterCallMode(this, st)

        val geminiOk = GeminiLiveAudioEngine.isUsable(st)
        if (geminiOk) {
            GeminiLiveAudioEngine.enterCallMode(this)
            // enterCallMode defaults speakerphone OFF - re-assert ON.
            AudioManagerController.enterCallAudioMode(this, speakerphone = true)
            GeminiLiveAudioEngine.startMic()
            if (GeminiLiveAudioEngine.isMicStreaming()) {
                LogBus.log("[BRIDGE] duplex live (mic streaming)", LogLevel.SUCCESS)
            } else {
                LogBus.log(
                    "[BRIDGE] duplex mic not streaming yet - will keep retrying",
                    LogLevel.WARN
                )
            }
        } else {
            LogBus.log(
                "[BRIDGE] Gemini not usable - fallback turn loop",
                LogLevel.WARN
            )
        }

        if (!geminiOk) {
            fallbackTurnLoop(st, startedAt)
            teardown()
            stopQuietly()
            return
        }

        // ---- monitoring loop (Gemini duplex) -----------------------------
        var lastUiSeen = System.currentTimeMillis()
        var lastHeal = 0L
        while (coroutine.isActive &&
            System.currentTimeMillis() - startedAt < MAX_BRIDGE_MS
        ) {
            delay(2000)
            if (CallStateMachine.current() == CallStateMachine.State.ENDED) break

            val alive = callAliveOnScreen()
            when (alive) {
                "dead" -> break
                "ui" -> lastUiSeen = System.currentTimeMillis()
                "no-ui" -> {
                    // Screen captured but no in-call UI: grace via lastUiSeen.
                }
                null -> {
                    // No vision right now (screen off / service gone): fall
                    // back to the shorter no-verification cap.
                    if (System.currentTimeMillis() - startedAt > NO_VISION_MAX_MS) break
                }
            }
            // In-call UI gone for a full minute (with vision working) = over.
            if (alive != null &&
                System.currentTimeMillis() - lastUiSeen > 60_000
            ) break

            // Heal a dropped duplex mic (throttled - never hammer).
            val now = System.currentTimeMillis()
            if (!GeminiLiveAudioEngine.isMicStreaming() && now - lastHeal > 10_000) {
                lastHeal = now
                GeminiLiveAudioEngine.startMic()
            }
        }

        LogBus.log("[BRIDGE] conversation over - cleaning up", LogLevel.INFO)
        teardown()
        stopQuietly()
    }

    /**
     * Fallback: turn-based conversation (same proven pattern as the phone
     * InCallService) - hear via SpeechCapture, reply via call-stream TTS.
     */
    private suspend fun fallbackTurnLoop(st: AppSettings, startedAt: Long) {
        Speaker.init(this)
        Speaker.enterCallMode(this, st)
        val greeting = if (st.language.code == "en") {
            "Hello, this is SQL AI speaking on behalf of the user. How can I help?"
        } else {
            "Namaste, this is SQL AI speaking for the user. Boliye, main sun rahi hoon."
        }
        var turns = 0
        var silentTurns = 0
        while (
            turns < 12 &&
            silentTurns < 2 &&
            System.currentTimeMillis() - startedAt < MAX_BRIDGE_MS
        ) {
            if (callAliveOnScreen() == "dead") break
            Speaker.speakOnCall(
                if (turns == 0) greeting else "Ji, boliye - main sun rahi hoon.",
                st
            )
            delay(1200)
            val heard = SpeechCapture.listenOnce(this, st.language.sttTag, timeoutMs = 11_000)
            if (heard.isNullOrBlank()) {
                silentTurns++
                LogBus.log("[BRIDGE] nothing heard (turn ${turns + 1})", LogLevel.WARN)
                continue
            }
            silentTurns = 0
            LogBus.log("[BRIDGE] caller said: \"$heard\"", LogLevel.SUCCESS)
            StateBus.setCommand(heard)
            val reply = generateReply(st, heard)
            Speaker.speakOnCall(reply, st)
            turns++
        }
        LogBus.log("[BRIDGE] fallback loop finished (turns=$turns)", LogLevel.INFO)
    }

    private suspend fun generateReply(st: AppSettings, heard: String): String {
        val systemPrompt = buildString {
            append("You are SQL AI, speaking LIVE on the user's phone call. ")
            append("Answer the caller naturally, warmly and briefly - max 25 words, ")
            append("spoken language only. No JSON, no symbols, no emoji. ")
            append(st.languageInstruction())
        }
        return try {
            AiClient.complete(
                settings = st,
                screenContext = null,
                history = listOf(
                    ChatMessage("user", "The caller said: \"$heard\". Reply as the assistant.")
                ),
                imageJpeg = null,
                systemPromptOverride = systemPrompt
            ).take(400)
        } catch (e: Exception) {
            "Theek hai, main aapki baat samajh gayi."
        }
    }

    // ------------------------------------------------------------- helpers

    /**
     * Vision check for a live WhatsApp call.
     *  "dead"  = explicit "call ended" text (certain death),
     *  "ui"    = in-call UI visible,
     *  "no-ui" = screen captured but no call UI (grace period applies),
     *  null    = unknown (no accessibility / no capture) - caller uses caps.
     */
    private fun callAliveOnScreen(): String? {
        val acc = SqlAccessibilityService.instance ?: return null
        val screen = try {
            acc.captureScreenText(40).lowercase()
        } catch (t: Throwable) {
            return null
        }
        if (screen.isBlank()) return null
        if (screen.contains("call ended") ||
            screen.contains("call rejected") ||
            screen.contains("call failed") ||
            screen.contains("no answer") ||
            screen.contains("missed call")
        ) return "dead"
        val hasCallUi = screen.contains("speaker") ||
            screen.contains("mute") ||
            screen.contains("end call") ||
            screen.contains("ongoing") ||
            screen.contains("record") ||
            Regex("\\b\\d{1,2}:\\d{2}\\b").containsMatchIn(screen)
        return if (hasCallUi) "ui" else "no-ui"
    }

    private fun teardown() {
        try {
            GeminiLiveAudioEngine.exitCallMode(this)
        } catch (t: Throwable) {
            // Ignore.
        }
        try {
            Speaker.exitCallMode()
        } catch (t: Throwable) {
            // Ignore.
        }
        try {
            AudioManagerController.exitCallAudioMode(this)
        } catch (t: Throwable) {
            // Ignore.
        }
        try {
            Speaker.setQueueGate(true)
        } catch (t: Throwable) {
            // Ignore.
        }
        try {
            if (CallStateMachine.current() != CallStateMachine.State.IDLE) {
                CallStateMachine.reset()
            }
        } catch (t: Throwable) {
            // Ignore.
        }
    }

    private fun stopQuietly() {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        } catch (t: Throwable) {
            // Ignore.
        }
        stopSelf()
    }

    private fun buildNotification(): Notification =
        Notification.Builder(this, SqlAiApp.CHANNEL_SERVICE)
            .setContentTitle("SQL AI call bridge")
            .setContentText("Speaking with them autonomously...")
            .setSmallIcon(R.drawable.ic_stat_sql)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SQLAI:CallBridge").apply {
                setReferenceCounted(false)
                acquire(MAX_BRIDGE_MS + 60_000L)
            }
        } catch (t: Throwable) {
            LogBus.log("[BRIDGE] wakelock failed: ${t.message}", LogLevel.WARN)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (t: Throwable) {
            // Ignore.
        }
        wakeLock = null
    }

    companion object {
        const val TAG = "CallBridge"
        private const val NOTIFICATION_ID = 7106
        private const val MAX_BRIDGE_MS = 30L * 60_000L
        private const val NO_VISION_MAX_MS = 10L * 60_000L

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            try {
                context.startForegroundService(
                    Intent(context, AutonomousCallBridgeService::class.java)
                )
                LogBus.log("[BRIDGE] start requested", LogLevel.INFO)
            } catch (t: Throwable) {
                LogBus.log("[BRIDGE] start failed: ${t.message}", LogLevel.ERROR)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(
                    Intent(context, AutonomousCallBridgeService::class.java)
                )
            } catch (t: Throwable) {
                // Ignore.
            }
        }
    }
}
