package io.github.patschgoo.castapp.service

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.PixelFormat
import android.view.View
import android.view.WindowManager

/**
 * "Turn apps sideways": an invisible 1×1 window that asks Android for landscape,
 * which rotates whatever app is open so it fills the TV picture.
 */
class OrientationLock(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var view: View? = null

    fun show(): Boolean {
        if (view != null) return true
        val v = View(context)
        val params = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            screenOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            alpha = 0f
            title = "CastApp landscape"
        }
        return try {
            windowManager.addView(v, params)
            view = v
            true
        } catch (_: Exception) {
            false
        }
    }

    fun hide() {
        val v = view ?: return
        view = null
        try { windowManager.removeView(v) } catch (_: Exception) {}
    }
}
