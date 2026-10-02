package com.sqlai.assistant.device

import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.view.WindowManager
import com.sqlai.assistant.SqlAiApp
import com.sqlai.assistant.core.LogBus
import com.sqlai.assistant.core.LogLevel
import com.sqlai.assistant.service.SqlAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Precision touch executor for the Visual Grounding pipeline.
 *
 * Instead of clicking Accessibility text nodes (which miss icons, floating
 * buttons, canvas/Compose elements and custom drawables), every targeted
 * element is resolved to an ABSOLUTE pixel (x, y) - usually produced by
 * Gemini Vision reading the screenshot - and dispatched through
 * `AccessibilityService.dispatchGesture()` with exact Path coordinates.
 *
 * Guarantees:
 *  - coordinates are clamped to the real display bounds (no dead taps),
 *  - vision coordinates captured at a different resolution are scaled back
 *    to the live screen resolution,
 *  - bounding boxes are tapped at their CENTER (icon-safe),
 *  - every dispatch reports success/failure so the agent can verify and
 *    retry instead of assuming the tap landed.
 */
object CoordinateGestureExecutor {

    data class ScreenSize(val width: Int, val height: Int)

    @Volatile
    private var cachedSize: ScreenSize? = null

    /** Real display size in pixels (stable per orientation session). */
    fun screenSize(): ScreenSize {
        cachedSize?.let { return it }
        val size = try {
            val app = SqlAiApp.instance
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = app.getSystemService(WindowManager::class.java)
                    .maximumWindowMetrics.bounds
                ScreenSize(bounds.width(), bounds.height())
            } else {
                val dm = app.resources.displayMetrics
                ScreenSize(dm.widthPixels, dm.heightPixels)
            }
        } catch (e: Exception) {
            ScreenSize(1080, 2340) // sane fallback; clamp still prevents crashes
        }
        cachedSize = size
        return size
    }

    /** Clamp into [0..width-1] / [0..height-1]. */
    fun clamp(x: Int, y: Int): Point {
        val s = screenSize()
        return Point(
            x.coerceIn(0, (s.width - 1).coerceAtLeast(0)),
            y.coerceIn(0, (s.height - 1).coerceAtLeast(0))
        )
    }

    /** Convert vision coords captured at [imgW]x[imgH] to live screen pixels. */
    fun scaleToScreen(x: Int, y: Int, imgW: Int, imgH: Int): Point {
        val s = screenSize()
        if (imgW <= 0 || imgH <= 0) return clamp(x, y)
        if (imgW == s.width && imgH == s.height) return clamp(x, y)
        val nx = (x.toDouble() / imgW * s.width).toInt()
        val ny = (y.toDouble() / imgH * s.height).toInt()
        return clamp(nx, ny)
    }

    /** Exact-pixel tap at (x, y). Returns true when the gesture dispatched. */
    suspend fun tap(x: Int, y: Int): Boolean = withContext(Dispatchers.IO) {
        val accessibility = SqlAccessibilityService.instance ?: return@withContext false
        val p = clamp(x, y)
        val ok = accessibility.tap(p.x, p.y)
        if (!ok) LogBus.log("Coordinate tap failed at (${p.x},${p.y})", LogLevel.WARN)
        ok
    }

    /**
     * Tap the CENTER of a vision bounding box [x1,y1,x2,y2] - the reliable
     * way to hit non-text targets (heart icons, floating action buttons).
     */
    suspend fun tapBox(x1: Int, y1: Int, x2: Int, y2: Int): Boolean {
        val cx = (x1 + x2) / 2
        val cy = (y1 + y2) / 2
        if (x2 - x1 < 4 || y2 - y1 < 4) {
            // Degenerate box - tap its origin rather than nothing.
            return tap(x1, y1)
        }
        return tap(cx, cy)
    }

    /**
     * Tap a node's bounds (from the Accessibility tree) at its center -
     * hybrid fallback when vision is unavailable.
     */
    suspend fun tapNodeBounds(bounds: Rect): Boolean {
        if (bounds.isEmpty) return false
        return tap(bounds.centerX(), bounds.centerY())
    }

    /**
     * G3 - press-and-hold at exact screen coordinates (voice-note recording).
     * Clamped like every other gesture.
     */
    suspend fun hold(x: Int, y: Int, durationMs: Int): Boolean =
        withContext(Dispatchers.IO) {
            val accessibility = SqlAccessibilityService.instance ?: return@withContext false
            val p = clamp(x, y)
            accessibility.hold(p.x, p.y, durationMs)
        }

    /** Exact-pixel swipe from (x1,y1) to (x2,y2). */
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean =
        withContext(Dispatchers.IO) {
            val accessibility = SqlAccessibilityService.instance ?: return@withContext false
            val a = clamp(x1, y1)
            val b = clamp(x2, y2)
            accessibility.swipe(a.x, a.y, b.x, b.y, durationMs.coerceIn(80, 4000))
        }

    /** Drop cache (rotation / display change). */
    fun invalidate() {
        cachedSize = null
    }
}
