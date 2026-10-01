package com.sqlai.assistant.service

import android.content.Context
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

    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        SqlVoiceInteractionSession(this)
}

/**
 * Receives HOME long-press / power-assist events and hands them to the
 * always-on listening pipeline as an immediate command capture.
 */
class SqlVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    private val appContext: Context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        LogBus.log("Default assistant triggered (HOME / power)", LogLevel.SUCCESS)

        // Arm the mic loop so the very next utterance becomes the command.
        ListeningService.trigger(appContext)

        // Surface the app so the user gets visible feedback even if the
        // overlay permission has not been granted yet.
        handler.postDelayed(
            {
                try {
                    val intent = Intent(appContext, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    appContext.startActivity(intent)
                } catch (e: Exception) {
                    LogBus.log("Assist UI failed: ${e.message}", LogLevel.WARN)
                }
                finish()
            },
            900
        )
    }

    override fun onBackPressed() {
        finish()
    }
}
