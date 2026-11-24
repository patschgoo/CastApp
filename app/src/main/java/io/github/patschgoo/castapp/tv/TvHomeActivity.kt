package io.github.patschgoo.castapp.tv

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.patschgoo.castapp.BuildConfig
import io.github.patschgoo.castapp.R
import io.github.patschgoo.castapp.settings.ThemeMode
import io.github.patschgoo.castapp.ui.theme.CastAppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The TV home of CastApp (shown on Fire TV, Android TV, Google TV): starts the
 * receiver, shows the name to pick on the phone, and holds the receiver's few settings.
 */
class TvHomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TvReceiver.init(this)
        TvReceiverService.start(this)
        setContent {
            CastAppTheme(ThemeMode.DARK) { TvHome() }
        }
    }

    override fun onResume() {
        super.onResume()
        TvReceiverService.start(this)
    }
}

/** The settings that open as a panel on the right. */
private enum class HomePanel { PROTECT, NAME, PLAYS, HOW }

/**
 * One screen, made for the remote: the TV's name on the left, large enough to read from the sofa,
 * and on the right one list to move through with Up and Down. Every row opens a [TvPanel]; Back
 * closes it. The first row has the focus as soon as the screen shows.
 *
 * [capabilities] replaces the TV's own answer to "what can it play" (screenshots).
 */
@Composable
internal fun TvHome(capabilities: Map<String, Any>? = null) {
    val context = LocalContext.current
    TvReceiver.init(context)
    val running by TvReceiverService.running.collectAsStateWithLifecycle()
    val address by TvReceiverService.address.collectAsStateWithLifecycle()
    val tvState by TvReceiver.state.collectAsStateWithLifecycle()
    val guard = TvReceiver.guard
    val data by guard.data.collectAsStateWithLifecycle()
    val mode = data.mode ?: guard.mode
    var name by remember { mutableStateOf(TvReceiver.deviceName(context)) }
    var startOnBoot by remember { mutableStateOf(TvReceiver.startOnBoot(context)) }
    var panel by remember { mutableStateOf<HomePanel?>(null) }

    // Back from Android's settings: the permission may have been given.
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes++ }
    val needsOverlay = remember(resumes) { Build.VERSION.SDK_INT >= 29 && !Settings.canDrawOverlays(context) }

    // Asking every decoder takes a moment: not on the main thread.
    val caps by produceState(capabilities) {
        if (value == null) {
            value = withContext(Dispatchers.Default) {
                try { TvReceiver.capabilities() } catch (_: Exception) { emptyMap() }
            }
        }
    }

    // Something was cast and its player was left with Home: one press brings it back.
    val media = TvReceiver.media
    val active = tvState.state == TransportState.PLAYING || tvState.state == TransportState.PAUSED || tvState.state == TransportState.TRANSITIONING
    val playing = media != null && active

    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { try { first.requestFocus() } catch (_: Exception) {} }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Row(Modifier.fillMaxSize().padding(horizontal = TvSize.marginSide), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            Column(
                Modifier.weight(1f).fillMaxHeight().padding(vertical = TvSize.marginTop),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CastConnected, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.tv_title), style = tvText(TvSize.body, FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(if (running) Color(0xFF4CAF50) else Color(0xFFFFA000)))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            when {
                                !running -> stringResource(R.string.tv_starting)
                                tvState.state == TransportState.PLAYING -> stringResource(R.string.tv_playing)
                                else -> stringResource(R.string.tv_ready)
                            },
                            style = tvText(22.sp, FontWeight.SemiBold),
                        )
                    }
                    // A long name ("Wohnzimmer Fire TV Stick 4K") gets the smaller size instead of three lines.
                    val nameSize = if (name.length > 18) 40.sp else TvSize.name
                    Text(
                        name,
                        style = tvText(nameSize, FontWeight.Bold).copy(lineHeight = nameSize * 1.1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(stringResource(R.string.tv_hint_pick), style = tvText(TvSize.label), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    listOfNotNull(address, stringResource(R.string.tv_version, BuildConfig.VERSION_NAME)).joinToString(" · "),
                    style = tvText(TvSize.small),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                // The padding lies inside the scrolling list, so the focus ring isn't cut off at its edges.
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = TvSize.marginTop),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Whichever row comes first gets the focus when the screen opens.
                    var given = false
                    fun top(): Modifier = if (given) Modifier else Modifier.focusRequester(first).also { given = true }

                    if (playing) {
                        TvRow(stringResource(R.string.tv_row_video), modifier = top(), hint = media?.title?.takeIf { it.isNotBlank() }) { TvReceiver.play() }
                    }
                    if (needsOverlay) {
                        TvRow(
                            stringResource(R.string.tv_background_title),
                            modifier = top(),
                            hint = stringResource(R.string.tv_background_text),
                            value = stringResource(R.string.tv_background_allow),
                            attention = true,
                        ) { openOverlaySetting(context) }
                    }
                    TvRow(
                        stringResource(R.string.protect_title),
                        modifier = top(),
                        value = stringResource(protectionTitle(mode)) +
                            if (mode == ProtectionMode.PAIRED) "\n" + stringResource(R.string.tv_code_value, data.code ?: guard.code) else "",
                    ) { panel = HomePanel.PROTECT }
                    TvRow(stringResource(R.string.tv_row_name), value = name) { panel = HomePanel.NAME }
                    TvRow(stringResource(R.string.tv_row_boot), checked = startOnBoot) {
                        startOnBoot = !startOnBoot
                        TvReceiver.setStartOnBoot(context, startOnBoot)
                    }
                    TvRow(stringResource(R.string.tv_row_plays), value = playsSummary(caps)) { panel = HomePanel.PLAYS }
                    TvRow(stringResource(R.string.tv_how_title)) { panel = HomePanel.HOW }
                }
            }
        }
    }

    when (panel) {
        HomePanel.PROTECT -> ReceiverProtectionPanel { panel = null }
        HomePanel.NAME -> NamePanel(
            name,
            onSave = {
                TvReceiver.setDeviceName(context, it)
                name = TvReceiver.deviceName(context)
                TvReceiverService.nameChanged(context)
                panel = null
            },
            onClose = { panel = null },
        )
        HomePanel.PLAYS -> PlaysPanel(caps) { panel = null }
        HomePanel.HOW -> HowPanel(name) { panel = null }
        null -> Unit
    }
}

private fun openOverlaySetting(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, ("package:" + context.packageName).toUri()))
    } catch (_: Exception) {
        android.widget.Toast.makeText(context, R.string.tv_background_unavailable, android.widget.Toast.LENGTH_LONG).show()
    }
}

/** The row's short answer: "Not Hi10P, AV1, DTS", or that everything common plays. */
@Composable
private fun playsSummary(caps: Map<String, Any>?): String {
    if (caps == null) return stringResource(R.string.tv_plays_checking)
    val missing = TvPlayable.missing(caps)
    if (missing.isEmpty()) return stringResource(R.string.tv_plays_all)
    val shown = missing.take(3).joinToString(", ") + if (missing.size > 3) " …" else ""
    return stringResource(R.string.tv_plays_not, shown)
}

@Composable
private fun NamePanel(name: String, onSave: (String) -> Unit, onClose: () -> Unit) {
    TvPanel(stringResource(R.string.tv_row_name), onClose) { first ->
        var draft by remember { mutableStateOf(name) }
        Text(stringResource(R.string.tv_name_label), style = tvText(TvSize.value), color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            singleLine = true,
            textStyle = tvText(TvSize.label),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSave(draft) }),
            modifier = Modifier.fillMaxWidth().focusRequester(first),
        )
        TvButton(stringResource(R.string.save), primary = true, modifier = Modifier.padding(top = 8.dp)) { onSave(draft) }
    }
}

/** What plays, what may stutter and what doesn't, for the picture and for the sound. */
@Composable
private fun PlaysPanel(caps: Map<String, Any>?, onClose: () -> Unit) {
    TvPanel(stringResource(R.string.tv_row_plays), onClose, width = 520.dp) {
        if (caps == null) {
            Text(stringResource(R.string.tv_plays_checking), style = tvText(TvSize.body))
        } else {
            PlaysGroup(stringResource(R.string.tv_plays_picture), TvPlayable.video(caps))
            PlaysGroup(stringResource(R.string.tv_plays_sound), TvPlayable.sound(caps))
            Text(stringResource(R.string.tv_plays_note), style = tvText(TvSize.value), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PlaysGroup(title: String, lists: TvPlayable.Lists) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        Text(title, style = tvText(TvSize.label, FontWeight.SemiBold))
        PlaysLine(R.string.tv_plays_yes, lists.plays)
        PlaysLine(R.string.tv_plays_slow, lists.slow)
        PlaysLine(R.string.tv_plays_no, lists.not)
    }
}

@Composable
private fun PlaysLine(label: Int, formats: List<String>) {
    if (formats.isEmpty()) return
    Text(stringResource(label), style = tvText(TvSize.small), color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(formats.joinToString(", "), style = tvText(TvSize.body))
}

@Composable
private fun HowPanel(name: String, onClose: () -> Unit) {
    TvPanel(stringResource(R.string.tv_how_title), onClose, width = 560.dp) {
        for (step in listOf(R.string.tv_step1, R.string.tv_step2, R.string.tv_step3)) {
            Text(stringResource(step, name), style = tvText(TvSize.body))
        }
        Text(stringResource(R.string.tv_other_apps), style = tvText(TvSize.value), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
