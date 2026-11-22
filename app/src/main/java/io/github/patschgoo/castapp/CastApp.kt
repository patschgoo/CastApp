package io.github.patschgoo.castapp

import android.app.Application
import android.content.Context
import io.github.patschgoo.castapp.discovery.DeviceRepository
import io.github.patschgoo.castapp.iptv.IptvRepository
import io.github.patschgoo.castapp.network.NetworkSources
import io.github.patschgoo.castapp.service.CastService
import io.github.patschgoo.castapp.settings.AppSettings

class CastApp : Application() {
    lateinit var settings: AppSettings
        private set
    lateinit var devices: DeviceRepository
        private set
    lateinit var network: NetworkSources
        private set
    lateinit var iptv: IptvRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = AppSettings(this)
        settings.applyTheme()
        devices = DeviceRepository(this)
        network = NetworkSources(this)
        iptv = IptvRepository(this)
        io.github.patschgoo.castapp.subtitles.VoskModels.refresh(this)
        CastService.createChannel(this)
    }

    companion object {
        /** For code without a Context at hand (e.g. asking for an AirPlay code). */
        lateinit var instance: CastApp
            private set

        fun get(context: Context): CastApp = context.applicationContext as CastApp
    }
}
