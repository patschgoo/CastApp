package io.github.patschgoo.castapp.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import io.github.patschgoo.castapp.CastApp
import io.github.patschgoo.castapp.MainActivity
import io.github.patschgoo.castapp.ProjectionActivity
import io.github.patschgoo.castapp.R
import io.github.patschgoo.castapp.service.CastController
import io.github.patschgoo.castapp.service.CastKind
import io.github.patschgoo.castapp.service.CastPhase
import io.github.patschgoo.castapp.service.CastService

/**
 * Home-screen widget: "Mirror to <last TV>" and "Open CastApp" while nothing is cast; title, device
 * and playback / volume buttons while casting. Pushed by [CastService] when the cast changes
 * ([CastWidget.refresh]); nothing runs in between.
 */
class CastWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        CastWidget.refresh(context, force = true)
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        CastWidget.refresh(context, force = true)
    }
}

/** The widget's buttons: forwards them to [CastService] (which ignores them when nothing is cast). */
class WidgetActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != CastWidget.ACTION_CONTROL) return
        val action = intent.getStringExtra(CastWidget.EXTRA_ACTION) ?: return
        if (action in CastWidget.SERVICE_ACTIONS.values) CastService.send(context, action)
    }
}

object CastWidget {
    const val ACTION_CONTROL = "io.github.patschgoo.castapp.action.WIDGET_CONTROL"
    const val EXTRA_ACTION = "service_action"

    /** What each button asks of [CastService]. */
    val SERVICE_ACTIONS: Map<WidgetControl, String> = mapOf(
        WidgetControl.PREVIOUS to CastService.ACTION_PREVIOUS,
        WidgetControl.PLAY_PAUSE to CastService.ACTION_PLAY_PAUSE,
        WidgetControl.NEXT to CastService.ACTION_NEXT,
        WidgetControl.VOLUME_DOWN to CastService.ACTION_VOLUME_DOWN,
        WidgetControl.VOLUME_UP to CastService.ACTION_VOLUME_UP,
        WidgetControl.STOP to CastService.ACTION_STOP,
    )

    private var lastModel: WidgetModel? = null

    /**
     * Redraws the widgets if what they show changed (or [force]). Cheap when nothing changed:
     * called on every cast state change, but the playback position is not part of the model.
     */
    @Synchronized
    fun refresh(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        val model = WidgetModel.from(CastController.state.value, lastTvName(app))
        if (!force && model == lastModel) return
        lastModel = model
        try {
            val manager = AppWidgetManager.getInstance(app)
            val ids = manager.getAppWidgetIds(ComponentName(app, CastWidgetProvider::class.java))
            for (id in ids) manager.updateAppWidget(id, build(app, model, manager.getAppWidgetOptions(id)))
        } catch (_: Exception) {
        }
    }

    private fun lastTvName(context: Context): String? = try {
        CastApp.get(context).devices.lastUsed()?.name
    } catch (_: Exception) {
        null
    }

    private fun build(context: Context, model: WidgetModel, options: Bundle?): RemoteViews {
        val widthDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) ?: 0
        val heightDp = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) ?: 0
        val wide = widthDp >= WidgetModel.WIDE_MIN_DP
        val tall = heightDp == 0 || heightDp >= WidgetModel.TALL_MIN_DP
        val views = RemoteViews(context.packageName, if (wide) R.layout.widget_wide else R.layout.widget_compact)
        val immutable = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        val openApp = PendingIntent.getActivity(
            context, 100, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), immutable,
        )
        views.setOnClickPendingIntent(android.R.id.background, openApp)
        views.setViewVisibility(R.id.idle_group, if (model.active) View.GONE else View.VISIBLE)
        views.setViewVisibility(R.id.active_group, if (model.active) View.VISIBLE else View.GONE)

        if (!model.active) {
            views.setTextViewText(
                R.id.idle_text,
                model.lastTv?.let { context.getString(R.string.widget_mirror_to, it) } ?: context.getString(R.string.widget_pick_tv),
            )
            val mirror = lastDeviceId(context)?.let { id ->
                PendingIntent.getActivity(context, 101, ProjectionActivity.intent(context, id, ProjectionActivity.MODE_SCREEN), immutable)
            } ?: openApp
            views.setOnClickPendingIntent(R.id.btn_mirror, mirror)
            views.setOnClickPendingIntent(R.id.btn_open, openApp)
            // In a one-cell-high widget there is only room for the Mirror button.
            views.setViewVisibility(R.id.btn_open, if (wide || tall) View.VISIBLE else View.GONE)
            return views
        }

        val queue = model.kind == CastKind.FILE && model.queueSize > 1
        views.setTextViewText(
            R.id.title,
            model.title ?: if (model.kind == CastKind.SCREEN) context.getString(R.string.widget_mirroring) else model.deviceName,
        )
        val subtitle = when {
            model.phase != CastPhase.LIVE ->
                context.getString(R.string.widget_subtitle_device, model.deviceName, model.phase.label)
            queue -> context.getString(R.string.widget_subtitle_queue, model.deviceName, model.queueIndex + 1, model.queueSize)
            else -> model.deviceName
        }
        views.setTextViewText(R.id.subtitle, subtitle)
        views.setViewVisibility(R.id.subtitle, if (wide || tall) View.VISIBLE else View.GONE)

        val room = if (widthDp >= WidgetModel.VOLUME_MIN_DP) 6 else 4
        val shown = model.controls(room)
        val buttons = mapOf(
            WidgetControl.PREVIOUS to R.id.btn_prev,
            WidgetControl.PLAY_PAUSE to R.id.btn_play,
            WidgetControl.NEXT to R.id.btn_next,
            WidgetControl.VOLUME_DOWN to R.id.btn_vol_down,
            WidgetControl.VOLUME_UP to R.id.btn_vol_up,
            WidgetControl.STOP to R.id.btn_stop,
        )
        for ((control, viewId) in buttons) {
            val visible = control in shown
            views.setViewVisibility(viewId, if (visible) View.VISIBLE else View.GONE)
            if (visible) {
                val intent = Intent(context, WidgetActionReceiver::class.java)
                    .setAction(ACTION_CONTROL)
                    .putExtra(EXTRA_ACTION, SERVICE_ACTIONS.getValue(control))
                views.setOnClickPendingIntent(
                    viewId,
                    PendingIntent.getBroadcast(context, 200 + control.ordinal, intent, immutable),
                )
            }
        }
        views.setImageViewResource(R.id.btn_play, if (model.paused) R.drawable.ic_w_play else R.drawable.ic_w_pause)
        views.setContentDescription(R.id.btn_play, context.getString(if (model.paused) R.string.action_play else R.string.action_pause))
        return views
    }

    private fun lastDeviceId(context: Context): String? = try {
        CastApp.get(context).devices.lastUsed()?.id
    } catch (_: Exception) {
        null
    }
}
