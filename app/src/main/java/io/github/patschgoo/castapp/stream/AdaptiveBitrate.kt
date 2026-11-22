package io.github.patschgoo.castapp.stream

/**
 * Automatic quality: watches how much video is waiting to be sent to the TV.
 * When the Wi-Fi can't keep up, the encoder bitrate goes down quickly; when the
 * connection has been comfortable for a while, it creeps back up to the maximum.
 */
class AdaptiveBitrate(
    private val maxBps: Int,
    private val minBps: Int = 1_000_000,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val apply: (Int) -> Unit,
) : StreamHub.CongestionListener {

    @Volatile var currentBps: Int = maxBps
        private set

    private var laggingSince = -1L
    private var smoothSince = -1L
    private var lastChange = 0L

    @Synchronized
    override fun onClientLag(lagUs: Long) {
        val now = clock()
        when {
            lagUs > LAG_HIGH_US -> {
                smoothSince = -1
                if (laggingSince < 0) laggingSince = now
                if (now - laggingSince >= LAG_HOLD_MS && now - lastChange >= DOWN_COOLDOWN_MS) change(0.7, now)
            }
            lagUs < LAG_LOW_US -> {
                laggingSince = -1
                if (smoothSince < 0) smoothSince = now
                if (currentBps < maxBps && now - smoothSince >= UP_AFTER_MS && now - lastChange >= UP_AFTER_MS) {
                    change(1.2, now)
                    smoothSince = now
                }
            }
            else -> {
                laggingSince = -1
                smoothSince = -1
            }
        }
    }

    @Synchronized
    override fun onClientDropped() {
        val now = clock()
        if (now - lastChange >= 1000) change(0.5, now)
        smoothSince = -1
    }

    private fun change(factor: Double, now: Long) {
        val next = (currentBps * factor).toInt().coerceIn(minBps, maxBps)
        lastChange = now
        laggingSince = -1
        if (next == currentBps) return
        currentBps = next
        apply(next)
    }

    companion object {
        const val LAG_HIGH_US = 350_000L
        const val LAG_LOW_US = 120_000L
        const val LAG_HOLD_MS = 700L
        const val DOWN_COOLDOWN_MS = 2000L
        const val UP_AFTER_MS = 10_000L
    }
}
