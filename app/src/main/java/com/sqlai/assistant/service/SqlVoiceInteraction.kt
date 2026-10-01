package com.sqlai.assistant.service

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.sqlai.assistant.MainActivity
import com.sqlai.assistant.core.LogBus
import com.sqlai.assistant.core.LogLevel

/**
 * System entry point that lets users pick SQL AI as their
 * Default Digital Assistant app (Settings > Default apps > Assistant).
 */
class SqlVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        LogBus.log("Voice interaction framework ready", LogLevel.SUCCESS)
    }
}

/** Hosts the session object the framework talks to. */
class SqlVoiceInteractionSessionService : VoiceInteractionSessionService() {

    override fun onGetSession(): VoiceInteractionSession =
        SqlVoiceInteractionSession(this)
}

/**
 * Receives HOME long-press / power-assist events and hands them to the
 * always-on listening pipeline as an immediate command capture.
 */
class SqlVoiceInteractionSession(context: android.content.Context) :
    VoiceInteractionSession(context) {

    private val appContext: android.content.Context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    override fun onShow(args: Bundle?) {
        super.onShow(args)
        LogBus.log("Default assistant triggered (HOME / power)", LogLevel.SUCCESS)
        ListeningService.trigger(appContext)

        // Surface the app so the user gets visible feedback if overlay
        // permission was not granted yet.
        handler.postDelayed({
            try {
                val intent = Intent(appContext, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                }
                appContext.startActivity(intent)
            } catch (e: Exception) {
                LogBus.log("Assist UI failed: ${e.message}", LogLevel.WARN)
            }
            finish()
        }, 900)
    }

    @Deprecated("Deprecated in Java")
    override fun onHandleAssist(data: AssistData?) {
        super.onHandleAssist(data)
        val query = try {
            data?.data?.getString("query")
                ?: data?.data?.getString("voice_interaction_query")
        } catch (e: Exception) {
            null
        }
        if (!query.isNullOrBlank()) {
            ListeningService.runCommand(appContext, query)
        }
    }

    override fun onCommand(command: Bundle?) {
        val text = command?.getString("android.voice_interaction.extra.VOICE_COMMAND")
            ?: command?.getStringArrayList("android.speech.extra.RESULTS")?.firstOrNull()
        if (!text.isNullOrBlank()) {
            ListeningService.runCommand(appContext, text)
        } else {
            ListeningService.trigger(appContext)
        }
        finish()
    }

    override fun onBackPressed(): Boolean {
        finish()
        return true
    }
}
