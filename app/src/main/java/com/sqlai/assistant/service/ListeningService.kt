package com.sqlai.assistant.service

import android.app.Notification
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
import com.sqlai.assistant.core.AssistantState
import com.sqlai.assistant.core.LogBus
import com.sqlai.assistant.core.LogLevel
import com.sqlai.assistant.core.StateBus
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
 * Flow:  wake word -> (optional pause for command) -> AssistantEngine.execute()
 */
class ListeningService : Service() {

    companion object {
        const val ACTION_START = "com.sqlai.assistant.action.START"
        const val ACTION_STOP = "com.sqlai.assistant.action.STOP"
        const val ACTION_TRIGGER = "com.sqlai.assistant.action.TRIGGER"
        const val ACTION_COMMAND = "com.sqlai.assistant.action.COMMAND"
        const val EXTRA_TEXT = "text"

        private const val NOTIFICATION_ID = 7010

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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var settingsJob: Job? = null
    private var overlayJob: Job? = null

    private var recognizer: SpeechRecognizer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var running = false
    @Volatile private var isListening = false
    @Volatile private var processing = false
    @Volatile private var awaitingCommand = false
    @Volatile private var awaitingAttempts = 0
    @Volatile private var cachedWakeWord = "sql"

    override fun onCreate() {
        super.onCreate()
        Speaker.init(this)
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
                if (text.isNotBlank()) {
                    processing = true
                    StateBus.setCommand(text)
                    LogBus.log("Assist command: \"$text\"")
                    scope.launch {
                        AssistantEngine.executeBlocking(text, "assist")
                        processing = false
                        resumeIdle()
                    }
                }
                return START_NOT_STICKY
            }

            ACTION_TRIGGER -> {
                promoteToForeground()
                ensureRunning()
                awaitingCommand = true
                StateBus.setState(AssistantState.LISTENING)
                LogBus.log("Assist triggered - waiting for your command")
                cancelRecognition()
                mainHandler.post { startRecognition() }
                return START_STICKY
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

    override fun onDestroy() {
        running = false
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
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            StateBus.setState(AssistantState.LISTENING)
            mainHandler.removeCallbacks(watchdog)
            mainHandler.postDelayed(watchdog, 400)
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
        settingsJob = scope.launch {
            SqlAiApp.settings.settings.collect { settings ->
                cachedWakeWord = settings.wakeWord.ifBlank { "sql" }
                val shouldRun = settings.assistantEnabled && settings.listenServiceEnabled
                if (shouldRun && !running) {
                    ensureRunning()
                } else if (!shouldRun && running) {
                    LogBus.log("Listening paused (disabled in settings)", LogLevel.WARN)
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
        processing = false
        mainHandler.removeCallbacksAndMessages(null)
        cancelRecognition()
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

    private fun resumeIdle() {
        awaitingCommand = false
        StateBus.setState(if (running) AssistantState.LISTENING else AssistantState.DISABLED)
        mainHandler.postDelayed({ if (running) startRecognition() }, 700)
    }

    // ------------------------------------------------------------ STT plumbing

    private val watchdog = object : Runnable {
        override fun run() {
            if (!running) return
            if (!isListening && !processing) startRecognition()
            mainHandler.postDelayed(this, 5000)
        }
    }

    private fun startRecognition() {
        if (!running || isListening || processing) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return

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
            }
            current.setRecognitionListener(recognitionListener)
            current.startListening(intent)
            isListening = true
            StateBus.setState(AssistantState.LISTENING)
        } catch (e: Exception) {
            isListening = false
            LogBus.log("STT start failed: ${e.message}", LogLevel.WARN)
            mainHandler.postDelayed({ if (running) startRecognition() }, 2000)
        }
    }

    private fun cancelRecognition() {
        try {
            recognizer?.cancel()
        } catch (e: Exception) {
            // Ignore.
        }
        isListening = false
    }

    private fun scheduleNext(delayMs: Long = 450) {
        mainHandler.postDelayed({ if (running && !processing) startRecognition() }, delayMs)
    }

    private val recognitionListener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onEndOfSpeech() {
            isListening = false
            StateBus.setLevel(0f)
        }

        override fun onRmsChanged(rmsdB: Float) {
            val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            StateBus.setLevel(normalized)
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onError(error: Int) {
            isListening = false
            StateBus.setLevel(0f)
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    Unit // Normal in always-on mode.

                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    LogBus.log("Microphone permission missing", LogLevel.ERROR)
                    StateBus.setState(AssistantState.ERROR)
                    running = false
                }

                else ->
                    LogBus.log("STT error code $error", LogLevel.WARN)
            }
            scheduleNext(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 900 else 450)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = topResult(partialResults) ?: return
            if (processing) return
            if (!awaitingCommand) evaluateWakeWord(text, isFinal = false)
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            StateBus.setLevel(0f)
            val text = topResult(results).orEmpty()
            if (processing) {
                scheduleNext()
                return
            }
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
        processing = true
        isListening = false
        cancelRecognition()
        StateBus.setState(AssistantState.PROCESSING)
        StateBus.setCommand(clean)
        LogBus.log("Command: \"$clean\"", LogLevel.SUCCESS)

        scope.launch {
            try {
                AssistantEngine.executeBlocking(clean, "wake-word")
            } finally {
                processing = false
                resumeIdle()
            }
        }
    }
}
