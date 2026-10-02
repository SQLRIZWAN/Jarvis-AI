package com.sqlai.assistant.service

import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import android.content.pm.ServiceInfo
import com.sqlai.assistant.R
import com.sqlai.assistant.SqlAiApp
import com.sqlai.assistant.core.LogBus
import com.sqlai.assistant.core.LogLevel

/**
 * Foreground WATCHDOG (Android 10-17) that keeps the assistant's two most
 * fragile dependencies alive:
 *
 *  1. ACCESSIBILITY SELF-HEALING - polls `Settings.Secure` every few seconds.
 *     If the OS revokes/disables `SqlAccessibilityService`, a low-latency
 *     persistent routine fires: high-priority + full-screen-intent
 *     notification straight into the Accessibility settings screen, plus a
 *     best-effort automatic settings launch. When the service is enabled but
 *     the instance died (OEM process kill), it waits for the system re-bind
 *     and re-nudges after a grace period, and restarts the listening
 *     pipeline if it is gone.
 *
 *  2. BATTERY OPTIMIZATION AUTO-FIX - if Android silently re-enables battery
 *     optimization for the app (the "service dies after some time" bug),
 *     the watchdog immediately re-issues the system allow-dialog and keeps
 *     a persistent notification until the exemption is granted.
 *
 * The watchdog itself runs as a typed foreground service (specialUse) so it
 * cannot be killed while monitoring.
 */
class AccessibilityWatchdogService : android.app.Service() {

    companion object {
        private const val NOTIF_ID = 7710
        private const val ALERT_A11Y_ID = 7711
        private const val ALERT_BATTERY_ID = 7712
        private const val CHECK_MS = 8_000L
        private const val REBIND_GRACE_MS = 24_000L
        private const val BATTERY_PROMPT_MS = 120_000L

        /** Safe entry - call from any service/activity. */
        fun start(context: Context) {
            try {
                val intent = Intent(context, AccessibilityWatchdogService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                LogBus.log("Watchdog start failed: ${e.message}", LogLevel.WARN)
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var cycles = 0
    private var a11yLostSince = 0L
    private var lastBatteryPrompt = 0L

    private val checker = object : Runnable {
        override fun run() {
            if (isDestroyed) return
            cycles++
            try {
                checkAccessibility()
                checkBattery()
                checkListeningPipeline()
            } catch (e: Exception) {
                LogBus.log("Watchdog cycle error: ${e.message}", LogLevel.WARN)
            }
            handler.postDelayed(this, CHECK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        promoteToForeground()
        handler.postDelayed(checker, CHECK_MS)
        LogBus.log("Accessibility watchdog started", LogLevel.SUCCESS)
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        // Self-heal: the system just tried to retire us - come right back.
        handler.postDelayed({ AccessibilityWatchdogService.start(this) }, 1_500)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------ foreground setup

    private fun promoteToForeground() {
        val notification = NotificationCompat.Builder(this, SqlAiApp.CHANNEL_SERVICE)
            .setContentTitle("SQL AI guard is active")
            .setContentText("Keeping screen control + battery alive")
            .setSmallIcon(R.drawable.ic_stat_sql)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        try {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } catch (e: Exception) {
            try {
                startForeground(NOTIF_ID, notification)
            } catch (e2: Exception) {
                LogBus.log("Watchdog foreground failed: ${e2.message}", LogLevel.WARN)
            }
        }
    }

    // ------------------------------------------------------ accessibility

    private fun isAccessibilityEnabledOn(): Boolean {
        val enabledFlag = Settings.Secure.getInt(
            contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0
        ) == 1
        val services = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val component = ComponentName(this, SqlAccessibilityService::class.java).flattenToString()
        val shortForm = "$packageName/${SqlAccessibilityService::class.java.name}"
        return enabledFlag && (services.contains(component) || services.contains(shortForm))
    }

    private fun checkAccessibility() {
        val enabled = isAccessibilityEnabledOn()
        val bound = SqlAccessibilityService.instance != null
        val now = System.currentTimeMillis()

        if (enabled && bound) {
            if (a11yLostSince != 0L) {
                a11yLostSince = 0L
                cancelAlert(ALERT_A11Y_ID)
                LogBus.log("Accessibility reconnected - watchdog healthy", LogLevel.SUCCESS)
            }
            return
        }

        // ---- REVOKED / DISABLED: fire the persistent re-bind routine -----
        if (!enabled) {
            if (a11yLostSince == 0L) {
                a11yLostSince = now
                LogBus.log("Accessibility DISABLED by system - restoring", LogLevel.ERROR)
            }
            notifyAccessibilityRestore()
            // Low-latency nudge: try to pop the settings screen directly
            // (allowed while our FGS is in the foreground-eligible window).
            if (cycles % 3 == 1) tryOpenAccessibilitySettings()
            return
        }

        // ---- Enabled but instance dead: wait for system re-bind ----------
        if (a11yLostSince == 0L) {
            a11yLostSince = now
            LogBus.log("Accessibility enabled but service not bound - waiting", LogLevel.WARN)
            return
        }
        if (now - a11yLostSince >= REBIND_GRACE_MS) {
            // System did not re-bind by itself - prompt the user to toggle.
            notifyAccessibilityRestore()
            if (cycles % 4 == 1) tryOpenAccessibilitySettings()
        }
    }

    private fun notifyAccessibilityRestore() {
        val settingsIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            this, 1, settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, SqlAiApp.CHANNEL_ALERTS)
            .setContentTitle("Screen Control is off")
            .setContentText("Tap to re-enable SQL AI accessibility service")
            .setSmallIcon(R.drawable.ic_stat_sql)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(pending)
            .setFullScreenIntent(pending, true) // heads-up instantly, no unlock needed
            .setAutoCancel(true)
            .build()
        try {
            notificationManager().notify(ALERT_A11Y_ID, notification)
        } catch (e: Exception) {
            LogBus.log("A11y alert failed: ${e.message}", LogLevel.WARN)
        }
    }

    private fun tryOpenAccessibilitySettings() {
        try {
            startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            // Background-activity start blocked - the full-screen intent above
            // remains the reliable path.
        }
    }

    // ------------------------------------------------------ battery auto-fix

    private fun checkBattery() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            cancelAlert(ALERT_BATTERY_ID)
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastBatteryPrompt < BATTERY_PROMPT_MS) return
        lastBatteryPrompt = now
        LogBus.log("Battery optimization re-enabled - auto-fixing", LogLevel.WARN)

        // Best-effort: pop the system exemption dialog directly.
        try {
            val request = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(request)
        } catch (e: Exception) {
            // Blocked from background - fall through to the notification.
        }
        notifyBatteryRestore()
    }

    private fun notifyBatteryRestore() {
        val request = PendingIntent.getActivity(
            this, 2,
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, SqlAiApp.CHANNEL_ALERTS)
            .setContentTitle("Allow auto-start for SQL AI")
            .setContentText("Battery optimization turned back on - tap to keep the assistant alive 24/7")
            .setSmallIcon(R.drawable.ic_stat_sql)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(request)
            .setFullScreenIntent(request, true)
            .setOngoing(true)
            .build()
        try {
            notificationManager().notify(ALERT_BATTERY_ID, notification)
        } catch (e: Exception) {
            LogBus.log("Battery alert failed: ${e.message}", LogLevel.WARN)
        }
    }

    // ------------------------------------------------------ pipeline alive

    private fun checkListeningPipeline() {
        // If the OEM killed the mic service, restart it (the watchdog keeps
        // running regardless because WE are the foreground guardian now).
        if (cycles % 5 != 0) return
        val running = try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            @Suppress("DEPRECATION")
            am.getRunningServices(60).any { it.service.className == ListeningService::class.java.name }
        } catch (e: Exception) {
            false
        }
        if (!running) {
            LogBus.log("Listening service dead - restarting", LogLevel.WARN)
            ListeningService.start(applicationContext)
        }
    }

    private fun cancelAlert(id: Int) {
        try {
            notificationManager().cancel(id)
        } catch (e: Exception) {
            // Ignore.
        }
    }

    private fun notificationManager() =
        getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
}
