package io.github.patschgoo.castapp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ScreenShare
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.SettingsRemote
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.patschgoo.castapp.R
import kotlinx.coroutines.launch

private class TourPage(val icon: ImageVector, val title: Int, val text: Int)

private val PAGES = listOf(
    TourPage(Icons.Rounded.CastConnected, R.string.tour_1_title, R.string.tour_1_text),
    TourPage(Icons.AutoMirrored.Rounded.ScreenShare, R.string.tour_2_title, R.string.tour_2_text),
    TourPage(Icons.Rounded.Lan, R.string.tour_3_title, R.string.tour_3_text),
    TourPage(Icons.Rounded.SettingsRemote, R.string.tour_4_title, R.string.tour_4_text),
)

/**
 * The short tour on the first start (and again from Settings → Help & support): four swipeable
 * pages, "Skip" on every page. [onDone] is called when it is finished or skipped.
 */
@Composable
fun FirstRunTour(onDone: () -> Unit) {
    val pager = rememberPagerState { PAGES.size }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == PAGES.lastIndex
    BackHandler {
        if (pager.currentPage > 0) scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } else onDone()
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
                if (!last) TextButton(onClick = onDone) { Text(stringResource(R.string.tour_skip)) }
                else Spacer(Modifier.height(48.dp))
            }
            HorizontalPager(pager, modifier = Modifier.weight(1f).fillMaxWidth()) { index ->
                val page = PAGES[index]
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Column(Modifier.readableWidth(max = 560.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.size(112.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(page.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(56.dp))
                        }
                        Spacer(Modifier.height(32.dp))
                        Text(
                            stringResource(page.title),
                            style = MaterialTheme.typography.headlineSmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.semantics { heading() },
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            stringResource(page.text),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            Row(
                Modifier.readableWidth(max = 560.dp).padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Dots: the position, also read out ("Page 2 of 4").
                val position = stringResource(R.string.tour_page, pager.currentPage + 1, PAGES.size)
                Row(
                    Modifier.weight(1f).clearAndSetSemantics { contentDescription = position },
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (i in PAGES.indices) {
                        val current = i == pager.currentPage
                        Box(
                            Modifier.size(if (current) 12.dp else 8.dp).clip(CircleShape).background(
                                if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            ).align(Alignment.CenterVertically),
                        )
                    }
                }
                Button(onClick = {
                    if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                }) { Text(stringResource(if (last) R.string.tour_done else R.string.tour_next)) }
            }
        }
    }
}
