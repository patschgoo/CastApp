package io.github.patschgoo.castapp.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * A clearly visible ring while this element (or something inside it) has the focus: for the
 * D-pad on a TV and for keyboards. Material's own focus state is a faint tint that is hard to
 * see from the sofa. Touch never focuses buttons, so on phones the ring only shows with a keyboard.
 */
fun Modifier.focusRing(shape: Shape = RoundedCornerShape(percent = 50)): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.primary
    this
        .onFocusChanged { focused = it.hasFocus }
        .border(width = 3.dp, color = if (focused) color else Color.Transparent, shape = shape)
}
