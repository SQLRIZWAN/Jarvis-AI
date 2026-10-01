package com.sqlai.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sqlai.assistant.SqlAiApp
import com.sqlai.assistant.core.LogBus
import com.sqlai.assistant.core.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Restarts the 24/7 listener after reboot or app update. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = SqlAiApp.settings.settings.first()
                if (settings.bootRestartEnabled &&
                    settings.assistantEnabled &&
                    settings.listenServiceEnabled
                ) {
                    LogBus.log("Auto-restart after $action", LogLevel.SUCCESS)
                    ListeningService.start(context.applicationContext)
                }
            } catch (e: Exception) {
                LogBus.log("Boot restart failed: ${e.message}", LogLevel.ERROR)
            } finally {
                pending.finish()
            }
        }
    }
}
