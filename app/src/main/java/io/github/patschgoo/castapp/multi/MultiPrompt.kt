package io.github.patschgoo.castapp.multi

import io.github.patschgoo.castapp.ui.focusRing
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.patschgoo.castapp.CastApp
import io.github.patschgoo.castapp.R
import io.github.patschgoo.castapp.settings.ThemeMode
import io.github.patschgoo.castapp.tv.Decision
import io.github.patschgoo.castapp.tv.TvReceiver
import io.github.patschgoo.castapp.tv.TvReceiverService
import io.github.patschgoo.castapp.ui.theme.CastAppTheme
import kotlinx.coroutines.delay

/** Brings a question from another CastApp device on screen: a dialog, and a notification that opens it. */
object MultiPrompt {
    private val main = Handler(Looper.getMainLooper())
    private const val NOTIFICATION_ID = 43

    /** From a network thread: someone wants to watch, offers a cast, or asks for this one. */
    fun attention(context: Context) {
        val app = context.applicationContext
        main.post {
            try {
                app.startActivity(
                    Intent(app, MultiPromptActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                )
            } catch (_: Exception) {
            }
            notify(app)
        }
    }

    private fun notify(app: Context) {
        if (!Peers.isReady) return
        val hub = Peers.hub
        val (title, text) = hub.incoming.pending.value?.let {
            app.getString(R.string.multi_offer_title, it.offer.fromName, screensText(app, it.offer)) to app.getString(R.string.multi_offer_question)
        } ?: hub.requests.pending.value?.let {
            app.getString(R.string.multi_request_title, it.name) to app.getString(R.string.multi_request_question)
        } ?: hub.joinGuard.pending.value.firstOrNull()?.let {
            app.getString(R.string.multi_join_prompt_title, it.name) to app.getString(R.string.multi_join_prompt_text, it.address)
        } ?: return
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        TvReceiverService.createChannels(app)
        val open = PendingIntent.getActivity(
            app, 4, Intent(app, MultiPromptActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(app, TvReceiverService.CHANNEL_PLAY)
            .setSmallIcon(R.drawable.ic_cast)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .build()
        try { nm.notify(NOTIFICATION_ID, n) } catch (_: SecurityException) {}
    }

    fun clear(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    /** "Living Room TV" or "Living Room TV and Kitchen". */
    fun screensText(context: Context, offer: HandOverOffer): String {
        val names = offer.screens.map { it.name }
        return when (names.size) {
            0 -> ""
            1 -> names[0]
            else -> context.getString(R.string.multi_screens_and, names.dropLast(1).joinToString(", "), names.last())
        }
    }
}

/**
 * "<A> offers you <TVs>. Take over?", "<B> wants to cast. Hand over?" and "Allow <C> to watch?".
 * Works with a D-pad: the main answer has the focus.
 */
class MultiPromptActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Peers.isReady) {
            finish()
            return
        }
        val app = CastApp.get(this)
        val tv = TvReceiver.isTv(this)
        val hub = Peers.hub
        setContent {
            val settings by app.settings.state.collectAsStateWithLifecycle()
            val offer by hub.incoming.pending.collectAsStateWithLifecycle()
            val request by hub.requests.pending.collectAsStateWithLifecycle()
            val joins by hub.joinGuard.pending.collectAsStateWithLifecycle()
            val join = joins.firstOrNull()
            // Said yes to an offer: stay until the other device answered (Android only lets a visible screen ask for capture).
            var accepting by remember { mutableStateOf(false) }
            val nothing = offer == null && request == null && join == null && !accepting
            LaunchedEffect(nothing) {
                if (nothing) {
                    MultiPrompt.clear(this@MultiPromptActivity)
                    finish()
                }
            }
            // Questions run out by themselves; the holders only notice when asked.
            LaunchedEffect(Unit) {
                while (true) {
                    delay(1000)
                    hub.incoming.prune()
                    hub.requests.prune()
                    hub.joinGuard.prune()
                }
            }
            CastAppTheme(if (tv) ThemeMode.DARK else settings.theme) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(offer?.offer?.id, request?.id, join?.id) { try { focus.requestFocus() } catch (_: Exception) {} }
                val o = offer
                val r = request
                when {
                    accepting -> AlertDialog(
                        onDismissRequest = {},
                        title = { Text(stringResource(R.string.multi_take_over)) },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(stringResource(R.string.multi_state_connecting))
                            }
                        },
                        confirmButton = {},
                    )
                    o != null -> AlertDialog(
                        onDismissRequest = { finish() },
                        title = { Text(stringResource(R.string.multi_offer_title, o.offer.fromName, MultiPrompt.screensText(this, o.offer))) },
                        text = {
                            Column {
                                Text(
                                    when {
                                        o.offer.mode == HandOverMode.CONTROL -> stringResource(R.string.multi_offer_control_text, o.offer.title)
                                        o.offer.isScreen -> stringResource(R.string.multi_offer_screen_text)
                                        else -> stringResource(R.string.multi_offer_file_text, o.offer.title)
                                    },
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(stringResource(R.string.multi_offer_question), style = MaterialTheme.typography.bodyMedium)
                            }
                        },
                        confirmButton = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { Peers.declineOffer(this@MultiPromptActivity, o) }, modifier = Modifier.focusRing()) { Text(stringResource(R.string.multi_decline)) }
                                Button(
                                    onClick = {
                                        hub.incoming.accept(o.offer.id)?.let {
                                            accepting = true
                                            Peers.acceptOffer(
                                                this@MultiPromptActivity, it,
                                                starter = { intent -> startActivity(intent) },
                                                onAnswered = { accepting = false },
                                            )
                                        }
                                    },
                                    modifier = Modifier.focusRequester(focus).focusRing(),
                                ) { Text(stringResource(if (o.offer.mode == HandOverMode.CONTROL) R.string.multi_take_controls else R.string.multi_take_over)) }
                            }
                        },
                    )
                    r != null -> AlertDialog(
                        onDismissRequest = { finish() },
                        title = { Text(stringResource(R.string.multi_request_title, r.name)) },
                        text = { Text(stringResource(R.string.multi_request_question), style = MaterialTheme.typography.bodyLarge) },
                        confirmButton = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { Peers.answerRequest(this@MultiPromptActivity, r, handOver = false) }, modifier = Modifier.focusRing()) { Text(stringResource(R.string.multi_decline)) }
                                Button(
                                    onClick = { Peers.answerRequest(this@MultiPromptActivity, r, handOver = true) },
                                    modifier = Modifier.focusRequester(focus).focusRing(),
                                ) { Text(stringResource(R.string.multi_hand_over)) }
                            }
                        },
                    )
                    join != null -> AlertDialog(
                        onDismissRequest = { finish() },
                        title = { Text(stringResource(R.string.multi_join_prompt_title, join.name)) },
                        text = {
                            Column {
                                Text(stringResource(R.string.multi_join_prompt_text, join.address), style = MaterialTheme.typography.bodyLarge)
                                if (joins.size > 1) {
                                    Spacer(Modifier.height(8.dp))
                                    Text(stringResource(R.string.protect_prompt_more, joins.size - 1), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        },
                        confirmButton = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { hub.joinGuard.answer(join.id, Decision.BLOCK) }, modifier = Modifier.focusRing()) { Text(stringResource(R.string.protect_block)) }
                                TextButton(onClick = { hub.joinGuard.answer(join.id, Decision.ALLOW_ALWAYS) }, modifier = Modifier.focusRing()) { Text(stringResource(R.string.protect_allow_always)) }
                                Button(onClick = { hub.joinGuard.answer(join.id, Decision.ALLOW_ONCE) }, modifier = Modifier.focusRequester(focus).focusRing()) {
                                    Text(stringResource(R.string.protect_allow))
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}
