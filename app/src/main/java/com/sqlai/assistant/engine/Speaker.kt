package com.sqlai.assistant.engine

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import com.sqlai.assistant.SqlAiApp
import com.sqlai.assistant.core.LogBus
import com.sqlai.assistant.core.LogLevel
import kotlinx.coroutines.flow.first
import java.util.Locale

/** Thin TextToSpeech wrapper - lazily initialised, settings-aware. */
object Speaker {

    private const val TAG = "SqlAiTts"

    @Volatile
    private var tts: TextToSpeech? = null

    @Volatile
    private var ready = false

    fun init(context: Context) {
        if (tts != null) return
        synchronized(this) {
            if (tts != null) return
            tts = TextToSpeech(context.applicationContext) { status ->
                val engine = tts ?: return@TextToSpeech
                if (status == TextToSpeech.SUCCESS) {
                    val locale = Locale.getDefault()
                    val result = engine.setLanguage(locale)
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        engine.language = Locale.ENGLISH
                    }
                    engine.setSpeechRate(1.0f)
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
            engine.setSpeechRate(settings.ttsSpeed)
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sqlai-${System.currentTimeMillis()}")
        } catch (e: Exception) {
            LogBus.log("TTS error: ${e.message}", LogLevel.WARN)
        }
    }

    fun stop() {
        try {
            tts?.stop()
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
}
