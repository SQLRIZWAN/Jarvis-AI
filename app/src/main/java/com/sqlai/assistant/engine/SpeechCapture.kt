package com.sqlai.assistant.engine

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * One-shot speech recognition - used by the live call assistant to hear the
 * caller (best effort: some OEMs lock the mic during cellular calls).
 */
object SpeechCapture {

    private val mainHandler = Handler(Looper.getMainLooper())

    suspend fun listenOnce(
        context: Context,
        languageTag: String,
        timeoutMs: Long = 12000
    ): String? {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return null

        return suspendCancellableCoroutine { continuation ->
            val appContext = context.applicationContext
            val resumed = AtomicBoolean(false)
            var recognizer: SpeechRecognizer? = null

            fun finish(result: String?) {
                if (resumed.compareAndSet(false, true)) {
                    try {
                        recognizer?.cancel()
                        recognizer?.destroy()
                    } catch (e: Exception) {
                        // ignore
                    }
                    continuation.resume(result)
                }
            }

            val timeout = Runnable { finish(null) }

            mainHandler.post {
                try {
                    val stt = SpeechRecognizer.createSpeechRecognizer(appContext)
                    recognizer = stt

                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(
                            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                        )
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
                    }

                    stt.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) = Unit
                        override fun onBeginningOfSpeech() = Unit
                        override fun onRmsChanged(rmsdB: Float) = Unit
                        override fun onBufferReceived(buffer: ByteArray?) = Unit
                        override fun onEndOfSpeech() = Unit

                        override fun onError(error: Int) = finish(null)

                        override fun onResults(results: Bundle?) {
                            val text = results
                                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull()
                                ?.trim()
                            finish(text?.ifBlank { null })
                        }

                        override fun onPartialResults(partialResults: Bundle?) = Unit
                        override fun onEvent(eventType: Int, params: Bundle?) = Unit
                    })

                    mainHandler.postDelayed(timeout, timeoutMs)
                    stt.startListening(intent)
                } catch (e: Exception) {
                    mainHandler.removeCallbacks(timeout)
                    finish(null)
                }
            }

            continuation.invokeOnCancellation {
                mainHandler.removeCallbacks(timeout)
                finish(null)
            }
        }
    }
}
