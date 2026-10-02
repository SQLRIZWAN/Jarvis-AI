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
import com.sqlai.assistant.core.AppSettings
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
     * Gemini Live round-trips. Order is preserved, no overlapping utterances.
     *
     * BUG #1 / FEATURE #3: every entry carries the epoch it was posted under.
     * [flushQueued] bumps the epoch so anything queued BEFORE a call ended is
     * dropped (never plays after the call). While a call session is active the
     * consumer drops texts instead of speaking them on the normal speaker.
     */
    private val speechScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val speechQueue = Channel<Pair<Int, String>>(capacity = 64)
    @Volatile private var queueEpoch = 0
    @Volatile private var consumerStarted = false

    /**
     * NON-BLOCKING speak: enqueue and return immediately. Used by the agent
     * executor for live action feedback ("Opening WhatsApp now...") so voice
     * never stalls the automation loop (and vice versa).
     */
    fun post(text: String) {
        if (text.isBlank()) return
        speechQueue.trySend(queueEpoch to text)
        startQueueConsumer()
    }

    /** Drop everything still queued (call ended / task reset). */
    fun flushQueued() {
        queueEpoch++
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
                for ((epoch, text) in speechQueue) {
                    if (epoch != queueEpoch) continue // flushed - never play
                    // FEATURE #3: no speaker output while a call session is
                    // in progress (DIALING/RINGING/CONNECTED/SPEAKING).
                    if (com.sqlai.assistant.service.CallStateMachine.isCallActive()) {
                        LogBus.log("[Speaker] dropped during call: \"$text\"", LogLevel.INFO)
                        continue
                    }
                    try {
                        speak(text)
                    } catch (e: Exception) {
                        Log.w(TAG, "queued speak failed", e)
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
            engine.setSpeechRate(settings.ttsSpeed)
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

    /** Route TTS output through the phone call audio stream (caller hears it). */
    fun enterCallMode(context: Context, settings: AppSettings) {
        init(context)
        callMode = true
        val engine = tts ?: return
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
        val engine = tts
        if (!ready || engine == null || text.isBlank()) return false
        return try {
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
            speechQueue.close()
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
