package com.sqlai.assistant.core

import android.graphics.Bitmap
import android.util.Log
import com.sqlai.assistant.SqlAiApp
import java.io.File

/**
 * FEATURE 2 - rolling screenshot log for debugging on-device runs.
 *
 * After every agent action (and every watchdog timeout) the current screen
 * is saved as `Step_<n>_<label>.png` under filesDir/step_logs. Only the
 * newest 20 files are kept. Pull them with:
 *   adb shell run-as com.sqlai.assistant ls files/step_logs
 */
object ScreenshotLog {

    private const val TAG = "ScreenshotLog"
    private const val DIR = "step_logs"
    private const val MAX_KEPT = 20

    private var counter = 0

    /** Reset counter at task start so names read Step_1..Step_N. */
    @Synchronized
    fun nextTask() {
        counter = 0
    }

    /**
     * G1 CRASH FIX - save [bitmap] as Step_<n>_<label>.jpg:
     *  - downscaled to max 720px wide + JPEG q70 (was full-res PNG q100:
     *    ~10-18MB ARGB encode per step -> heap OOM mid-task),
     *  - Throwable caught (OutOfMemoryError is NOT an Exception),
     *  - synchronized counter (no filename races),
     *  - bitmap is service-owned: never recycled here.
     */
    @Synchronized
    fun save(bitmap: Bitmap?, label: String) {
        if (bitmap == null || bitmap.isRecycled) return
        try {
            val n = ++counter
            val dir = File(SqlAiApp.instance.filesDir, DIR)
            if (!dir.exists()) dir.mkdirs()
            val safe = label.replace(Regex("[^A-Za-z0-9_]+"), "_").take(40)
            val file = File(dir, "Step_${n}_${safe}.jpg")
            var out: Bitmap = bitmap
            val maxW = 720
            if (bitmap.width > maxW) {
                val h = (bitmap.height.toLong() * maxW / bitmap.width).toInt().coerceAtLeast(1)
                out = Bitmap.createScaledBitmap(bitmap, maxW, h, false)
            }
            file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 70, it) }
            if (out !== bitmap) out.recycle()
            trim(dir)
            LogBus.log("[SHOTS] ${file.name}", LogLevel.INFO)
        } catch (t: Throwable) {
            Log.w(TAG, "save failed: $t")
        }
    }

    /** Capture the current screen and save it. Returns quietly on failure. */
    suspend fun captureAndSave(label: String) {
        try {
            val a11y = com.sqlai.assistant.service.SqlAccessibilityService.instance
            val shot = a11y?.captureScreenshot()
            save(shot, label)
        } catch (t: Throwable) {
            Log.w(TAG, "capture failed: $t")
        }
    }

    private fun trim(dir: File) {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        val excess = files.size - MAX_KEPT
        if (excess > 0) files.take(excess).forEach { it.delete() }
    }
}
