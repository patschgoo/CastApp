package io.github.patschgoo.castapp.support

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri
import io.github.patschgoo.castapp.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Phone makers whose battery savers stop apps with the screen off, and need an extra setting. */
enum class Brand(val tipRes: Int?) {
    XIAOMI(R.string.battery_tip_xiaomi),
    SAMSUNG(R.string.battery_tip_samsung),
    HUAWEI(R.string.battery_tip_huawei),
    ONEPLUS_OPPO(R.string.battery_tip_oneplus),
    OTHER(null),
    ;

    companion object {
        /** Maps `Build.MANUFACTURER` (and brand) to a [Brand]. */
        fun of(manufacturer: String?, brand: String? = null): Brand {
            val m = "${manufacturer.orEmpty()} ${brand.orEmpty()}".lowercase()
            return when {
                listOf("xiaomi", "redmi", "poco").any { it in m } -> XIAOMI
                "samsung" in m -> SAMSUNG
                listOf("huawei", "honor").any { it in m } -> HUAWEI
                listOf("oneplus", "oppo", "realme").any { it in m } -> ONEPLUS_OPPO
                else -> OTHER
            }
        }
    }
}

/**
 * Casting a file keeps playing with the screen off, but many phones then stop the app.
 * This explains it once, and asks Android to leave CastApp alone.
 */
object BatteryHelp {
    fun isIgnoring(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return try { pm.isIgnoringBatteryOptimizations(context.packageName) } catch (_: Exception) { false }
    }

    /** The system dialog that asks to exempt CastApp (needs REQUEST_IGNORE_BATTERY_OPTIMIZATIONS). */
    @SuppressLint("BatteryLife")
    fun requestIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())

    /** The list of all apps' battery settings, the fallback when the direct request is unavailable. */
    fun listIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    fun currentBrand(): Brand = Brand.of(Build.MANUFACTURER, Build.BRAND)
}

/**
 * The one-time "keep playing with the screen off" dialog. Call [onBackgroundCastStarted]
 * where casting something that must keep playing with the screen off starts.
 */
object BatteryPrompt {
    private val _pending = MutableStateFlow(false)
    /** True while the dialog should be shown. */
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    /** Pure rule: ask only when not asked before and the app is not exempt yet. */
    fun shouldAsk(alreadyAsked: Boolean, exempt: Boolean): Boolean = !alreadyAsked && !exempt

    fun onBackgroundCastStarted(context: Context) {
        val app = context.applicationContext
        val asked = SupportPrefs.of(app).getBoolean(SupportPrefs.BATTERY_ASKED, false)
        if (shouldAsk(asked, BatteryHelp.isIgnoring(app))) _pending.value = true
    }

    /** The dialog was shown and dealt with; don't ask again by itself. */
    fun finished(context: Context) {
        SupportPrefs.of(context).edit().putBoolean(SupportPrefs.BATTERY_ASKED, true).apply()
        _pending.value = false
    }

    /** Opened from Settings: show the dialog regardless of earlier answers. */
    fun showFromSettings() {
        _pending.value = true
    }
}
