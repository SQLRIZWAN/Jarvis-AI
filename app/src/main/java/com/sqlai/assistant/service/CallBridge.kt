package com.sqlai.assistant.service

import android.content.Context
import android.os.Build
import android.telecom.Call
import android.telephony.TelephonyManager
import com.sqlai.assistant.core.LogBus
import com.sqlai.assistant.core.LogLevel

/**
 * Small bridge so any component (voice command, DeviceController, UI) can
 * answer or hang up the ringing call. Prefers the live [SqlInCallService]
 * Call object and falls back to the deprecated TelephonyManager helpers.
 */
object CallBridge {

    fun answer(context: Context) {
        if (SqlInCallService.answerActiveCall()) {
            LogBus.log("Call answered via InCallService", LogLevel.SUCCESS)
            return
        }
        try {
            @Suppress("DEPRECATION")
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            tm.acceptRingingCall()
            LogBus.log("Call answered via TelephonyManager", LogLevel.SUCCESS)
        } catch (e: Exception) {
            LogBus.log("Answer failed: ${e.message}", LogLevel.ERROR)
        }
    }

    fun endCall(context: Context) {
        if (SqlInCallService.endActiveCall()) {
            LogBus.log("Call ended", LogLevel.SUCCESS)
            return
        }
        try {
            @Suppress("DEPRECATION")
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            tm.endCall()
            LogBus.log("Call ended via TelephonyManager", LogLevel.SUCCESS)
        } catch (e: Exception) {
            LogBus.log("End call failed: ${e.message}", LogLevel.ERROR)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun isInCall(): Boolean = SqlInCallService.instance?.isInCall() == true

    fun sdkOk(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
}
