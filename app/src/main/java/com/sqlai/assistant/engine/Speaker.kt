package com.sqlai.assistant.engine

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.sqlai.assistant.SqlAiApp
import com.sqlai.assistant.ai.GeminiLiveAudioEngine
import com.sqlai.assistant.ai.GeminiMaleVoiceStreamer
import com.sqlai.assistant.core.AppCrashHandler
import com.sqlai.assistant.core.AppSettings
import com.sqlai.assistant.core.AudioManagerController
import com.sqlai.assistant.core.LogBus
import com.sqlai.assistant.core.LogLevel
import com.sqlai.assistant.core.VoiceGender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Voice output with gender / language / pitch control.
 *  - Male   : lower pitch (0.90x) + best matching voice
 *  - Female : higher pitch (1.20x) + best matching voice
 *  - Locale : hi-IN / en-IN based on the language setting
 */
object Speaker {

    private const val TAG = "SqlAiTts"

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    private var ready = false

    private val pendingUtterances = ConcurrentHashMap<String, (Boolean) -> Unit>()
    private val utteranceIds = java.util.concurrent.atomic.AtomicLong(0L)

    @Volatile
    private var callMode = false

    /** Number of utterances currently in flight - drives isSpeaking(). */
    private val activeUtterances = AtomicInteger(0)

    @Volatile
    private var speakingSince = 0L

    @Volatile
    private var liveSpeaking = false

    /**
     * Serialized speech queue (Loop A of the dual-loop agent): producers call
     * [post] and return immediately so the task executor never blocks on TTS /
     * Gemini Live round-trips.
     *
     * BUG #3 (v5.2) - speech can never lag the actions:
     *  - MAX 2 pending lines: posting a 3rd drops the OLDEST immediately,
     *  - a line that waited >1.5s is dropped at dequeue time (stale progress
     *    is worthless - only the latest is spoken),
     *  - [postPriority] clears the backlog for final/important lines,
     *  - while a backlog exists the Android TTS path speeds up ("fast mode").
     */
    private class Pending(
        val text: String,
        val ts: Long,
        val epoch: Int,
        /** G2: set for awaitCallSpeech/awaitSpeech - completed by the consumer. */
        val result: kotlinx.coroutines.CompletableDeferred<Boolean>? = null
    )

    private val speechScope = CoroutineScope(SupervisorJob() + Dispatchers.Main + AppCrashHandler.coroutineHandler)
    private val queueLock = Any()
    private val pendingLines = ArrayDeque<Pending>()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var queueEpoch = 0
    @Volatile private var consumerStarted = false
    /**
     * G2: while false the consumer PAUSES (used around Gemini speakText so
     * TTS and the Gemini track never overlap on the call).
     */
    @Volatile private var queueGateOpen = true

    fun setQueueGate(open: Boolean) {
        queueGateOpen = open
        if (open) signal.trySend(Unit)
    }

    /**
     * NON-BLOCKING speak: enqueue and return immediately. Used by the agent
     * executor for live action feedback ("Opening WhatsApp now...") so voice
     * never stalls the automation loop (and vice versa).
     */
    fun post(text: String) {
        enqueue(text)
    }

    /**
     * BUG #3: important line (task result / interrupt answer) - drops every
     * stale pending line first so it speaks next.
     */
    fun postPriority(text: String) {
        if (text.isBlank()) return
        synchronized(queueLock) {
            val dropped = pendingLines.toList()
            pendingLines.clear()
            dropped.forEach { it.result?.complete(false) }
            if (dropped.isNotEmpty()) LogBus.log("[Speaker] priority: dropped ${dropped.size} pending line(s)", LogLevel.INFO)
        }
        enqueue(text)
    }

    private fun enqueue(text: String, result: kotlinx.coroutines.CompletableDeferred<Boolean>? = null) {
        if (text.isBlank()) {
            result?.complete(false)
            return
        }
        synchronized(queueLock) {
            while (result == null && pendingLines.size >= 2) {
                val dropped = pendingLines.removeFirst()
                dropped.result?.complete(false)
                LogBus.log("[Speaker] backlog>2 - dropped stale progress line", LogLevel.INFO)
            }
            pendingLines.addLast(Pending(text, System.currentTimeMillis(), queueEpoch, result))
        }
        signal.trySend(Unit)
        startQueueConsumer()
    }

    /**
     * G2 - speak [text] on the active call and suspend until playback ends.
     * Runs through the single queue consumer, so it is SERIALIZED with every
     * other speech line (no TTS/Gemini collisions during delivery).
     */
    suspend fun awaitCallSpeech(text: String, timeoutMs: Long = 30_000): Boolean {
        if (text.isBlank()) return false
        val deferred = kotlinx.coroutines.CompletableDeferred<Boolean>()
        enqueue(text, deferred)
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
    }

    /** Lines waiting to be spoken (0 = perfectly in sync). */
    fun backlog(): Int = synchronized(queueLock) { pendingLines.size }

    /** Drop everything still queued (call ended / task reset). */
    fun flushQueued() {
        queueEpoch++
        val dropped = synchronized(queueLock) {
            val l = pendingLines.toList()
            pendingLines.clear()
            l
        }
        dropped.forEach { it.result?.complete(false) }
    }

    /** True while something is being spoken (TTS or Gemini Live). */
    fun isSpeaking(): Boolean =
        activeUtterances.get() > 0 || liveSpeaking ||
            (System.currentTimeMillis() - speakingSince < 800 && speakingSince != 0L)

    internal fun setLiveSpeaking(value: Boolean) {
        liveSpeaking = value
        if (value) speakingSince = System.currentTimeMillis()
    }

    internal fun markSpeechStart() {
        activeUtterances.incrementAndGet()
        speakingSince = System.currentTimeMillis()
    }

    internal fun markSpeechEnd() {
        activeUtterances.updateAndGet { v -> (v - 1).coerceAtLeast(0) }
    }

    private fun startQueueConsumer() {
        if (consumerStarted) return
        synchronized(this) {
            if (consumerStarted) return
            consumerStarted = true
            speechScope.launch {
                for (u in signal) {
                    while (true) {
                        val item = synchronized(queueLock) {
                            pendingLines.removeFirstOrNull()
                        } ?: break
                        if (item.epoch != queueEpoch) {
                            item.result?.complete(false)
                            continue // flushed
                        }
                        // G2: paused while a Gemini delivery owns the output.
                        while (!queueGateOpen) delay(100)
                        val onCall =
                            com.sqlai.assistant.service.CallStateMachine.isCallActive()
                        // BUG #3: a line older than 1.5s is stale progress -
                        // drop it (skipped on a call - statuses still matter).
                        val age = System.currentTimeMillis() - item.ts
                        if (item.result == null && age > 1_500 && !onCall) {
                            LogBus.log(
                                "[Speaker] dropped STALE line (${age}ms): \"${item.text.take(40)}\"",
                                LogLevel.INFO
                            )
                            continue
                        }
                        var ok = false
                        try {
                            if (onCall) {
                                // G2 F7: during a call every line is routed onto
                                // the call stream instead of being DROPPED.
                                val st = SqlAiApp.settings.settings.first()
                                ok = speakOnCall(item.text, st)
                            } else {
                                speak(item.text)
                                ok = true
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "queued speak failed", t)
                        }
                        item.result?.complete(ok)
                    }
                }
            }
        }
    }

    fun init(context: Context) {
        if (tts != null) return
        synchronized(this) {
            if (tts != null) return
            tts = TextToSpeech(context.applicationContext) { status ->
                val engine = tts ?: return@TextToSpeech
                if (status == TextToSpeech.SUCCESS) {
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            markSpeechStart()
                        }

                        override fun onDone(utteranceId: String?) {
                            markSpeechEnd()
                            utteranceId?.let { pendingUtterances.remove(it)?.invoke(true) }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            markSpeechEnd()
                            utteranceId?.let { pendingUtterances.remove(it)?.invoke(false) }
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            markSpeechEnd()
                            utteranceId?.let { pendingUtterances.remove(it)?.invoke(false) }
                        }
                    })
                    startQueueConsumer()
                    ready = true
                    LogBus.log("Voice output (TTS) ready", LogLevel.SUCCESS)
                } else {
                    ready = false
                    LogBus.log("TTS init failed ($status)", LogLevel.WARN)
                }
            }
        }
    }

    suspend fun speak(text: String) {
        if (text.isBlank()) return
        val settings = SqlAiApp.settings.settings.first()
        if (!settings.ttsEnabled) return

        // v6.0: release the STT mic cleanly BEFORE any playback route runs
        // (TTS/Gemini). fastRearm/watchdog re-arm it after speech ends.
        AudioManagerController.yieldMicForPlayback()

        // ---- preferred 1: Gemini MALE voice streamer (v5 enforced) --------
        // Direct WebSocket native audio, hardcoded Puck/Fenrir male profile.
        // Android TTS is bypassed whenever this delivers.
        if (GeminiMaleVoiceStreamer.isUsable(settings)) {
            setLiveSpeaking(true)
            val ok = try {
                GeminiMaleVoiceStreamer.speak(settings, text)
            } finally {
                setLiveSpeaking(false)
            }
            if (ok) return
        }

        // ---- preferred 2: Gemini Live engine (call / duplex contexts) -----
        if (GeminiLiveAudioEngine.isUsable(settings)) {
            setLiveSpeaking(true)
            val ok = try {
                GeminiLiveAudioEngine.speakText(settings, text)
            } finally {
                setLiveSpeaking(false)
            }
            if (ok) return
            LogBus.log("Gemini voices unavailable - Android TTS last resort", LogLevel.WARN)
        }

        // ---- fallback: Android TTS ----------------------------------------
        if (tts == null) {
            try {
                init(SqlAiApp.instance)
            } catch (e: Exception) {
                LogBus.log("TTS init error: ${e.message}", LogLevel.WARN)
            }
        }
        val engine = awaitReady(2500) ?: run {
            LogBus.log("TTS not ready - said: $text")
            return
        }
        try {
            applyVoiceSettings(engine, settings)
            // BUG #3 "speaking fast" mode: while lines are queued the TTS
            // speeds up so voice catches up with the actions (~500ms lag).
            val rate = if (backlog() > 0) {
                (settings.ttsSpeed * 1.2f).coerceAtMost(1.8f)
            } else settings.ttsSpeed
            engine.setSpeechRate(rate)
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sqlai-${nextId()}")
        } catch (e: Exception) {
            LogBus.log("TTS error: ${e.message}", LogLevel.WARN)
        }
    }

    /** Waits (briefly) for the async TTS engine to finish initializing. */
    private suspend fun awaitReady(timeoutMs: Long): TextToSpeech? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val engine = tts
            if (ready && engine != null) return engine
            delay(80)
        }
        return if (ready) tts else null
    }

    // ------------------------------------------------------------- call mode

    /**
     * Route TTS output through the phone call audio stream (caller hears it).
     * G2 F3: WAITS for the async TTS engine (was: silent no-op when the
     * engine had not finished init -> every later speakOnCall failed).
     */
    suspend fun enterCallMode(context: Context, settings: AppSettings) {
        init(context)
        callMode = true
        val engine = awaitReady(4000) ?: run {
            LogBus.log("[Speaker] enterCallMode: TTS not ready in 4s", LogLevel.WARN)
            return
        }
        try {
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            applyVoiceSettings(engine, settings)
        } catch (e: Exception) {
            Log.w(TAG, "enterCallMode failed", e)
        }
    }

    fun exitCallMode() {
        callMode = false
        val engine = tts ?: return
        try {
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        } catch (e: Exception) {
            Log.w(TAG, "exitCallMode failed", e)
        }
    }

    /**
     * Speak [text] onto the active phone call and suspend until playback ends
     * (so the call assistant can listen right after).
     */
    suspend fun speakOnCall(text: String, settings: AppSettings, timeoutMs: Long = 15000): Boolean {
        if (text.isBlank()) return false
        // v6.0: same clean mic hand-off before call-stream TTS.
        AudioManagerController.yieldMicForPlayback()
        // G2 F3: wait up to 4s for the engine instead of failing instantly.
        val engine = awaitReady(4000) ?: run {
            LogBus.log("[Speaker] speakOnCall: TTS unavailable", LogLevel.WARN)
            return false
        }
        return try {
            if (callMode) {
                engine.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
            }
            applyVoiceSettings(engine, settings)
            engine.setSpeechRate(settings.ttsSpeed)
            val id = "call-${nextId()}"
            val done = withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<Boolean> { continuation ->
                    pendingUtterances[id] = { ok ->
                        if (continuation.isActive) continuation.resume(ok)
                    }
                    val started = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
                    if (started != TextToSpeech.SUCCESS) {
                        pendingUtterances.remove(id)
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
            } ?: false
            done
        } catch (e: Exception) {
            Log.w(TAG, "speakOnCall failed", e)
            false
        }
    }

    /**
     * G5 - deterministic Android TTS start for voice-note recording.
     * Bypasses the Gemini routes (websocket round-trip / silent failure ->
     * WhatsApp records EMPTY audio) and forces USAGE_MEDIA so the mic
     * definitely picks it up. Returns true when playback actually started.
     */
    suspend fun speakForHold(text: String): Boolean {
        if (text.isBlank()) return false
        if (tts == null) {
            try {
                init(SqlAiApp.instance)
            } catch (e: Exception) {
                LogBus.log("TTS init error: ${e.message}", LogLevel.WARN)
            }
        }
        val settings = try {
            SqlAiApp.settings.settings.first()
        } catch (e: Exception) {
            com.sqlai.assistant.core.AppSettings()
        }
        val engine = awaitReady(4000) ?: run {
            LogBus.log("[VOICE-NOTE] TTS not ready - nothing to speak", LogLevel.WARN)
            return false
        }
        return try {
            engine.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            applyVoiceSettings(engine, settings)
            engine.setSpeechRate(settings.ttsSpeed)
            val started = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hold-${nextId()}")
            LogBus.log(
                "[VOICE-NOTE] speakForHold ${if (started == TextToSpeech.SUCCESS) "STARTED" else "FAILED($started)"}",
                if (started == TextToSpeech.SUCCESS) LogLevel.SUCCESS else LogLevel.WARN
            )
            started == TextToSpeech.SUCCESS
        } catch (t: Throwable) {
            Log.w(TAG, "speakForHold failed", t)
            false
        }
    }

    // ----------------------------------------------------------------- voice

    /** Picks locale + closest gender voice + pitch factor. */
    private fun applyVoiceSettings(engine: TextToSpeech, settings: AppSettings) {
        val locale = Locale.forLanguageTag(settings.language.ttsTag)
        try {
            engine.language = locale
        } catch (e: Exception) {
            // Engine may not support the locale - falls through to voice search below.
        }

        try {
            val voices: Set<Voice>? = engine.voices
            if (!voices.isNullOrEmpty()) {
                val localeVoices = voices.filter {
                    it.locale != null &&
                        it.locale.language.equals(locale.language, ignoreCase = true) &&
                        !it.isNetworkConnectionRequired
                }
                val pool = localeVoices.ifEmpty { voices.filter { it.locale?.language == locale.language } }
                if (pool.isNotEmpty()) {
                    val chosen = pickGenderVoice(pool, settings.voiceGender)
                    engine.voice = chosen
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "voice selection failed", e)
        }

        val genderFactor = if (settings.voiceGender == VoiceGender.FEMALE) 1.18f else 0.90f
        try {
            engine.setPitch((settings.pitch * genderFactor).coerceIn(0.5f, 2.0f))
        } catch (e: Exception) {
            // Ignore engine quirks.
        }
    }

    /**
     * Some engines expose explicit gender hints in the voice name
     * (e.g. "...-female-...", "...-male-..."). "female" is checked first
     * because it contains "male".
     */
    private fun pickGenderVoice(voices: List<Voice>, gender: VoiceGender): Voice {
        val want = if (gender == VoiceGender.FEMALE) "female" else "male"

        voices.firstOrNull { it.name.contains(want, ignoreCase = true) }?.let { return it }
        // "male" query must not match "...female..."
        if (gender == VoiceGender.MALE) {
            voices.firstOrNull {
                it.name.contains("male", ignoreCase = true) &&
                    !it.name.contains("female", ignoreCase = true)
            }?.let { return it }
        }
        // No gender hint anywhere - prefer an on-device voice, else the first.
        return voices.firstOrNull { !it.isNetworkConnectionRequired }
            ?: voices.first()
    }

    fun stop() {
        try {
            tts?.stop()
            pendingUtterances.values.forEach { it(false) }
            pendingUtterances.clear()
        } catch (e: Exception) {
            Log.w(TAG, "stop failed", e)
        }
    }

    fun shutdown() {
        try {
            signal.close()
            synchronized(queueLock) { pendingLines.clear() }
        } catch (e: Exception) {
            // Ignore.
        }
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "shutdown failed", e)
        }
        tts = null
        ready = false
    }

    fun isCallMode(): Boolean = callMode

    private fun nextId(): Long = utteranceIds.incrementAndGet()
}
