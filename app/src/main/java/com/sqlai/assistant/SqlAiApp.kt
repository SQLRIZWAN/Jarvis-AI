package com.sqlai.assistant

import com.sqlai.assistant.core.SettingsRepository

/**
 * Compatibility facade — the real Application is [SQLApplicationV6].
 * Kept so existing `SqlAiApp.instance` / `SqlAiApp.settings` /
 * `SqlAiApp.CHANNEL_*` references compile without churn.
 */
object SqlAiApp {
    const val CHANNEL_SERVICE = SQLApplicationV6.CHANNEL_SERVICE
    const val CHANNEL_ALERTS = SQLApplicationV6.CHANNEL_ALERTS

    val instance: SQLApplicationV6
        get() = SQLApplicationV6.instance
    val settings: SettingsRepository
        get() = SQLApplicationV6.settings
}
