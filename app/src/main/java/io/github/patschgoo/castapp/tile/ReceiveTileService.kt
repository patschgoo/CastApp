package io.github.patschgoo.castapp.tile

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.patschgoo.castapp.R
import io.github.patschgoo.castapp.tv.TvReceiver
import io.github.patschgoo.castapp.tv.TvReceiverService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Quick Settings tile "Receive on this phone": a toggle for the receiver that lets other phones
 * (and DLNA apps) cast to this phone. Active while [TvReceiverService] runs.
 */
class ReceiveTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        job?.cancel()
        job = scope.launch { TvReceiverService.running.collect { render(it) } }
    }

    override fun onStopListening() {
        job?.cancel()
        job = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun render(on: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_receive_label)
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = if (on) TvReceiver.deviceName(this) else getString(R.string.tile_receive_off)
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        // The tile follows the service (the running flow), so a refused start leaves it off.
        if (TvReceiverService.running.value) TvReceiverService.stop(this) else TvReceiverService.start(this)
    }
}
