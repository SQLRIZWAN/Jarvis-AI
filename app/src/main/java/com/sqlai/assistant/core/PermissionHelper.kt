package com.sqlai.assistant.core

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
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
            "enabled_notification_listeners"
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

    // ------------------------------------------------------------ storage

    /**
     * Storage access that works on every Android version:
     *  - Android 13+ : READ_MEDIA_IMAGES / AUDIO / VIDEO
     *  - Android 10-12: READ_EXTERNAL_STORAGE (scoped fallback)
     *  - Optional full file access via MANAGE_EXTERNAL_STORAGE
     */
    fun hasStorage(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            has(context, Manifest.permission.READ_MEDIA_IMAGES) ||
                has(context, Manifest.permission.READ_MEDIA_AUDIO) ||
                has(context, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            has(context, Manifest.permission.READ_EXTERNAL_STORAGE) ||
                Environment.isExternalStorageManager()
        }
    }

    fun hasManageAllFiles(): Boolean = Environment.isExternalStorageManager()

    // ------------------------------------------------------------- phone

    fun hasPhoneAccess(context: Context): Boolean =
        has(context, Manifest.permission.READ_PHONE_STATE) &&
            has(context, Manifest.permission.CALL_PHONE)

    // -------------------------------------------------------- statuses

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
            description = "Read messages + auto-reply, WhatsApp call detection",
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
            id = "phone",
            title = "Phone & Calls",
            description = "Auto-receive calls, speak live on call, place calls",
            granted = hasPhoneAccess(context) &&
                has(context, Manifest.permission.ANSWER_PHONE_CALLS)
        ),
        PermissionStatus(
            id = "contacts",
            title = "Contacts",
            description = "Call or message people by name",
            granted = has(context, Manifest.permission.READ_CONTACTS)
        ),
        PermissionStatus(
            id = "storage",
            title = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                "Photos, Video & Audio (Android 13+)"
            else "Storage (All files access)",
            description = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                "READ_MEDIA_IMAGES / AUDIO / VIDEO access"
            else "Legacy storage + optional All Files permission",
            granted = hasStorage(context)
        ),
        PermissionStatus(
            id = "assistant",
            title = "Default Digital Assistant",
            description = "Launch SQL AI with long-press HOME / power",
            granted = isDefaultAssistant(context)
        )
    )

    fun isDefaultAssistant(context: Context): Boolean {
        return try {
            val intent = Intent("android.settings.VOICE_INPUT_SETTINGS")
            isAccessibilityEnabled(context) &&
                intent.resolveActivity(context.packageManager) != null &&
                has(context, Manifest.permission.RECORD_AUDIO)
        } catch (e: Exception) {
            false
        }
    }

    // ------------------------------------------------- runtime requests

    /** Version-correct runtime permission set (fixes Android 13 storage denials). */
    fun runtimePermissions(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.CAMERA,
            Manifest.permission.ANSWER_PHONE_CALLS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Manifest.permission.READ_MEDIA_IMAGES
            list += Manifest.permission.READ_MEDIA_AUDIO
            list += Manifest.permission.READ_MEDIA_VIDEO
        } else {
            list += Manifest.permission.READ_EXTERNAL_STORAGE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                list += Manifest.permission.WRITE_EXTERNAL_STORAGE
            }
        }
        return list.toTypedArray()
    }

    /** Launch the correct system screen for a permission card id. */
    fun open(context: Context, id: String) {
        val intent: Intent? = when (id) {
            "mic", "notifications_perm", "contacts", "storage" -> storageOrRuntimeIntent(context, id)
            "phone" -> null // runtime
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

    private fun storageOrRuntimeIntent(context: Context, id: String): Intent? {
        if (id != "storage") return null
        // Android 11+ fallback page for "All files access" when media perms are not enough.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasManageAllFiles()) {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
        } else null
    }

    fun requestRuntime(activity: Activity) {
        activity.requestPermissions(runtimePermissions(), 1001)
    }
}
