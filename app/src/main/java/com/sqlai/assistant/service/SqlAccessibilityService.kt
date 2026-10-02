package com.sqlai.assistant.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.sqlai.assistant.core.LogBus
import com.sqlai.assistant.core.LogLevel
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Full phone-control surface of SQL AI.
 *
 * Everything the AI plan executor needs lives here:
 * screen hierarchy reading, tap/swipe gestures, typing, scrolling and global keys.
 */
class SqlAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SqlAiA11y"

        @Volatile
        var instance: SqlAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        serviceInfo = serviceInfo?.apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }
        LogBus.log("Accessibility service connected", LogLevel.SUCCESS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        try {
            shotExecutor.shutdown()
        } catch (t: Throwable) {
        }
        cachedShot = null
        if (instance === this) instance = null
        LogBus.log("Accessibility service destroyed", LogLevel.WARN)
        super.onDestroy()
    }

    // ------------------------------------------------------------- screen read

    /** Human readable dump of everything visible on the current screen. */
    fun captureScreenText(maxItems: Int = 40): String {
        val root = try {
            rootInActiveWindow
        } catch (e: Exception) {
            null
        } ?: return "Screen unavailable (is accessibility enabled?)"

        val pkg = root.packageName?.toString() ?: "unknown"
        val out = LinkedHashSet<String>()
        collectNodeText(root, out, 0)
        val body = out.take(maxItems).joinToString(" | ")
        return "[$pkg] $body"
    }

    fun frontPackage(): String = try {
        rootInActiveWindow?.packageName?.toString() ?: ""
    } catch (e: Exception) {
        ""
    }

    private fun collectNodeText(node: AccessibilityNodeInfo, out: MutableSet<String>, depth: Int) {
        if (depth > 25 || out.size >= 200) return
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty()) out.add(text)
        val desc = node.contentDescription?.toString()?.trim()
        if (!desc.isNullOrEmpty() && desc != text) out.add(desc)
        for (i in 0 until node.childCount) {
            val child = try {
                node.getChild(i)
            } catch (e: Exception) {
                null
            } ?: continue
            collectNodeText(child, out, depth + 1)
        }
    }

    /**
     * Agent-grade screen dump: every interactive element with its on-screen
     * bounds so the LLM can reason about positions and tap exact coordinates.
     */
    fun captureScreenDetailed(maxItems: Int = 60): String {
        val root = try {
            rootInActiveWindow
        } catch (e: Exception) {
            null
        } ?: return "Screen unavailable (accessibility off)"

        val pkg = root.packageName?.toString() ?: "unknown"
        val lines = mutableListOf<String>()
        val seen = HashSet<String>()
        collectDetailed(root, lines, seen, 0)
        val body = lines.take(maxItems).joinToString("\n")
        return "FOREGROUND_APP=$pkg\n$body"
    }

    private fun collectDetailed(
        node: AccessibilityNodeInfo,
        out: MutableList<String>,
        seen: HashSet<String>,
        depth: Int
    ) {
        if (depth > 30 || out.size >= 300) return
        val rect = android.graphics.Rect()
        try {
            node.getBoundsInScreen(rect)
        } catch (e: Exception) {
            // ignore
        }

        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        val isInteractive = node.isClickable || node.isLongClickable || node.isEditable || node.isScrollable

        if ((text.isNotEmpty() || desc.isNotEmpty() || isInteractive) && !rect.isEmpty) {
            val key = "$text|$desc|${rect.left},${rect.top}"
            if (seen.add(key)) {
                val flags = buildString {
                    if (node.isClickable) append("clickable ")
                    if (node.isEditable) append("editable ")
                    if (node.isScrollable) append("scrollable ")
                    if (node.isCheckable) append(if (node.isChecked) "checked" else "unchecked")
                }.trim()
                val label = when {
                    text.isNotEmpty() && desc.isNotEmpty() && text != desc -> "\"$text\" desc=\"$desc\""
                    text.isNotEmpty() -> "\"$text\""
                    else -> "desc=\"$desc\""
                }
                out.add("$label [${rect.left},${rect.top} ${rect.width()}x${rect.height()}] $flags")
            }
        }

        for (i in 0 until node.childCount) {
            val child = try {
                node.getChild(i)
            } catch (e: Exception) {
                null
            } ?: continue
            collectDetailed(child, out, seen, depth + 1)
        }
    }

    // ------------------------------------------------------------ screenshot

    /**
     * G1 CRASH FIX - full screen capture through the accessibility API (no
     * MediaProjection permission needed). API 30+; returns null on older devices.
     *
     * - ONE shared daemon executor (the old code spawned a new
     *   single-thread executor per capture and never shut it down -> one
     *   leaked thread per screenshot -> hundreds of live threads per task
     *   -> native OOM crash mid-task).
     * - 800 ms frame cache: back-to-back callers get the SAME frame, so a
     *   task can never fire bursts of captures ("200 screenshots/sec").
     * - Ownership: the returned bitmap is SERVICE-OWNED. Callers must
     *   NEVER recycle() it - it may be handed to other callers too.
     * - OutOfMemoryError during the ARGB copy is caught -> null, no crash.
     */
    private val shotExecutor: java.util.concurrent.ExecutorService =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "sqlai-shot").apply { isDaemon = true }
        }
    @Volatile private var cachedShot: android.graphics.Bitmap? = null
    @Volatile private var cachedShotAt = 0L
    private val shotLock = Any()

    suspend fun captureScreenshot(minIntervalMs: Long = 800): android.graphics.Bitmap? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return null
        cachedShot?.let { hot ->
            if (!hot.isRecycled && android.os.SystemClock.elapsedRealtime() - cachedShotAt < minIntervalMs) {
                return hot
            }
        }
        return kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
            try {
                takeScreenshot(
                    android.view.Display.DEFAULT_DISPLAY,
                    shotExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            var result: android.graphics.Bitmap? = null
                            try {
                                val buffer = screenshot.hardwareBuffer
                                val bmp = android.graphics.Bitmap.wrapHardwareBuffer(
                                    buffer,
                                    screenshot.colorSpace
                                )
                                if (bmp != null) {
                                    result = if (bmp.config == android.graphics.Bitmap.Config.HARDWARE) {
                                        bmp.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
                                    } else {
                                        bmp
                                    }
                                }
                                buffer?.close()
                            } catch (t: Throwable) {
                                Log.w(TAG, "screenshot convert failed: $t")
                            }
                            // Publish to the frame cache BEFORE resume so an
                            // abandoned (timed-out) capture still serves the
                            // next caller instead of leaking unrecycled.
                            if (result != null) {
                                synchronized(shotLock) {
                                    cachedShot = result
                                    cachedShotAt = android.os.SystemClock.elapsedRealtime()
                                }
                            }
                            if (continuation.isActive) continuation.resume(result)
                        }

                        override fun onFailure(errorCode: Int) {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }
                )
            } catch (t: Throwable) {
                Log.w(TAG, "screenshot dispatch failed: $t")
                if (continuation.isActive) continuation.resume(null)
            }
        }
    }

    // ----------------------------------------------------------- tap / gestures

    /** Tap exact screen coordinates. */
    suspend fun tap(x: Int, y: Int): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return dispatch(path, 60L)
    }

    /** Swipe from (x1,y1) to (x2,y2). */
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 300): Boolean {
        val path = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        return dispatch(path, durationMs.coerceIn(50, 5000).toLong())
    }

    /** Result of BUG #4 priority element detection. */
    data class NodeMatch(
        val node: AccessibilityNodeInfo,
        val confidence: Int,
        val via: String
    )

    /**
     * BUG #4 - detect screen elements with an explicit priority chain:
     *   contentDescription (exact) > view-id (exact) > text (exact) >
     *   contentDescription (contains) > text (contains) > fuzzy similarity.
     * Returns the best match with a confidence score (0..100).
     */
    fun findBestMatch(label: String): NodeMatch? {
        val root = try { rootInActiveWindow } catch (e: Exception) { null } ?: return null
        val needle = label.trim().lowercase()
        if (needle.isEmpty()) return null

        var best: NodeMatch? = null
        fun offer(node: AccessibilityNodeInfo, confidence: Int, via: String) {
            if (confidence > (best?.confidence ?: -1)) best = NodeMatch(node, confidence, via)
        }

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 25) return
            val text = node.text?.toString()?.trim()
            val desc = node.contentDescription?.toString()?.trim()
            val viewId = node.viewIdResourceName?.substringAfterLast('/')

            if (desc != null && desc.equals(label, true)) offer(node, 96, "content-desc exact")
            if (viewId != null && viewId.equals(label, true)) offer(node, 94, "view-id exact")
            if (text != null && text.equals(label, true)) offer(node, 92, "text exact")
            if (desc != null && desc.lowercase().contains(needle)) offer(node, 84, "content-desc contains")
            if (text != null && text.lowercase().contains(needle)) offer(node, 80, "text contains")
            // BUG #4: fuzzy ONLY at >=0.85 similarity (spec: fuzzy <85 never taps).
            val probe = (text ?: desc ?: "").lowercase()
            val sim = com.sqlai.assistant.device.ContactMatcher.similarity(needle, probe)
            if (sim >= 0.85) {
                offer(node, minOf(90, (sim * 100).toInt()), "fuzzy ${"%.2f".format(sim)}")
            }
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                walk(child, depth + 1)
            }
        }
        walk(root, 0)
        return best
    }

    /**
     * Click the best-priority match for [label]. Returns false only when the
     * match is missing or confidence is below the hard floor (55) - callers
     * then scroll / wait / re-scan instead of tapping garbage.
     */
    suspend fun clickText(label: String): Boolean {
        val match = findBestMatch(label) ?: return false
        com.sqlai.assistant.core.LogBus.log(
            "[MATCH] '$label' conf=${match.confidence} via=${match.via}",
            com.sqlai.assistant.core.LogLevel.INFO
        )
        // BUG #4: hard accuracy floor - below 80 we scroll/wait/rescan instead
        // of tapping garbage (spec: fuzzy <85 rejected, weak matches rejected).
        if (match.confidence < 80) return false

        val node = match.node
        // BUG #4: verify bounds BEFORE acting - node must be on-screen.
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) return false
        val dm = resources.displayMetrics
        if (rect.centerX() !in 0..dm.widthPixels ||
            rect.centerY() !in 0..dm.heightPixels
        ) {
            com.sqlai.assistant.core.LogBus.log(
                "[MATCH] '$label' off-screen bounds - rejected", com.sqlai.assistant.core.LogLevel.WARN
            )
            return false
        }

        var clickable: AccessibilityNodeInfo? = node
        while (clickable != null && !clickable.isClickable) {
            clickable = clickable.parent
        }
        if (clickable != null) {
            val performed = try {
                clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } catch (e: Exception) {
                false
            }
            if (performed) return true
        }

        return tap(rect.centerX(), rect.centerY())
    }

    /**
     * FEATURE 4 - list every clickable row's visible label on screen, so the
     * contact matcher can score candidates instead of blind first-match taps.
     */
    fun collectClickableTexts(maxItems: Int = 40): List<String> {
        val root = try { rootInActiveWindow } catch (e: Exception) { null } ?: return emptyList()
        val out = linkedSetOf<String>()

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 25 || out.size >= maxItems) return
            var clickable: AccessibilityNodeInfo? = node
            var isRow = false
            var hops = 0
            while (clickable != null && hops < 6) {
                if (clickable.isClickable) { isRow = true; break }
                clickable = clickable.parent
                hops++
            }
            if (isRow) {
                val text = node.text?.toString()?.trim()
                val desc = node.contentDescription?.toString()?.trim()
                text?.takeIf { it.isNotBlank() && it.length in 2..60 }?.let { out.add(it) }
                desc?.takeIf { it.isNotBlank() && it.length in 2..60 && it != text }?.let { out.add(it) }
            }
            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (e: Exception) { null } ?: continue
                walk(child, depth + 1)
            }
        }
        walk(root, 0)
        return out.toList()
    }


    // ----------------------------------------------------------------- typing

    /** Replace text in the currently focused input, clipboard-paste as fallback. */
    suspend fun typeText(text: String): Boolean {
        val root = try {
            rootInActiveWindow
        } catch (e: Exception) {
            null
        }
        val focused = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

        if (focused != null) {
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }
            val ok = try {
                focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            } catch (e: Exception) {
                false
            }
            if (ok) return true
            val pasted = try {
                focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            } catch (e: Exception) {
                false
            }
            if (pasted) return true
        }

        return try {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("SQL AI", text))
            LogBus.log("Text placed on clipboard (no focused input found)", LogLevel.WARN)
            false
        } catch (e: Exception) {
            false
        }
    }

    // --------------------------------------------------------------- scrolling

    suspend fun scroll(direction: String): Boolean {
        val root = try {
            rootInActiveWindow
        } catch (e: Exception) {
            null
        } ?: return false

        val scrollable = findScrollable(root, 0)
        if (scrollable != null) {
            val action = if (direction == "up") {
                AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            } else {
                AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
            val ok = try {
                scrollable.performAction(action)
            } catch (e: Exception) {
                false
            }
            if (ok) return true
        }

        // Fallback: screen-centre swipe.
        val metrics = resources.displayMetrics
        val cx = metrics.widthPixels / 2
        val cy = metrics.heightPixels / 2
        return if (direction == "up") {
            swipe(cx, cy + 300, cx, cy - 300)
        } else {
            swipe(cx, cy - 300, cx, cy + 300)
        }
    }

    private fun findScrollable(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > 25) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = try {
                node.getChild(i)
            } catch (e: Exception) {
                null
            } ?: continue
            findScrollable(child, depth + 1)?.let { return it }
        }
        return null
    }

    // -------------------------------------------------------------- global keys

    fun globalBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun globalHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun globalRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun globalNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun globalQuickSettings(): Boolean = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)

    fun pressEnter(): Boolean {
        val root = try {
            rootInActiveWindow
        } catch (e: Exception) {
            null
        }
        val focused = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return try {
            // ACTION_IME_ENTER is not public API - append a newline instead,
            // which most editors and message boxes treat as "send/enter".
            val current = focused.text?.toString().orEmpty()
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    current + "\n"
                )
            }
            focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (e: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------ private

    private suspend fun dispatch(path: Path, durationMs: Long): Boolean =
        suspendCancellableCoroutine { continuation ->
            val gesture = try {
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                    .build()
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resume(false)
                return@suspendCancellableCoroutine
            }

            val accepted = try {
                dispatchGesture(
                    gesture,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (continuation.isActive) continuation.resume(true)
                        }

                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (continuation.isActive) continuation.resume(false)
                        }
                    },
                    null
                )
            } catch (e: Exception) {
                false
            }
            if (!accepted && continuation.isActive) continuation.resume(false)
        }
}
