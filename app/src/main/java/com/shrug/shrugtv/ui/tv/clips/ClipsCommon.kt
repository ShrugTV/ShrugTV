package com.shrug.shrugtv.ui.tv.clips

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import com.shrug.shrugtv.R
import com.shrug.shrugtv.clips.ClipsPeriod
import com.shrug.shrugtv.clips.ClipsSettings
import com.shrug.shrugtv.clips.ClipsSort
import com.shrug.shrugtv.clips.ClipsSource
import com.shrug.shrugtv.model.Movie
import com.shrug.shrugtv.prefs.Prefs
import com.shrug.shrugtv.ui.tv.Inter
import com.shrug.shrugtv.ui.tv.TvColors
import com.shrug.shrugtv.ui.tv.TvShapes
import com.shrug.shrugtv.ui.tv.TvStyle
import com.shrug.shrugtv.ui.tv.components.focusFrame
import com.shrug.shrugtv.ui.tv.components.focusGlow
import com.shrug.shrugtv.ui.tv.components.menuKey
import com.shrug.shrugtv.ui.tv.components.withoutSurface
import com.shrug.shrugtv.ui.tv.home.NetImage

/** Building blocks the four layouts of the clips page share. */

/** One entry of a choice: its text, whether it is the one in effect, and what choosing it does. */
class Choice(val label: String, val selected: Boolean, val pick: () -> Unit)

@Composable
fun periodName(p: ClipsPeriod) = stringResource(
    when (p) {
        ClipsPeriod.DAY -> R.string.clips_period_day
        ClipsPeriod.WEEK -> R.string.clips_period_week
        ClipsPeriod.MONTH -> R.string.clips_period_month
        ClipsPeriod.YEAR -> R.string.clips_period_year
        ClipsPeriod.ALL -> R.string.clips_period_all
    }
)

@Composable
fun sourceName(s: ClipsSource) = stringResource(if (s == ClipsSource.FAVORITES) R.string.clips_source_favorites else R.string.clips_source_followed)

@Composable
fun sortName(s: ClipsSort) = stringResource(
    when (s) {
        ClipsSort.NEWEST -> R.string.clips_sort_newest
        ClipsSort.SHORTEST -> R.string.clips_sort_shortest
        ClipsSort.RELATIVE -> R.string.clips_sort_relative
        else -> R.string.clips_sort_views
    }
)

@Composable
fun periodChoices(ui: ClipsUi) = ClipsPeriod.values().map { p -> Choice(periodName(p), ui.settings.period == p) { ui.change(Prefs.clipsPeriod.key, p.key) } }

@Composable
fun sourceChoices(ui: ClipsUi) = ClipsSource.values().map { s -> Choice(sourceName(s), ui.settings.source == s) { ui.change(Prefs.clipsSource.key, s.key) } }

@Composable
fun sortChoices(ui: ClipsUi) = ClipsSort.values().map { s -> Choice(sortName(s), ui.settings.sort == s) { ui.change(Prefs.clipsSort.key, s.key) } }

@Composable
fun categoryChoices(ui: ClipsUi): List<Choice> =
    listOf(Choice(stringResource(R.string.clips_category_all), ui.gameFilter == 0L) { ui.pickCategory(0L) }) +
        ui.categories.map { (id, name) -> Choice(name, ui.gameFilter == id) { ui.pickCategory(id) } }

/** A pill of the clips page: the shared [ShrugChip], so a chosen entry looks chosen, not focused. */
@Composable
fun Chip(text: String, selected: Boolean, modifier: Modifier = Modifier, click: () -> Unit) =
    com.shrug.shrugtv.ui.tv.components.ShrugChip(text, selected, modifier, onClick = click)

/**
 * A pill that shows "Label value" and opens the list of choices under it (Feinschliff and Stage layouts). OK on an entry chooses
 * it and closes the list; Back closes it without a change.
 */
@Composable
fun ChoiceMenu(label: String, choices: List<Choice>) {
    var open by remember { mutableStateOf(false) }
    val current = choices.firstOrNull { it.selected }?.label ?: choices.firstOrNull()?.label ?: ""
    Box {
        com.shrug.shrugtv.ui.tv.components.ShrugButton(
            onClick = { open = true },
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                // Without a label (tight layouts) the value alone says what the menu is
                if (label.isNotEmpty()) Text(label, fontFamily = Inter, fontSize = 11.sp, modifier = Modifier.alpha(0.7f))
                Text(current, fontFamily = Inter, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text("▾", fontFamily = Inter, fontSize = 11.sp)
            }
        }
        if (open) {
            val first = remember { FocusRequester() }
            Popup(offset = IntOffset(0, 110), onDismissRequest = { open = false }, properties = PopupProperties(focusable = true)) {
                Column(
                    Modifier.width(230.dp).clip(TvShapes.r(14.dp)).background(TvColors.Panel).padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    val at = choices.indexOfFirst { it.selected }.coerceAtLeast(0)
                    choices.forEachIndexed { i, c ->
                        Chip(c.label, c.selected, if (i == at) Modifier.fillMaxWidth().focusRequester(first) else Modifier.fillMaxWidth()) {
                            open = false
                            c.pick()
                        }
                    }
                }
                LaunchedEffect(Unit) {
                    for (attempt in 1..10) {
                        kotlinx.coroutines.delay(50)
                        if (runCatching { first.requestFocus() }.isSuccess) break
                    }
                }
            }
        }
    }
}

/**
 * A clip's picture with a shade at the bottom, views bottom left, length bottom right and, if > 0, a rank top left. [video]: the
 * clip runs muted over the picture when the setting asks for it ([ClipVideo]).
 */
@Composable
fun ClipPicture(m: Movie, rank: Int = 0, big: Boolean = false, showLength: Boolean = true, video: Boolean = false) {
    Box(Modifier.fillMaxSize()) {
        if (video) ClipVideo(m, Modifier.fillMaxSize(), width = if (big) 800 else 440, height = if (big) 450 else 248)
        else NetImage(m.cardImageUrl, Modifier.fillMaxSize(), width = if (big) 800 else 440, height = if (big) 450 else 248)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.78f))))
        if (rank > 0) {
            Text(
                rank.toString(), fontFamily = Inter, fontSize = if (big) 14.sp else 11.sp, fontWeight = FontWeight.ExtraBold,
                color = if (rank <= 3) TvColors.OnAccent else Color.White,
                modifier = Modifier.padding(if (big) 8.dp else 6.dp).align(Alignment.TopStart).clip(TvShapes.r(5.dp))
                    .background(if (rank <= 3) TvColors.Accent else Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = if (big) 9.dp else 7.dp, vertical = 2.dp),
            )
        }
        if (showLength) {
            val sz = if (big) 11.sp else 9.sp
            Row(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = if (big) 10.dp else 7.dp, vertical = if (big) 8.dp else 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
            ) {
                if (m.viewCount > 0) {
                    Text("▶ " + ClipsFormat.compactCount(m.viewCount), fontFamily = Inter, fontSize = sz, fontWeight = FontWeight.Bold, color = Color.White)
                } else Spacer(Modifier.size(1.dp))
                Text(
                    ClipsFormat.duration(m.durationSeconds), fontFamily = Inter, fontSize = sz, fontWeight = FontWeight.SemiBold, color = Color.White,
                    modifier = Modifier.clip(TvShapes.r(4.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
    }
}

/** A clip that was opened before is drawn dimmed, if the setting asks for it. */
fun Modifier.seenAlpha(m: Movie) = alpha(if (m.clipSeen) 0.4f else 1f)

/** Channel picture, channel name and category in one line. */
@Composable
fun ChannelLine(m: Movie, avatar: Dp, size: TextUnit, modifier: Modifier = Modifier, extra: String = "") {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        NetImage(m.avatarUrl, Modifier.size(avatar).clip(CircleShape), width = 80, height = 80)
        Text(
            listOf(m.displayName, m.gameName, extra).filter { it.isNotEmpty() }.joinToString("  ·  "),
            fontFamily = Inter, fontSize = size, color = TvColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A focusable clip picture in the theme's card style; [onFocus] tells the layout which clip has the focus. */
@Composable
fun ClipTile(
    m: Movie, click: () -> Unit, longPress: () -> Unit, modifier: Modifier = Modifier, rank: Int = 0, big: Boolean = false,
    corner: Dp = 10.dp, showLength: Boolean = true, onFocus: () -> Unit = {},
) {
    Card(
        onClick = click, onLongClick = longPress,
        modifier = modifier.aspectRatio(16f / 9f).onFocusChanged { if (it.isFocused) onFocus() }.menuKey(longPress),
        shape = CardDefaults.shape(TvShapes.r(corner)),
        scale = CardDefaults.scale(focusedScale = TvStyle.focusScale),
        border = focusFrame(), colors = withoutSurface(), glow = focusGlow(),
    ) { ClipPicture(m, rank, big, showLength) }
}

/** A grid card: picture, a two-line title and the channel line. */
@Composable
fun ClipCard(m: Movie, rank: Int, click: () -> Unit, longPress: () -> Unit, focus: FocusRequester? = null) {
    Column(Modifier.seenAlpha(m).fillMaxWidth()) {
        ClipTile(m, click, longPress, if (focus != null) Modifier.fillMaxWidth().focusRequester(focus) else Modifier.fillMaxWidth(), rank = rank)
        Text(
            m.title ?: "", fontFamily = Inter, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = TvColors.Text,
            maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 13.sp, modifier = Modifier.padding(top = 6.dp, start = 2.dp),
        )
        ChannelLine(m, 16.dp, 9.sp, Modifier.padding(top = 4.dp, start = 2.dp))
    }
}

/**
 * "Loading …", an error with "Try again" or why the list is empty, in the place of the list: the shared [StatusNote]
 *.
 */
@Composable
fun EmptyNote(ui: ClipsUi, modifier: Modifier = Modifier) {
    if (ui.shown.isNotEmpty()) return
    com.shrug.shrugtv.ui.tv.components.StatusNote(
        com.shrug.shrugtv.ui.tv.components.listStatus(ui.loading, ui.failed, true) ?: com.shrug.shrugtv.ui.tv.components.ListStatus.EMPTY,
        stringResource(if (ui.settings.source == ClipsSource.FAVORITES) R.string.clips_empty_favorites else R.string.clips_empty),
        modifier,
        retry = ui.retry,
    )
}

/** How long ago a clip was made, in the app language ("2 days ago"); empty if unknown. */
fun ago(m: Movie): String {
    val at = m.publishedAt ?: return ""
    return android.text.format.DateUtils.getRelativeTimeSpanString(
        at.toInstant().toEpochMilli(), System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
    ).toString()
}
