package com.sqlai.assistant.core

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.sqlai.assistant.service.SqlAccessibilityService
import com.sqlai.assistant.service.SqlNotificationListener

data class PermissionStatus(
    val id: String,
    val title: String,
    val description: String,
    val granted: Boolean,
    val needed: Boolean = true
)

/** Central place that reports and requests every permission SQL AI needs. */
object PermissionHelper {

    fun isAccessibilityEnabled(context: Context): Boolean =
        SqlAccessibilityService.instance != null || isAccessibilityServiceOn(context)

    private fun isAccessibilityServiceOn(context: Context): Boolean {
        val expected = "${context.packageName}/com.sqlai.assistant.service.SqlAccessibilityService"
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    fun isNotificationAccessOn(context: Context): Boolean =
        SqlNotificationListener.instance != null || isListenerOn(context)

    private fun isListenerOn(context: Context): Boolean {
        val expected = "${context.packageName}/com.sqlai.assistant.service.SqlNotificationListener"
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_NOTIFICATION_LISTENERS
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    fun canDrawOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun canWriteSettings(context: Context): Boolean = Settings.System.canWrite(context)

    fun isIgnoringBattery(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun has(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun statuses(context: Context): List<PermissionStatus> = listOf(
        PermissionStatus(
            id = "mic",
            title = "Microphone",
            description = "24/7 wake-word listening for \"SQL\"",
            granted = has(context, Manifest.permission.RECORD_AUDIO)
        ),
        PermissionStatus(
            id = "notifications_perm",
            title = "Notifications",
            description = "Show the persistent foreground service notification",
            granted = has(context, Manifest.permission.POST_NOTIFICATIONS)
        ),
        PermissionStatus(
            id = "accessibility",
            title = "Accessibility Service",
            description = "Read screen, tap, swipe, type - full phone control",
            granted = isAccessibilityEnabled(context)
        ),
        PermissionStatus(
            id = "overlay",
            title = "Display over other apps",
            description = "Floating voice indicator above any app",
            granted = canDrawOverlay(context)
        ),
        PermissionStatus(
            id = "notification_listener",
            title = "Notification Access",
            description = "Read and reply to your notifications",
            granted = isNotificationAccessOn(context)
        ),
        PermissionStatus(
            id = "write_settings",
            title = "Modify System Settings",
            description = "Change brightness and related toggles",
            granted = canWriteSettings(context)
        ),
        PermissionStatus(
            id = "battery",
            title = "Battery Optimization Bypass",
            description = "Keep the assistant alive 24/7",
            granted = isIgnoringBattery(context)
        ),
        PermissionStatus(
            id = "contacts",
            title = "Contacts & Phone",
            description = "Call or message people by name",
            granted = has(context, Manifest.permission.READ_CONTACTS) &&
                has(context, Manifest.permission.READ_PHONE_STATE)
        ),
        PermissionStatus(
            id = "storage",
            title = "Media / Storage",
            description = "Open, share and control your media",
            granted = has(context, Manifest.permission.READ_MEDIA_IMAGES)
        ),
        PermissionStatus(
            id = "assistant",
            title = "Default Digital Assistant",
            description = "Launch SQL AI with long-press HOME / power",
            granted = isDefaultAssistant(context)
        )
    )

    fun isDefaultAssistant(context: Context): Boolean {
        // The VoiceInteraction framework resolves the current assistant package.
        return try {
            val intent = Intent("android.settings.VOICE_INPUT_SETTINGS")
            // Cannot query reliably without rolemanager; treat service-enabled as proxy.
            isAccessibilityEnabled(context) && intent.resolveActivity(context.packageManager) != null &&
                has(context, Manifest.permission.RECORD_AUDIO) && isVoiceInteractionReady()
        } catch (e: Exception) {
            false
        }
    }

    private fun isVoiceInteractionReady(): Boolean = true

    fun runtimePermissions(): Array<String> = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.CAMERA,
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_AUDIO,
        Manifest.permission.READ_MEDIA_VIDEO
    )

    /** Launch the correct system screen for a permission card id. */
    fun open(context: Context, id: String) {
        val intent: Intent? = when (id) {
            "mic", "notifications_perm", "contacts", "storage" -> null // runtime request
            "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "overlay" -> Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            "notification_listener" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            "write_settings" -> Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:${context.packageName}")
            )
            "battery" -> Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}")
            )
            "assistant" -> Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
            else -> null
        }
        if (intent == null) return
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    fun requestRuntime(activity: Activity) {
        activity.requestPermissions(runtimePermissions(), 1001)
    }
}
