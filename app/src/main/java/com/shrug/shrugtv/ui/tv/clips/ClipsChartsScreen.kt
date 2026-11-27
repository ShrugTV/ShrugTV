package com.shrug.shrugtv.ui.tv.clips

import com.shrug.shrugtv.ui.tv.components.menuKey
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import com.shrug.shrugtv.R
import com.shrug.shrugtv.model.Movie
import com.shrug.shrugtv.ui.tv.Inter
import com.shrug.shrugtv.ui.tv.TvColors
import com.shrug.shrugtv.ui.tv.TvShapes
import com.shrug.shrugtv.ui.tv.components.number
import com.shrug.shrugtv.ui.tv.home.NetImage

/**
 * Layout "Charts" (design 3): the clips as a ranked list on the left (place, small picture, title, channel,
 * views, length), on the right the focused clip large with its numbers. The time frame sits as tabs above the list.
 *
 * With the setting "Play the focused clip" the right side runs the clip muted, otherwise it shows the
 * picture. Not yet from the design: the arrows for rising and falling places (they need yesterday's list, which the app does
 * not keep).
 */
@Composable
fun ClipsChartsScreen(ui: ClipsUi) {
    var focused by remember { mutableStateOf<Movie?>(null) }
    val hero = focused?.takeIf { f -> ui.shown.any { it.clipSlug == f.clipSlug } } ?: ui.shown.firstOrNull()

    Row(Modifier.fillMaxSize().background(TvColors.Background)) {
        Column(Modifier.width(430.dp).fillMaxSize().padding(start = 48.dp, top = 24.dp, end = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.clips_charts).uppercase(), fontFamily = Inter, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = TvColors.AccentLight)
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (c in periodChoices(ui)) Chip(c.label, c.selected) { c.pick() }
            }
            EmptyNote(ui)
            LazyColumn(contentPadding = PaddingValues(top = 4.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                itemsIndexed(ui.visible, key = { _, m -> m.clipSlug }) { i, m ->
                    ui.Watch(i)
                    androidx.compose.runtime.key(ui.resumes) {
                        ChartRow(ui, m, i, Modifier.then(if (i == 0) Modifier.focusRequester(ui.firstFocus) else Modifier)) { focused = m }
                    }
                }
            }
        }
        Column(Modifier.fillMaxSize().padding(start = 12.dp, top = 24.dp, end = 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                // Value only: three menus with labels do not fit beside the list
                ChoiceMenu("", sourceChoices(ui))
                if (ui.categories.isNotEmpty()) ChoiceMenu("", categoryChoices(ui))
                ChoiceMenu("", sortChoices(ui))
            }
            if (hero != null) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(TvShapes.r(16.dp))) {
                    ClipPicture(hero, big = true, showLength = false, video = true)
                }
                Text(hero.title ?: "", fontFamily = Inter, fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.ExtraBold, color = TvColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                ChannelLine(hero, 18.dp, 11.sp, extra = ago(hero))
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    Stat(if (hero.viewCount > 0) number(hero.viewCount) else "–", stringResource(R.string.clips_stat_views))
                    Stat(ClipsFormat.duration(hero.durationSeconds), stringResource(R.string.clips_stat_length))
                    val place = ui.shown.indexOfFirst { it.clipSlug == hero.clipSlug } + 1
                    if (ui.ranked && place > 0) Stat(place.toString(), stringResource(R.string.clips_stat_place))
                }
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column {
        Text(value, fontFamily = Inter, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = TvColors.Text)
        Text(label, fontFamily = Inter, fontSize = 9.sp, color = TvColors.TextMuted)
    }
}

/** One place of the charts: the whole row is the button; focused it turns light like the other focused buttons. */
@Composable
private fun ChartRow(ui: ClipsUi, m: Movie, i: Int, modifier: Modifier, onFocus: () -> Unit) {
    var hasFocus by remember { mutableStateOf(false) }
    val text = if (hasFocus) TvColors.Background else TvColors.Text
    val sub = if (hasFocus) TvColors.Background.copy(alpha = 0.75f) else TvColors.TextMuted
    Card(
        onClick = { ui.play(m, ui.shown.drop(i)) }, onLongClick = { ui.menu(m) },
        modifier = modifier.fillMaxWidth().height(46.dp).seenAlpha(m).onFocusChanged { hasFocus = it.isFocused; if (it.isFocused) onFocus() }
            .menuKey { ui.menu(m) },
        shape = CardDefaults.shape(TvShapes.r(10.dp)),
        scale = CardDefaults.scale(focusedScale = 1f),
        colors = CardDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent, focusedContainerColor = TvColors.Ink),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (ui.ranked) (i + 1).toString() else "", fontFamily = Inter, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.End,
                color = if (hasFocus) TvColors.Background else if (i < 3) TvColors.AccentLight else TvColors.TextMuted, modifier = Modifier.width(26.dp),
            )
            NetImage(m.cardImageUrl, Modifier.width(56.dp).aspectRatio(16f / 9f).clip(TvShapes.r(5.dp)), width = 160, height = 90)
            Column(Modifier.weight(1f)) {
                Text(m.title ?: "", fontFamily = Inter, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(m.displayName, if (m.viewCount > 0) ClipsFormat.compactCount(m.viewCount) else "").filter { it.isNotEmpty() }.joinToString("  ·  "),
                    fontFamily = Inter, fontSize = 9.sp, color = sub, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Text(ClipsFormat.duration(m.durationSeconds), fontFamily = Inter, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = sub)
        }
    }
}
