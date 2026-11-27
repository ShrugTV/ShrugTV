package com.shrug.shrugtv.ui.tv.stats

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.shrug.shrugtv.R
import com.shrug.shrugtv.stats.PeriodStats
import com.shrug.shrugtv.stats.Ranked
import com.shrug.shrugtv.stats.StatsPrefs
import com.shrug.shrugtv.stats.formatDuration
import com.shrug.shrugtv.stats.formatShortDuration
import com.shrug.shrugtv.ui.tv.Inter
import com.shrug.shrugtv.ui.tv.TvColors
import com.shrug.shrugtv.ui.tv.TvShapes
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The four other looks of the statistics page, next to the tiles of StatsActivity: a week grid, a
 * magazine, a 24-hour clock and a highscore board. All of them draw the same PeriodStats; only presentation lives here.
 */

/** Height the looks are drawn in: the page area below the period buttons on a 1080p TV. */
private val STYLE_HEIGHT = 392.dp

private val CHANNEL_COLORS = listOf(Color(0xFFFFB86B), Color(0xFF6EE7B7), Color(0xFFB9A2FF), Color(0xFFF472B6), Color(0xFFFDE68A), Color(0xFF93C5FD))
private val CLIP_BLUE = Color(0xFF4CC9F0)

@Composable
private fun T(text: String, size: TextUnit, color: Color = TvColors.Text, weight: FontWeight = FontWeight.Normal, modifier: Modifier = Modifier,
              family: FontFamily = Inter, align: TextAlign? = null, lines: Int = 1) =
    Text(text, fontFamily = family, fontWeight = weight, fontSize = size, color = color, maxLines = lines, overflow = TextOverflow.Ellipsis,
        textAlign = align, modifier = modifier)

@Composable
private fun Panel(modifier: Modifier, color: Color = TvColors.Surface, content: @Composable () -> Unit) {
    Box(modifier.clip(TvShapes.r(16.dp)).background(color).padding(horizontal = 16.dp, vertical = 12.dp)) { content() }
}

private fun peakHour(p: PeriodStats): Int? = p.peakHour

/** "Mon" ... "Sun" in the language of the app. */
private fun weekdayShort(i: Int): String = DayOfWeek.of(i + 1).getDisplayName(TextStyle.SHORT, Locale.getDefault())

// ---------------------------------------------------------------- 2 · week grid

@Composable
internal fun GridContent(p: PeriodStats, prefs: StatsPrefs) {
    val fmt = prefs.timeFormat
    Row(Modifier.fillMaxWidth().height(STYLE_HEIGHT), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        Column(Modifier.width(250.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            T(stringResource(R.string.stats_total_label), 12.sp, TvColors.TextMuted, FontWeight.SemiBold)
            T(formatDuration(p.totalSec, fmt), 34.sp, weight = FontWeight.ExtraBold)
            if (prefs.compare && p.previousTotalSec >= 0) {
                val diff = p.totalSec - p.previousTotalSec
                T((if (diff >= 0) "▲ " else "▼ ") + stringResource(R.string.stats_vs_before, formatDuration(kotlin.math.abs(diff), fmt)), 11.sp,
                    if (diff >= 0) TvColors.AccentLight else TvColors.TextMuted, FontWeight.SemiBold, lines = 2)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Fact("${p.daysWatched}/${p.periodDays}", stringResource(R.string.stats_tile_days), Modifier.weight(1f))
                Fact(p.sessions.toString(), stringResource(R.string.stats_tile_sessions), Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Fact(formatDuration(p.longestSec, fmt), stringResource(R.string.stats_tile_longest), Modifier.weight(1f))
                Fact(peakHour(p)?.let { stringResource(R.string.stats_hour_value, it) } ?: "–", stringResource(R.string.stats_style_peak), Modifier.weight(1f))
            }
            T(stringResource(R.string.stats_top_channels), 12.sp, TvColors.TextMuted, FontWeight.SemiBold)
            p.topChannels.take(4).forEachIndexed { i, r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T("${i + 1}", 12.sp, TvColors.TextMuted, FontWeight.Bold, Modifier.width(16.dp))
                    T(r.name, 13.sp, weight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    T(formatDuration(r.seconds, fmt), 12.sp, TvColors.TextMuted)
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                T(stringResource(R.string.stats_grid_title), 18.sp, weight = FontWeight.Bold)
                T(stringResource(R.string.stats_grid_hint), 11.sp, TvColors.TextMuted)
            }
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.width(30.dp))
                for (h in 0 until 24) T(if (h % 3 == 0) h.toString() else "", 10.sp, TvColors.TextMuted, modifier = Modifier.weight(1f), align = TextAlign.Center)
            }
            val max = p.secPerWeekdayHour.maxOf { row -> row.maxOrNull() ?: 0L }.coerceAtLeast(1L)
            val empty = TvColors.SurfaceHigh
            val full = TvColors.AccentLight
            for (d in 0 until 7) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    T(weekdayShort(d), 11.sp, TvColors.TextMedium, FontWeight.SemiBold, Modifier.width(27.dp))
                    for (h in 0 until 24) {
                        val v = p.secPerWeekdayHour[d][h]
                        val level = if (v <= 0) 0f else 0.25f + 0.75f * (v.toFloat() / max)
                        Box(Modifier.weight(1f).height(22.dp).clip(TvShapes.r(4.dp)).background(if (v <= 0) empty else lerp(empty, full, level)))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                T(stringResource(R.string.stats_grid_less), 10.sp, TvColors.TextMuted)
                listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { f -> Box(Modifier.size(14.dp, 9.dp).clip(TvShapes.r(2.dp)).background(if (f == 0f) empty else lerp(empty, full, f))) }
                T(stringResource(R.string.stats_grid_more), 10.sp, TvColors.TextMuted)
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth().height(70.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Panel(Modifier.weight(1.4f).fillMaxHeight()) { ShareBar(p, prefs) }
                Panel(Modifier.weight(1f).fillMaxHeight()) {
                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(38.dp).clip(TvShapes.r(10.dp)).background(CLIP_BLUE.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                            T("▶", 16.sp, CLIP_BLUE, FontWeight.Bold)
                        }
                        Column {
                            T(stringResource(R.string.stats_style_clips_count, p.clipCount), 17.sp, weight = FontWeight.Bold)
                            T(if (p.clipCount > 0) "Ø " + formatShortDuration(p.clipAverageSec, fmt) + (p.topClipChannels.firstOrNull()?.let { " · " + it.name } ?: "") else "–", 11.sp, TvColors.TextMuted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Fact(value: String, label: String, modifier: Modifier) {
    Column(modifier.clip(TvShapes.r(12.dp)).background(TvColors.Surface).padding(horizontal = 10.dp, vertical = 7.dp)) {
        T(value, 16.sp, weight = FontWeight.Bold)
        T(label, 10.sp, TvColors.TextMuted)
    }
}

/** Live, recordings and clips as one bar with a line of figures under it. */
@Composable
private fun ShareBar(p: PeriodStats, prefs: StatsPrefs) {
    val total = (p.liveSec + p.vodSec + p.clipSec).coerceAtLeast(1L)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
        T(stringResource(R.string.stats_style_share), 11.sp, TvColors.TextMuted, FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth().height(12.dp).clip(TvShapes.r(4.dp)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            if (p.liveSec > 0) Box(Modifier.weight(p.liveSec.toFloat()).fillMaxHeight().background(TvColors.Live))
            if (p.vodSec > 0) Box(Modifier.weight(p.vodSec.toFloat()).fillMaxHeight().background(TvColors.AccentLight))
            if (p.clipSec > 0) Box(Modifier.weight(p.clipSec.toFloat()).fillMaxHeight().background(CLIP_BLUE))
            if (p.liveSec + p.vodSec + p.clipSec == 0L) Box(Modifier.weight(1f).fillMaxHeight().background(TvColors.SurfaceHigh))
        }
        T("${(p.liveSec * 100 / total)} % " + stringResource(R.string.stats_legend_live) + " · " +
            formatDuration(p.vodSec, prefs.timeFormat) + " " + stringResource(R.string.stats_legend_recordings) + " · " +
            formatDuration(p.clipSec, prefs.timeFormat) + " " + stringResource(R.string.stats_style_clips), 11.sp, TvColors.TextMedium)
    }
}

// ---------------------------------------------------------------- 3 · magazine

@Composable
internal fun MagazineContent(p: PeriodStats, prefs: StatsPrefs) {
    val fmt = prefs.timeFormat
    val purple = TvColors.Accent
    val onPurple = TvColors.OnAccent
    Row(Modifier.fillMaxWidth().height(STYLE_HEIGHT), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.weight(1.2f).fillMaxHeight().clip(TvShapes.r(22.dp)).background(purple).padding(22.dp)) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
                T(stringResource(R.string.stats_mag_time), 12.sp, onPurple, FontWeight.Bold)
                Column {
                    T(formatDuration(p.totalSec, fmt), 38.sp, onPurple, FontWeight.ExtraBold)
                    val films = (p.totalSec / 7200).toInt()
                    if (films >= 1) T(stringResource(R.string.stats_mag_films, films), 17.sp, onPurple, FontWeight.Bold, lines = 2)
                    if (prefs.compare && p.previousTotalSec >= 0) {
                        val diff = p.totalSec - p.previousTotalSec
                        T((if (diff >= 0) "▲ " else "▼ ") + stringResource(R.string.stats_vs_before, formatDuration(kotlin.math.abs(diff), fmt)), 13.sp, onPurple, FontWeight.SemiBold, lines = 2)
                    }
                }
                val max = p.buckets.maxOfOrNull { it.seconds }?.coerceAtLeast(1L) ?: 1L
                Row(Modifier.fillMaxWidth().height(46.dp), horizontalArrangement = Arrangement.spacedBy(if (p.buckets.size > 14) 1.dp else 5.dp), verticalAlignment = Alignment.Bottom) {
                    p.buckets.forEach { b ->
                        Box(Modifier.weight(1f).fillMaxHeight((b.seconds.toFloat() / max).coerceAtLeast(0.04f)).clip(TvShapes.r(3.dp)).background(onPurple))
                    }
                }
            }
        }
        Column(Modifier.weight(2f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val top: Ranked? = p.topChannels.firstOrNull()
                MagTile(Modifier.weight(1f), TvColors.Ink, TvColors.Background, stringResource(R.string.stats_mag_channel)) {
                    if (top == null) T("–", 30.sp, TvColors.Background, FontWeight.ExtraBold) else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(Modifier.size(42.dp).clip(CircleShape).background(CHANNEL_COLORS[0]), contentAlignment = Alignment.Center) {
                                T(top.name.take(1).uppercase(), 20.sp, Color(0xFF111111), FontWeight.ExtraBold)
                            }
                            Column {
                                T(top.name, 22.sp, TvColors.Background, FontWeight.ExtraBold)
                                val share = if (p.totalSec > 0) (top.seconds * 100 / p.totalSec).toInt() else 0
                                T(formatDuration(top.seconds, fmt) + " · " + stringResource(R.string.stats_mag_share, share), 12.sp, TvColors.Background.copy(alpha = 0.75f), lines = 2)
                            }
                        }
                    }
                }
                MagTile(Modifier.weight(1f), TvColors.Surface, TvColors.Text, stringResource(R.string.stats_mag_hour)) {
                    T(peakHour(p)?.let { stringResource(R.string.stats_hour_value, it) } ?: "–", 40.sp, weight = FontWeight.ExtraBold)
                    HourBars(p, Modifier.fillMaxWidth().height(26.dp), TvColors.AccentLight, TvColors.TextMuted.copy(alpha = 0.5f))
                }
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MagTile(Modifier.weight(1f), CLIP_BLUE, Color(0xFF06222C), stringResource(R.string.stats_style_clips)) {
                    T(p.clipCount.toString(), 46.sp, Color(0xFF06222C), FontWeight.ExtraBold)
                    T(if (p.clipCount > 0) formatDuration(p.clipSec, fmt) + " · Ø " + formatShortDuration(p.clipAverageSec, fmt) else "–", 13.sp, Color(0xFF06222C), FontWeight.SemiBold)
                }
                val cat = p.topCategories.firstOrNull()
                MagTile(Modifier.weight(1f), Color(0xFFFF7A59), Color(0xFF2A0D05), stringResource(R.string.stats_mag_category)) {
                    T(cat?.name ?: "–", 28.sp, Color(0xFF2A0D05), FontWeight.ExtraBold)
                    T((cat?.let { formatDuration(it.seconds, fmt) + " · " } ?: "") + stringResource(R.string.stats_mag_days, p.daysWatched, p.periodDays), 13.sp, Color(0xFF2A0D05), FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun MagTile(modifier: Modifier, bg: Color, fg: Color, label: String, content: @Composable () -> Unit) {
    Box(modifier.fillMaxHeight().clip(TvShapes.r(22.dp)).background(bg).padding(horizontal = 18.dp, vertical = 14.dp)) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            T(label, 11.sp, fg.copy(alpha = 0.8f), FontWeight.Bold)
            Column { content() }
        }
    }
}

@Composable
private fun HourBars(p: PeriodStats, modifier: Modifier, peak: Color, rest: Color) {
    val max = p.secPerHour.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    val top = p.peakHour
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        p.secPerHour.forEachIndexed { h, v ->
            Box(Modifier.weight(1f).fillMaxHeight((v.toFloat() / max).coerceAtLeast(0.08f)).clip(TvShapes.r(2.dp)).background(if (h == top) peak else rest))
        }
    }
}

// ---------------------------------------------------------------- 4 · 24-hour clock

@Composable
internal fun ClockContent(p: PeriodStats, prefs: StatsPrefs) {
    val fmt = prefs.timeFormat
    Row(Modifier.fillMaxWidth().height(STYLE_HEIGHT), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(220.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            T(stringResource(R.string.stats_clock_title), 22.sp, weight = FontWeight.Bold)
            T(stringResource(R.string.stats_clock_hint), 12.sp, TvColors.TextMedium, lines = 4)
            Spacer(Modifier.height(4.dp))
            ClockFact(stringResource(R.string.stats_style_peak), peakHour(p)?.let { stringResource(R.string.stats_hour_value, it) } ?: "–")
            ClockFact(stringResource(R.string.stats_tile_sessions), p.sessions.toString())
            ClockFact(stringResource(R.string.stats_tile_longest), formatDuration(p.longestSec, fmt))
            ClockFact(stringResource(R.string.stats_tile_days), "${p.daysWatched}/${p.periodDays}")
        }
        val track = TvColors.SurfaceHigh
        val ray = TvColors.Accent.copy(alpha = 0.55f)
        val peakColor = TvColors.AccentLight
        val tick = TvColors.TextMuted
        Box(Modifier.size(STYLE_HEIGHT), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val c = Offset(size.width / 2, size.height / 2)
                val outer = size.minDimension / 2 - 30.dp.toPx()
                val r0 = outer * 0.6f
                val span = outer - r0
                val max = p.secPerHour.maxOrNull()?.coerceAtLeast(1L) ?: 1L
                val clipMax = p.clipSecPerHour.maxOrNull()?.coerceAtLeast(1L) ?: 1L
                val stroke = (2 * PI * r0 / 24 * 0.62f).toFloat()
                drawCircle(track, r0 - stroke * 0.9f, c, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
                for (h in 0 until 24) {
                    val a = (h + 0.5) / 24 * 2 * PI
                    val dir = Offset(sin(a).toFloat(), -cos(a).toFloat())
                    val v = p.secPerHour[h]
                    val len = if (v > 0) 6.dp.toPx() + span * (v.toFloat() / max) else 3.dp.toPx()
                    drawLine(if (h == p.peakHour) peakColor else if (v > 0) ray else track, c + dir * r0, c + dir * (r0 + len), stroke, StrokeCap.Round)
                    val cv = p.clipSecPerHour[h]
                    if (cv > 0) drawLine(CLIP_BLUE, c + dir * (r0 - 8.dp.toPx() - 10.dp.toPx() * (cv.toFloat() / clipMax)), c + dir * (r0 - 6.dp.toPx()), stroke * 0.7f, StrokeCap.Round)
                }
                for (h in listOf(0, 6, 12, 18)) {
                    val a = h / 24.0 * 2 * PI
                    val dir = Offset(sin(a).toFloat(), -cos(a).toFloat())
                    drawLine(tick, c + dir * (outer + 4.dp.toPx()), c + dir * (outer + 10.dp.toPx()), 2.dp.toPx())
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                T(formatDuration(p.totalSec, fmt), 19.sp, weight = FontWeight.ExtraBold)
                T(stringResource(R.string.stats_total_label), 11.sp, TvColors.TextMuted)
            }
            T("0", 11.sp, TvColors.TextMuted, FontWeight.Bold, Modifier.align(Alignment.TopCenter))
            T("12", 11.sp, TvColors.TextMuted, FontWeight.Bold, Modifier.align(Alignment.BottomCenter))
            T("6", 11.sp, TvColors.TextMuted, FontWeight.Bold, Modifier.align(Alignment.CenterEnd))
            T("18", 11.sp, TvColors.TextMuted, FontWeight.Bold, Modifier.align(Alignment.CenterStart))
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
            T(stringResource(R.string.stats_clock_orbit), 12.sp, TvColors.TextMuted, FontWeight.Bold)
            val ch = p.topChannels.take(4)
            val maxSec = ch.firstOrNull()?.seconds?.coerceAtLeast(1L) ?: 1L
            ch.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    row.forEach { r ->
                        val i = ch.indexOf(r)
                        val d: Dp = (46 + sqrt(r.seconds.toFloat() / maxSec) * 42).dp
                        Box(Modifier.size(d).clip(CircleShape).background(CHANNEL_COLORS[i % CHANNEL_COLORS.size]), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                T(r.name, if (d > 70.dp) 11.sp else 9.sp, Color(0xFF111111), FontWeight.Bold, Modifier.padding(horizontal = 4.dp), align = TextAlign.Center)
                                T(formatDuration(r.seconds, fmt), 9.sp, Color(0xFF111111).copy(alpha = 0.8f))
                            }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(CLIP_BLUE))
                T(stringResource(R.string.stats_style_clips_count, p.clipCount) + " · " + formatDuration(p.clipSec, fmt), 11.sp, TvColors.TextMedium)
            }
        }
    }
}

@Composable
private fun ClockFact(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        T(label, 12.sp, TvColors.TextMedium, modifier = Modifier.weight(1f))
        T(value, 15.sp, weight = FontWeight.Bold)
    }
}

// ---------------------------------------------------------------- 5 · highscore board

private val ARC_BG = Color(0xFF050307)
private val ARC_YELLOW = Color(0xFFFFD23F)
private val ARC_PINK = Color(0xFFFF3CAC)
private val ARC_CYAN = Color(0xFF5EF2FF)
private val ARC_GREEN = Color(0xFF7CFF6B)
private val ARC_WHITE = Color(0xFFFFF8E1)
private val ARC_DIM = Color(0xFF6B5A7A)

@Composable
internal fun ArcadeContent(p: PeriodStats, prefs: StatsPrefs) {
    val fmt = prefs.timeFormat
    val mono = FontFamily.Monospace
    Column(Modifier.fillMaxWidth().height(STYLE_HEIGHT).clip(TvShapes.r(6.dp)).background(ARC_BG).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().border(BorderStroke(2.dp, ARC_PINK)).padding(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            T(stringResource(R.string.stats_arc_title), 20.sp, ARC_PINK, FontWeight.Bold, family = mono)
            T("1P ▶ " + stringResource(R.string.stats_arc_you), 12.sp, ARC_YELLOW, FontWeight.Bold, family = mono)
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1.2f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    T(stringResource(R.string.stats_arc_rank), 11.sp, ARC_CYAN, FontWeight.Bold, Modifier.width(52.dp), mono)
                    T(stringResource(R.string.stats_arc_channel), 11.sp, ARC_CYAN, FontWeight.Bold, Modifier.weight(1f), mono)
                    T(stringResource(R.string.stats_arc_time), 11.sp, ARC_CYAN, FontWeight.Bold, family = mono)
                }
                val colors = listOf(ARC_YELLOW, ARC_WHITE, Color(0xFFFFB36B), Color(0xFFC9B6FF), Color(0xFFC9B6FF), Color(0xFFC9B6FF))
                p.topChannels.take(6).forEachIndexed { i, r ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        T(listOf("1ST", "2ND", "3RD", "4TH", "5TH", "6TH")[i], 20.sp, colors[i], FontWeight.Bold, Modifier.width(52.dp), mono)
                        T(r.name.uppercase() + " " + ".".repeat((14 - r.name.length).coerceAtLeast(2)), 20.sp, colors[i], FontWeight.Bold, Modifier.weight(1f), mono)
                        T(formatDuration(r.seconds, fmt), 18.sp, colors[i], FontWeight.Bold, family = mono)
                    }
                }
                Spacer(Modifier.weight(1f))
                p.busiest?.let { b -> T(stringResource(R.string.stats_arc_record, formatDuration(b.seconds, fmt)), 11.sp, ARC_PINK, FontWeight.Bold, family = mono) }
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Counter(stringResource(R.string.stats_arc_total), formatDuration(p.totalSec, fmt), ARC_YELLOW, Modifier.weight(1f))
                    Counter(stringResource(R.string.stats_arc_sessions), p.sessions.toString().padStart(3, '0'), ARC_PINK, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Counter(stringResource(R.string.stats_arc_days), "${p.daysWatched}/${p.periodDays}", ARC_CYAN, Modifier.weight(1f))
                    Counter(stringResource(R.string.stats_style_clips).uppercase(), p.clipCount.toString().padStart(3, '0'), ARC_GREEN, Modifier.weight(1f))
                }
                Column(Modifier.fillMaxWidth().weight(1f).border(BorderStroke(2.dp, ARC_CYAN)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    T(stringResource(R.string.stats_arc_levels), 10.sp, ARC_CYAN, FontWeight.Bold, family = mono)
                    val days = p.buckets.takeLast(if (p.buckets.size > 14) 14 else p.buckets.size)
                    val max = days.maxOfOrNull { it.seconds }?.coerceAtLeast(1L) ?: 1L
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
                        days.forEach { b ->
                            val n = if (b.seconds <= 0) 0 else (1 + (b.seconds * 9 / max)).toInt().coerceAtMost(10)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                for (k in 9 downTo 0) {
                                    Box(Modifier.fillMaxWidth().height(6.dp).background(if (k < n) (if (k >= 7) ARC_PINK else if (k >= 4) ARC_YELLOW else ARC_GREEN) else Color.Transparent))
                                }
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            T(stringResource(R.string.stats_arc_hint), 10.sp, ARC_WHITE, FontWeight.Bold, family = mono)
            T("CREDIT 01", 10.sp, ARC_DIM, FontWeight.Bold, family = mono)
        }
    }
}

@Composable
private fun Counter(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier.border(BorderStroke(2.dp, color)).padding(horizontal = 10.dp, vertical = 6.dp)) {
        T(label, 10.sp, color, FontWeight.Bold, family = FontFamily.Monospace)
        T(value, 19.sp, ARC_WHITE, FontWeight.Bold, family = FontFamily.Monospace)
    }
}
