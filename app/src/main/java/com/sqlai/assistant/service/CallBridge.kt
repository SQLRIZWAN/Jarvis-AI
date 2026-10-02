package com.sqlai.assistant.service

import android.content.Context
import android.os.Build
import android.telecom.TelecomManager
import com.sqlai.assistant.core.LogBus
import com.sqlai.assistant.core.LogLevel

/**
 * Small bridge so any component (voice command, DeviceController, UI) can
 * answer or hang up the ringing call. Prefers the live [SqlInCallService]
 * Call object, then TelecomManager, then a reflective TelephonyManager call
 * (those helpers were removed from the public SDK but still exist at runtime
 * on older builds).
 */
object CallBridge {

    fun answer(context: Context) {
        if (SqlInCallService.answerActiveCall()) {
            LogBus.log("Call answered via InCallService", LogLevel.SUCCESS)
            return
        }
        if (reflectTelephony(context, "acceptRingingCall")) {
            LogBus.log("Call answered via TelephonyManager", LogLevel.SUCCESS)
            return
        }
        LogBus.log(
            "Answer failed - enable Call Assistant or Accessibility",
            LogLevel.ERROR
        )
    }

    fun endCall(context: Context) {
        if (SqlInCallService.endActiveCall()) {
            LogBus.log("Call ended", LogLevel.SUCCESS)
            return
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val tm = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                if (tm.endCall()) {
                    LogBus.log("Call ended via TelecomManager", LogLevel.SUCCESS)
                    return
                }
            }
        } catch (e: Exception) {
            LogBus.log("TelecomManager.endCall failed: ${e.message}", LogLevel.WARN)
        }
        if (reflectTelephony(context, "endCall")) {
            LogBus.log("Call ended via TelephonyManager", LogLevel.SUCCESS)
            return
        }
        LogBus.log("End call failed", LogLevel.ERROR)
    }

    /** Call the removed TelephonyManager helper via reflection (runtime-only). */
    private fun reflectTelephony(context: Context, methodName: String): Boolean = try {
        @Suppress("DEPRECATION")
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE)
        val method = tm?.javaClass?.getMethod(methodName)
        method?.invoke(tm)
        method != null
    } catch (e: Exception) {
        false
    }

    fun isInCall(): Boolean = SqlInCallService.instance?.isInCall() == true

    fun sdkOk(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
}
