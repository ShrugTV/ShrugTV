package com.shrug.shrugtv.ui.tv.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.shrug.shrugtv.R
import com.shrug.shrugtv.model.Movie
import com.shrug.shrugtv.ui.tv.Inter
import com.shrug.shrugtv.ui.tv.TvColors
import com.shrug.shrugtv.ui.tv.clips.ClipTile
import com.shrug.shrugtv.ui.tv.clips.ClipsFormat
import com.shrug.shrugtv.ui.tv.components.ListStatus
import com.shrug.shrugtv.ui.tv.components.StatusNote
import java.time.ZonedDateTime

/**
 * "Highlights of this stream": the most viewed clips since the live stream started, in a strip
 * at the bottom like the chapters. OK plays a clip in this player (the others follow as its queue), Back on the clip
 * returns to the live stream. [clips] null = loading, [failed] = Twitch did not answer.
 */
@Composable
fun PlayerHighlightsOverlay(
    visible: Boolean,
    clips: List<Movie>?,
    failed: Boolean,
    streamStart: ZonedDateTime?,
    onSelect: (Movie) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(visible, clips, failed) {
        if (visible) {
            kotlinx.coroutines.delay(50)
            runCatching { first.requestFocus() }
        }
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.45f to Color(0x99000000),
                        0.7f to TvColors.Background.copy(alpha = 0xDD / 255f),
                        1.0f to TvColors.Background.copy(alpha = 0xF8 / 255f)
                    )
                ),
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
                Column(Modifier.padding(horizontal = 32.dp, vertical = 12.dp)) {
                    Text(
                        stringResource(R.string.player_highlights_title),
                        fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = TvColors.Text
                    )
                    Text(
                        stringResource(R.string.player_highlights_hint),
                        fontFamily = Inter, fontSize = 12.sp, color = TvColors.TextMuted
                    )
                }
                val status = when {
                    failed -> ListStatus.ERROR
                    clips == null -> ListStatus.LOADING
                    clips.isEmpty() -> ListStatus.EMPTY
                    else -> null
                }
                val list = clips
                if (status != null || list == null) {
                    // The note takes the focus so Back and "Try again" work; the bar behind it stays closed
                    StatusNote(
                        status = status ?: ListStatus.LOADING,
                        emptyText = stringResource(R.string.player_highlights_empty),
                        errorText = stringResource(R.string.player_highlights_error),
                        retry = onRetry,
                        modifier = Modifier.padding(horizontal = 32.dp).height(72.dp).focusRequester(first).focusable()
                    )
                } else {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 32.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        itemsIndexed(list, key = { _, c -> c.clipSlug }) { i, clip ->
                            HighlightCard(clip, i + 1, streamStart, { onSelect(clip) }, if (i == 0) Modifier.focusRequester(first) else Modifier)
                        }
                    }
                }
            }
        }
    }
}

private val CARD_WIDTH = 232.dp

@Composable
private fun HighlightCard(clip: Movie, rank: Int, streamStart: ZonedDateTime?, onClick: () -> Unit, focus: Modifier) {
    Column(Modifier.width(CARD_WIDTH)) {
        ClipTile(clip, onClick, {}, focus.fillMaxWidth(), rank = rank)
        Text(
            clip.title ?: "", fontFamily = Inter, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TvColors.Text,
            maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 15.sp, modifier = Modifier.padding(top = 6.dp, start = 2.dp)
        )
        val parts = listOfNotNull(
            PlayerHighlights.secondsIntoStream(clip, streamStart)?.let { stringResource(R.string.player_highlights_after, ClipsFormat.duration(it.toInt())) },
            stringResource(R.string.clips_views, ClipsFormat.compactCount(clip.viewCount))
        )
        Text(
            parts.joinToString("  ·  "), fontFamily = Inter, fontSize = 10.sp, color = TvColors.TextMuted,
            maxLines = 1, modifier = Modifier.padding(top = 2.dp, start = 2.dp)
        )
    }
}
