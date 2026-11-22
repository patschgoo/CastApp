package io.github.patschgoo.castapp.automation

import io.github.patschgoo.castapp.service.CastKind
import io.github.patschgoo.castapp.service.CastService
import io.github.patschgoo.castapp.service.CastState
import io.github.patschgoo.castapp.wearproto.WearCommand

/** What to do about a control command: send this to [CastService], or answer right away. */
sealed interface ServiceStep {
    data class Call(val action: String, val extras: Map<String, Any> = emptyMap()) : ServiceStep
    data class Answer(val result: AutomationResult) : ServiceStep
}

/**
 * Maps the control commands (stop, play/pause, next, volume, quality ...) to the actions of
 * [CastService], looking at what is being cast. Shared by the automation API and the watch remote.
 * No Android code, so it is tested on the JVM.
 */
object ServiceMapping {
    /** Volume step of the TV remote buttons, in percent (CastService uses 5 %). */
    const val VOLUME_STEP_PERCENT = 5

    fun stepFor(command: AutomationCommand, state: CastState): ServiceStep? {
        val active = state as? CastState.Active
        return when (command) {
            AutomationCommand.Stop ->
                if (active == null) answer(AutomationResult.ok("Nothing was being cast")) else call(CastService.ACTION_STOP)

            AutomationCommand.PlayPause -> needsFile(active) { call(CastService.ACTION_PLAY_PAUSE) }
            AutomationCommand.Play -> needsFile(active) { if (it.paused) call(CastService.ACTION_PLAY_PAUSE) else answer(AutomationResult.ok("Already playing")) }
            AutomationCommand.Pause -> needsFile(active) { if (!it.paused) call(CastService.ACTION_PLAY_PAUSE) else answer(AutomationResult.ok("Already paused")) }
            AutomationCommand.Next -> needsFile(active) { call(CastService.ACTION_NEXT) }
            AutomationCommand.Previous -> needsFile(active) { call(CastService.ACTION_PREVIOUS) }

            is AutomationCommand.SetVolume -> needsCast(active) { call(CastService.ACTION_SET_VOLUME, mapOf(CastService.EXTRA_LEVEL to command.percent / 100.0)) }
            is AutomationCommand.ChangeVolume -> needsCast(active) {
                val amount = command.amountPercent
                when {
                    amount == null -> call(if (command.up) CastService.ACTION_VOLUME_UP else CastService.ACTION_VOLUME_DOWN)
                    else -> {
                        val current = (it.volume ?: 0.5f).toDouble()
                        val target = (current + (if (command.up) amount else -amount) / 100.0).coerceIn(0.0, 1.0)
                        call(CastService.ACTION_SET_VOLUME, mapOf(CastService.EXTRA_LEVEL to target))
                    }
                }
            }
            AutomationCommand.Mute -> needsCast(active) { call(CastService.ACTION_TOGGLE_MUTE) }

            is AutomationCommand.Quality -> needsCast(active) {
                if (it.qualityOptions.isEmpty()) {
                    answer(AutomationResult.unsupported("This cast has no quality setting"))
                } else {
                    val wanted = command.label.trim()
                    val match = it.qualityOptions.firstOrNull { o -> o.equals(wanted, ignoreCase = true) }
                        ?: it.qualityOptions.firstOrNull { o -> shortLabel(o).equals(wanted, ignoreCase = true) }
                    if (match == null) {
                        answer(AutomationResult.invalid("Unknown quality \"$wanted\"; available: ${it.qualityOptions.joinToString(", ")}"))
                    } else {
                        call(CastService.ACTION_QUALITY, mapOf(CastService.EXTRA_QUALITY to match))
                    }
                }
            }

            // Not about the running cast.
            is AutomationCommand.Mirror, is AutomationCommand.CastUrl, is AutomationCommand.CastFile,
            is AutomationCommand.Receive, is AutomationCommand.OpenRemote -> null
        }
    }

    /** "720p HD" -> "720p": the first word, so "quality=720p" finds "720p HD". */
    private fun shortLabel(option: String) = option.trim().substringBefore(' ')

    private fun call(action: String, extras: Map<String, Any> = emptyMap()): ServiceStep = ServiceStep.Call(action, extras)
    private fun answer(result: AutomationResult): ServiceStep = ServiceStep.Answer(result)

    private inline fun needsCast(active: CastState.Active?, then: (CastState.Active) -> ServiceStep): ServiceStep =
        if (active == null) answer(AutomationResult.NOT_CASTING) else then(active)

    /** Play/pause and the play list only exist when playing videos, music or photos, not when mirroring. */
    private inline fun needsFile(active: CastState.Active?, then: (CastState.Active) -> ServiceStep): ServiceStep = when {
        active == null -> answer(AutomationResult.NOT_CASTING)
        active.kind != CastKind.FILE -> answer(AutomationResult.unsupported("Only for videos, music and photos, not for screen mirroring"))
        else -> then(active)
    }

    /** What a button on the watch means. "Mirror" is not a control of a running cast: the executor shows the capture prompt for it. */
    fun commandFor(command: WearCommand): AutomationCommand = when (command) {
        WearCommand.PLAY_PAUSE -> AutomationCommand.PlayPause
        WearCommand.PREVIOUS -> AutomationCommand.Previous
        WearCommand.NEXT -> AutomationCommand.Next
        WearCommand.VOLUME_UP -> AutomationCommand.ChangeVolume(up = true)
        WearCommand.VOLUME_DOWN -> AutomationCommand.ChangeVolume(up = false)
        WearCommand.STOP -> AutomationCommand.Stop
        WearCommand.MIRROR_LAST -> AutomationCommand.Mirror(DeviceRef())
    }
}
