package com.shrug.shrugtv.ui.tv.clips

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.shrug.shrugtv.R
import com.shrug.shrugtv.model.Movie
import com.shrug.shrugtv.ui.tv.Inter
import com.shrug.shrugtv.ui.tv.TvColors
import com.shrug.shrugtv.ui.tv.TvShapes
import com.shrug.shrugtv.ui.tv.components.number

/** One themed row of the stage layout. */
private class StageRow(val key: String, val title: String, val clips: List<Movie>, val numbered: Boolean = false, val watched: Boolean = false)

/**
 * Layout "Stage" (design 2): the focused clip fills the upper right as a backdrop with its title and numbers
 * beside it; the time frame sits as tabs on top; below, rows by theme: the top ten with large numbers, new since yesterday,
 * short ones, the three biggest categories and all clips. OK plays a clip, and the rest of its row follows. With the setting
 * "Play the focused clip" the backdrop runs the clip muted.
 */
@Composable
fun ClipsStageScreen(ui: ClipsUi) {
    var focused by remember { mutableStateOf<Movie?>(null) }
    val shown = ui.shown
    val hero = focused?.takeIf { f -> shown.any { it.clipSlug == f.clipSlug } } ?: shown.firstOrNull()
    val topTitle = if (ui.ranked) stringResource(R.string.clips_row_top) else sortName(ui.settings.sort)
    val newTitle = stringResource(R.string.clips_row_new)
    val shortTitle = stringResource(R.string.clips_row_short)
    val allTitle = stringResource(R.string.clips_row_all)
    val rows = remember(shown, ui.visible.size, ui.ranked, topTitle) {
        val day = java.time.ZonedDateTime.now().minusDays(1)
        val out = mutableListOf(StageRow("top", topTitle, shown.take(10), numbered = true))
        shown.filter { it.publishedAt?.isAfter(day) == true }.sortedByDescending { it.publishedAt }.take(15)
            .takeIf { it.size >= 2 }?.let { out += StageRow("new", newTitle, it) }
        shown.filter { it.durationSeconds in 1..20 }.take(15).takeIf { it.size >= 2 }?.let { out += StageRow("short", shortTitle, it) }
        if (ui.gameFilter == 0L) for ((id, name) in ui.categories.take(3)) {
            shown.filter { it.gameId == id }.take(15).takeIf { it.size >= 2 }?.let { out += StageRow("game$id", name, it) }
        }
        out += StageRow("all", allTitle, ui.visible, watched = true)
        out
    }

    Box(Modifier.fillMaxSize().background(TvColors.Background)) {
        // The backdrop: the focused clip, faded into the page on the left and at the bottom
        if (hero != null) {
            Box(Modifier.align(Alignment.TopEnd).width(680.dp).height(382.dp)) {
                ClipVideo(hero, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to TvColors.Background, 0.45f to TvColors.Background.copy(alpha = 0.55f), 1f to Color.Transparent)))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to TvColors.Background)))
            }
        }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 56.dp, top = 22.dp, end = 40.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.clips_title_short).uppercase(), fontFamily = Inter, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = TvColors.AccentLight, modifier = Modifier.padding(end = 12.dp))
                    for (c in periodChoices(ui)) Chip(c.label, c.selected) { c.pick() }
                }
                // The categories are rows of their own here; the menus show their value only, so they fit beside the tabs
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceMenu("", sourceChoices(ui))
                    ChoiceMenu("", sortChoices(ui))
                }
            }
            // What the backdrop shows
            Column(Modifier.padding(start = 56.dp, top = 22.dp).width(400.dp).height(150.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                if (hero != null) {
                    val rank = shown.indexOfFirst { it.clipSlug == hero.clipSlug } + 1
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (ui.ranked && rank > 0) Text(
                            stringResource(R.string.clips_rank, rank).uppercase(), fontFamily = Inter, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, color = TvColors.OnAccent,
                            modifier = Modifier.clip(TvShapes.r(4.dp)).background(TvColors.Accent).padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                        Text(
                            listOf(ClipsFormat.duration(hero.durationSeconds), if (hero.viewCount > 0) stringResource(R.string.clips_views, number(hero.viewCount)) else "", ago(hero))
                                .filter { it.isNotEmpty() }.joinToString("  ·  "),
                            fontFamily = Inter, fontSize = 10.sp, color = TvColors.TextMedium,
                        )
                    }
                    Text(hero.title ?: "", fontFamily = Inter, fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.ExtraBold, color = TvColors.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    ChannelLine(hero, 18.dp, 11.sp)
                    Text(stringResource(R.string.clips_play_hint), fontFamily = Inter, fontSize = 9.sp, color = TvColors.TextMuted)
                }
            }
            Box(Modifier.fillMaxSize()) {
                // Without clips the row titles ("Top 10", "All clips") stay away; they lay over the note
                if (ui.shown.isEmpty()) EmptyNote(ui, Modifier.padding(start = 56.dp))
                else LazyColumn(contentPadding = PaddingValues(bottom = 60.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(rows, key = { it.key }) { row ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(row.title, fontFamily = Inter, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = TvColors.Text, modifier = Modifier.padding(start = 56.dp))
                            StageLane(ui, row, if (row.key == "top") ui.firstFocus else null) { focused = it }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StageLane(ui: ClipsUi, row: StageRow, first: FocusRequester?, onFocus: (Movie) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(start = 56.dp, end = 40.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(if (row.numbered) 4.dp else 12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        itemsIndexed(row.clips, key = { _, m -> m.clipSlug }) { i, m ->
            if (row.watched) ui.Watch(i)
            // The clip and what follows it in its row (in "all clips": the whole pool from here) play one after the other
            val play = { ui.play(m, if (row.watched) ui.shown.drop(i) else row.clips.drop(i)) }
            androidx.compose.runtime.key(ui.resumes) {
                if (row.numbered) {
                    Row(Modifier.seenAlpha(m), verticalAlignment = Alignment.Bottom) {
                        Text(
                            (i + 1).toString(), fontFamily = Inter, fontSize = 74.sp, lineHeight = 60.sp, fontWeight = FontWeight.Black,
                            color = TvColors.TextMuted, style = TextStyle(drawStyle = Stroke(width = 4f)),
                            modifier = Modifier.offset(x = 10.dp, y = 8.dp),
                        )
                        ClipTile(
                            m, play, { ui.menu(m) }, (if (i == 0 && first != null) Modifier.focusRequester(first) else Modifier).width(150.dp),
                            onFocus = { onFocus(m) },
                        )
                    }
                } else {
                    Column(Modifier.seenAlpha(m).width(150.dp)) {
                        ClipTile(m, play, { ui.menu(m) }, Modifier.width(150.dp), onFocus = { onFocus(m) })
                        Text(m.title ?: "", fontFamily = Inter, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = TvColors.TextMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp, start = 2.dp))
                    }
                }
            }
        }
    }
}
