package io.github.patschgoo.castapp.wear

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import io.github.patschgoo.castapp.automation.AutomationExecutor
import io.github.patschgoo.castapp.service.CastController
import io.github.patschgoo.castapp.wearproto.WearCodec
import io.github.patschgoo.castapp.wearproto.WearPaths

/**
 * Receives what the CastApp watch app sends: commands (play/pause, next, volume, stop, mirror), the
 * volume from its bezel or crown (a level, or several steps at once) and "what is playing?".
 * Google Play services starts this service only when a watch sends something, so a phone without a
 * watch never runs it.
 */
class WearListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            WearPaths.COMMAND -> {
                val message = WearCodec.decodeMessage(event.data) ?: return
                // A watch is not an activity on screen: mirroring comes as a notification to tap.
                AutomationExecutor(this).run(WearCommands.commandFor(message), interactive = false)
            }
            WearPaths.STATE_REQUEST -> WearSync.reply(this, event.sourceNodeId, CastController.state.value)
        }
    }
}
