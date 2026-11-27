package com.shrug.shrugtv.ui.tv.player

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import com.shrug.shrugtv.ui.tv.TvColors

// The layers of the player that read state of their own, split from PlayerScreen.

/**
 * The video: the main player alone, or side by side with the multiview player ([secondPlayer] is read on every update,
 * as before), the one with the sound framed.
 */
@Composable
internal fun PlayerVideoSurface(
    mainPlayer: Player,
    secondPlayer: () -> Player?,
    isMultiview: Boolean,
    isPrimaryAudio: Boolean,
    modifier: Modifier
) {
    Box(modifier = modifier) {
        // 1. Video view (single or multiview split screen)
        if (isMultiview && secondPlayer() != null) {
            Row(modifier = Modifier.fillMaxSize()) {
                // Main player (left)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .border(
                            width = if (isPrimaryAudio) 3.dp else 0.dp,
                            color = if (isPrimaryAudio) TvColors.Focus else Color.Transparent
                        )
                ) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = false
                                controllerAutoShow = false
                            }
                        },
                        update = { playerView ->
                            if (playerView.player !== mainPlayer) {
                                playerView.player = mainPlayer
                            }
                            playerView.useController = false
                            playerView.hideController()
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Second player (right)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .border(
                            width = if (!isPrimaryAudio) 3.dp else 0.dp,
                            color = if (!isPrimaryAudio) TvColors.Focus else Color.Transparent
                        )
                ) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = false
                                controllerAutoShow = false
                            }
                        },
                        update = { playerView ->
                            if (playerView.player !== secondPlayer()) {
                                playerView.player = secondPlayer()
                            }
                            playerView.useController = false
                            playerView.hideController()
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        } else {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        controllerAutoShow = false
                        // No black flash between live and its recording
                        setKeepContentOnPlayerReset(true)
                    }
                },
                update = { playerView ->
                    if (playerView.player !== mainPlayer) {
                        playerView.player = mainPlayer
                    }
                    playerView.useController = false
                    playerView.hideController()
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * The chat panel with its own subscription to the lines: every message recomposes only this
 * layer, not the whole player. Transparent layout: only messages of the last 30 s, max. 8; re-checked every 2 s.
 */
@Composable
internal fun PlayerChatLayer(
    chat: ChatViewModel,
    visible: Boolean,
    transparent: Boolean,
    modifier: Modifier,
    textSizeSp: Float,
    background: Color,
    textShadow: Boolean,
    fromLeft: Boolean,
    look: com.shrug.shrugtv.chat.ChatLook
) {
    val lines by chat.lines.collectAsState()
    val roomSettings by chat.roomSettings.collectAsState()
    val transparentNow by produceState(0L, transparent, visible) {
        while (visible && transparent) {
            value = android.os.SystemClock.uptimeMillis()
            kotlinx.coroutines.delay(2_000L)
        }
    }
    ChatPanel(
        visible = visible,
        lines = if (transparent) {
            lines.filter { !it.deleted && transparentNow - it.addedAt < 30_000L }.takeLast(8)
        } else lines,
        roomSettings = if (transparent) null else roomSettings,
        modifier = modifier,
        textSizeSp = textSizeSp,
        background = background,
        textShadow = textShadow,
        fromLeft = fromLeft,
        look = look
    )
}

/** The statistics overlay, collecting its values (once a second while open) itself. */
@Composable
internal fun PlayerStatsLayer(visible: Boolean, stats: kotlinx.coroutines.flow.StateFlow<PlayerStatsState>) {
    val values by stats.collectAsState()
    PlayerStatsOverlay(visible = visible, stats = values)
}
