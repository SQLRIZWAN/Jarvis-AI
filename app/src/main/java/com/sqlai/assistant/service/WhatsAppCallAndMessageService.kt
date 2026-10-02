package com.sqlai.assistant.service

import android.app.Notification
import android.util.Log
import android.service.notification.StatusBarNotification
import com.sqlai.assistant.SqlAiApp
import com.sqlai.assistant.ai.GeminiLiveAudioEngine
import com.sqlai.assistant.core.LogBus
import com.sqlai.assistant.core.LogLevel
import com.sqlai.assistant.device.DeviceController
import com.sqlai.assistant.engine.AutoReplyEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * WhatsApp / Telegram call handler + dynamic message reply router.
 *
 * Calls:
 *  - detects incoming messenger audio/video calls from notification payloads
 *    (CATEGORY_CALL or "Incoming call" style titles),
 *  - auto-taps Answer through the accessibility service,
 *  - when Call Assistant is enabled, opens a Gemini Live audio session in
 *    voice-call mode so the assistant actually SPEAKS and LISTENS over the
 *    ongoing call audio (phone speaker / Bluetooth call route),
 *  - closes the live session again when the call notification disappears.
 *
 * Messages:
 *  - every incoming WhatsApp / SMS / Telegram text is routed to
 *    [AutoReplyEngine], which generates the reply dynamically with the
 *    configured Gemini/LLM (no hardcoded answers - template only as offline
 *    fallback).
 */
object WhatsAppCallAndMessageService {

    private const val TAG = "WACallMsg"

    private val CALL_PACKAGES = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "org.telegram.messenger",
        "org.telegram.messenger.web"
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var liveCallSession = false
    @Volatile private var lastCallPkg = ""

    /** Route a posted notification to the call handler or the auto-replier. */
    fun onNotificationPosted(sbn: StatusBarNotification, notification: Notification, title: String, text: String) {
        val pkg = sbn.packageName
        if (pkg in CALL_PACKAGES && looksLikeCall(notification, title)) {
            handleIncomingCall(pkg, title)
            return
        }
        // Regular message -> dynamic AI reply.
        AutoReplyEngine.onMessage(pkg = pkg, title = title, message = text)
    }

    /** The call (or notification) went away - end our live call session. */
    fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName == lastCallPkg && liveCallSession) {
            endLiveSession()
            LogBus.log("Call ended - live voice session closed", LogLevel.INFO)
        }
    }

    private fun looksLikeCall(notification: Notification, title: String): Boolean {
        val categoryCall = notification.category == Notification.CATEGORY_CALL
        return categoryCall ||
            title.contains("Incoming call", ignoreCase = true) ||
            title.contains("incoming voice", ignoreCase = true) ||
            title.contains("incoming video", ignoreCase = true) ||
            title.contains("is calling", ignoreCase = true) ||
            title.contains("WhatsApp call", ignoreCase = true) ||
            title.contains("Telegram call", ignoreCase = true)
    }

    private fun handleIncomingCall(pkg: String, title: String) {
        scope.launch {
            try {
                val settings = SqlAiApp.settings.settings.first()
                if (!settings.callAssistantEnabled) return@launch
                LogBus.log("Messenger call in $pkg - auto-answering", LogLevel.SUCCESS)

                val resolved = DeviceController.resolvePackage(pkg) ?: return@launch
                DeviceController.launchPackage(resolved)
                delay(2200)

                val svc = SqlAccessibilityService.instance ?: return@launch
                val answered = svc.clickText("Answer") ||
                    svc.clickText("Accept") ||
                    svc.clickText("answer") ||
                    svc.clickText("जवाब दें") ||
                    svc.clickText("उत्तर दें")
                LogBus.log(
                    if (answered) "Messenger call answered" else "Answer button not found",
                    if (answered) LogLevel.SUCCESS else LogLevel.WARN
                )
                if (answered) startLiveSession(pkg, title, settings)
            } catch (e: Exception) {
                LogBus.log("Call auto-answer failed: ${e.message}", LogLevel.ERROR)
            }
        }
    }

    /** Open the Gemini Live duplex audio session over the ongoing call. */
    private suspend fun startLiveSession(
        pkg: String,
        title: String,
        settings: com.sqlai.assistant.core.AppSettings
    ) {
        if (liveCallSession) return
        val app = SqlAiApp.instance
        if (com.sqlai.assistant.ai.GeminiLiveAudioEngine.isUsable(settings)) {
            GeminiLiveAudioEngine.enterCallMode(app)
            liveCallSession = true
            lastCallPkg = pkg
            GeminiLiveAudioEngine.startMic()
            delay(500)
            GeminiLiveAudioEngine.speakText(
                settings,
                "Hello, ${title.trim()}! SQL AI is answering this call. " +
                    "Please speak, I am listening."
            )
            LogBus.log("Live voice session active over call", LogLevel.SUCCESS)
        } else {
            LogBus.log(
                "Call answered. Enable Gemini Live voice for spoken replies on calls.",
                LogLevel.INFO
            )
        }
    }

    private fun endLiveSession() {
        if (!liveCallSession) return
        liveCallSession = false
        try {
            GeminiLiveAudioEngine.exitCallMode(SqlAiApp.instance)
        } catch (e: Exception) {
            Log.w(TAG, "exit failed", e)
        }
        lastCallPkg = ""
    }

    fun hasActiveCallSession(): Boolean = liveCallSession

    fun shutdown() = endLiveSession()
}
