package io.github.patschgoo.castapp

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import io.github.patschgoo.castapp.ui.readableWidth

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Input
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Dialpad
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.patschgoo.castapp.remote.NetworkRemotes
import io.github.patschgoo.castapp.remote.RemoteKey
import io.github.patschgoo.castapp.remote.RemoteProvider
import io.github.patschgoo.castapp.remote.RemoteProviders
import io.github.patschgoo.castapp.remote.RemoteTarget
import io.github.patschgoo.castapp.ui.theme.CastAppTheme

/**
 * A TV remote on the phone: pick a TV (network, Bluetooth or infrared), then steer it
 * with power, a D-pad, volume, channels, media keys, numbers and typing.
 */
class RemoteActivity : ComponentActivity() {
    private lateinit var vm: RemoteViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        vm = ViewModelProvider(this)[RemoteViewModel::class.java]
        vm.start(intent?.getStringExtra(EXTRA_TARGET))
        val app = CastApp.get(this)
        setContent {
            val settings by app.settings.state.collectAsStateWithLifecycle()
            CastAppTheme(settings.theme) {
                RemoteScreen(vm, onBack = { finish() })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_TARGET)?.let { vm.start(it, force = true) }
    }

    override fun onStart() {
        super.onStart()
        for (p in RemoteProviders.all) try { p.startDiscovery(this) } catch (_: Exception) {}
    }

    override fun onStop() {
        vm.stopVoice()
        for (p in RemoteProviders.all) try { p.stopDiscovery() } catch (_: Exception) {}
        super.onStop()
    }

    companion object {
        private const val EXTRA_TARGET = "target"

        /** Opens the remote; with [targetId] it connects to that TV, otherwise to the one used last. */
        fun intent(context: Context, targetId: String? = null): Intent =
            Intent(context, RemoteActivity::class.java).apply { if (targetId != null) putExtra(EXTRA_TARGET, targetId) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteScreen(vm: RemoteViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val showList by vm.showList.collectAsStateWithLifecycle()
    val code by vm.codeRequest.collectAsStateWithLifecycle()
    val searchingFor by vm.searchingFor.collectAsStateWithLifecycle()
    val target = state.target
    val listVisible = showList || (target == null && searchingFor == null)

    BackHandler(enabled = showList && target != null) { vm.setShowList(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            listVisible -> stringResource(R.string.remote_pick_tv)
                            target != null -> target.name
                            else -> stringResource(R.string.remote_title)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (showList && target != null) vm.setShowList(false) else onBack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    if (!listVisible) {
                        IconButton(onClick = { vm.setShowList(true) }) {
                            Icon(Icons.Rounded.SwapHoriz, contentDescription = stringResource(R.string.remote_switch))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                listVisible -> TargetList(state, onPick = { vm.connect(it) })
                target == null -> Searching(searchingFor)
                else -> RemoteControls(state, target, vm)
            }
        }
    }

    code?.let { request -> CodeDialog(request, onAnswer = { vm.answerCode(it) }) }
}

@Composable
private fun Searching(name: String?) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(
            if (name.isNullOrEmpty()) stringResource(R.string.remote_searching) else stringResource(R.string.remote_connecting, name),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

// ---- target list -------------------------------------------------------------

@Composable
private fun TargetList(state: RemoteUiState, onPick: (RemoteTarget) -> Unit) {
    val context = LocalContext.current
    val providers = remember { RemoteProviders.all }
    val sections = providers.map { p -> p to p.targets(context).collectAsStateWithLifecycle().value }
    LazyColumn(Modifier.fillMaxSize().readableWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        state.error?.let { error ->
            item(key = "error") { ErrorCard(error, onRetry = state.target?.let { t -> { onPick(t) } }) }
        }
        item(key = "hint") {
            Text(
                stringResource(R.string.remote_list_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        for ((provider, targets) in sections) {
            item(key = "h:" + provider.title) { SectionHeader(provider, targets.isEmpty()) }
            items(targets, key = { provider.title + "/" + it.id }) { t ->
                TargetRow(t, current = state.target?.id == t.id && state.session != null, onClick = { onPick(t) })
            }
        }
    }
}

@Composable
private fun SectionHeader(provider: RemoteProvider, empty: Boolean) {
    Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp)) {
        Text(provider.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { heading() })
        if (empty) {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (provider === NetworkRemotes) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    stringResource(if (provider === NetworkRemotes) R.string.remote_searching else R.string.remote_none_found),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TargetRow(target: RemoteTarget, current: Boolean, onClick: () -> Unit) {
    val icon = when (target.label) {
        stringResource(R.string.bt_remote_label) -> Icons.Rounded.Bluetooth
        stringResource(R.string.ir_remote_label) -> Icons.Rounded.SettingsRemote
        else -> Icons.Rounded.Tv
    }
    ListItem(
        onClick = onClick,
        leadingContent = { Icon(icon, contentDescription = null) },
        supportingContent = { Text(target.label) },
        trailingContent = if (current) ({ Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary) }) else null,
    ) { Text(target.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@Composable
private fun ErrorCard(message: String, onRetry: (() -> Unit)?) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
            if (onRetry != null) {
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = onRetry) { Text(stringResource(R.string.remote_reconnect)) }
            }
        }
    }
}

// ---- dialogs -----------------------------------------------------------------

@Composable
private fun CodeDialog(request: CodeRequest, onAnswer: (String?) -> Unit) {
    var code by rememberSaveable(request) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(request) { try { focus.requestFocus() } catch (_: Exception) {} }
    AlertDialog(
        onDismissRequest = { onAnswer(null) },
        title = { Text(request.title) },
        text = {
            Column {
                Text(request.message, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { v -> code = v.filterNot { it.isWhitespace() }.take(16) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.remote_code_label)) },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { if (code.isNotBlank()) onAnswer(code) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onAnswer(code) }, enabled = code.isNotBlank()) { Text(stringResource(R.string.remote_ok)) } },
        dismissButton = { TextButton(onClick = { onAnswer(null) }) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun TypeDialog(onSend: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { try { focus.requestFocus() } catch (_: Exception) {} }
    fun send() {
        if (text.isEmpty()) return
        onSend(text)
        onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remote_type_title)) },
        text = {
            Column {
                Text(stringResource(R.string.remote_type_hint), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(200) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.remote_type_label)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        },
        confirmButton = { TextButton(onClick = { send() }, enabled = text.isNotEmpty()) { Text(stringResource(R.string.remote_send)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

// ---- the remote ----------------------------------------------------------------

@Composable
private fun RemoteControls(state: RemoteUiState, target: RemoteTarget, vm: RemoteViewModel) {
    val keys = target.keys
    val enabled = state.session != null
    var showNumbers by rememberSaveable { mutableStateOf(false) }
    var showTyping by rememberSaveable { mutableStateOf(false) }
    val press: (RemoteKey) -> Unit = { vm.press(it) }
    val hasNumbers = (0..9).any { RemoteKey.valueOf("NUM_$it") in keys }
    val voice by vm.voice.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startVoice() else vm.voicePermissionDenied()
    }
    val startVoice = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.startVoice()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    val banners: @Composable () -> Unit = {
        StatusLine(state, onRetry = { vm.retry() })
        VoiceBanner(
            voice,
            onDone = { vm.stopVoice() },
            onDismiss = { vm.dismissVoiceMessage() },
            onOpenSettings = {
                vm.dismissVoiceMessage()
                try {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } catch (_: Exception) {
                }
            },
        )
    }
    // Power, input, keyboard, numbers
    val topKeys: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            if (RemoteKey.POWER in keys) {
                KeyButton(
                    RemoteKey.POWER, Icons.Rounded.PowerSettingsNew, stringResource(R.string.remote_key_power), enabled, press,
                    container = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            } else {
                Spacer(Modifier.size(KEY_SIZE))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (RemoteKey.INPUT in keys) KeyButton(RemoteKey.INPUT, Icons.AutoMirrored.Rounded.Input, stringResource(R.string.remote_key_input), enabled, press)
                if (target.supportsText) {
                    ActionButton(Icons.Rounded.Keyboard, stringResource(R.string.remote_keyboard), enabled) { showTyping = true }
                }
                if (hasNumbers) {
                    ActionButton(Icons.Rounded.Dialpad, stringResource(R.string.remote_numbers), true, selected = showNumbers) { showNumbers = !showNumbers }
                }
            }
        }
        if (showNumbers && hasNumbers) {
            Spacer(Modifier.height(16.dp))
            NumberPad(keys, enabled, press)
        }
    }
    // Back, home, voice, menu
    val navKeys: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            OptionalKey(RemoteKey.BACK, Icons.AutoMirrored.Rounded.ArrowBack, R.string.remote_key_back, keys, enabled, press)
            OptionalKey(RemoteKey.HOME, Icons.Rounded.Home, R.string.remote_key_home, keys, enabled, press)
            if (target.supportsVoice) MicButton(voice, enabled, onStart = startVoice, onStop = { vm.stopVoice() })
            OptionalKey(RemoteKey.MENU, Icons.Rounded.Menu, R.string.remote_key_menu, keys, enabled, press)
        }
    }
    // Volume, channels and the media keys
    val lowerKeys: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Rocker(
                RemoteKey.VOLUME_UP, RemoteKey.VOLUME_DOWN, stringResource(R.string.remote_vol),
                stringResource(R.string.remote_key_volume_up), stringResource(R.string.remote_key_volume_down), keys, enabled, press,
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OptionalKey(RemoteKey.MUTE, Icons.AutoMirrored.Rounded.VolumeOff, R.string.remote_key_mute, keys, enabled, press)
                OptionalKey(RemoteKey.INFO, Icons.Rounded.Info, R.string.remote_key_info, keys, enabled, press)
            }
            Rocker(
                RemoteKey.CHANNEL_UP, RemoteKey.CHANNEL_DOWN, stringResource(R.string.remote_ch),
                stringResource(R.string.remote_key_channel_up), stringResource(R.string.remote_key_channel_down), keys, enabled, press,
            )
        }

        val media = listOf(RemoteKey.REWIND, RemoteKey.PLAY_PAUSE, RemoteKey.FAST_FORWARD)
        if (media.any { it in keys }) {
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                OptionalKey(RemoteKey.REWIND, Icons.Rounded.FastRewind, R.string.remote_key_rewind, keys, enabled, press, repeat = true)
                if (RemoteKey.PLAY_PAUSE in keys) {
                    KeyButton(RemoteKey.PLAY_PAUSE, null, stringResource(R.string.remote_key_play_pause), enabled, press) {
                        Row {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                            Icon(Icons.Rounded.Pause, contentDescription = null, modifier = Modifier.size(20.dp))
                        }
                    }
                }
                OptionalKey(RemoteKey.FAST_FORWARD, Icons.Rounded.FastForward, R.string.remote_key_fast_forward, keys, enabled, press, repeat = true)
            }
        }
        val extra = listOf(RemoteKey.PREVIOUS, RemoteKey.STOP, RemoteKey.NEXT)
        if (extra.any { it in keys }) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                OptionalKey(RemoteKey.PREVIOUS, Icons.Rounded.SkipPrevious, R.string.remote_key_previous, keys, enabled, press)
                OptionalKey(RemoteKey.STOP, Icons.Rounded.Stop, R.string.remote_key_stop, keys, enabled, press)
                OptionalKey(RemoteKey.NEXT, Icons.Rounded.SkipNext, R.string.remote_key_next, keys, enabled, press)
            }
        }
        val more = listOf(RemoteKey.GUIDE, RemoteKey.SETTINGS)
        if (more.any { it in keys }) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                OptionalKey(RemoteKey.GUIDE, Icons.Rounded.LiveTv, R.string.remote_key_guide, keys, enabled, press)
                OptionalKey(RemoteKey.SETTINGS, Icons.Rounded.Settings, R.string.remote_key_settings, keys, enabled, press)
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Landscape (or a wide window): D-pad on the left, the other keys on the right, so nothing
        // needs scrolling past the D-pad. Portrait: one column.
        val sideBySide = maxWidth > maxHeight && maxWidth >= 560.dp
        if (sideBySide) {
            val dpadMax = min(maxHeight - 120.dp, 260.dp).coerceAtLeast(168.dp)
            Row(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
            ) {
                Column(
                    Modifier.weight(1f).widthIn(max = 420.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    banners()
                    DPad(keys, enabled, press, maxSize = dpadMax)
                    Spacer(Modifier.height(12.dp))
                    navKeys()
                    Spacer(Modifier.height(16.dp))
                }
                Column(
                    Modifier.weight(1f).widthIn(max = 420.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    topKeys()
                    Spacer(Modifier.height(16.dp))
                    lowerKeys()
                    Spacer(Modifier.height(16.dp))
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                banners()
                Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    topKeys()
                    Spacer(Modifier.height(16.dp))
                    DPad(keys, enabled, press)
                    Spacer(Modifier.height(16.dp))
                    navKeys()
                    Spacer(Modifier.height(16.dp))
                    lowerKeys()
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }

    if (showTyping) TypeDialog(onSend = { vm.type(it) }, onDismiss = { showTyping = false })
}

/**
 * The mic: tap to start and tap again (or Done) to stop, or hold while talking and let go.
 */
@Composable
private fun MicButton(voice: VoiceUiState, enabled: Boolean, onStart: () -> Unit, onStop: () -> Unit) {
    val view = LocalView.current
    val active by rememberUpdatedState(voice.active)
    val start by rememberUpdatedState(onStart)
    val stop by rememberUpdatedState(onStop)
    val label = stringResource(R.string.remote_voice)
    val listening = stringResource(R.string.polish_listening)
    val container = if (voice.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiaryContainer
    val content = if (voice.active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onTertiaryContainer
    Box(
        Modifier
            .size(KEY_SIZE)
            .clip(CircleShape)
            .background(if (enabled) container else container.copy(alpha = 0.38f))
            .semantics {
                role = Role.Button
                contentDescription = label
                if (voice.active) stateDescription = listening
                if (!enabled) disabled()
                onClick(label) { if (enabled) { if (active) stop() else start() }; enabled }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown()
                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    if (active) {
                        if (waitForUpOrCancellation() != null) stop()
                    } else {
                        val downAt = System.currentTimeMillis()
                        start()
                        // Held while talking: letting go ends it. A short tap keeps listening.
                        if (waitForUpOrCancellation() != null && System.currentTimeMillis() - downAt > 600) stop()
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Mic, contentDescription = null, tint = if (enabled) content else content.copy(alpha = 0.38f))
    }
}

@Composable
private fun VoiceBanner(voice: VoiceUiState, onDone: () -> Unit, onDismiss: () -> Unit, onOpenSettings: () -> Unit) {
    when {
        voice.active -> Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                val scale by animateFloatAsState(if (voice.listening) 1f + voice.level * 0.6f else 1f, label = "mic level")
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.size(36.dp).graphicsLayer { scaleX = scale; scaleY = scale }
                            .clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (voice.listening) {
                            Icon(Icons.Rounded.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
                        } else {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(if (voice.listening) R.string.remote_voice_listening else R.string.remote_voice_starting),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (voice.listening) Text(stringResource(R.string.remote_voice_hint), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onDone) { Text(stringResource(R.string.remote_voice_done)) }
            }
        }
        voice.message != null -> Card(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(voice.message, style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (voice.needsPermission) TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.remote_voice_open_settings)) }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.remote_ok)) }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(state: RemoteUiState, onRetry: () -> Unit) {
    when {
        state.error != null -> ErrorCard(state.error, onRetry)
        state.connecting || state.message != null -> Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.connecting) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
            }
            Text(state.message.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        else -> Spacer(Modifier.height(8.dp))
    }
}

private val KEY_SIZE = 56.dp

/** Keys that repeat while held. */
private val REPEATING = setOf(
    RemoteKey.UP, RemoteKey.DOWN, RemoteKey.LEFT, RemoteKey.RIGHT,
    RemoteKey.VOLUME_UP, RemoteKey.VOLUME_DOWN, RemoteKey.CHANNEL_UP, RemoteKey.CHANNEL_DOWN,
    RemoteKey.REWIND, RemoteKey.FAST_FORWARD,
)

@Composable
private fun OptionalKey(
    key: RemoteKey,
    icon: ImageVector,
    label: Int,
    keys: Set<RemoteKey>,
    enabled: Boolean,
    press: (RemoteKey) -> Unit,
    repeat: Boolean = key in REPEATING,
) {
    if (key in keys) KeyButton(key, icon, stringResource(label), enabled, press, repeat = repeat)
    else Spacer(Modifier.size(KEY_SIZE))
}

@Composable
private fun KeyButton(
    key: RemoteKey,
    icon: ImageVector?,
    label: String,
    enabled: Boolean,
    press: (RemoteKey) -> Unit,
    /** A size here wins over the default [KEY_SIZE]. */
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer,
    repeat: Boolean = key in REPEATING,
    content: (@Composable () -> Unit)? = null,
) {
    PressSurface(label, enabled, repeat, onPress = { press(key) }, modifier.then(Modifier.size(KEY_SIZE)), shape, container, contentColor) {
        if (content != null) content() else if (icon != null) Icon(icon, contentDescription = null)
    }
}

/** A local button (keyboard, number pad) that doesn't send a key. */
@Composable
private fun ActionButton(icon: ImageVector, label: String, enabled: Boolean, selected: Boolean? = null, onClick: () -> Unit) {
    val on = selected == true
    val container = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer
    val content = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer
    val stateText = selected?.let { stringResource(if (it) R.string.polish_on else R.string.polish_off) }
    PressSurface(label, enabled, repeat = false, onPress = onClick, Modifier.size(KEY_SIZE), CircleShape, container, content, haptic = false, state = stateText) {
        Icon(icon, contentDescription = null)
    }
}

/**
 * A button that acts on release, or repeats while held when [repeat] is set
 * (first repeat after 400 ms, then every 110 ms). Gives a short haptic tick per press.
 * Scrolling away from it cancels the press.
 */
@Composable
private fun PressSurface(
    label: String,
    enabled: Boolean,
    repeat: Boolean,
    onPress: () -> Unit,
    modifier: Modifier,
    shape: Shape,
    container: Color,
    contentColor: Color,
    haptic: Boolean = true,
    /** Read out after the label, e.g. "On" for a toggle. */
    state: String? = null,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    val action by rememberUpdatedState {
        if (haptic) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        onPress()
    }
    var pressed by remember { mutableStateOf(false) }
    val background = when {
        !enabled -> container.copy(alpha = 0.38f)
        pressed -> lerpColor(container, contentColor, 0.24f)
        else -> container
    }
    Box(
        modifier
            .clip(shape)
            .background(background)
            .semantics {
                role = Role.Button
                contentDescription = label
                if (state != null) stateDescription = state
                if (!enabled) disabled()
                onClick(label) { if (enabled) action(); enabled }
            }
            .pointerInput(enabled, repeat) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    try {
                        if (!repeat) {
                            if (waitForUpOrCancellation() != null) action()
                        } else {
                            // 0: still held after the delay, 1: released (a tap), 2: cancelled (scrolled away)
                            var outcome = 0
                            withTimeoutOrNull(400) { outcome = if (waitForUpOrCancellation() != null) 1 else 2 }
                            if (outcome == 1) action()
                            if (outcome == 0) {
                                action()
                                var held = true
                                while (held) {
                                    withTimeoutOrNull(110) { waitForUpOrCancellation(); held = false }
                                    if (held) action()
                                }
                            }
                        }
                    } finally {
                        pressed = false
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val tint = if (enabled) contentColor else contentColor.copy(alpha = 0.38f)
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides tint) { content() }
    }
}

private fun lerpColor(a: Color, b: Color, t: Float) = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = a.alpha,
)

@Composable
private fun DPad(keys: Set<RemoteKey>, enabled: Boolean, press: (RemoteKey) -> Unit, maxSize: Dp = 260.dp) {
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val size: Dp = min(maxWidth * 0.8f, maxSize)
        val arm = size / 3
        val ring = MaterialTheme.colorScheme.surfaceContainerHighest
        val onRing = MaterialTheme.colorScheme.onSurface
        Box(Modifier.size(size).clip(CircleShape).background(ring)) {
            @Composable
            fun Arrow(key: RemoteKey, icon: ImageVector, label: Int, align: Alignment, w: Dp, h: Dp) {
                if (key !in keys) return
                Box(Modifier.align(align)) {
                    KeyButton(
                        key, icon, stringResource(label), enabled, press,
                        modifier = Modifier.size(w, h), shape = RoundedCornerShape(16.dp),
                        container = Color.Transparent, contentColor = onRing,
                    )
                }
            }
            Arrow(RemoteKey.UP, Icons.Rounded.KeyboardArrowUp, R.string.remote_key_up, Alignment.TopCenter, arm * 1.2f, arm)
            Arrow(RemoteKey.DOWN, Icons.Rounded.KeyboardArrowDown, R.string.remote_key_down, Alignment.BottomCenter, arm * 1.2f, arm)
            Arrow(RemoteKey.LEFT, Icons.AutoMirrored.Rounded.KeyboardArrowLeft, R.string.remote_key_left, Alignment.CenterStart, arm, arm * 1.2f)
            Arrow(RemoteKey.RIGHT, Icons.AutoMirrored.Rounded.KeyboardArrowRight, R.string.remote_key_right, Alignment.CenterEnd, arm, arm * 1.2f)
            if (RemoteKey.OK in keys) {
                Box(Modifier.align(Alignment.Center)) {
                    KeyButton(
                        RemoteKey.OK, null, stringResource(R.string.remote_key_ok), enabled, press,
                        modifier = Modifier.size(arm * 1.15f),
                        container = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Text(stringResource(R.string.remote_key_ok), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun Rocker(
    up: RemoteKey,
    down: RemoteKey,
    title: String,
    upLabel: String,
    downLabel: String,
    keys: Set<RemoteKey>,
    enabled: Boolean,
    press: (RemoteKey) -> Unit,
) {
    if (up !in keys && down !in keys) {
        Spacer(Modifier.width(KEY_SIZE + 8.dp))
        return
    }
    val container = MaterialTheme.colorScheme.secondaryContainer
    val content = MaterialTheme.colorScheme.onSecondaryContainer
    Column(
        Modifier.width(KEY_SIZE + 8.dp).clip(RoundedCornerShape(32.dp)).background(if (enabled) container else container.copy(alpha = 0.38f)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val shape = RoundedCornerShape(32.dp)
        if (up in keys) {
            KeyButton(up, Icons.Rounded.Add, upLabel, enabled, press, Modifier.size(KEY_SIZE + 8.dp, KEY_SIZE), shape, Color.Transparent, content)
        } else {
            Spacer(Modifier.size(KEY_SIZE + 8.dp, KEY_SIZE))
        }
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) content else content.copy(alpha = 0.38f),
            modifier = Modifier.padding(vertical = 2.dp),
        )
        if (down in keys) {
            KeyButton(down, Icons.Rounded.Remove, downLabel, enabled, press, Modifier.size(KEY_SIZE + 8.dp, KEY_SIZE), shape, Color.Transparent, content)
        } else {
            Spacer(Modifier.size(KEY_SIZE + 8.dp, KEY_SIZE))
        }
    }
}

@Composable
private fun NumberPad(keys: Set<RemoteKey>, enabled: Boolean, press: (RemoteKey) -> Unit) {
    val rows = listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9), listOf(null, 0, null))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (row in rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                for (n in row) {
                    val key = n?.let { RemoteKey.valueOf("NUM_$it") }
                    if (key == null || key !in keys) {
                        Spacer(Modifier.size(KEY_SIZE))
                    } else {
                        KeyButton(key, null, n.toString(), enabled, press) {
                            Text(n.toString(), style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
        }
    }
}
