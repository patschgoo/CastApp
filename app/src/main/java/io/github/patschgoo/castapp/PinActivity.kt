package io.github.patschgoo.castapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.patschgoo.castapp.service.CastController
import io.github.patschgoo.castapp.ui.theme.CastAppTheme

/** Asks for the code an Apple TV shows the first time CastApp casts to it. */
class PinActivity : ComponentActivity() {
    private var answered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = CastApp.get(this)
        setContent {
            val settings by app.settings.state.collectAsStateWithLifecycle()
            val request by CastController.pinRequest.collectAsStateWithLifecycle()
            LaunchedEffect(request) { if (request == null) finish() }
            var code by remember { mutableStateOf("") }
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { try { focus.requestFocus() } catch (_: Exception) {} }
            fun submit() {
                if (code.isBlank()) return
                answered = true
                CastController.answerPin(code)
                finish()
            }
            CastAppTheme(settings.theme) {
                AlertDialog(
                    onDismissRequest = { finish() },
                    title = { Text(stringResource(R.string.pin_title)) },
                    text = {
                        Column {
                            Text(stringResource(R.string.pin_text, request ?: ""), style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(12.dp))
                            OutlinedTextField(
                                value = code,
                                onValueChange = { v -> code = v.filter { it.isDigit() }.take(8) },
                                singleLine = true,
                                label = { Text(stringResource(R.string.pin_label)) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { submit() }),
                                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                            )
                        }
                    },
                    confirmButton = { TextButton(onClick = { submit() }, enabled = code.isNotBlank()) { Text(stringResource(R.string.pin_pair)) } },
                    dismissButton = { TextButton(onClick = { finish() }) { Text(stringResource(R.string.cancel)) } },
                )
            }
        }
    }

    override fun onDestroy() {
        if (!answered && isFinishing) CastController.answerPin(null)
        super.onDestroy()
    }
}
