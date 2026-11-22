package io.github.patschgoo.castapp.tile

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.patschgoo.castapp.MainActivity
import io.github.patschgoo.castapp.R

/** Quick Settings tile "TV remote": opens the phone-as-remote screen. */
class RemoteTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_remote_label)
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = getString(R.string.tile_remote_subtitle)
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        launchFromTile(remoteIntent())
    }

    /** The remote screen is written separately: start it by name, the main screen if it isn't there. */
    private fun remoteIntent(): Intent {
        val remote = Intent().setClassName(packageName, REMOTE_ACTIVITY)
        return if (packageManager.resolveActivity(remote, 0) != null) remote else Intent(this, MainActivity::class.java)
    }

    private companion object {
        const val REMOTE_ACTIVITY = "io.github.patschgoo.castapp.remote.RemoteActivity"
    }
}
