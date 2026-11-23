package io.github.patschgoo.castapp.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** From this width on (tablet, unfolded foldable, phone in landscape) screens may use two panes. */
val TwoPaneMinWidth: Dp = 600.dp

/** From this width on, panes stop growing and stay at a readable width. */
val WideMinWidth: Dp = 1000.dp

/** Fills the width up to [max] and centers the result, so text lines stay readable on wide screens. */
fun Modifier.readableWidth(max: Dp = 720.dp): Modifier =
    this.fillMaxWidth()
        .wrapContentWidth(Alignment.CenterHorizontally)
        .widthIn(max = max)
        .fillMaxWidth()

/** Where a line may break without splitting a word. */
private val WORD_GAPS = Regex("[\\s-]+")

/**
 * Text that breaks lines only between words. When one word is wider than the space (narrow
 * columns, large fonts) the text stays on one line and ends in "…" instead of splitting that word.
 */
@Composable
fun WholeWordsText(
    text: String,
    style: TextStyle,
    maxLines: Int,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val measurer = rememberTextMeasurer()
    val merged = LocalTextStyle.current.merge(style)
    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth
        val lines = remember(text, merged, width, maxLines) {
            val wordTooWide = maxLines > 1 && width != Constraints.Infinity && text.split(WORD_GAPS).any { word ->
                word.isNotEmpty() && measurer.measure(word, merged, maxLines = 1, softWrap = false).size.width > width
            }
            if (wordTooWide) 1 else maxLines
        }
        Text(text, style = style, color = color, maxLines = lines, overflow = TextOverflow.Ellipsis)
    }
}
