package com.sqlai.assistant.engine

import com.sqlai.assistant.SqlAiApp
import com.sqlai.assistant.ai.AiClient
import com.sqlai.assistant.ai.ChatMessage
import com.sqlai.assistant.core.AppCrashHandler
import com.sqlai.assistant.core.LogBus
import com.sqlai.assistant.core.LogLevel
import com.sqlai.assistant.device.DeviceController
import com.sqlai.assistant.service.SqlAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Auto-replies to incoming WhatsApp / SMS / Telegram messages.
 *
 * Flow: notification arrives -> short AI reply generated (or local template
 * fallback) -> app is opened -> conversation tapped -> reply typed -> Send.
 * Rate-limited per conversation so it never spams.
 */
object AutoReplyEngine {

    private const val COOLDOWN_MS = 3 * 60_000L

    private val SUPPORTED = setOf(
        "com.whatsapp",
        "com.whatsapp.w4b",
        "com.google.android.apps.messaging",
        "com.android.mms",
        "org.telegram.messenger",
        "org.telegram.messenger.web"
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + AppCrashHandler.coroutineHandler)
    private val lastReplied = ConcurrentHashMap<String, Long>()

    fun onMessage(pkg: String, title: String, message: String) {
        if (pkg !in SUPPORTED) return
        scope.launch { tryHandle(pkg, title, message) }
    }

    private suspend fun tryHandle(pkg: String, title: String, message: String) {
        try {
            val settings = SqlAiApp.settings.settings.first()
            if (!settings.autoReplyEnabled) return
            if (title.contains("SQL AI", ignoreCase = true)) return
            if (looksLikeOtp(message)) {
                LogBus.log("Auto-reply skipped (looks like an OTP)")
                return
            }

            val key = "$pkg|$title"
            val now = System.currentTimeMillis()
            val previous = lastReplied[key] ?: 0L
            if (now - previous < COOLDOWN_MS) return
            lastReplied[key] = now

            LogBus.log("Auto-reply to $title ($pkg)", LogLevel.INFO)
            val replyText = generateReply(settings, title, message)
            sendReply(pkg, title, replyText)
        } catch (e: Exception) {
            LogBus.log("Auto-reply failed: ${e.message}", LogLevel.ERROR)
        }
    }

    private fun looksLikeOtp(message: String): Boolean {
        val upper = message.uppercase()
        if (upper.contains("OTP") || upper.contains("VERIFICATION CODE") ||
            upper.contains("ONE TIME") || upper.contains("验证码")
        ) return true
        // A lone 4-8 digit code is almost certainly a login code.
        return Regex("\\b\\d{4,8}\\b").containsMatchIn(message) && message.length < 40
    }

    private suspend fun generateReply(
        settings: com.sqlai.assistant.core.AppSettings,
        title: String,
        message: String
    ): String {
        return try {
            AiClient.complete(
                settings = settings,
                screenContext = null,
                history = listOf(
                    ChatMessage(
                        "user",
                        "Write ONE short friendly auto-reply (max 15 words, plain text, " +
                            "${settings.language.languageCodeHint()}) for this incoming message. " +
                            "No quotes, no emoji. Message from $title: \"$message\""
                    )
                ),
                imageJpeg = null,
                systemPromptOverride = com.sqlai.assistant.core.CorePromptBuilder.reply(settings)
            ).take(200).replace("\"", "").trim()
        } catch (e: Exception) {
            LogBus.log("AI reply failed, using template: ${e.message}", LogLevel.WARN)
            settings.autoReplyTemplate
        }
    }

    private suspend fun sendReply(pkg: String, title: String, reply: String) {
        val accessibility = SqlAccessibilityService.instance
        if (accessibility == null) {
            LogBus.log("Auto-reply needs the accessibility service ON", LogLevel.WARN)
            return
        }

        val launched = DeviceController.resolvePackage(pkg)?.let { DeviceController.launchPackage(it) } == true
        if (!launched) {
            LogBus.log("Cannot open $pkg for auto-reply", LogLevel.WARN)
            return
        }
        delay(1800)

        // Tap the conversation row (exact title, then looser match).
        var opened = accessibility.clickText(title)
        if (!opened && title.length > 6) opened = accessibility.clickText(title.take(8))
        if (!opened) opened = accessibility.clickText(title.split(" ").first())
        if (!opened) {
            LogBus.log("Conversation \"$title\" not found on screen", LogLevel.WARN)
            return
        }
        delay(900)

        val typed = accessibility.typeText(reply)
        if (!typed) {
            LogBus.log("No message box found for auto-reply", LogLevel.WARN)
            return
        }
        delay(500)

        val sent = accessibility.clickText("Send") ||
            accessibility.clickText("सेंड") ||
            accessibility.pressEnter()
        LogBus.log(
            if (sent) "Auto-reply sent to $title" else "Auto-reply typed but send failed",
            if (sent) LogLevel.SUCCESS else LogLevel.WARN
        )
    }
}

private fun com.sqlai.assistant.core.AssistantLanguage.languageCodeHint(): String = when (code) {
    "hi" -> "Hindi"
    "hinglish" -> "Hinglish (Roman Hindi)"
    else -> "English"
}
