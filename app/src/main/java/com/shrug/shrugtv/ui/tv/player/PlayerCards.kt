package com.shrug.shrugtv.ui.tv.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.shrug.shrugtv.R
import com.shrug.shrugtv.model.Movie
import com.shrug.shrugtv.model.RaidNotification
import com.shrug.shrugtv.ui.tv.Inter
import com.shrug.shrugtv.ui.tv.TvColors
import com.shrug.shrugtv.ui.tv.TvShapes

// The cards and notes over the picture, split from PlayerScreen. Where a card sits and when it
// shows is decided by PlayerScreen; these only draw it, with the [modifier] they get.

/** Notice from Home Assistant or the phone, e.g. the doorbell with a camera picture. */
@Composable
internal fun PlayerNoticeCard(n: ExternalNotice, modifier: Modifier) {
    androidx.compose.foundation.layout.Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(TvShapes.r(16.dp))
            .background(TvColors.Panel)
            .border(1.dp, TvColors.glass(0.25f), TvShapes.r(16.dp))
            .padding(14.dp)
    ) {
        if (n.imageUrl != null) {
            com.shrug.shrugtv.ui.tv.home.NetImage(
                url = n.imageUrl,
                modifier = Modifier.width(192.dp).height(108.dp).clip(TvShapes.r(10.dp)),
                width = 384,
                height = 216
            )
            androidx.compose.foundation.layout.Spacer(Modifier.width(16.dp))
        }
        androidx.compose.foundation.layout.Column(modifier = Modifier.widthIn(max = 420.dp)) {
            Text(n.title, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White)
            n.text?.let { Text(it, fontFamily = Inter, fontSize = 14.sp, color = TvColors.glass(0.8f)) }
        }
    }
}

/** Raid from this channel: offer to follow (docs/ideas.md 2). */
@Composable
internal fun PlayerRaidCard(raid: RaidNotification, channelName: String, modifier: Modifier) {
    Box(
        modifier = modifier
            .clip(TvShapes.r(16.dp))
            .background(TvColors.Panel)
            .border(1.dp, TvColors.AccentSoft, TvShapes.r(16.dp))
            .padding(horizontal = 24.dp, vertical = 14.dp)
    ) {
        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.player_raid_title, channelName, raid.targetDisplayName ?: ""),
                fontFamily = Inter,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = Color.White
            )
            Text(
                text = stringResource(R.string.player_raid_hint),
                fontFamily = Inter,
                fontSize = 14.sp,
                color = TvColors.glass(0.7f),
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/** A followed channel went live: offer to switch. */
@Composable
internal fun PlayerLiveCard(live: Movie, modifier: Modifier) {
    androidx.compose.foundation.layout.Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(TvShapes.r(16.dp))
            .background(TvColors.Panel)
            .border(1.dp, TvColors.Live, TvShapes.r(16.dp))
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        live.avatarUrl?.takeIf { it.isNotBlank() }?.let { url ->
            com.shrug.shrugtv.ui.tv.home.NetImage(
                url = url,
                modifier = Modifier.width(44.dp).height(44.dp).clip(TvShapes.r(22.dp)),
                width = 88,
                height = 88
            )
            androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
        }
        androidx.compose.foundation.layout.Column(modifier = Modifier.widthIn(max = 360.dp)) {
            Text(
                text = stringResource(R.string.player_live_card_title, live.displayName.ifBlank { live.login ?: "" }),
                fontFamily = Inter,
                fontWeight = FontWeight.Bold,
                fontSize = 17.sp,
                color = Color.White,
                maxLines = 1
            )
            if (live.gameName.isNotBlank()) {
                Text(live.gameName, fontFamily = Inter, fontSize = 14.sp, color = TvColors.glass(0.8f), maxLines = 1)
            }
            Text(
                text = stringResource(R.string.player_live_card_hint),
                fontFamily = Inter,
                fontSize = 13.sp,
                color = TvColors.glass(0.6f),
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/** Ad-break sign from the ad blocker (B13); [label] is a string resource. */
@Composable
internal fun PlayerAdBreakSign(label: Int, modifier: Modifier) {
    Box(
        modifier = modifier
            .clip(TvShapes.r(12.dp))
            .background(Color.Black.copy(alpha = 0.75f))
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = stringResource(label),
            fontFamily = Inter,
            fontSize = 14.sp,
            color = Color.White
        )
    }
}

/** The title of a clip with its channel and its place in the queue. */
@Composable
internal fun PlayerClipTitle(title: String, channel: String, clipNav: Pair<Int, Int>?, modifier: Modifier) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier
            .padding(24.dp)
            .clip(com.shrug.shrugtv.ui.tv.TvShapes.r(12.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .widthIn(max = 560.dp)
    ) {
        Text(title, fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Color.White, maxLines = 2)
        val where = clipNav?.let { " · ${it.first} / ${it.second}" }.orEmpty()
        Text(channel + where, fontFamily = Inter, fontSize = 13.sp, color = TvColors.glass(0.75f))
    }
}

/** Seek HUD (+10 s / -10 s, seek target, replay, notes). */
@Composable
internal fun PlayerSeekHud(text: String) {
    Box(
        modifier = Modifier
            .clip(TvShapes.r(12.dp))
            .background(Color.Black.copy(alpha = 0.8f))
            .border(1.dp, TvColors.glass(0.2f), TvShapes.r(12.dp))
            .padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
        Text(
            text = text,
            fontFamily = Inter,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
            color = Color.White
        )
    }
}

/** Switch between live and the recording: say what happens instead of a hanging picture. */
@Composable
internal fun PlayerArchiveSwitchNote(archiveSwitch: Int) {
    Box(
        modifier = Modifier
            .clip(TvShapes.r(16.dp))
            .background(Color.Black.copy(alpha = 0.8f))
            .border(1.dp, TvColors.glass(0.2f), TvShapes.r(16.dp))
            .padding(horizontal = 28.dp, vertical = 16.dp)
    ) {
        Text(
            text = stringResource(if (archiveSwitch == 1) R.string.player_loading_archive else R.string.player_loading_live),
            fontFamily = Inter,
            fontWeight = FontWeight.SemiBold,
            fontSize = 18.sp,
            color = Color.White
        )
    }
}

/**
 * Error, end of a live stream or end of a recording with a next one (B8); the countdown is collected
 * here, so its tick recomposes only the card.
 */
@Composable
internal fun PlayerStatusCard(
    playbackStatus: PlaybackStatus,
    isVod: Boolean,
    next: Movie?,
    countdown: kotlinx.coroutines.flow.StateFlow<Int>
) {
    val endCountdown by countdown.collectAsState()
    Box(
        modifier = Modifier
            .clip(TvShapes.r(16.dp))
            .background(Color.Black.copy(alpha = 0.8f))
            .border(1.dp, TvColors.glass(0.2f), TvShapes.r(16.dp))
            .padding(horizontal = 32.dp, vertical = 20.dp)
    ) {
        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(when {
                    playbackStatus == PlaybackStatus.ERROR -> R.string.player_playback_error
                    isVod -> R.string.player_vod_ended
                    else -> R.string.player_stream_ended
                }),
                fontFamily = Inter,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = Color.White
            )
            if (next != null) {
                // Next followed live channel, switched to when the countdown ends (docs/ideas.md 3);
                // after a recording the next recording of the channel, named by its title
                val name = if (isVod) next.title.orEmpty().ifEmpty { next.displayName } else next.displayName.ifEmpty { next.login ?: "" }
                Text(
                    text = if (endCountdown > 0) {
                        stringResource(R.string.player_next_channel, name, endCountdown)
                    } else {
                        stringResource(R.string.player_next_channel_suggest, name)
                    },
                    maxLines = 2,
                    fontFamily = Inter,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = Color.White,
                    modifier = Modifier.padding(top = 10.dp).widthIn(max = 640.dp)
                )
                if (next.gameName.isNotEmpty()) {
                    Text(text = next.gameName, fontFamily = Inter, fontSize = 14.sp, color = TvColors.glass(0.7f))
                }
            }
            Text(
                text = stringResource(if (next != null) R.string.player_next_hint else R.string.player_status_hint),
                fontFamily = Inter,
                fontSize = 14.sp,
                color = TvColors.glass(0.7f),
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
