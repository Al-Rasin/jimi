package com.alrasin.jarvis.agent

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.alrasin.jarvis.actions.JarvisAccessibilityService
import com.alrasin.jarvis.core.JarvisLog

/** Small accessibility overlay: the underlying app remains accessible and focused. */
class AgentPanel(private val context: Context, private val onStop: () -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private var panel: LinearLayout? = null
    private var label: TextView? = null
    private var manager: WindowManager? = null
    @Volatile private var closed = false

    fun show(text: String) {
        handler.post {
            if (closed) return@post
            if (panel == null) {
                val service = JarvisAccessibilityService.instance ?: return@post
                val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val density = context.resources.displayMetrics.density
                val view = LinearLayout(service).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding((12 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
                    setBackgroundColor(Color.rgb(24, 28, 38))
                }
                val status = TextView(service).apply {
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    maxLines = 3
                    importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES
                }
                view.addView(status, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                view.addView(Button(service).apply {
                    this.text = "Stop"
                    setOnClickListener { onStop(); close() }
                })
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT,
                ).apply { gravity = Gravity.TOP }
                try {
                    wm.addView(view, params)
                    manager = wm
                    panel = view
                    label = status
                } catch (e: RuntimeException) {
                    JarvisLog.i("Screen agent panel unavailable: ${e.javaClass.simpleName}")
                    return@post
                }
            }
            label?.text = text
        }
    }

    fun close() {
        closed = true
        handler.post {
            panel?.let { view -> runCatching { manager?.removeView(view) } }
            panel = null
            label = null
            manager = null
        }
    }
}
