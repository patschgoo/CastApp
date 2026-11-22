package io.github.patschgoo.castapp

import android.app.Application
import android.content.Context
import io.github.patschgoo.castapp.discovery.DeviceRepository
import io.github.patschgoo.castapp.service.CastService
import io.github.patschgoo.castapp.settings.AppSettings

class CastApp : Application() {
    lateinit var settings: AppSettings
        private set
    lateinit var devices: DeviceRepository
        private set

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        settings.applyTheme()
        devices = DeviceRepository(this)
        CastService.createChannel(this)
    }

    companion object {
        fun get(context: Context): CastApp = context.applicationContext as CastApp
    }
}
