package com.sqlai.assistant.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.ServiceCompat
import com.sqlai.assistant.R
import com.sqlai.assistant.SqlAiApp
import com.sqlai.assistant.core.AppCrashHandler
import com.sqlai.assistant.core.AssistantState
import com.sqlai.assistant.core.AudioManagerController
import com.sqlai.assistant.core.LogBus
import com.sqlai.assistant.core.LogLevel
import com.sqlai.assistant.core.StateBus
import com.sqlai.assistant.core.VoskWakeWordEngine
import com.sqlai.assistant.ai.GeminiLiveAudioEngine
import com.sqlai.assistant.agent.AgentOS
import com.sqlai.assistant.engine.AssistantEngine
import com.sqlai.assistant.engine.OverlayManager
import com.sqlai.assistant.engine.Speaker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * High-priority foreground service that keeps the microphone alive 24/7 and
 * watches for the wake word ("SQL" by default).
 *
 * v7 M3 flow: the always-on Vosk engine (offline Kaldi) detects the wake
 * word, yields the mic, and a <=6 s SpeechRecognizer burst captures the
 * command -> AgentOS routes it. wakeEngine="speech" (or any Vosk failure
 * such as a dead model download) falls back to the legacy 24/7 STT wake
 * loop with zero behaviour change.
 */
class ListeningService : Service() {

    companion object {
        /** v6.0 dashboard badge: true while the service instance lives. */
        @Volatile
        var isActive = false
            private set

        const val ACTION_START = "com.sqlai.assistant.action.START"
        const val ACTION_STOP = "com.sqlai.assistant.action.STOP"
        const val ACTION_TRIGGER = "com.sqlai.assistant.action.TRIGGER"
        const val ACTION_COMMAND = "com.sqlai.assistant.action.COMMAND"
        /** v7.0.0.2 push-to-talk: end the held/locked mic burst. */
        const val ACTION_PTT_STOP = "com.sqlai.assistant.action.PTT_STOP"
        const val EXTRA_TEXT = "text"
        const val EXTRA_SEND = "send"

        private const val NOTIFICATION_ID = 7010
        private const val BURST_TIMEOUT_MS = 15_000L

        fun start(context: Context) {
            val intent = Intent(context, ListeningService::class.java).setAction(ACTION_START)
            startSvc(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ListeningService::class.java).setAction(ACTION_STOP)
            startSvc(context, intent)
        }

        /** Voice-interaction assist trigger: capture next utterance as a command. */
        fun trigger(context: Context) {
            val intent = Intent(context, ListeningService::class.java).setAction(ACTION_TRIGGER)
            startSvc(context, intent)
        }

        /**
         * v7.0.0.2 push-to-talk end. send=true finalizes the burst (the
         * captured speech becomes a command), send=false discards it.
         */
        fun pttStop(context: Context, send: Boolean) {
            val intent = Intent(context, ListeningService::class.java)
                .setAction(ACTION_PTT_STOP)
                .putExtra(EXTRA_SEND, send)
            startSvc(context, intent)
        }

        /** Direct command (used by assist sessions that already have the text). */
        fun runCommand(context: Context, text: String) {
            val intent = Intent(context, ListeningService::class.java)
                .setAction(ACTION_COMMAND)
                .putExtra(EXTRA_TEXT, text)
            startSvc(context, intent)
        }

        private fun startSvc(context: Context, intent: Intent) {
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                LogBus.log("Foreground start blocked: ${e.message}", LogLevel.ERROR)
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + AppCrashHandler.coroutineHandler)
    private var settingsJob: Job? = null
    private var overlayJob: Job? = null

    private var recognizer: SpeechRecognizer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var running = false
    @Volatile private var wasRunning = false
    @Volatile private var isListening = false
    @Volatile private var awaitingCommand = false
    @Volatile private var listeningSince = 0L
    @Volatile private var consecutiveErrors = 0
    @Volatile private var speechPauseSince = 0L
    @Volatile private var awaitingAttempts = 0
    @Volatile private var cachedWakeWord = "sql"
    @Volatile private var cachedLanguageTag = "en-IN"
    @Volatile private var wasSpeaking = false
    @Volatile private var permissionWarnedAt = 0L

    // ---- v7 M3: always-on Vosk wake engine state ----
    @Volatile private var voskEngine: VoskWakeWordEngine? = null
    @Volatile private var voskActive = false
    @Volatile private var voskFailed = false
    @Volatile private var voskGen = 0
    // v7.0.1: matches the default wakeEngine - never boot Vosk transiently
    // before the first settings emission lands.
    @Volatile private var wakeMode = "speech"
    @Volatile private var cachedModelLang = "en"
    @Volatile private var lastVoskTranscript = ""
    /** v7.0.0.2: PTT/assist window - bypass the speech-pause until it lapses. */
    @Volatile private var forceRecordUntil = 0L
    /** v7.0.3: false = on-demand mic (burst only), true = 24/7 wake loop. */
    @Volatile private var bgListen = true
    @Volatile private var awaitingSince = 0L

    override fun onCreate() {
        super.onCreate()
        isActive = true
        Speaker.init(this)
        // v6.0: when Speaker yields the mic for playback, cancel our
        // recognizer session so ownership bookkeeping stays honest.
        AudioManagerController.setPlaybackYieldHook {
            mainHandler.post { if (isListening) cancelRecognition() }
        }
        // v5: guardian watching accessibility + battery + this pipeline.
        AccessibilityWatchdogService.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }

            ACTION_COMMAND -> {
                val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
                promoteToForeground()
                observeSettings()
                observeOverlay()
                if (text.isNotBlank()) {
                    StateBus.setCommand(text)
                    LogBus.log("Assist command: \"$text\"")
                    // v7: AgentOS routes it - chat answers instantly while
                    // Loop B keeps running, new tasks queue instead of drop.
                    AgentOS.dispatchText(text, "assist")
                    scheduleNext(900)
                }
                mainHandler.postDelayed({
                    if (!running && !awaitingCommand) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }, 1_200L)
                return START_NOT_STICKY
            }

            ACTION_TRIGGER -> {
                promoteToForeground()
                observeSettings()
                observeOverlay()
                ensureRunning()
                awaitingCommand = true
                awaitingSince = System.currentTimeMillis()
                // v7.0.0.2: user invoked the mic (assist / hold-to-talk) -
                // cut assistant audio so the burst never records itself.
                Speaker.interruptPlayback()
                // PTT: bypass the speech-pause guard - record immediately.
                forceRecordUntil = System.currentTimeMillis() + 2_000L
                StateBus.setState(AssistantState.LISTENING)
                LogBus.log("Assist triggered - waiting for your command")
                cancelRecognition()
                // v7 M3: direct burst - Vosk yields the mic, re-arms after.
                stopVosk()
                mainHandler.postDelayed({ startRecognition() }, 150)
                return START_STICKY
            }

            ACTION_PTT_STOP -> {
                val send = intent.getBooleanExtra(EXTRA_SEND, true)
                if (send) {
                    if (isListening) {
                        // Finalize: onResults -> evaluateWakeWord -> processCommand.
                        try {
                            recognizer?.stopListening()
                        } catch (e: Exception) {
                            LogBus.log("PTT stop failed: ${e.message}", LogLevel.WARN)
                            resumeIdle()
                        }
                    } else if (awaitingCommand) {
                        // Burst never got to record - nothing to send.
                        resumeIdle()
                    }
                } else {
                    awaitingCommand = false
                    awaitingAttempts = 0
                    cancelRecognition()
                    StateBus.setState(if (running) AssistantState.LISTENING else AssistantState.DISABLED)
                    LogBus.log("PTT cancelled", LogLevel.INFO)
                    maybeStopOnDemand()
                }
                return START_NOT_STICKY
            }

            else -> {
                promoteToForeground()
                ensureRunning()
                observeSettings()
                observeOverlay()
                return START_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Keep the service alive 24/7: when the user swipes the app away or the
     * system kills the task, schedule an immediate restart as long as the
     * assistant is enabled in settings.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        scheduleRestart(1200)
    }

    private fun scheduleRestart(delayMs: Long) {
        try {
            val restart = Intent(applicationContext, ListeningService::class.java)
                .setAction(ACTION_START)
            val pending = PendingIntent.getService(
                applicationContext,
                7011,
                restart,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // Fresh handler - survives this service's own cleanup.
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    pending.send()
                } catch (e: Exception) {
                    LogBus.log("Restart blocked: ${e.message}", LogLevel.WARN)
                }
            }, delayMs)
        } catch (e: Exception) {
            LogBus.log("Restart schedule failed: ${e.message}", LogLevel.WARN)
        }
    }

    override fun onDestroy() {
        wasRunning = running
        running = false
        isActive = false
        stopVosk()
        AudioManagerController.setPlaybackYieldHook(null)
        mainHandler.removeCallbacksAndMessages(null)
        settingsJob?.cancel()
        overlayJob?.cancel()
        cancelRecognition()
        recognizer?.destroy()
        recognizer = null
        try {
            wakeLock?.release()
        } catch (e: Exception) {
            // Already released.
        }
        wakeLock = null
        OverlayManager.hide()
        StateBus.setState(AssistantState.DISABLED)
        scope.cancel()
        if (wasRunning) scheduleRestart(1500)
        super.onDestroy()
    }

    // ------------------------------------------------------------- lifecycle

    private fun promoteToForeground() {
        val notification = Notification.Builder(this, SqlAiApp.CHANNEL_SERVICE)
            .setContentTitle(getString(R.string.notification_service_title))
            .setContentText(
                if (awaitingCommand) "Listening for your command..."
                else getString(R.string.notification_service_text)
            )
            .setSmallIcon(R.drawable.ic_stat_sql)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } catch (e: Exception) {
            LogBus.log("startForeground failed: ${e.message}", LogLevel.ERROR)
        }
    }

    private fun ensureRunning() {
        acquireWakeLock()
        if (!running) {
            running = true
            LogBus.log("24/7 wake-word listening started", LogLevel.SUCCESS)
        }
        // v7 M3: offline Vosk wake engine first; speech loop keeps interim
        // coverage while its model downloads on first run.
        if (wakeMode == "vosk" && !voskFailed) startVoskPipeline()
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            StateBus.setState(AssistantState.LISTENING)
            mainHandler.removeCallbacks(watchdog)
            mainHandler.postDelayed(watchdog, 400)
            // BUG #1 fast path: 300ms cadence re-arms the mic the instant
            // TTS finishes + heals stuck mic ownership (never dies).
            mainHandler.removeCallbacks(fastRearm)
            mainHandler.post(fastRearm)
        } else {
            LogBus.log("No speech recognition engine on this device", LogLevel.ERROR)
            StateBus.setState(AssistantState.ERROR)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sqlai:listening").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun observeSettings() {
        if (settingsJob != null) return
        // Mic preemption: when the call/live engine takes the mic, stop our
        // recognizer session immediately (prevents dual-recording). Also
        // heals a live-engine mic leaked OUTSIDE a call (auto-heal).
        scope.launch {
            AudioManagerController.micOwner.collect { owner ->
                if (owner != AudioManagerController.MicOwner.NONE &&
                    owner != AudioManagerController.MicOwner.STT && isListening
                ) {
                    cancelRecognition()
                }
                val inCall = CallStateMachine.isCallActive() ||
                    AudioManagerController.isCallMode()
                if (owner == AudioManagerController.MicOwner.GEMINI_LIVE && !inCall) {
                    LogBus.log("[MIC] live mic leaked outside call - releasing", LogLevel.WARN)
                    GeminiLiveAudioEngine.stopMic()
                }
                // SPEC: exactly 300ms after ANY mic release the STT loop
                // re-arms (covers TTS/call/one-shot capture endings).
                if (owner == AudioManagerController.MicOwner.NONE &&
                    !isListening && running && !speechPauseActive()
                ) {
                    mainHandler.postDelayed({
                        if (running && !isListening && micAvailable()) startRecognition()
                    }, 300)
                }
            }
        }
        settingsJob = scope.launch {
            SqlAiApp.settings.settings.collect { settings ->
                cachedWakeWord = settings.wakeWord.ifBlank { "sql" }
                cachedLanguageTag = settings.language.sttTag
                val newMode = if (settings.wakeEngine.equals("speech", ignoreCase = true)) {
                    "speech"
                } else "vosk"
                val newLang = if (settings.wakeModelLang.equals("hi", ignoreCase = true)) {
                    "hi"
                } else "en"
                if (newMode != wakeMode || newLang != cachedModelLang) {
                    // Setting changed - retry Vosk if the user flips back.
                    voskFailed = false
                    if (newMode == "speech") stopVosk()
                    wakeMode = newMode
                    cachedModelLang = newLang
                    if (running && newMode == "vosk") startVoskPipeline()
                }
                val shouldRun = settings.assistantEnabled && settings.listenServiceEnabled
                bgListen = shouldRun
                if (shouldRun && !running) {
                    ensureRunning()
                } else if (!shouldRun && running && !sessionActive()) {
                    LogBus.log("Background mic off (on-demand mode)", LogLevel.WARN)
                    stopEverything()
                }
            }
        }
    }

    private fun observeOverlay() {
        if (overlayJob != null) return
        overlayJob = scope.launch {
            combine(StateBus.state, StateBus.lastCommand) { state, command -> state to command }
                .collect { (state, command) ->
                    val overlayAllowed = SqlAiApp.settings.settings.first().overlayEnabled
                    if (!overlayAllowed) {
                        OverlayManager.hide()
                        return@collect
                    }
                    when (state) {
                        AssistantState.LISTENING -> OverlayManager.show(this@ListeningService, if (awaitingCommand) "SQL AI - speak your command" else "SQL AI - listening for \"SQL\"")
                        AssistantState.PROCESSING -> OverlayManager.show(this@ListeningService, "SQL AI - $command")
                        else -> OverlayManager.hide()
                    }
                    promoteToForeground()
                }
        }
    }

    private fun stopEverything() {
        running = false
        awaitingCommand = false
        mainHandler.removeCallbacksAndMessages(null)
        cancelRecognition()
        stopVosk()
        try {
            wakeLock?.release()
        } catch (e: Exception) {
            // Ignore.
        }
        OverlayManager.hide()
        StateBus.setState(AssistantState.DISABLED)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun sessionActive(): Boolean =
        awaitingCommand ||
            System.currentTimeMillis() < forceRecordUntil ||
            StateBus.state.value == AssistantState.PROCESSING

    private fun maybeStopOnDemand() {
        if (bgListen) return
        mainHandler.postDelayed(
            { if (running && !bgListen && !awaitingCommand) stopEverything() },
            700L
        )
    }

    private fun resumeIdle() {
        awaitingCommand = false
        StateBus.setState(if (running) AssistantState.LISTENING else AssistantState.DISABLED)
        mainHandler.postDelayed({ if (running) armWakeLoop() }, 700)
    }

    // ------------------------------------------------------------ STT plumbing

    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            if (voskActive) {
                // v7 M3: health-check the offline wake engine thread.
                if (voskEngine?.isAlive() != true) {
                    LogBus.log("Vosk engine thread died - restarting", LogLevel.WARN)
                    stopVosk()
                    startVoskPipeline()
                }
            } else if (isListening) {
                // Frozen session detector: a SpeechRecognizer that never
                // returns results is force-restarted instead of hanging.
                if (System.currentTimeMillis() - listeningSince > 9000) {
                    LogBus.log("STT session stuck - restarting recognizer", LogLevel.WARN)
                    endSession()
                    destroyRecognizer()
                }
            } else if (!speechPauseActive() && micAvailable()) {
                startRecognition()
            }
            mainHandler.postDelayed(this, 2500)
        }
    }

    /**
     * BUG #1 - runs every 300 ms:
     *  1. Re-arms the mic exactly 300 ms after TTS/Gemini speech ends
     *     (spec: "har speak ke end pe 300ms baad mic re-arm").
     *  2. Auto-recovers a mic stuck >2 s with no legitimate owner
     *     (spec: "mic 2s se zyada busy -> on-screen mic-stuck log + recovery").
     */
    private val fastRearm = object : Runnable {
        override fun run() {
            if (!running) return
            val speaking = Speaker.isSpeaking()
            val justEnded = wasSpeaking && !speaking
            wasSpeaking = speaking
            if (justEnded && !isListening && !speechPauseActive() && micAvailable()) {
                LogBus.log("[MIC] speech ended - re-arming mic in 300ms", LogLevel.INFO)
                mainHandler.postDelayed({
                    if (running && !isListening && micAvailable()) startRecognition()
                }, 300)
            }
            maybeRecoverStuckMic()
            mainHandler.postDelayed(this, 300)
        }
    }

    /** True while a legit voice-call session owns mic/audio resources. */
    private fun callSessionActive(): Boolean =
        CallStateMachine.isCallActive() || AudioManagerController.isCallMode()

    private fun maybeRecoverStuckMic() {
        val owner = AudioManagerController.micOwner.value
        if (owner == AudioManagerController.MicOwner.NONE) return
        if (owner == AudioManagerController.MicOwner.CALL_CAPTURE) {
            // One-shot capture has its own 12s timeout - only force past it.
            if (AudioManagerController.micHeldMs() <= 15_000) return
        } else if (owner == AudioManagerController.MicOwner.VOICE_NOTE) {
            // G3: an active voice-note hold is legitimate for up to 45s -
            // never force-release it mid-recording (would cut the note).
            if (AudioManagerController.micHeldMs() <= 45_000) return
        } else if (callSessionActive()) {
            return // duplex during a call may legitimately hold the mic
        } else if (owner == AudioManagerController.MicOwner.VOSK &&
            voskActive && voskEngine?.isAlive() == true
        ) {
            return // v7: the 24/7 offline wake engine legitimately holds it
        } else if (owner == AudioManagerController.MicOwner.GEMINI_LIVE &&
            GeminiLiveAudioEngine.isMicStreaming()
        ) {
            return // live streaming session in progress
        } else if (owner == AudioManagerController.MicOwner.STT && isListening) {
            return // our own active recognition session
        }
        val held = AudioManagerController.micHeldMs()
        if (held in 1..2_000) return
        // ---- STUCK: >2s busy with no legitimate holder -> on-screen log + heal
        LogBus.log(
            "[MIC] STUCK: ${owner.name} held ${held}ms outside call - force recovering",
            LogLevel.ERROR
        )
        if (owner == AudioManagerController.MicOwner.GEMINI_LIVE) {
            GeminiLiveAudioEngine.stopMic()
        }
        AudioManagerController.forceRelease()
        cancelRecognition()
        mainHandler.postDelayed({ if (running && !isListening) startRecognition() }, 200)
    }

    /** True while the assistant itself is speaking (avoid self-hearing), capped. */
    private fun speechPauseActive(): Boolean {
        // v7.0.0.2 PTT/assist trigger: record immediately (2s window, self-lapsing)
        if (System.currentTimeMillis() < forceRecordUntil) return false
        if (!Speaker.isSpeaking()) {
            speechPauseSince = 0L
            return false
        }
        val now = System.currentTimeMillis()
        if (speechPauseSince == 0L) speechPauseSince = now
        // Hard cap: never let speech pause the mic for more than 25 s.
        if (now - speechPauseSince > 25_000) {
            speechPauseSince = 0L
            return false
        }
        return true
    }

    private fun micAvailable(): Boolean {
        val owner = AudioManagerController.micOwner.value
        return owner == AudioManagerController.MicOwner.NONE ||
            owner == AudioManagerController.MicOwner.STT
    }

    private fun startRecognition() {
        if (!running || isListening) return
        if (speechPauseActive()) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        // v6.0: cross-subsystem record-open throttle (mic-reopen lockup fix).
        if (!AudioManagerController.noteRecordAttempt()) {
            mainHandler.postDelayed(
                { if (running && !isListening) startRecognition() },
                maxOf(AudioManagerController.recordCooldownRemainingMs(), 500L)
            )
            return
        }
        // Exclusive mic ownership - never double-record with the live engine.
        // acquireMic applies the preemption rule (STT may be taken from none;
        // Gemini Live / call capture blocks STT until they release).
        if (!AudioManagerController.acquireMic(this, AudioManagerController.MicOwner.STT)) {
            return
        }

        try {
            val current = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
                recognizer = it
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, cachedLanguageTag)
            }
            current.setRecognitionListener(recognitionListener)
            current.startListening(intent)
            isListening = true
            listeningSince = System.currentTimeMillis()
            StateBus.setState(AssistantState.LISTENING)
            // v7 M6 barge-in, v7.0.0.2 narrowed: ONLY cut assistant audio
            // when the user actually invoked a command burst (awaiting).
            // Idle wake re-arms must never bump playEpoch - that silently
            // killed every in-flight reply line ("voice gone" field bug).
            if (awaitingCommand) Speaker.interruptPlayback()
        } catch (e: Exception) {
            isListening = false
            AudioManagerController.releaseMic(AudioManagerController.MicOwner.STT)
            LogBus.log("STT start failed: ${e.message}", LogLevel.WARN)
            // Exponential backoff instead of a tight reopen loop.
            val backoff = 1500L + consecutiveErrors.coerceAtMost(4) * 1000L
            consecutiveErrors++
            mainHandler.postDelayed({ if (running) startRecognition() }, backoff)
        }
    }

    /** Terminal bookkeeping for one recognition session. */
    private fun endSession() {
        isListening = false
        listeningSince = 0L
        AudioManagerController.releaseMic(AudioManagerController.MicOwner.STT)
    }

    private fun destroyRecognizer() {
        try {
            recognizer?.destroy()
        } catch (e: Exception) {
            // Ignore.
        }
        recognizer = null
        isListening = false
        consecutiveErrors = 0
        if (running) mainHandler.postDelayed({ armWakeLoop() }, 400)
    }

    private fun cancelRecognition() {
        try {
            recognizer?.cancel()
        } catch (e: Exception) {
            // Ignore.
        }
        endSession()
    }

    private fun scheduleNext(delayMs: Long = 450) {
        mainHandler.postDelayed({ if (running) armWakeLoop() }, delayMs)
    }

    /**
     * v7 M3 - re-arm the right wake pipeline: offline Vosk when enabled and
     * healthy, the legacy STT loop otherwise (or while the Vosk model is
     * still downloading on first run - interim coverage, no dead air).
     */
    private fun armWakeLoop() {
        if (!running) return
        if (!bgListen) {
            val burstTimedOut = awaitingCommand &&
                System.currentTimeMillis() - awaitingSince > BURST_TIMEOUT_MS
            if (!awaitingCommand || burstTimedOut) {
                if (burstTimedOut) {
                    LogBus.log("On-demand burst timed out - releasing mic", LogLevel.WARN)
                }
                LogBus.log("On-demand mic: burst over - background loop stays off", LogLevel.INFO)
                awaitingCommand = false
                stopEverything()
                return
            }
        }
        if (wakeMode == "vosk" && !voskFailed) {
            startVoskPipeline() // no-op when an engine already exists
            if (voskActive) return
        }
        startRecognition()
    }

    // -------------------------------------------------------- v7 Vosk wake

    private fun startVoskPipeline() {
        if (!running || wakeMode != "vosk" || voskFailed || voskEngine != null) return
        val gen = ++voskGen
        LogBus.log("Vosk wake engine starting (model=$cachedModelLang, word=\"$cachedWakeWord\")")
        val engine = VoskWakeWordEngine(
            context = applicationContext,
            wakeWord = cachedWakeWord,
            modelLang = cachedModelLang,
            onReady = {
                mainHandler.post {
                    if (gen != voskGen || !running) return@post
                    voskActive = true
                    LogBus.log("Vosk wake engine ready - offline listening", LogLevel.SUCCESS)
                    if (isListening) cancelRecognition()
                }
            },
            onWake = { transcript ->
                mainHandler.post {
                    if (gen == voskGen && running) onVoskWake(transcript)
                }
            },
            onFailed = { reason ->
                mainHandler.post {
                    if (gen == voskGen) onVoskFailed(reason)
                }
            }
        )
        voskEngine = engine
        engine.start()
    }

    /** Wake word heard: yield the mic, then a 300 ms-later STT burst. */
    private fun onVoskWake(transcript: String) {
        if (!running) return
        // Self-hearing guard: never treat our own TTS as a wake word.
        if (Speaker.isSpeaking() || awaitingCommand) return
        lastVoskTranscript = transcript
        LogBus.log("Vosk wake word detected - capturing command", LogLevel.SUCCESS)
        awaitingCommand = true
        awaitingSince = System.currentTimeMillis()
        awaitingAttempts = 0
        StateBus.setState(AssistantState.LISTENING)
        promoteToForeground()
        stopVosk()
        mainHandler.postDelayed({
            if (!running || !awaitingCommand) return@postDelayed
            if (SpeechRecognizer.isRecognitionAvailable(this)) {
                if (!isListening) startRecognition()
                return@postDelayed
            }
            // No Google STT on device - consume the Vosk buffer directly.
            val fallback = lastVoskTranscript
            lastVoskTranscript = ""
            awaitingCommand = false
            val cmd = stripWakeWord(fallback.lowercase(), cachedWakeWord).trim()
            if (cmd.length >= 2) processCommand(cmd) else armWakeLoop()
        }, 300)
    }

    /** Vosk unavailable - latch the fallback and keep the STT loop alive. */
    private fun onVoskFailed(reason: String) {
        voskEngine = null
        voskActive = false
        voskFailed = true
        LogBus.log(
            "Vosk wake unavailable ($reason) - falling back to STT wake loop",
            LogLevel.WARN
        )
        if (running && !isListening) scheduleNext(450)
    }

    private fun stopVosk() {
        voskGen++ // invalidate any in-flight engine callbacks
        voskActive = false
        val engine = voskEngine
        voskEngine = null
        engine?.stop()
    }

    private val recognitionListener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onEndOfSpeech() {
            // NOT terminal - onResults/onError will close the session.
            // (Setting isListening=false here let the watchdog start a SECOND
            // session while results were still in flight -> double-record.)
            StateBus.setLevel(0f)
        }

        override fun onRmsChanged(rmsdB: Float) {
            val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            StateBus.setLevel(normalized)
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onError(error: Int) {
            endSession()
            StateBus.setLevel(0f)
            // v7 M3: the burst heard nothing - fall back to whatever Vosk
            // buffered around the wake word before it yielded the mic.
            if ((error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) &&
                awaitingCommand && lastVoskTranscript.isNotBlank()
            ) {
                val fallback = lastVoskTranscript
                lastVoskTranscript = ""
                LogBus.log("STT burst empty - using Vosk buffer: \"$fallback\"", LogLevel.WARN)
                consecutiveErrors = 0
                evaluateWakeWord(fallback, isFinal = true)
                return
            }
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    consecutiveErrors = 0 // Normal in always-on mode.

                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    // BUG #1: never kill the listen loop - keep retrying so
                    // granting the permission later resumes instantly.
                    val now = System.currentTimeMillis()
                    if (now - permissionWarnedAt > 30_000) {
                        permissionWarnedAt = now
                        LogBus.log(
                            "Microphone permission missing - grant it; retrying every 30s",
                            LogLevel.ERROR
                        )
                    }
                    StateBus.setState(AssistantState.ERROR)
                    if (!bgListen) {
                        awaitingCommand = false
                        stopEverything()
                        return
                    }
                    scheduleNext(30_000)
                    consecutiveErrors = 0
                    return
                }

                else -> {
                    consecutiveErrors++
                    LogBus.log("STT error code $error", LogLevel.WARN)
                }
            }
            // Exponential backoff: 450 -> 900 -> 1800 -> 3600 -> 5000 ms cap.
            val backoff = if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                900L
            } else {
                val shift = consecutiveErrors.coerceIn(0, 4)
                (450L shl shift).coerceAtMost(5000L)
            }
            // Repeated failures -> rebuild the recognizer from scratch.
            if (consecutiveErrors >= 5) {
                destroyRecognizer()
            } else {
                scheduleNext(backoff)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = topResult(partialResults) ?: return
            // Wake word keeps evaluating DURING task execution (Loop A stays on)
            // so the user can say "SQL stop" while the agent works.
            if (!awaitingCommand) evaluateWakeWord(text, isFinal = false)
        }

        override fun onResults(results: Bundle?) {
            endSession()
            StateBus.setLevel(0f)
            consecutiveErrors = 0
            val text = topResult(results).orEmpty()
            val consumed = evaluateWakeWord(text, isFinal = true)
            if (!consumed) scheduleNext()
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        private fun topResult(bundle: Bundle?): String? =
            bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                ?.lowercase()
    }

    /**
     * Wake-word logic.
     * Returns true when the utterance was consumed (command handed to the engine).
     */
    private fun evaluateWakeWord(rawText: String, isFinal: Boolean): Boolean {
        val text = rawText.lowercase().trim()
        if (text.isEmpty()) return false
        val wakeWord = cachedWakeWord

        if (awaitingCommand) {
            if (!isFinal) return false // wait for the final, stable utterance
            val command = stripWakeWord(text, wakeWord)
            if (command.isNotBlank() && command.length >= 2) {
                processCommand(command)
            } else {
                // Wake word repeated / silence - stay armed for a few tries.
                awaitingAttempts++
                if (awaitingAttempts >= 3) {
                    awaitingAttempts = 0
                    awaitingCommand = false
                    scheduleNext()
                }
            }
            return true
        }

        val index = findWakeWord(text, wakeWord)
        if (index < 0) return false

        val remainder = text.substring(index + wakeWord.length)
            .trim { it.isWhitespace() || it == ',' || it == '.' || it == '!' || it == '?' || it == ':' || it == ';' }

        return when {
            remainder.isNotBlank() && isFinal -> {
                processCommand(stripWakeWord(remainder, wakeWord))
                true
            }

            remainder.isNotBlank() && !isFinal -> {
                // Partial fragment - wait for final result.
                false
            }

            isFinal -> {
                awaitingCommand = true
                awaitingSince = System.currentTimeMillis()
                awaitingAttempts = 0
                StateBus.setState(AssistantState.LISTENING)
                LogBus.log("Wake word \"$wakeWord\" detected - listening for command", LogLevel.SUCCESS)
                promoteToForeground()
                false
            }

            else -> false
        }
    }

    private fun findWakeWord(text: String, wakeWord: String): Int {
        if (wakeWord.isEmpty()) return -1
        var from = 0
        while (true) {
            val idx = text.indexOf(wakeWord, from)
            if (idx < 0) return -1
            val beforeOk = idx == 0 || !text[idx - 1].isLetterOrDigit()
            val end = idx + wakeWord.length
            val afterOk = end >= text.length || !text[end].isLetterOrDigit()
            if (beforeOk && afterOk) return idx
            from = idx + 1
        }
    }

    private fun stripWakeWord(text: String, wakeWord: String): String {
        val idx = findWakeWord(text, wakeWord)
        return if (idx >= 0) {
            text.substring(idx + wakeWord.length)
                .trim { it.isWhitespace() || it == ',' || it == '.' || it == '!' || it == '?' }
        } else text
    }

    private fun processCommand(command: String) {
        val clean = command.trim()
        if (clean.isEmpty()) {
            resumeIdle()
            return
        }
        awaitingCommand = false
        isListening = false
        cancelRecognition()
        StateBus.setState(AssistantState.PROCESSING)
        StateBus.setCommand(clean)
        LogBus.log("Command: \"$clean\"", LogLevel.SUCCESS)

        // DUAL-LOOP: hand the task to Loop B (executor) WITHOUT awaiting it,
        // then immediately re-arm Loop A (voice bridge) so the assistant can
        // keep listening / giving spoken progress while the task runs.
        AssistantEngine.execute(clean, "wake-word")
        scheduleNext(900)
    }
}
