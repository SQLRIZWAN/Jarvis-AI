package com.sqlai.assistant.engine

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView

/**
 * Floating "SQL AI" bubble drawn above other apps while the assistant is
 * active. Requires the Display-over-other-apps permission.
 */
object OverlayManager {

    private const val AUTO_HIDE_MS = 6_000L

    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var bubble: TextView? = null
    private val autoHide = Runnable { hide() }

    fun show(context: Context, text: String) {
        if (!Settings.canDrawOverlays(context)) return
        val appContext = context.applicationContext
        handler.post {
            try {
                val existing = bubble
                if (existing != null) {
                    existing.text = text
                    if (existing.parent == null) addView(appContext, existing)
                    existing.visibility = View.VISIBLE
                    scheduleHide()
                    return@post
                }
                val view = TextView(appContext).apply {
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    setPadding(dp(appContext, 14), dp(appContext, 8), dp(appContext, 14), dp(appContext, 8))
                    background = GradientDrawable().apply {
                        cornerRadius = dp(appContext, 24).toFloat()
                        setColor(Color.argb(225, 11, 18, 32))
                    }
                    this.text = text
                    elevation = dp(appContext, 8).toFloat()
                }
                bubble = view
                addView(appContext, view)
                scheduleHide()
            } catch (e: Exception) {
                // Overlay permission revoked mid-flight - ignore.
            }
        }
    }

    fun hide() {
        handler.removeCallbacks(autoHide)
        handler.post {
            val view = bubble ?: return@post
            try {
                view.visibility = View.GONE
                (view.parent as? ViewGroup)?.removeView(view)
            } catch (e: Exception) {
                // Already detached.
            }
        }
    }

    /** v7.0.0.2: the chip must never linger over another app forever. */
    private fun scheduleHide() {
        handler.removeCallbacks(autoHide)
        handler.postDelayed(autoHide, AUTO_HIDE_MS)
    }

    private fun addView(context: Context, view: View) {
        val wm = windowManager ?: run {
            val created = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager = created
            created
        }

        if (view.parent != null) return

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                // v7.0.0.2 field bug: the chip ate taps (Send could not be
                // pressed while it floated over WhatsApp). It is status-only
                // now - every touch passes straight through to the app below.
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(context, 48)
        }
        wm.addView(view, params)
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
