package com.sqlai.assistant

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.sqlai.assistant.core.SettingsRepository

class SqlAiApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = SettingsRepository(this)
        createNotificationChannels()
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

        lateinit var instance: SqlAiApp
            private set
        lateinit var settings: SettingsRepository
            private set
    }
}
