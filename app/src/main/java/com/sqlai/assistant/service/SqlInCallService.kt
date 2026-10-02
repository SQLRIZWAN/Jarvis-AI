package com.sqlai.assistant.service

import android.telecom.Call
import android.telecom.InCallService
import android.telecom.VideoProfile
import com.sqlai.assistant.SqlAiApp
import com.sqlai.assistant.ai.AiClient
import com.sqlai.assistant.ai.ChatMessage
import com.sqlai.assistant.core.LogBus
import com.sqlai.assistant.core.LogLevel
import com.sqlai.assistant.core.StateBus
import com.sqlai.assistant.engine.Speaker
import com.sqlai.assistant.engine.SpeechCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Live call assistant.
 *
 * When the Call Assistant toggle is ON:
 *   incoming call -> auto answer -> TTS greeting on the call stream
 *   -> listen (SpeechRecognizer) -> short AI reply -> loop a few turns.
 *
 * Registered in the manifest as an InCallService, so the Telecom framework
 * binds it automatically for every incoming call.
 */
class SqlInCallService : InCallService() {

    companion object {
        @Volatile
        var instance: SqlInCallService? = null
            private set

        fun answerActiveCall(): Boolean =
            instance?.activeCall?.let { call ->
                try {
                    if (call.state == Call.STATE_RINGING) {
                        call.answer(VideoProfile.STATE_AUDIO)
                        true
                    } else false
                } catch (e: Exception) {
                    false
                }
            } == true

        fun endActiveCall(): Boolean =
            instance?.activeCall?.let { call ->
                try {
                    call.disconnect()
                    true
                } catch (e: Exception) {
                    false
                }
            } == true
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var sessionJob: Job? = null

    @Volatile
    var activeCall: Call? = null
        private set

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            if (state == Call.STATE_DISCONNECTED || state == Call.STATE_DISCONNECTING) {
                stopSession()
            }
        }
    }

    fun isInCall(): Boolean = activeCall?.state == Call.STATE_ACTIVE

    override fun onCreate() {
        super.onCreate()
        instance = this
        LogBus.log("InCallService bound", LogLevel.INFO)
    }

    override fun onDestroy() {
        stopSession()
        if (instance === this) instance = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        activeCall = call
        try {
            call.registerCallback(callCallback)
        } catch (e: Exception) {
            LogBus.log("Call callback failed: ${e.message}", LogLevel.WARN)
        }
        scope.launch { handleCall(call) }
    }

    override fun onCallRemoved(call: Call) {
        try {
            call.unregisterCallback(callCallback)
        } catch (e: Exception) {
            // ignore
        }
        if (activeCall === call) {
            activeCall = null
            stopSession()
        }
    }

    private suspend fun handleCall(call: Call) {
        val settings = SqlAiApp.settings.settings.first()
        if (!settings.callAssistantEnabled) {
            LogBus.log("Incoming call ignored (Call Assistant OFF)", LogLevel.INFO)
            return
        }

        when (call.state) {
            Call.STATE_RINGING -> {
                try {
                    call.answer(VideoProfile.STATE_AUDIO)
                    LogBus.log("Call auto-answered", LogLevel.SUCCESS)
                } catch (e: Exception) {
                    LogBus.log("Auto-answer failed: ${e.message}", LogLevel.ERROR)
                    return
                }
                delay(1600)
                startLiveSession(call)
            }

            Call.STATE_ACTIVE -> startLiveSession(call)

            else -> Unit
        }
    }

    private fun startLiveSession(call: Call) {
        sessionJob?.cancel()
        sessionJob = scope.launch {
            val settings = SqlAiApp.settings.settings.first()
            Speaker.init(this@SqlInCallService)
            Speaker.enterCallMode(this@SqlInCallService, settings)

            val greeting = if (settings.language.code == "en") {
                "Hello, this is SQL AI speaking on behalf of the user. How can I help?"
            } else {
                "Namaste, main SQL AI bol rahi hoon. Bataiye, main kya madad kar sakti hoon?"
            }

            var turns = 0
            var silentTurns = 0

            while (isActive && call.state == Call.STATE_ACTIVE && turns < 8 && silentTurns < 2) {
                Speaker.speakOnCall(greeting.takeIf { turns == 0 }
                    ?: "Ji, boliye - main sun rahi hoon.", settings)
                delay(1200)

                val heard = SpeechCapture.listenOnce(
                    this@SqlInCallService,
                    settings.language.sttTag,
                    timeoutMs = 11000
                )

                if (heard.isNullOrBlank()) {
                    silentTurns++
                    LogBus.log("Call: nothing heard (turn ${turns + 1})", LogLevel.WARN)
                    continue
                }
                silentTurns = 0
                LogBus.log("Caller said: \"$heard\"", LogLevel.SUCCESS)
                StateBus.setCommand(heard)

                val reply = generateCallReply(settings, heard)
                Speaker.speakOnCall(reply, settings)
                turns++
            }

            LogBus.log("Call session finished (turns=$turns)", LogLevel.INFO)
            Speaker.exitCallMode()
        }
    }

    private suspend fun generateCallReply(
        settings: com.sqlai.assistant.core.AppSettings,
        heard: String
    ): String {
        val systemPrompt = buildString {
            append("You are SQL AI, speaking LIVE on the user's phone call. ")
            append("Answer the caller naturally, warmly and briefly - max 25 words, spoken language only. ")
            append("No JSON, no symbols, no emoji. ")
            append(settings.languageInstruction())
        }
        return try {
            AiClient.complete(
                settings = settings,
                screenContext = null,
                history = listOf(ChatMessage("user", "The caller said: \"$heard\". Reply as the assistant.")),
                imageJpeg = null,
                systemPromptOverride = systemPrompt
            ).take(400)
        } catch (e: Exception) {
            LogBus.log("Call AI failed: ${e.message}", LogLevel.WARN)
            if (settings.language.code == "en") "I understand, please continue."
            else "Ji bilkul, samajh gayi. Please boliye."
        }
    }

    private fun stopSession() {
        sessionJob?.cancel()
        sessionJob = null
        Speaker.exitCallMode()
    }
}
