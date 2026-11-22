package io.github.patschgoo.castapp.tv

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.patschgoo.castapp.CastApp
import io.github.patschgoo.castapp.R
import io.github.patschgoo.castapp.settings.ThemeMode
import io.github.patschgoo.castapp.ui.theme.CastAppTheme
import kotlinx.coroutines.delay

/** Keeps the receiver's decisions in its preferences. */
class PrefsGuardStore(private val prefs: SharedPreferences) : GuardStore {
    override fun load(): GuardData = GuardData.fromJson(prefs.getString(KEY, null))

    override fun save(data: GuardData) {
        prefs.edit().putString(KEY, data.toJson()).apply()
    }

    private companion object {
        const val KEY = "protection"
    }
}

/** Brings a question for the receiver's owner on screen: a dialog, and a notification with the same answers. */
object ReceiverPrompt {
    private val main = Handler(Looper.getMainLooper())
    private const val NOTIFICATION_ID = 42
    internal const val ACTION_ANSWER = "io.github.patschgoo.castapp.action.RECEIVER_ANSWER"
    internal const val EXTRA_ID = "id"
    internal const val EXTRA_DECISION = "decision"

    /** From a network thread: a sender waits for an answer, or a phone wants to pair. */
    fun attention(context: Context, guard: ReceiverGuard) {
        val app = context.applicationContext
        main.post {
            try {
                app.startActivity(
                    Intent(app, ReceiverPromptActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                )
            } catch (_: Exception) {
            }
            notify(app, guard)
        }
    }

    private fun notify(app: Context, guard: ReceiverGuard) {
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        TvReceiverService.createChannels(app)
        val open = PendingIntent.getActivity(
            app, 3, Intent(app, ReceiverPromptActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val pending = guard.pending.value.firstOrNull()
        val builder = NotificationCompat.Builder(app, TvReceiverService.CHANNEL_PLAY)
            .setSmallIcon(R.drawable.ic_cast)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setAutoCancel(true)
            .setTimeoutAfter(30_000)
        if (pending != null) {
            builder.setContentTitle(app.getString(R.string.protect_prompt_title, pending.name))
                .setContentText(app.getString(R.string.protect_prompt_text, pending.address))
            fun action(decision: Decision, label: Int, request: Int) = builder.addAction(
                R.drawable.ic_cast, app.getString(label),
                PendingIntent.getBroadcast(
                    app, request,
                    Intent(app, ReceiverPromptReceiver::class.java).setAction(ACTION_ANSWER)
                        .putExtra(EXTRA_ID, pending.id).putExtra(EXTRA_DECISION, decision.name),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            action(Decision.ALLOW_ONCE, R.string.protect_allow, 10)
            action(Decision.ALLOW_ALWAYS, R.string.protect_allow_always, 11)
            action(Decision.BLOCK, R.string.protect_block, 12)
        } else {
            val notice = guard.pairing.value ?: return
            builder.setContentTitle(app.getString(R.string.protect_pairing_title, notice.name))
                .setContentText(app.getString(R.string.protect_pairing_notification, guard.code))
        }
        try { nm.notify(NOTIFICATION_ID, builder.build()) } catch (_: SecurityException) {}
    }

    fun clear(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }
}

/** The buttons of the prompt notification. */
class ReceiverPromptReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReceiverPrompt.ACTION_ANSWER) return
        val decision = Decision.entries.firstOrNull { it.name == intent.getStringExtra(ReceiverPrompt.EXTRA_DECISION) } ?: return
        TvReceiver.init(context)
        TvReceiver.guard.answer(intent.getLongExtra(ReceiverPrompt.EXTRA_ID, -1), decision)
        ReceiverPrompt.clear(context)
    }
}

/**
 * "Allow Pixel 8 to cast?" (or, for "Only paired phones", the code to type in on the phone).
 * Works with a D-pad: Allow has the focus at first.
 */
class ReceiverPromptActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TvReceiver.init(this)
        val app = CastApp.get(this)
        val tv = TvReceiver.isTv(this)
        setContent {
            val settings by app.settings.state.collectAsStateWithLifecycle()
            val guard = TvReceiver.guard
            val pending by guard.pending.collectAsStateWithLifecycle()
            val notice by guard.pairing.collectAsStateWithLifecycle()
            val first = pending.firstOrNull()
            LaunchedEffect(first == null && notice == null) {
                if (first == null && notice == null) {
                    ReceiverPrompt.clear(this@ReceiverPromptActivity)
                    finish()
                }
            }
            // Questions run out by themselves; the guard only notices when asked.
            LaunchedEffect(Unit) {
                while (true) {
                    delay(1000)
                    guard.prune()
                }
            }
            CastAppTheme(if (tv) ThemeMode.DARK else settings.theme) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(first?.id, notice) { try { focus.requestFocus() } catch (_: Exception) {} }
                if (first != null) {
                    AlertDialog(
                        onDismissRequest = { finish() },
                        title = { Text(stringResource(R.string.protect_prompt_title, first.name)) },
                        text = {
                            Column {
                                Text(
                                    stringResource(if (tv) R.string.protect_prompt_text_tv else R.string.protect_prompt_text_phone, first.address),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                if (pending.size > 1) {
                                    Spacer(Modifier.height(8.dp))
                                    Text(stringResource(R.string.protect_prompt_more, pending.size - 1), style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        },
                        confirmButton = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { guard.answer(first.id, Decision.BLOCK) }) { Text(stringResource(R.string.protect_block)) }
                                TextButton(onClick = { guard.answer(first.id, Decision.ALLOW_ALWAYS) }) { Text(stringResource(R.string.protect_allow_always)) }
                                Button(onClick = { guard.answer(first.id, Decision.ALLOW_ONCE) }, modifier = Modifier.focusRequester(focus)) {
                                    Text(stringResource(R.string.protect_allow))
                                }
                            }
                        },
                    )
                } else if (notice != null) {
                    PairingCodeDialog(notice!!.name, guard.code, focus) { finish() }
                }
            }
        }
    }
}

@Composable
private fun PairingCodeDialog(name: String, code: String, focus: FocusRequester, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.protect_pairing_title, name)) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.protect_pairing_text), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Text(code, style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.primary)
            }
        },
        confirmButton = { Button(onClick = onClose, modifier = Modifier.focusRequester(focus)) { Text(stringResource(R.string.protect_close)) } },
    )
}
