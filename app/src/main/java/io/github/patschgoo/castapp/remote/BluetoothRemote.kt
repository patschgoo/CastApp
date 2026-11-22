package io.github.patschgoo.castapp.remote

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Placeholder: filled in by the remote-control work. */
object BluetoothRemote : RemoteProvider {
    override val title = "Bluetooth"
    private val list = MutableStateFlow<List<RemoteTarget>>(emptyList())
    override fun targets(context: Context): StateFlow<List<RemoteTarget>> = list
}
