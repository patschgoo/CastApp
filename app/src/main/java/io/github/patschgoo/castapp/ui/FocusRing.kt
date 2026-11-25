package io.github.patschgoo.castapp.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp

/**
 * A clearly visible ring while this element (or something inside it) has the focus: for the
 * D-pad on a TV and for keyboards. Material's own focus state is a faint tint that is hard to
 * see from the sofa. Touch never focuses buttons, so on phones the ring only shows with a keyboard.
 *
 * The ring has the text colour (white on the always-dark TV screens), not the accent: an accent
 * ring disappears on a filled button of the same colour. It is drawn with a gap around the
 * element; [inside] draws it on the element's own edge instead, for rows that fill a scrolling
 * list from side to side (a list cuts off what is drawn outside it).
 */
fun Modifier.focusRing(shape: Shape = RoundedCornerShape(percent = 50), inside: Boolean = false): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val color = MaterialTheme.colorScheme.onSurface
    this
        .onFocusChanged { focused = it.hasFocus }
        .drawWithContent {
            drawContent()
            if (focused) {
                val stroke = RING_WIDTH.toPx()
                // How far the middle of the line lies outside the element's edge.
                val grow = if (inside) -stroke / 2 else RING_GAP.toPx() + stroke / 2
                val outline = shape.createOutline(Size(size.width + 2 * grow, size.height + 2 * grow), layoutDirection, this)
                translate(-grow, -grow) { drawOutline(outline, color, style = Stroke(stroke)) }
            }
        }
}

private val RING_WIDTH = 3.dp
private val RING_GAP = 3.dp
