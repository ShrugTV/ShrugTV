package com.shrug.shrugtv.ui.tv.clips

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.shrug.shrugtv.R
import com.shrug.shrugtv.model.Movie
import com.shrug.shrugtv.ui.tv.Inter
import com.shrug.shrugtv.ui.tv.TvColors
import com.shrug.shrugtv.ui.tv.TvShapes
import com.shrug.shrugtv.ui.tv.components.number
import com.shrug.shrugtv.ui.tv.home.NetImage

/** A "channel" of Clip TV: a slice of the clip list that plays as one programme. */
private class Station(val key: String, val name: String, val clips: List<Movie>)

/**
 * Layout "Clip TV" (design 4): lean back instead of browsing. On the left the channels (all clips, the
 * biggest categories, short ones), the first clip of the chosen channel fills the screen, at the bottom what comes next.
 * OK on a channel plays it from the start, OK on a clip of the strip from there; the queue then runs through the channel.
 *
 * The design has the clip running right on this page; here the page shows its picture and OK starts the player, because a
 * second player on this page has not been measured on the Fire TV yet.
 */
@Composable
fun ClipTvScreen(ui: ClipsUi) {
    val allName = stringResource(R.string.clips_tv_all)
    val shortName = stringResource(R.string.clips_row_short)
    val stations = remember(ui.shown, allName, shortName) {
        val out = mutableListOf(Station("all", allName, ui.shown))
        for ((id, name) in ui.categories.take(6)) ui.shown.filter { it.gameId == id }.takeIf { it.size >= 2 }?.let { out += Station("game$id", name, it) }
        ui.shown.filter { it.durationSeconds in 1..20 }.takeIf { it.size >= 2 }?.let { out += Station("short", shortName, it) }
        out
    }
    var stationKey by remember { mutableStateOf("all") }
    val station = stations.firstOrNull { it.key == stationKey } ?: stations.first()
    var focusedClip by remember { mutableStateOf<Movie?>(null) }
    val hero = focusedClip?.takeIf { f -> station.clips.any { it.clipSlug == f.clipSlug } } ?: station.clips.firstOrNull()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hero != null) NetImage(hero.cardImageUrl, Modifier.fillMaxSize(), width = 800, height = 450)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.6f), 0.25f to Color.Transparent, 0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.92f))))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.88f), 0.3f to Color.Transparent)))

        Row(Modifier.fillMaxWidth().padding(start = 40.dp, top = 22.dp, end = 40.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("CLIP", fontFamily = Inter, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                Text("TV", fontFamily = Inter, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = TvColors.AccentLight, modifier = Modifier.padding(start = 0.dp))
                if (station.clips.isNotEmpty()) Text(
                    stringResource(R.string.clips_tv_length, station.clips.size, (station.clips.sumOf { it.durationSeconds } + 59) / 60),
                    fontFamily = Inter, fontSize = 10.sp, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(start = 10.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceMenu(stringResource(R.string.settings_clips_period), periodChoices(ui))
                ChoiceMenu(stringResource(R.string.settings_clips_source), sourceChoices(ui))
                ChoiceMenu(stringResource(R.string.settings_clips_sort), sortChoices(ui))
            }
        }

        // The channels
        Column(Modifier.padding(start = 40.dp, top = 74.dp).width(190.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stringResource(R.string.clips_tv_channels).uppercase(), fontFamily = Inter, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(start = 10.dp, bottom = 4.dp))
            EmptyNote(ui, Modifier.padding(start = 10.dp))
            // Fixed height: more channels scroll inside it instead of running into the title below
            LazyColumn(Modifier.height(168.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                itemsIndexed(stations, key = { _, s -> s.key }) { i, s ->
                    val chosen = s.key == station.key
                    Button(
                        onClick = { s.clips.firstOrNull()?.let { ui.play(it, s.clips) } },
                        modifier = Modifier.fillMaxWidth()
                            .then(if (i == 0) Modifier.focusRequester(ui.firstFocus) else Modifier)
                            .onFocusChanged { if (it.isFocused) { stationKey = s.key; focusedClip = null } },
                        shape = ButtonDefaults.shape(TvShapes.r(10.dp)),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        colors = ButtonDefaults.colors(
                            containerColor = if (chosen) Color.White.copy(alpha = 0.16f) else Color.Transparent, contentColor = Color.White,
                            focusedContainerColor = Color.White, focusedContentColor = Color.Black,
                        ),
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(String.format(java.util.Locale.ROOT, "%02d", i + 1), fontFamily = Inter, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(18.dp))
                            Text(s.name, fontFamily = Inter, fontSize = 12.sp, fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(s.clips.size.toString(), fontFamily = Inter, fontSize = 9.sp)
                        }
                    }
                }
            }
        }

        // What plays first, and what comes after it
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (hero != null) {
                Column(Modifier.padding(start = 40.dp, end = 40.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChannelLine(hero, 20.dp, 11.sp, extra = listOf(if (hero.viewCount > 0) stringResource(R.string.clips_views, number(hero.viewCount)) else "", ago(hero)).filter { it.isNotEmpty() }.joinToString("  ·  "))
                    Text(hero.title ?: "", fontFamily = Inter, fontSize = 24.sp, lineHeight = 28.sp, fontWeight = FontWeight.Black, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(620.dp))
                    Text(stringResource(R.string.clips_tv_hint), fontFamily = Inter, fontSize = 9.sp, color = Color.White.copy(alpha = 0.7f))
                }
            }
            Text(stringResource(R.string.clips_tv_next).uppercase(), fontFamily = Inter, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.8f), modifier = Modifier.padding(start = 40.dp))
            LazyRow(contentPadding = PaddingValues(start = 40.dp, end = 40.dp, top = 4.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(station.clips.take(40), key = { _, m -> m.clipSlug }) { i, m ->
                    if (station.key == "all") ui.Watch(i)
                    androidx.compose.runtime.key(ui.resumes) {
                        Box(Modifier.seenAlpha(m)) {
                            ClipTile(m, { ui.play(m, station.clips.drop(i)) }, { ui.menu(m) }, Modifier.width(124.dp), showLength = true, onFocus = { focusedClip = m })
                        }
                    }
                }
            }
        }
    }
}
