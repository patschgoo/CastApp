package io.github.patschgoo.castapp.remote

import android.content.Context
import android.hardware.ConsumerIrManager
import io.github.patschgoo.castapp.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * TV remotes over the phone's infrared emitter (Xiaomi / Redmi / POCO and a few others).
 * One target per brand in [IrCodes]; the protocols are in [IrEncoders].
 */
object InfraredRemotes : RemoteProvider {
    override val title = "Infrared"
    private val list = MutableStateFlow<List<RemoteTarget>>(emptyList())

    override fun targets(context: Context): StateFlow<List<RemoteTarget>> {
        val app = context.applicationContext
        if (list.value.isEmpty() && irManager(app) != null) {
            list.value = IrCodes.all.map { Target(app, it) }
        }
        return list
    }

    private fun irManager(context: Context): ConsumerIrManager? = try {
        (context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager)?.takeIf { it.hasIrEmitter() }
    } catch (_: Exception) {
        null
    }

    private class Target(app: Context, private val brand: IrBrand) : RemoteTarget {
        override val id = "ir:${brand.id}"
        override val name = app.getString(if (brand.older) R.string.ir_remote_tv_older else R.string.ir_remote_tv, brand.brand)
        override val label = app.getString(R.string.ir_remote_label)
        override val keys: Set<RemoteKey> = brand.buttons.keys

        override suspend fun connect(context: Context, ui: RemoteUi): RemoteSession {
            val app = context.applicationContext
            val ir = irManager(app) ?: throw IOException(app.getString(R.string.ir_remote_no_emitter))
            val ranges = try {
                ir.carrierFrequencies?.map { it.minFrequency..it.maxFrequency }.orEmpty()
            } catch (_: Exception) {
                emptyList()
            }
            ui.showMessage(app.getString(R.string.ir_remote_aim))
            return Session(app, ir, brand, ranges)
        }
    }

    private class Session(
        private val app: Context,
        private val ir: ConsumerIrManager,
        private val brand: IrBrand,
        private val carriers: List<IntRange>,
    ) : RemoteSession {
        private val presser = IrPresser()
        private val lock = Mutex()

        override suspend fun press(key: RemoteKey) {
            val codes = brand.buttons[key] ?: return
            lock.withLock {
                val plan = presser.plan(key, codes, now())
                val wait = plan.notBeforeMs - now()
                if (wait > 0) delay(wait)
                presser.started(now())
                try {
                    // transmit() blocks for the length of the pattern on most phones.
                    withContext(Dispatchers.IO) { ir.transmit(pickCarrier(plan.carrierHz, carriers), plan.pattern) }
                } catch (e: Exception) {
                    throw IOException(app.getString(R.string.ir_remote_failed), e)
                }
            }
        }

        private fun now() = android.os.SystemClock.elapsedRealtime()

        override fun close() {}
    }
}
