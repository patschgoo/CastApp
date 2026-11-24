package io.github.patschgoo.castapp.tv

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.patschgoo.castapp.R
import io.github.patschgoo.castapp.ui.focusRing

/**
 * The sizes of CastApp TV's screens, read from the sofa: a 1080p TV is 960 x 540 dp. Nothing is
 * smaller than [small]; what the remote can select is at least [rowHeight] high.
 */
internal object TvSize {
    /** Kept free at the screen's edges (some TVs crop them). */
    val marginSide = 56.dp
    val marginTop = 40.dp
    val rowHeight = 64.dp
    val buttonHeight = 56.dp
    val panelWidth = 440.dp
    val questionWidth = 620.dp

    val name = 52.sp
    val question = 34.sp
    val title = 28.sp
    val label = 20.sp
    val body = 18.sp
    val value = 16.sp
    val small = 14.sp
}

/** The app's font at a TV size. */
@Composable
internal fun tvText(size: androidx.compose.ui.unit.TextUnit, weight: FontWeight = FontWeight.Normal): TextStyle =
    MaterialTheme.typography.bodyLarge.copy(fontSize = size, lineHeight = size * 1.25f, fontWeight = weight, letterSpacing = 0.sp)

private val RowShape = RoundedCornerShape(14.dp)

/**
 * One line of a TV list, selected with the remote: a label, and on the right its current [value],
 * a switch ([checked]) or nothing. With [selected] it is one choice of several (a radio button in
 * front). The focused row is lighter, a little bigger and has the white ring.
 */
@Composable
internal fun TvRow(
    label: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    hint: String? = null,
    checked: Boolean? = null,
    selected: Boolean? = null,
    attention: Boolean = false,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.02f else 1f, label = "row")
    val indication = LocalIndication.current
    val click = when {
        checked != null -> Modifier.toggleable(value = checked, interactionSource = interaction, indication = indication, role = Role.Switch, onValueChange = { onClick() })
        selected != null -> Modifier.selectable(selected = selected, interactionSource = interaction, indication = indication, role = Role.RadioButton, onClick = onClick)
        else -> Modifier.clickable(interactionSource = interaction, indication = indication, role = Role.Button, onClick = onClick)
    }
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .focusRing(RowShape)
            .clip(RowShape)
            .background(if (focused) scheme.surfaceContainerHighest else scheme.surfaceContainerLow)
            .then(click)
            .heightIn(min = TvSize.rowHeight)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (selected != null) RadioButton(selected = selected, onClick = null)
        if (attention) Box(Modifier.size(12.dp).clip(CircleShape).background(Color(0xFFFFA000)))
        Column(Modifier.weight(1f)) {
            Text(label, style = tvText(TvSize.label, FontWeight.SemiBold))
            if (hint != null) Text(hint, style = tvText(TvSize.value), color = scheme.onSurfaceVariant)
        }
        when {
            checked != null -> Switch(checked = checked, onCheckedChange = null)
            value != null -> Text(
                value,
                style = tvText(TvSize.value),
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(0.8f, fill = false),
            )
        }
    }
}

/** A big button for the remote: [primary] is the answer that has the focus first. */
@Composable
internal fun TvButton(text: String, modifier: Modifier = Modifier, primary: Boolean = false, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.03f else 1f, label = "button")
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .focusRing(shape)
            .clip(shape)
            .background(if (primary) scheme.primary else scheme.surfaceContainerHighest)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .heightIn(min = TvSize.buttonHeight)
            .padding(horizontal = 28.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = tvText(TvSize.label, FontWeight.SemiBold), color = if (primary) scheme.onPrimary else scheme.onSurface, maxLines = 1)
    }
}

/**
 * A panel over the right side of the screen, for one setting: the home screen stays visible
 * (dimmed) behind it, Back closes it, and the focus moves up and down in one column. Give
 * [first] to the row that should have the focus when the panel opens.
 */
@Composable
internal fun TvPanel(
    title: String,
    onClose: () -> Unit,
    width: Dp = TvSize.panelWidth,
    content: @Composable ColumnScope.(first: FocusRequester) -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val first = remember { FocusRequester() }
        // A panel without anything to select (plain text) has nothing to focus.
        LaunchedEffect(Unit) { try { first.requestFocus() } catch (_: Exception) {} }
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(width)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 40.dp, vertical = TvSize.marginTop),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(title, style = tvText(TvSize.title, FontWeight.Bold), modifier = Modifier.padding(bottom = 8.dp))
                content(first)
                Text(
                    stringResource(R.string.tv_panel_back),
                    style = tvText(TvSize.small),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

/**
 * A question that fills the screen ("Allow Pixel 8 to cast?"), for an activity with a see-through
 * window: what was on screen stays visible, dimmed, behind the card.
 */
@Composable
internal fun TvQuestion(title: String, text: String?, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = TvSize.questionWidth)
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 40.dp, vertical = 36.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = tvText(TvSize.question, FontWeight.Bold))
            if (text != null) Text(text, style = tvText(TvSize.body), color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}
