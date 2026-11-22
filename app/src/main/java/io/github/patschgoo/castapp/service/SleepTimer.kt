package io.github.patschgoo.castapp.service

import android.content.Context
import android.os.SystemClock
import io.github.patschgoo.castapp.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A running sleep timer: stop casting at [endsAtElapsed]
 * (SystemClock.elapsedRealtime(), which keeps counting while the phone sleeps),
 * or at the end of the item playing now.
 */
data class SleepPlan(val endsAtElapsed: Long, val atItemEnd: Boolean, val minutes: Int) {
    fun remainingMs(nowElapsed: Long): Long = if (atItemEnd) -1 else (endsAtElapsed - nowElapsed).coerceAtLeast(0)

    fun isDue(nowElapsed: Long): Boolean = !atItemEnd && nowElapsed >= endsAtElapsed

    companion object {
        val CHOICES = listOf(15, 30, 45, 60, 90)

        fun after(minutes: Int, nowElapsed: Long) = SleepPlan(nowElapsed + minutes * 60_000L, atItemEnd = false, minutes = minutes)

        fun atEndOfItem() = SleepPlan(0, atItemEnd = true, minutes = 0)

        /** "23:10" left, "1:05:00" for longer ones. */
        fun format(ms: Long): String {
            val total = (ms + 999) / 1000
            val h = total / 3600
            val m = (total % 3600) / 60
            val s = total % 60
            return if (h > 0) "%d:%02d:%02d".format(java.util.Locale.ROOT, h, m, s) else "%d:%02d".format(java.util.Locale.ROOT, m, s)
        }
    }
}

/**
 * Stops casting after a while (screen mirroring and files alike) or when the
 * current file ends. Ends by itself when casting stops.
 */
object SleepTimer {
    private val _plan = MutableStateFlow<SleepPlan?>(null)
    val plan: StateFlow<SleepPlan?> = _plan.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var ticker: Job? = null

    @Synchronized
    fun start(context: Context, minutes: Int) = begin(context.applicationContext, SleepPlan.after(minutes, SystemClock.elapsedRealtime()))

    /** File casting: stop when the item playing now ends. */
    @Synchronized
    fun stopAtEndOfItem(context: Context) = begin(context.applicationContext, SleepPlan.atEndOfItem())

    @Synchronized
    fun cancel() {
        ticker?.cancel()
        ticker = null
        _plan.value = null
    }

    private fun begin(app: Context, plan: SleepPlan) {
        ticker?.cancel()
        _plan.value = plan
        ticker = scope.launch {
            // Checked every few seconds against the real clock, so deep sleep can't make it late.
            while (true) {
                delay(2000)
                val current = _plan.value ?: return@launch
                if (!CastController.isActive) {
                    cancelFrom(current)
                    return@launch
                }
                if (current.isDue(SystemClock.elapsedRealtime())) {
                    cancelFrom(current)
                    fire(app)
                    return@launch
                }
            }
        }
    }

    @Synchronized
    private fun cancelFrom(plan: SleepPlan) {
        if (_plan.value === plan) _plan.value = null
    }

    private fun fire(app: Context) {
        CastService.stop(app)
        CastController.message(app.getString(R.string.sleep_stopped))
    }

    /** The file playing now ended: true if the timer says to stop here (and it is used up). */
    @Synchronized
    fun takeItemEnd(): Boolean {
        val p = _plan.value ?: return false
        if (!p.atItemEnd) return false
        ticker?.cancel()
        ticker = null
        _plan.value = null
        return true
    }
}
