package com.sqlai.assistant.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * v6.0 crash safety: never lose a stack trace.
 *
 * - Catches every uncaught JVM crash, writes it to files/crash/last_crash.txt
 *   (survives the process), mirrors it to LogBus + logcat, then chains the
 *   previous handler so system crash reporting still works.
 * - [coroutineHandler] is attached to every long-lived CoroutineScope in the
 *   app so a single failed coroutine logs instead of taking the process down.
 */
object AppCrashHandler {

    private const val TAG = "AppCrashHandler"
    private const val CRASH_DIR = "crash"
    private const val CRASH_FILE = "last_crash.txt"

    private var previous: Thread.UncaughtExceptionHandler? = null

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val report = buildString {
                    append("time=").append(System.currentTimeMillis()).append('\n')
                    append("thread=").append(thread.name).append('\n')
                    append(sw.toString())
                }
                val dir = File(app.filesDir, CRASH_DIR).apply { mkdirs() }
                File(dir, CRASH_FILE).writeText(report)
                Log.e(TAG, "Uncaught crash on ${thread.name}", throwable)
                LogBus.log(
                    "CRASH: ${throwable.javaClass.simpleName}: ${throwable.message}",
                    LogLevel.ERROR
                )
            } catch (t: Throwable) {
                Log.e(TAG, "crash handler failed", t)
            } finally {
                previous?.uncaughtException(thread, throwable)
            }
        }
    }

    fun lastCrashText(context: Context): String? = try {
        val file = File(context.applicationContext.filesDir, "$CRASH_DIR/$CRASH_FILE")
        if (file.exists()) file.readText() else null
    } catch (t: Throwable) {
        null
    }

    /** Derives a scope (e.g. rememberCoroutineScope) that logs failures instead of crashing. */
    fun safeScope(base: CoroutineScope): CoroutineScope =
        CoroutineScope(base.coroutineContext + coroutineHandler)

    /** Shared handler: log scope failures instead of killing the app. */
    val coroutineHandler: CoroutineExceptionHandler =
        CoroutineExceptionHandler { _, throwable ->
            try {
                Log.e(TAG, "Coroutine scope error", throwable)
                LogBus.log(
                    "Scope error: ${throwable.javaClass.simpleName}: ${throwable.message}",
                    LogLevel.ERROR
                )
            } catch (t: Throwable) {
                Log.e(TAG, "coroutine handler failed", t)
            }
        }
}
