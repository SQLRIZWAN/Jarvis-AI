package com.sqlai.assistant.device

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import com.sqlai.assistant.SqlAiApp
import com.sqlai.assistant.ai.Action
import com.sqlai.assistant.core.LogBus
import com.sqlai.assistant.core.LogLevel
import com.sqlai.assistant.ai.GeminiLiveAudioEngine
import com.sqlai.assistant.core.AudioManagerController
import com.sqlai.assistant.engine.Speaker
import com.sqlai.assistant.service.SqlAccessibilityService
import com.sqlai.assistant.service.WhatsAppCallAutomationHandler
import com.sqlai.assistant.service.SqlNotificationListener
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * Executes the AI's action plan: launches apps, drives the screen through the
 * accessibility service and flips system toggles.
 */
object DeviceController {

    private val app: Context get() = SqlAiApp.instance

    suspend fun execute(actions: List<Action>) {
        if (actions.isEmpty()) {
            LogBus.log("No device actions needed", LogLevel.INFO)
            return
        }
        actions.forEachIndexed { index, action ->
            try {
                LogBus.log("Action ${index + 1}/${actions.size}: ${describe(action)}")
                perform(action)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 5s step timeout / agent stop - abort the WHOLE batch at once,
                // never swallow the cancel (it would keep firing actions).
                throw e
            } catch (e: Exception) {
                LogBus.log("Action failed (${action.type}): ${e.message}", LogLevel.ERROR)
            }
        }
    }

    private fun describe(action: Action): String = when (action.type) {
        "open_app", "close_app" -> "${action.type} ${action.app}"
        "tap_text", "type_text" -> "${action.type} \"${action.text}\""
        "tap" -> "tap (${action.x},${action.y})"
        "swipe" -> "swipe (${action.x1()}-${action.x2()})"
        "set_volume" -> "volume=${action.value}"
        "set_brightness" -> "brightness=${action.value}"
        "toggle_flashlight" -> "flashlight=${action.on}"
        "open_settings" -> "settings:${action.item}"
        "wait" -> "wait ${action.ms}ms"
        "wa_call" -> "wa_call ${action.text} -> ${action.message ?: "(no message)"}"
        "speak" -> "speak \"${action.text}\""
        else -> action.type
    }

    private suspend fun perform(action: Action) {
        val accessibility = SqlAccessibilityService.instance
        when (action.type) {

            "open_app" -> {
                val target = action.app.orEmpty()
                val pkg = resolvePackage(target)
                if (pkg == null) {
                    LogBus.log("App not found: \"$target\"", LogLevel.WARN)
                } else {
                    launchPackage(pkg)
                    delay(700)
                }
            }

            "close_app" -> {
                val pkg = resolvePackage(action.app.orEmpty())
                if (pkg != null) {
                    val activityManager =
                        app.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                    activityManager.killBackgroundProcesses(pkg)
                    LogBus.log("Closed $pkg", LogLevel.SUCCESS)
                }
            }

            "tap_text" -> {
                val ok = accessibility?.clickText(action.text.orEmpty()) == true
                if (!ok) LogBus.log("Could not find \"${action.text}\" on screen", LogLevel.WARN)
                delay(400)
            }

            "tap" -> {
                val x = action.x
                val y = action.y
                if (x != null && y != null) {
                    // Visual-grounding path: exact pixel dispatch, clamped to
                    // the real display so vision coords can never miss screen.
                    CoordinateGestureExecutor.tap(x, y)
                }
                delay(300)
            }

            "swipe" -> {
                val x1 = action.x1(); val y1 = action.y1()
                val x2 = action.x2(); val y2 = action.y2()
                if (x1 != null && y1 != null && x2 != null && y2 != null) {
                    CoordinateGestureExecutor.swipe(x1, y1, x2, y2, action.durationMs ?: 300)
                }
                delay(300)
            }

            "scroll" -> {
                accessibility?.scroll(action.direction ?: "down")
                delay(350)
            }

            "type_text" -> {
                val text = action.text.orEmpty()
                val ok = accessibility?.typeText(text) == true
                if (ok) delay(250) else LogBus.log("No focused input for typing", LogLevel.WARN)
            }

            "press_key" -> when (action.key?.lowercase()) {
                "back" -> accessibility?.globalBack()
                "home" -> accessibility?.globalHome()
                "recents" -> accessibility?.globalRecents()
                "notifications" -> accessibility?.globalNotifications()
                "quick_settings" -> accessibility?.globalQuickSettings()
                "enter" -> accessibility?.pressEnter()
                else -> accessibility?.globalBack()
            }.also { delay(350) }

            "set_volume" -> setVolume(action.value ?: 7)

            "volume_up" -> adjustVolume(AudioManager.ADJUST_RAISE)

            "volume_down" -> adjustVolume(AudioManager.ADJUST_LOWER)

            "set_brightness" -> setBrightness(action.value ?: 128)

            "toggle_flashlight" -> setFlashlight(action.on ?: true)

            "toggle_wifi" -> openPanel(Settings.Panel.ACTION_WIFI)

            "toggle_bluetooth" -> toggleBluetooth()

            "open_settings" -> openSettingsItem(action.item)

            "read_screen" -> {
                val dump = accessibility?.captureScreenText(30) ?: "Screen unavailable"
                LogBus.log("Screen: $dump")
            }

            "read_notifications" -> {
                val listener = SqlNotificationListener.instance
                val count = listener?.count() ?: 0
                val snapshot = listener?.snapshot(8) ?: "Notification access is off"
                LogBus.log("Notifications ($count): $snapshot")
            }

            "wait" -> delay((action.ms ?: 500).coerceIn(0, 15000).toLong())

            "wait_for" -> {
                val target = action.text.orEmpty()
                val timeout = (action.ms ?: 5000).coerceIn(500, 20000).toLong()
                val found = waitForText(target, timeout)
                LogBus.log(
                    if (found) "\"$target\" appeared" else "Timeout waiting for \"$target\"",
                    if (found) LogLevel.SUCCESS else LogLevel.WARN
                )
            }

            "call" -> placeCall(action.text ?: action.app.orEmpty())

            "end_call" -> endCall()

            "answer_call" -> answerCall()

            "wa_call" -> {
                // WhatsApp voice call via vision workflow - the handler talks
                // progress + the spoken message over the call, non-blocking.
                WhatsAppCallAutomationHandler.placeCall(
                    contact = action.text ?: action.app.orEmpty(),
                    spokenMessage = action.message
                )
            }

            "speak" -> {
                val spoken = action.text.orEmpty()
                if (spoken.isNotBlank()) {
                    if (AudioManagerController.isCallMode()) {
                        // Live over the active call stream (user's voice cfg).
                        val liveSettings = try {
                            SqlAiApp.settings.settings.first()
                        } catch (e: Exception) {
                            com.sqlai.assistant.core.AppSettings()
                        }
                        GeminiLiveAudioEngine.speakText(liveSettings, spoken)
                    } else {
                        // Fire-and-forget: never blocks the Loop B executor.
                        Speaker.post(spoken)
                    }
                }
            }

            else -> LogBus.log("Unknown action type: ${action.type}", LogLevel.WARN)
        }
    }

    /** Poll the screen until [text] shows up or the timeout expires. */
    private suspend fun waitForText(text: String, timeoutMs: Long): Boolean {
        if (text.isBlank()) return false
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val dump = SqlAccessibilityService.instance?.captureScreenText(80).orEmpty()
            if (dump.contains(text, ignoreCase = true)) return true
            delay(600)
        }
        return false
    }

    private fun placeCall(target: String) {
        if (target.isBlank()) {
            LogBus.log("No number/name given for call", LogLevel.WARN)
            return
        }
        val number = target.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
        val uri = if (number.length >= 3) {
            android.net.Uri.parse("tel:$number")
        } else {
            // Resolve a contact by display name.
            val contactUri = queryContactNumber(target) ?: run {
                LogBus.log("Contact not found: $target", LogLevel.WARN)
                return
            }
            android.net.Uri.parse("tel:$contactUri")
        }
        try {
            app.startActivity(
                Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            LogBus.log("Dialing $target", LogLevel.SUCCESS)
        } catch (e: Exception) {
            LogBus.log("Call failed: ${e.message}", LogLevel.ERROR)
        }
    }

    private fun queryContactNumber(name: String): String? {
        return try {
            val projection = arrayOf(
                android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
                android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            )
            val cursor = app.contentResolver.query(
                android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null, null, null
            )
            cursor?.use {
                val nameIdx = it.getColumnIndexOrThrow(
                    android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                )
                val numIdx = it.getColumnIndexOrThrow(
                    android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER
                )
                while (it.moveToNext()) {
                    val display = it.getString(nameIdx) ?: ""
                    if (display.contains(name, ignoreCase = true)) {
                        return it.getString(numIdx)?.replace(Regex("[^0-9+]"), "")
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun answerCall() {
        com.sqlai.assistant.service.CallBridge.answer(app)
    }

    private fun endCall() {
        com.sqlai.assistant.service.CallBridge.endCall(app)
    }

    // ------------------------------------------------------------- app control

    /** Resolve a spoken app name ("whatsapp") to a launchable package. */
    fun resolvePackage(name: String): String? {
        val pm = app.packageManager
        val needle = name.trim().lowercase().removeSuffix(" app")
        if (needle.isEmpty()) return null

        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launchables = try {
            pm.queryIntentActivities(launcherIntent, 0)
        } catch (e: Exception) {
            emptyList()
        }

        // 1. exact package name
        launchables.firstOrNull { it.activityInfo.packageName.equals(needle, true) }
            ?.let { return it.activityInfo.packageName }

        // 2. exact label ("whatsapp" == "WhatsApp")
        launchables.firstOrNull {
            it.loadLabel(pm).toString().trim().lowercase() == needle
        }?.let { return it.activityInfo.packageName }

        // 3. label contains the needle (handles "insta" -> Instagram)
        launchables.firstOrNull {
            it.loadLabel(pm).toString().trim().lowercase().contains(needle)
        }?.let { return it.activityInfo.packageName }

        // 4. any installed app (needs QUERY_ALL_PACKAGES)
        val installed = try {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        } catch (e: Exception) {
            emptyList()
        }
        installed.firstOrNull {
            it.loadLabel(pm).toString().trim().lowercase().contains(needle)
        }?.let { return it.packageName }

        return null
    }

    fun launchPackage(pkg: String): Boolean {
        return try {
            val intent = app.packageManager.getLaunchIntentForPackage(pkg)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            if (intent == null) {
                LogBus.log("No launch intent for $pkg", LogLevel.WARN)
                false
            } else {
                app.startActivity(intent)
                true
            }
        } catch (e: Exception) {
            LogBus.log("Launch failed: ${e.message}", LogLevel.ERROR)
            false
        }
    }

    // --------------------------------------------------------- system controls

    private fun setVolume(level: Int) {
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, level.coerceIn(0, max), 0)
        LogBus.log("Volume set to ${level.coerceIn(0, max)}/$max", LogLevel.SUCCESS)
    }

    private fun adjustVolume(direction: Int) {
        val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        LogBus.log("Volume $current/$max", LogLevel.SUCCESS)
    }

    private fun setBrightness(value: Int) {
        if (!Settings.System.canWrite(app)) {
            LogBus.log("Modify-System-Settings permission missing", LogLevel.WARN)
            PermissionShim.openWriteSettings(app)
            return
        }
        val resolver = app.contentResolver
        Settings.System.putInt(
            resolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
        )
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, value.coerceIn(0, 255))
        LogBus.log("Brightness set to ${value.coerceIn(0, 255)}", LogLevel.SUCCESS)
    }

    private fun setFlashlight(on: Boolean) {
        try {
            val camera = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = camera.cameraIdList.firstOrNull { id ->
                camera.getCameraCharacteristics(id)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return
            camera.setTorchMode(cameraId, on)
            LogBus.log(if (on) "Flashlight ON" else "Flashlight OFF", LogLevel.SUCCESS)
        } catch (e: Exception) {
            LogBus.log("Flashlight unavailable: ${e.message}", LogLevel.WARN)
        }
    }

    private fun openPanel(action: String) {
        try {
            app.startActivity(
                Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            LogBus.log("Opened system panel", LogLevel.SUCCESS)
        } catch (e: Exception) {
            LogBus.log("Panel unavailable, opening Settings", LogLevel.WARN)
            openSettingsItem("wifi")
        }
    }

    private fun toggleBluetooth() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Direct toggling is restricted on modern Android - open the tile page.
                openSettingsItem("bluetooth")
            } else {
                @Suppress("DEPRECATION")
                val bluetooth = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                if (bluetooth != null) {
                    if (bluetooth.isEnabled) bluetooth.disable() else bluetooth.enable()
                    LogBus.log(if (bluetooth.isEnabled) "Bluetooth ON" else "Bluetooth OFF")
                } else {
                    openSettingsItem("bluetooth")
                }
            }
        } catch (e: Exception) {
            openSettingsItem("bluetooth")
        }
    }

    private fun openSettingsItem(item: String?) {
        val action = when (item?.lowercase()) {
            "wifi", "internet" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "battery" -> android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS
            "display", "screen" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound", "volume" -> Settings.ACTION_SOUND_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "about" -> Settings.ACTION_DEVICE_INFO_SETTINGS
            "developer" -> Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        try {
            app.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            LogBus.log("Opened settings: ${item ?: "main"}", LogLevel.SUCCESS)
        } catch (e: Exception) {
            LogBus.log("Settings screen unavailable: ${e.message}", LogLevel.WARN)
        }
    }
}

/** Tiny indirection so DeviceController does not depend on core imports twice. */
internal object PermissionShim {
    fun openWriteSettings(context: Context) {
        try {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    android.net.Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            LogBus.log("Cannot open write-settings page", LogLevel.WARN)
        }
    }
}

// Coordinate helpers keep the Action data class free of null-unwrapping noise.
private fun Action.x1(): Int? = x
private fun Action.y1(): Int? = y
private fun Action.x2(): Int? = x2 ?: x
private fun Action.y2(): Int? = y2 ?: y
