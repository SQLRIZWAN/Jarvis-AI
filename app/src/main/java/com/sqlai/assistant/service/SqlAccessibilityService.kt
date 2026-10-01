package com.sqlai.assistant.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
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

    /**
     * Find a node whose text/description matches [label] and click it.
     * Falls back to a coordinate tap at the node's centre.
     */
    suspend fun clickText(label: String): Boolean {
        val root = try {
            rootInActiveWindow
        } catch (e: Exception) {
            null
        } ?: return false

        val needle = label.trim().lowercase()
        if (needle.isEmpty()) return false

        val matches = mutableListOf<AccessibilityNodeInfo>()
        collectMatches(root, needle, matches, 0)
        if (matches.isEmpty()) return false

        // Prefer exact match, then anything clickable.
        val exact = matches.firstOrNull { it.text?.toString().equals(label, true) }
        val node = exact ?: matches.first()

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

        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) return false
        return tap(rect.centerX(), rect.centerY())
    }

    private fun collectMatches(
        node: AccessibilityNodeInfo,
        needle: String,
        out: MutableList<AccessibilityNodeInfo>,
        depth: Int
    ) {
        if (depth > 25 || out.size >= 30) return
        val text = node.text?.toString()?.lowercase()
        val desc = node.contentDescription?.toString()?.lowercase()
        val hit = (text != null && text.contains(needle)) || (desc != null && desc.contains(needle))
        if (hit) out.add(node)
        for (i in 0 until node.childCount) {
            val child = try {
                node.getChild(i)
            } catch (e: Exception) {
                null
            } ?: continue
            collectMatches(child, needle, out, depth + 1)
        }
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
