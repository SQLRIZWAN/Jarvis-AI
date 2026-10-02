package com.sqlai.assistant

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentCallbacks2
import com.sqlai.assistant.ai.GeminiLiveAudioEngine
import com.sqlai.assistant.ai.GeminiMaleVoiceStreamer
import com.sqlai.assistant.core.AppCrashHandler
import com.sqlai.assistant.core.LogLevel
import com.sqlai.assistant.core.LogBus
import com.sqlai.assistant.core.SettingsRepository

/**
 * v6.0 Application. Owns process-level init (crash handler first, then state),
 * notification channels and the last-resort engine shutdown.
 *
 * [SqlAiApp] remains as a thin compatibility facade so the 20+ files that
 * reference `SqlAiApp.instance` / `SqlAiApp.settings` keep working unchanged.
 */
class SQLApplicationV6 : Application() {

    override fun onCreate() {
        super.onCreate()
        // Must be first: everything after it is now crash-logged to disk.
        AppCrashHandler.install(this)
        instance = this
        settings = SettingsRepository(this)
        createNotificationChannels()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Background + dying: drop live sockets/threads so nothing leaks.
        // (Foreground call flows only ever deliver level 10/15.)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            shutdownEngines()
        }
    }

    private fun shutdownEngines() {
        try {
            GeminiLiveAudioEngine.shutdown()
        } catch (t: Throwable) {
            LogBus.log("Live engine shutdown failed: ${t.message}", LogLevel.WARN)
        }
        try {
            GeminiMaleVoiceStreamer.shutdown()
        } catch (t: Throwable) {
            LogBus.log("Voice engine shutdown failed: ${t.message}", LogLevel.WARN)
        }
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SERVICE,
                getString(R.string.notification_channel_service),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_service_text)
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                getString(R.string.notification_channel_alerts),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
    }

    companion object {
        const val CHANNEL_SERVICE = "sql_ai_service"
        const val CHANNEL_ALERTS = "sql_ai_alerts"

        lateinit var instance: SQLApplicationV6
            private set
        lateinit var settings: SettingsRepository
            private set
    }
}
