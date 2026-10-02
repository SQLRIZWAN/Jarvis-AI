package com.sqlai.assistant.engine

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import com.sqlai.assistant.SqlAiApp
import com.sqlai.assistant.core.AppSettings
import com.sqlai.assistant.core.LogBus
import com.sqlai.assistant.core.LogLevel
import com.sqlai.assistant.core.VoiceGender
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
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

    fun init(context: Context) {
        if (tts != null) return
        synchronized(this) {
            if (tts != null) return
            tts = TextToSpeech(context.applicationContext) { status ->
                val engine = tts ?: return@TextToSpeech
                if (status == TextToSpeech.SUCCESS) {
                    engine.setOnUtteranceProgressListener(object : TextToSpeech.UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit

                        override fun onDone(utteranceId: String?) {
                            utteranceId?.let { pendingUtterances.remove(it)?.invoke(true) }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            utteranceId?.let { pendingUtterances.remove(it)?.invoke(false) }
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            utteranceId?.let { pendingUtterances.remove(it)?.invoke(false) }
                        }
                    })
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
        val settings = SqlAiApp.settings.settings.first()
        if (!settings.ttsEnabled || text.isBlank()) return
        val engine = tts
        if (!ready || engine == null) {
            LogBus.log("TTS said: $text")
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
            engine.pitch = (settings.pitch * genderFactor).coerceIn(0.5f, 2.0f)
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
