package io.github.patschgoo.castapp

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.patschgoo.castapp.iptv.RecordingState
import io.github.patschgoo.castapp.iptv.Recordings
import io.github.patschgoo.castapp.iptv.RecordingsScreen
import io.github.patschgoo.castapp.ui.theme.CastAppTheme

/** Recordings and reminders of TV channels: what is planned, running and saved. */
class RecordingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        startDueRecording(intent)
        val app = CastApp.get(this)
        setContent {
            val settings by app.settings.state.collectAsStateWithLifecycle()
            CastAppTheme(settings.theme) {
                RecordingsScreen(onBack = { finish() })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        startDueRecording(intent)
    }

    /** A notification said a planned recording is due and Android did not let the app start it by itself: do it now. */
    private fun startDueRecording(intent: Intent?) {
        val id = intent?.getStringExtra(EXTRA_START_ID) ?: return
        intent.removeExtra(EXTRA_START_ID)
        val center = Recordings.get(this)
        if (center.book.get(id)?.state == RecordingState.SCHEDULED) center.start(id)
    }

    override fun onStart() {
        super.onStart()
        CastApp.get(this).devices.startDiscovery()
    }

    override fun onStop() {
        CastApp.get(this).devices.stopDiscovery()
        super.onStop()
    }

    companion object {
        const val EXTRA_START_ID = "start_id"
    }
}
