package io.github.patschgoo.castapp.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.patschgoo.castapp.CastApp
import io.github.patschgoo.castapp.MainActivity
import io.github.patschgoo.castapp.ProjectionActivity
import io.github.patschgoo.castapp.R
import io.github.patschgoo.castapp.service.CastController
import io.github.patschgoo.castapp.service.CastService
import io.github.patschgoo.castapp.service.CastState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Quick Settings tile: tap to mirror the screen to the last used TV, tap again
 * to stop. Long-press opens the app to pick another TV.
 */
class CastTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        job?.cancel()
        job = scope.launch { CastController.state.collect { render(it) } }
    }

    override fun onStopListening() {
        job?.cancel()
        job = null
        super.onStopListening()
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    private fun render(state: CastState) {
        val tile = qsTile ?: return
        val last = CastApp.get(this).devices.lastUsed()
        when (state) {
            is CastState.Active -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = getString(R.string.tile_label_active)
                if (Build.VERSION.SDK_INT >= 29) tile.subtitle = state.device.name
            }
            CastState.Idle -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = getString(R.string.tile_label)
                if (Build.VERSION.SDK_INT >= 29) tile.subtitle = last?.name ?: getString(R.string.tile_pick_tv)
            }
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        if (CastController.isActive) {
            CastService.stop(this)
            return
        }
        val last = CastApp.get(this).devices.lastUsed()
        val intent = if (last != null) {
            ProjectionActivity.intent(this, last.id, ProjectionActivity.MODE_SCREEN)
        } else {
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (isLocked) unlockAndRun { launch(intent) } else launch(intent)
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launch(intent: Intent) {
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
