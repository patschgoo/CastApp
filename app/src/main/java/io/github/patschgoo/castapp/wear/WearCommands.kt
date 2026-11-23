package io.github.patschgoo.castapp.wear

import io.github.patschgoo.castapp.automation.AutomationCommand
import io.github.patschgoo.castapp.automation.ServiceMapping
import io.github.patschgoo.castapp.wearproto.WearCommand
import io.github.patschgoo.castapp.wearproto.WearMessage

/** What a message from the watch means for the phone. No Android code, so it is tested on the JVM. */
object WearCommands {
    fun commandFor(message: WearMessage): AutomationCommand = when (message) {
        // A turn of the bezel or crown: several remote steps as one volume change, so the TV gets one
        // request instead of a burst (the watch coalesces fast turns).
        is WearMessage.Command -> if (message.command.isVolumeStep && message.repeat > 1) {
            AutomationCommand.ChangeVolume(
                up = message.command == WearCommand.VOLUME_UP,
                amountPercent = message.repeat * ServiceMapping.VOLUME_STEP_PERCENT,
            )
        } else {
            ServiceMapping.commandFor(message.command)
        }
        is WearMessage.SetVolume -> AutomationCommand.SetVolume(message.percent.coerceIn(0, 100))
    }
}
