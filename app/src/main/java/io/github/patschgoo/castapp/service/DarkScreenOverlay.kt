package io.github.patschgoo.castapp.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.patschgoo.castapp.R

/**
 * "Dark screen": a black, minimum-brightness window over everything that keeps
 * the phone's display technically on (so the shared app keeps running and
 * playing) while it looks switched off.
 *
 * It only makes sense when a single app is shared (Android 14+): the capture
 * then contains just that app's window, so this overlay is invisible on the TV.
 * Tap once for controls, double-tap to wake the screen.
 */
class DarkScreenOverlay(private val context: Context, private val callbacks: Callbacks) {

    interface Callbacks {
        fun onVolume(delta: Double)
        fun onStopCasting()
        fun onWake()
    }

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var root: FrameLayout? = null
    private var controls: View? = null
    private val hideControls = Runnable {
        val panel = controls ?: return@Runnable
        // Invisible controls must not catch taps, so hide them for real after fading out.
        panel.animate().alpha(0f).setDuration(400).withEndAction { panel.visibility = View.INVISIBLE }.start()
    }

    @SuppressLint("ClickableViewAccessibility")
    fun show(): Boolean {
        if (root != null) return true
        if (!Settings.canDrawOverlays(context)) return false

        val layout = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        val panel = buildControls()
        panel.alpha = 0f
        panel.visibility = View.INVISIBLE
        layout.addView(
            panel,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                showControls()
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                callbacks.onWake()
                return true
            }
        })
        layout.setOnTouchListener { _, event -> detector.onTouchEvent(event) }

        @Suppress("DEPRECATION")
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (android.os.Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            // Not focusable: key events (and focus) stay with the app that is being cast.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.OPAQUE,
        ).apply {
            screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF // lowest backlight
            if (Build.VERSION.SDK_INT >= 30) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            title = "CastApp dark screen"
        }
        return try {
            windowManager.addView(layout, params)
            if (Build.VERSION.SDK_INT >= 30) {
                layout.windowInsetsController?.hide(WindowInsets.Type.systemBars())
            }
            root = layout
            controls = panel
            showControls()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun hide() {
        handler.removeCallbacks(hideControls)
        val r = root ?: return
        root = null
        controls = null
        try { windowManager.removeView(r) } catch (_: Exception) {}
    }

    private fun showControls() {
        val panel = controls ?: return
        handler.removeCallbacks(hideControls)
        panel.animate().cancel()
        panel.visibility = View.VISIBLE
        panel.animate().alpha(1f).setDuration(150).start()
        handler.postDelayed(hideControls, 4000)
    }

    private fun buildControls(): View {
        val dim = Color.rgb(150, 150, 150)
        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()
        fun button(label: Int, onClick: () -> Unit) = TextView(context).apply {
            setText(label)
            setTextColor(dim)
            textSize = 16f
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), Color.rgb(70, 70, 70))
            }
            setOnClickListener {
                onClick()
                showControls()
            }
        }

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        column.addView(TextView(context).apply {
            setText(R.string.dark_screen_overlay_text)
            setTextColor(dim)
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(20))
        })
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val gap = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(6), dp(6), dp(6), dp(6))
        }
        row.addView(button(R.string.action_volume_down) { callbacks.onVolume(-0.05) }, gap)
        row.addView(button(R.string.action_volume_up) { callbacks.onVolume(+0.05) }, gap)
        column.addView(row)
        val row2 = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        row2.addView(button(R.string.action_wake_screen) { callbacks.onWake() }, LinearLayout.LayoutParams(gap))
        row2.addView(button(R.string.action_stop_casting) { callbacks.onStopCasting() }, LinearLayout.LayoutParams(gap))
        column.addView(row2)
        return column
    }
}
