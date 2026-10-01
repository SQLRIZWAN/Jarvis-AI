package com.sqlai.assistant.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.sqlai.assistant.core.LogBus
import com.sqlai.assistant.core.LogLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Grants SQL AI the ability to read (and later reply to) your notifications. */
class SqlNotificationListener : NotificationListenerService() {

    companion object {
        @Volatile
        var instance: SqlNotificationListener? = null
            private set

        private const val MAX_KEPT = 40
    }

    private val lock = Any()
    private val recent = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.US)

    override fun onListenerConnected() {
        instance = this
        LogBus.log("Notification access connected", LogLevel.SUCCESS)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val notification = sbn.notification ?: return
        val extras = notification.extras ?: return
        val title = extras.getCharSequence("android.title")?.toString() ?: sbn.packageName
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        val time = timeFormat.format(Date(sbn.postTime))
        val line = "[$time] $title: $text"
        synchronized(lock) {
            recent.addLast(line)
            while (recent.size > MAX_KEPT) recent.removeFirst()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun snapshot(limit: Int = 15): String = synchronized(lock) {
        if (recent.isEmpty()) "No recent notifications."
        else recent.takeLast(limit).joinToString("\n")
    }

    fun count(): Int = synchronized(lock) { recent.size }
}
