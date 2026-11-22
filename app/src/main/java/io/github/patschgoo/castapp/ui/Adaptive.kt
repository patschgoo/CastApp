package io.github.patschgoo.castapp.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
