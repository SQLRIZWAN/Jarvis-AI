package com.sqlai.assistant.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.sqlai.assistant.core.LogBus
import com.sqlai.assistant.core.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

        if (sbn.packageName == context.packageName) return

        // WhatsApp / messenger auto-reply
        com.sqlai.assistant.engine.AutoReplyEngine.onMessage(
            pkg = sbn.packageName,
            title = title,
            message = text
        )

        // WhatsApp incoming call -> tap Answer through accessibility
        handlePossibleCall(sbn, notification, title)
    }

    private fun handlePossibleCall(
        sbn: StatusBarNotification,
        notification: android.app.Notification,
        title: String
    ) {
        val pkg = sbn.packageName
        val isCallCategory = notification.category == android.app.Notification.CATEGORY_CALL
        val looksLikeCall = isCallCategory ||
            title.contains("Incoming call", ignoreCase = true) ||
            title.contains("incoming voice", ignoreCase = true) ||
            title.contains("incoming video", ignoreCase = true) ||
            title.contains("is calling", ignoreCase = true)
        if (!looksLikeCall) return

        val isMessengerCall = pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b" ||
            pkg == "org.telegram.messenger"
        if (!isMessengerCall) return

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = com.sqlai.assistant.SqlAiApp.settings.settings.first()
                if (!settings.callAssistantEnabled) return@launch
                LogBus.log("Messenger call detected in $pkg - attempting auto-answer", LogLevel.SUCCESS)
                val pkgResolved = com.sqlai.assistant.device.DeviceController.resolvePackage(pkg)
                if (pkgResolved == null) return@launch
                com.sqlai.assistant.device.DeviceController.launchPackage(pkgResolved)
                delay(2200)
                val svc = instance ?: return@launch
                val answered = svc.clickText("Answer") ||
                    svc.clickText("Accept") ||
                    svc.clickText("answer") ||
                    svc.clickText("जवाब दें")
                LogBus.log(
                    if (answered) "Messenger call answered" else "Answer button not found",
                    if (answered) LogLevel.SUCCESS else LogLevel.WARN
                )
            } catch (e: Exception) {
                LogBus.log("Messenger call answer failed: ${e.message}", LogLevel.ERROR)
            }
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
