package com.shrug.shrugtv.ui.tv.player

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shrug.shrugtv.R
import com.shrug.shrugtv.model.Enums
import com.shrug.shrugtv.model.Movie
import com.shrug.shrugtv.player.QualityMatch
import com.shrug.shrugtv.prefs.Prefs
import com.shrug.shrugtv.ui.tv.TvColors

/**
 * Complete Compose PlayerScreen (slices 4b, 4c, 4d).
 *
 * Verwaltet:
 * - ExoPlayer video surface (single and multiview split screen)
 * - Compose overlays (bar, channel switcher, quality menu, search, message, statistics, speed, chapters)
 * - Seek HUD (+10s / -10s)
 * - Remote D-pad key mapping
 */
@Composable
fun PlayerScreen(
    viewModel: PlayerViewModel,
    onBack: () -> Unit,
    onNavigateToChannel: (Movie) -> Unit,
    modifier: Modifier = Modifier
) {
    // Debug builds: recomposition counts in the log every 10 s
    CountRecompositions("PlayerScreen")
    RecompositionLogger()
    val movie by viewModel.currentMovie.collectAsState()
    val mainPlayer by viewModel.mainPlayerState.collectAsState()
    val overlay by viewModel.activeOverlay.collectAsState()
    val currentQuality by viewModel.currentQuality.collectAsState()
    val isFollowed by viewModel.isFollowed.collectAsState()
    val qualities by viewModel.qualities.collectAsState()
    val chapters by viewModel.chapters.collectAsState()
    val highlights by viewModel.highlights.collectAsState()
    val highlightsFailed by viewModel.highlightsFailed.collectAsState()
    val isChatVisible by viewModel.isChatVisible.collectAsState()
    val currentSpeed by viewModel.currentSpeed.collectAsState()
    val seekHudText by viewModel.seekHudText.collectAsState()
    val isMultiview by viewModel.isMultiview.collectAsState()
    val isPrimaryAudio by viewModel.isPrimaryAudio.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val streamAvatar by viewModel.streamAvatar.collectAsState()
    val pendingRaid by viewModel.pendingRaid.collectAsState()
    val liveCard by viewModel.liveCard.collectAsState()
    val liveArchive by viewModel.liveArchive.collectAsState()
    val archiveSwitch by viewModel.archiveSwitch.collectAsState()
    val endSuggestion by viewModel.endSuggestion.collectAsState()
    val externalNotice by viewModel.notice.collectAsState()
    // Audio only: black picture with name and clock; the chat stays on top
    val audioOnly = QualityMatch.isAudioOnly(currentQuality) && !isMultiview

    // Home Assistant / phone: "close the player"
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.closeRequests.collect { onBack() }
    }
    val playbackStatus by viewModel.playbackStatus.collectAsState()

    // Chat layout (docs/chat_plan.md phase 3): side by side is the default; "Adjust chat" changes it live
    val chatAppearance by viewModel.chatAppearance.collectAsState()
    val chatLayout = chatAppearance.layout
    val chatUnavailable by viewModel.chatUnavailable.collectAsState()
    val clipSourceReady by viewModel.clipSourceReady.collectAsState()
    val clipTitle by viewModel.clipTitle.collectAsState()
    val clipNav by viewModel.clipNav.collectAsState()
    val clipChannelLive by viewModel.clipChannelLive.collectAsState()
    val chatShown = isChatVisible && !chatUnavailable
    val lastBackAt = remember { longArrayOf(0L) }
    val sideBySide = chatShown && chatLayout == com.shrug.shrugtv.chat.ChatLayout.SIDE_BY_SIDE
    val chatWidth = chatAppearance.widthDp(chatAppearance.textSizeSp).dp
    val chatOnLeft = chatAppearance.onLeft
    // Animated, so the picture glides aside when the chat appears instead of jumping
    val videoStart by androidx.compose.animation.core.animateDpAsState(if (sideBySide && chatOnLeft) chatWidth else 0.dp, label = "videoStart")
    val videoEnd by androidx.compose.animation.core.animateDpAsState(if (sideBySide && !chatOnLeft) chatWidth else 0.dp, label = "videoEnd")
    val videoPadding = Modifier.padding(start = videoStart, end = videoEnd)
    val adBlockLabel by viewModel.adBlockLabel.collectAsState()
    val burnIn = viewModel.burnInProtection

    val isVod = movie?.playbackType == Enums.PlaybackKind.VOD || (movie?.videoId ?: 0L) > 0L

    val playerFocusRequester = remember { FocusRequester() }
    // Highlighted button of the control bar and the "More" menu, kept across submenus (B34, B37)
    val controlsFocusIndex = remember { androidx.compose.runtime.mutableStateOf(0) }
    val moreMenuOpen = remember { androidx.compose.runtime.mutableStateOf(false) }
    // Opens the bar from the video with the first button highlighted
    val openControls = {
        controlsFocusIndex.value = 0
        moreMenuOpen.value = false
        viewModel.showOverlay(PlayerOverlayType.CONTROLS)
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val focusManager = LocalFocusManager.current

    // Bring the focus back to the root box when no overlay is active
    androidx.compose.runtime.LaunchedEffect(overlay) {
        if (overlay == PlayerOverlayType.NONE) {
            runCatching { playerFocusRequester.requestFocus() }
        }
    }

    // Initially on opening: show the control bar for 5 seconds
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.setOverlay(PlayerOverlayType.CONTROLS)
    }

    // Auto-hide after the LAST key press (setting "Hide the control bar after", default 8 s). Keyed only on the overlay, the bar used to vanish
    // 5 s after opening, also while the user was navigating it or the "More" menu (B29).
    var lastInteraction by remember { mutableLongStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(overlay, lastInteraction) {
        if (overlay == PlayerOverlayType.CONTROLS) {
            kotlinx.coroutines.delay(viewModel.controlsTimeoutMs)
            viewModel.dismissOverlay()
        }
    }

    // Paused for longer than the setting: rest screen until the next key press
    val restAfterMin = viewModel.restAfterMinutes
    val restShowing by produceState(false, isPlaying, playbackStatus, lastInteraction, restAfterMin) {
        value = false
        if (restAfterMin > 0 && !isPlaying && playbackStatus == PlaybackStatus.PAUSED) {
            kotlinx.coroutines.delay(restAfterMin * 60_000L)
            value = !viewModel.mainPlayer.playWhenReady
        }
    }

    // Statistics overlay: fresh values every second while it is open
    androidx.compose.runtime.LaunchedEffect(overlay) {
        while (overlay == PlayerOverlayType.STATS) {
            viewModel.updateStats()
            kotlinx.coroutines.delay(1_000L)
        }
    }

    // Rewinding a live stream into its recording
    val inArchive = movie?.archiveOfLive == true
    // The recording of a live stream has the same timeline as the live stream, also while it loads
    val liveTimeline = liveArchive != null && (!isVod || inArchive)
    val barOpen = overlay == PlayerOverlayType.CONTROLS

    // Back pressed to leave: the note shows until the activity has closed
    var leaving by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    // Back key: first close an active overlay, otherwise back to the home screen
    BackHandler(enabled = true) {
        if (overlay == PlayerOverlayType.NONE && pendingRaid != null) {
            viewModel.dismissRaid()
        } else if (overlay == PlayerOverlayType.NONE && endSuggestion != null) {
            viewModel.cancelEndSuggestion()
        } else if (overlay == PlayerOverlayType.NONE && liveCard != null) {
            viewModel.dismissLiveCard()
        } else if (overlay != PlayerOverlayType.NONE) {
            viewModel.backFromOverlay()
        } else if (isMultiview) {
            viewModel.stopMultiview()
        } else if (viewModel.backToLiveFromHighlight()) {
            // A clip opened from the highlights: back to the live stream
        } else if (!leaving) {
            // Answer the press at once: sound and picture off, "Leaving the stream …" for one frame, then close
            //; further presses meanwhile do nothing, and the home screen ignores one right after
            viewModel.quietForExit()
            com.shrug.shrugtv.ui.tv.player.PlayerExit.mark()
            leaving = true
        }
    }
    if (leaving) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            androidx.compose.runtime.withFrameNanos { }
            androidx.compose.runtime.withFrameNanos { }
            onBack()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(playerFocusRequester)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                // Sees every key before the focused element does
                lastInteraction = android.os.SystemClock.uptimeMillis()
                val native = keyEvent.nativeKeyEvent
                // The key that ends the rest screen does nothing else
                if (restShowing) return@onPreviewKeyEvent true
                // Holding OK repeated the action (chat on/off/on…); only seeking may repeat (B33)
                native.repeatCount > 0 && (native.keyCode in NO_REPEAT_KEYS ||
                    (!isVod && overlay == PlayerOverlayType.NONE &&
                        (native.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || native.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)))
            }
            .onKeyEvent { keyEvent ->
                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                    if (overlay == PlayerOverlayType.CONTROLS) {
                        when (keyEvent.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_DPAD_LEFT -> { focusManager.moveFocus(FocusDirection.Left); return@onKeyEvent true }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> { focusManager.moveFocus(FocusDirection.Right); return@onKeyEvent true }
                            KeyEvent.KEYCODE_DPAD_DOWN -> { focusManager.moveFocus(FocusDirection.Down); return@onKeyEvent true }
                        }
                    }
                    when (keyEvent.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_BACK -> {
                            if (overlay != PlayerOverlayType.NONE) {
                                viewModel.backFromOverlay()
                                return@onKeyEvent true
                            }
                            // Setting "Press BACK twice": the first press only asks, a second one within 2 s leaves
                            if (Prefs.streamDoubleTapBack.get(context)) {
                                val now = android.os.SystemClock.uptimeMillis()
                                if (now - lastBackAt[0] > 2000L) {
                                    lastBackAt[0] = now
                                    android.widget.Toast.makeText(context, R.string.player_press_back_again, android.widget.Toast.LENGTH_SHORT).show()
                                    return@onKeyEvent true
                                }
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER -> {
                            // Raid card or "next channel" card: OK switches (docs/ideas.md 2, 3)
                            if (overlay == PlayerOverlayType.NONE && pendingRaid != null) {
                                viewModel.followRaid()
                                return@onKeyEvent true
                            }
                            if (overlay == PlayerOverlayType.NONE && endSuggestion != null) {
                                viewModel.playEndSuggestion()
                                return@onKeyEvent true
                            }
                            // "X is live" card: OK switches
                            if (overlay == PlayerOverlayType.NONE && liveCard != null) {
                                viewModel.switchToLiveCard()
                                return@onKeyEvent true
                            }
                            // Error or ended stream: OK tries again (B8)
                            if (overlay == PlayerOverlayType.NONE && (playbackStatus == PlaybackStatus.ERROR ||
                                    (playbackStatus == PlaybackStatus.ENDED && !isVod))) {
                                viewModel.retry()
                                return@onKeyEvent true
                            }
                            // Audio only: OK is the one press back to the picture
                            if (overlay == PlayerOverlayType.NONE && audioOnly) {
                                viewModel.toggleAudioOnly()
                                return@onKeyEvent true
                            }
                            if (overlay == PlayerOverlayType.NONE) {
                                openControls()
                                return@onKeyEvent true
                            }
                            false
                        }
                        // Yellow key: audio only on/off
                        KeyEvent.KEYCODE_PROG_YELLOW -> {
                            if (overlay == PlayerOverlayType.NONE || overlay == PlayerOverlayType.CONTROLS) {
                                viewModel.toggleAudioOnly()
                                return@onKeyEvent true
                            }
                        }
                        // Remote keys that were not used (B37)
                        KeyEvent.KEYCODE_MENU -> {
                            if (overlay == PlayerOverlayType.NONE || overlay == PlayerOverlayType.CONTROLS) {
                                controlsFocusIndex.value = Int.MAX_VALUE // clamped to "More", the last button
                                moreMenuOpen.value = !(overlay == PlayerOverlayType.CONTROLS && moreMenuOpen.value)
                                viewModel.showOverlay(PlayerOverlayType.CONTROLS)
                                return@onKeyEvent true
                            }
                        }
                        KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                            if (overlay == PlayerOverlayType.NONE && !isVod && !isMultiview) {
                                viewModel.zap(1)
                                return@onKeyEvent true
                            }
                            if ((overlay == PlayerOverlayType.NONE || overlay == PlayerOverlayType.CONTROLS) && isVod) {
                                viewModel.jumpChapter(1)
                                return@onKeyEvent true
                            }
                        }
                        KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                            if (overlay == PlayerOverlayType.NONE && !isVod && !isMultiview) {
                                viewModel.zap(-1)
                                return@onKeyEvent true
                            }
                            if ((overlay == PlayerOverlayType.NONE || overlay == PlayerOverlayType.CONTROLS) && isVod) {
                                viewModel.jumpChapter(-1)
                                return@onKeyEvent true
                            }
                        }
                        KeyEvent.KEYCODE_INFO -> {
                            viewModel.setOverlay(PlayerOverlayType.STATS)
                            return@onKeyEvent true
                        }
                        KeyEvent.KEYCODE_CAPTIONS -> {
                            viewModel.toggleChat()
                            return@onKeyEvent true
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (overlay == PlayerOverlayType.NONE) {
                                viewModel.setOverlay(PlayerOverlayType.SWITCHER)
                                return@onKeyEvent true
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (overlay == PlayerOverlayType.NONE) {
                                openControls()
                                return@onKeyEvent true
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (overlay == PlayerOverlayType.NONE) {
                                if (isVod) {
                                    viewModel.seekStep(-1)
                                    return@onKeyEvent true
                                } else if (isMultiview) {
                                    viewModel.swapMultiviewAudio()
                                    return@onKeyEvent true
                                } else {
                                    viewModel.zap(-1)
                                    return@onKeyEvent true
                                }
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (overlay == PlayerOverlayType.NONE) {
                                if (isVod) {
                                    viewModel.seekStep(1)
                                    return@onKeyEvent true
                                } else if (isMultiview) {
                                    viewModel.swapMultiviewAudio()
                                    return@onKeyEvent true
                                } else {
                                    viewModel.zap(1)
                                    return@onKeyEvent true
                                }
                            }
                        }
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                            viewModel.togglePlayPause()
                            return@onKeyEvent true
                        }
                        KeyEvent.KEYCODE_MEDIA_PLAY -> {
                            if (!viewModel.mainPlayer.isPlaying) viewModel.togglePlayPause()
                            return@onKeyEvent true
                        }
                        KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                            if (viewModel.mainPlayer.isPlaying) viewModel.togglePlayPause()
                            return@onKeyEvent true
                        }
                        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                            // Live: fast-forward means back to the live edge
                            if (isVod) viewModel.seekStep(1) else viewModel.jumpToLive()
                            return@onKeyEvent true
                        }
                        KeyEvent.KEYCODE_MEDIA_REWIND -> {
                            if (isVod || liveTimeline) viewModel.seekStep(-1)
                            return@onKeyEvent true
                        }
                    }
                }
                false
            }
    ) {
        // 1. Video (single or multiview); side by side it makes room for the chat
        PlayerVideoSurface(
            mainPlayer = mainPlayer,
            secondPlayer = { viewModel.multiviewPlayer },
            isMultiview = isMultiview,
            isPrimaryAudio = isPrimaryAudio,
            modifier = Modifier.fillMaxSize().then(videoPadding)
        )

        // Audio only: nothing to see, so a dim screen that wanders (under the chat and the overlays)
        if (audioOnly && viewModel.audioOnlyScreen) {
            RestScreen(
                channel = movie?.displayName.orEmpty(),
                title = movie?.title,
                hint = stringResource(R.string.player_audio_only_hint),
                // Over the picture the chat covers one side: the screen keeps to the other
                modifier = Modifier.then(videoPadding).padding(
                    start = if (chatShown && !sideBySide && chatOnLeft) chatWidth else 0.dp,
                    end = if (chatShown && !sideBySide && !chatOnLeft) chatWidth else 0.dp
                )
            )
        }

        // Notice from Home Assistant or the phone, e.g. the doorbell with a camera picture
        externalNotice?.let { n ->
            PlayerNoticeCard(
                n,
                Modifier
                    .align(Alignment.TopCenter)
                    .then(videoPadding)
                    .padding(top = if (pendingRaid != null) 110.dp else 24.dp)
            )
        }

        // Raid from this channel: offer to follow (docs/ideas.md 2)
        pendingRaid?.let { raid ->
            PlayerRaidCard(
                raid,
                channelName = movie?.displayName ?: "",
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .then(videoPadding)
                    .padding(top = 24.dp)
            )
        }

        val transparent = chatLayout == com.shrug.shrugtv.chat.ChatLayout.TRANSPARENT
        // The lines change with every message: collected in PlayerChatLayer, not here
        PlayerChatLayer(
            chat = viewModel.chatViewModel,
            visible = chatShown,
            transparent = transparent,
            modifier = Modifier
                // Height and vertical place from "Adjust chat"
                .align(
                    when (chatAppearance.vertical) {
                        com.shrug.shrugtv.chat.ChatVertical.TOP -> if (chatOnLeft) Alignment.TopStart else Alignment.TopEnd
                        com.shrug.shrugtv.chat.ChatVertical.MIDDLE -> if (chatOnLeft) Alignment.CenterStart else Alignment.CenterEnd
                        com.shrug.shrugtv.chat.ChatVertical.BOTTOM -> if (chatOnLeft) Alignment.BottomStart else Alignment.BottomEnd
                    }
                )
                .burnInDrift(burnIn, "chat")
                .width(chatWidth)
                .fillMaxHeight(chatAppearance.heightFraction),
            textSizeSp = chatAppearance.textSizeSp,
            background = when (chatLayout) {
                com.shrug.shrugtv.chat.ChatLayout.SIDE_BY_SIDE -> TvColors.ChatSide
                com.shrug.shrugtv.chat.ChatLayout.OVERLAY -> Color.Black.copy(alpha = chatAppearance.backgroundAlpha)
                com.shrug.shrugtv.chat.ChatLayout.TRANSPARENT -> Color.Transparent
            },
            textShadow = when (viewModel.chatLook.textShadow) { "on" -> true; "off" -> false; else -> transparent },
            fromLeft = chatOnLeft,
            look = viewModel.chatLook
        )

        // A followed channel went live: offer to switch. Only without an open
        // overlay, because only then OK goes to the card. Drawn after the chat, so it stays on top
        liveCard?.takeIf { overlay == PlayerOverlayType.NONE }?.let { live ->
            PlayerLiveCard(
                live,
                Modifier
                    .align(Alignment.TopEnd)
                    // Beside the chat when it is on the right, side by side or over the picture
                    // Below the ad-break sign, which sits in the same corner
                    .padding(top = if (adBlockLabel != null) 80.dp else 24.dp, end = if (chatShown && !chatOnLeft) chatWidth + 24.dp else 24.dp)
            )
        }

        // Ad-break sign from the ad blocker (B13)
        adBlockLabel?.let { label ->
            PlayerAdBreakSign(
                label,
                Modifier
                    .align(Alignment.TopEnd)
                    // Inside the video area: side by side it covered the top of the chat
                    .then(videoPadding)
                    .padding(24.dp)
            )
        }
        // Who is streaming, top left while the bar is open (avatar, LIVE data, category, title)
        // Same fade and slide as the bar and the clock (CONTROLS_ANIM_MS / CONTROLS_SLIDE), so they come and go together
        val m = movie
        val slidePx = with(androidx.compose.ui.platform.LocalDensity.current) { CONTROLS_SLIDE.roundToPx() }
        AnimatedVisibility(
            visible = overlay == PlayerOverlayType.CONTROLS && m != null && viewModel.showInfoCorner,
            enter = fadeIn(androidx.compose.animation.core.tween(CONTROLS_ANIM_MS)) +
                androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(CONTROLS_ANIM_MS)) { -slidePx },
            exit = fadeOut(androidx.compose.animation.core.tween(CONTROLS_ANIM_MS)) +
                androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(CONTROLS_ANIM_MS)) { -slidePx },
            modifier = Modifier.align(Alignment.TopStart).then(videoPadding)
        ) {
            movie?.let { mv ->
                val liveOffsetMs by liveOffsetWhile(barOpen, isVod, viewModel)
                PlayerStreamInfo(
                    movie = mv,
                    avatarUrl = streamAvatar,
                    isFollowed = isFollowed,
                    isVod = isVod,
                    behindLiveSeconds = if (isBehindLive(viewModel, isVod, liveOffsetMs)) (liveOffsetMs / 1000).toInt() else null,
                    modifier = Modifier.burnInDrift(burnIn, "info").padding(24.dp)
                )
            }
        }

        // The title of a clip stays on screen (setting "Show the clip title"); while the bar is open the info corner takes its place
        AnimatedVisibility(
            visible = movie?.isClip == true && viewModel.showClipTitle && !clipTitle.isNullOrEmpty() && overlay != PlayerOverlayType.CONTROLS,
            enter = fadeIn(androidx.compose.animation.core.tween(CONTROLS_ANIM_MS)),
            exit = fadeOut(androidx.compose.animation.core.tween(CONTROLS_ANIM_MS)),
            modifier = Modifier.align(Alignment.TopStart).then(videoPadding)
        ) {
            PlayerClipTitle(
                title = clipTitle.orEmpty(),
                channel = movie?.displayName.orEmpty(),
                clipNav = clipNav,
                modifier = Modifier.burnInDrift(burnIn, "info")
            )
        }

        // Centre of the video area: seek HUD, loading note and error box one under the other, never on
        // top of each other
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.align(Alignment.Center).then(videoPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
        ) {
            // Seek HUD Anzeige (+10s / -10s)
            AnimatedVisibility(
                visible = seekHudText != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                PlayerSeekHud(seekHudText ?: "")
            }

            // Switch between live and the recording: say what happens instead of a hanging picture
            if (archiveSwitch != 0) {
                PlayerArchiveSwitchNote(archiveSwitch)
            }

            // Error or end of a live stream: say so instead of a black picture (B8); the end of a recording only with a next one
            if (playbackStatus == PlaybackStatus.ERROR || (playbackStatus == PlaybackStatus.ENDED && (!isVod || endSuggestion != null))) {
                // The countdown ticks every second: collected in the card, not here
                PlayerStatusCard(playbackStatus, isVod, endSuggestion, viewModel.endCountdown)
            }
        }

        // 2. Channel switcher overlay (#70)
        ChannelSwitcherOverlay(
            visible = overlay == PlayerOverlayType.SWITCHER,
            current = movie,
            onSelect = { selectedMovie ->
                viewModel.dismissOverlay()
                viewModel.play(selectedMovie)
            },
            onMultiview = { secondMovie ->
                viewModel.dismissOverlay()
                viewModel.startMultiview(secondMovie)
            },
            onDismiss = { viewModel.dismissOverlay() }
        )

        // 3. Player control bar overlay (#69, #74). Position and live offset change every 500 ms while the bar is open:
        // read in a scope of their own, so only the bar recomposes, not the whole player
        IsolatedScope {
            val liveOffsetMs by liveOffsetWhile(barOpen, isVod, viewModel)
            val behindLive = isBehindLive(viewModel, isVod, liveOffsetMs)
            val timeline = timelineWhile(barOpen, isVod, liveTimeline, viewModel)
            PlayerControlsOverlay(
                visible = overlay == PlayerOverlayType.CONTROLS,
                movie = movie,
                currentQuality = currentQuality,
                isFollowed = isFollowed,
                isVod = isVod,
                liveTimeline = liveTimeline,
                compact = Prefs.playerBarLabels.get(context) != "all",
                vodPositionMs = timeline[0],
                vodDurationMs = timeline[1],
                isPlaying = isPlaying,
                isMultiview = isMultiview,
                phoneAvailable = com.shrug.shrugtv.companion.Companion.isEnabled(),
                chapters = chapters,
                onQualityClick = { viewModel.setOverlay(PlayerOverlayType.QUALITY) },
                onChatClick = { viewModel.toggleChat() },
                onChatLayoutClick = { viewModel.toggleChatLayout() },
                onChannelClick = {
                    viewModel.dismissOverlay()
                    movie?.let { onNavigateToChannel(it) }
                },
                // Helix has no follow endpoint; like the channel page, follow via QR code in the official app (B14)
                onFollowClick = {
                    movie?.let { m ->
                        viewModel.dismissOverlay()
                        context.startActivity(com.shrug.shrugtv.ui.tv.dialogs.FollowQrActivity.intent(context, m))
                    }
                },
                onSearchClick = { viewModel.setOverlay(PlayerOverlayType.SEARCH) },
                onSpeedClick = { viewModel.setOverlay(PlayerOverlayType.SPEED) },
                onChaptersClick = { viewModel.setOverlay(PlayerOverlayType.CHAPTERS) },
                onHighlightsClick = if (!isVod && movie?.isClip != true && !isMultiview && Prefs.playerHighlights.get(context)) ({ viewModel.openHighlights() }) else null,
                onWatchLive = if (movie?.isClip == true) ({ viewModel.watchClipChannel() }) else null,
                channelLive = clipChannelLive,
                onJumpToSource = if (movie?.isClip == true) ({ viewModel.jumpToClipSource() }) else null,
                sourceAvailable = clipSourceReady,
                onPreviousClip = if (clipNav != null) ({ viewModel.playClipStep(-1) }) else null,
                hasPreviousClip = (clipNav?.first ?: 1) > 1,
                onNextClip = if (clipNav != null) ({ viewModel.playClipStep(1) }) else null,
                hasNextClip = clipNav?.let { it.first < it.second } == true,
                onPreviousCategoryClick = { viewModel.jumpChapter(-1) },
                onNextCategoryClick = { viewModel.jumpChapter(1) },
                onMoreAction = { action ->
                    when (action) {
                        PlayerMoreAction.SEND_MESSAGE -> viewModel.setOverlay(PlayerOverlayType.SEND_MESSAGE)
                        PlayerMoreAction.ADJUST_CHAT -> viewModel.openChatEditor()
                        PlayerMoreAction.STATS -> viewModel.setOverlay(PlayerOverlayType.STATS)
                        PlayerMoreAction.VOLUME,
                        PlayerMoreAction.ADJUST_MULTIVIEW -> {
                            if (isMultiview) {
                                viewModel.swapMultiviewAudio()
                            }
                        }
                        PlayerMoreAction.SAVE_VOD -> viewModel.saveVodProgress()
                        PlayerMoreAction.RELOAD_EMOTES -> viewModel.reloadEmotes()
                        PlayerMoreAction.PHONE -> context.startActivity(android.content.Intent(context, com.shrug.shrugtv.companion.CompanionQrActivity::class.java))
                        else -> {}
                    }
                },
                onJumpToLive = if (behindLive || inArchive) ({ viewModel.jumpToLive() }) else null,
                // The bar closes: in the recording it changes its buttons, and a second OK must not land on "-10 s"
                onReplay = if (!isVod && !inArchive && viewModel.replayEnabled) ({ viewModel.replay(); viewModel.dismissOverlay() }) else null,
                buttonFocusIndex = controlsFocusIndex,
                moreMenuState = moreMenuOpen,
                onPlayPauseClick = { viewModel.togglePlayPause() },
                onSeekRelative = { delta -> viewModel.seekRelative(delta) },
                onSeekStep = { direction -> viewModel.seekStep(direction) },
                onDismiss = { viewModel.dismissOverlay() },
                // Side by side the bar stays under the video, not over the chat
                modifier = videoPadding
            )
        }

        // 4. Quality menu overlay (#85)
        PlayerQualityMenuOverlay(
            visible = overlay == PlayerOverlayType.QUALITY,
            qualities = qualities,
            currentQuality = currentQuality,
            onSelectQuality = { selected -> viewModel.selectQuality(selected) },
            onDismiss = { viewModel.backFromOverlay() }
        )

        // 5. Speed menu overlay (#85, slice 4c)
        PlayerSpeedOverlay(
            visible = overlay == PlayerOverlayType.SPEED,
            currentSpeed = currentSpeed,
            onSelectSpeed = { speed -> viewModel.selectSpeed(speed) },
            onDismiss = { viewModel.backFromOverlay() }
        )

        // 6. Kapitel-Auswahl Overlay (#74, Slice 4c)
        PlayerChaptersOverlay(
            visible = overlay == PlayerOverlayType.CHAPTERS,
            chapters = chapters,
            currentPositionMs = viewModel.timelineMs()[0],
            onSelectChapter = { chapter -> viewModel.selectChapter(chapter) },
            onDismiss = { viewModel.backFromOverlay() }
        )

        // Highlights of the running live stream
        PlayerHighlightsOverlay(
            visible = overlay == PlayerOverlayType.HIGHLIGHTS,
            clips = highlights,
            failed = highlightsFailed,
            streamStart = movie?.startedAt,
            onSelect = { clip -> viewModel.playHighlight(clip) },
            onRetry = { viewModel.openHighlights(force = true) }
        )

        // 7. Suche im Player Overlay (#73)
        PlayerSearchOverlay(
            visible = overlay == PlayerOverlayType.SEARCH,
            onSelectStream = { selectedMovie ->
                viewModel.dismissOverlay()
                viewModel.play(selectedMovie)
            },
            onDismiss = { viewModel.backFromOverlay() }
        )

        // 8. Nachricht senden Overlay (#73)
        PlayerSendMessageOverlay(
            visible = overlay == PlayerOverlayType.SEND_MESSAGE,
            channelName = movie?.displayName ?: "",
            onSendMessage = { text ->
                viewModel.sendChatMessage(text)
                viewModel.dismissOverlay()
            },
            onDismiss = { viewModel.backFromOverlay() }
        )

        // 9. Video-Statistiken Overlay (#85); the values change every second, collected in the layer
        PlayerStatsLayer(
            visible = overlay == PlayerOverlayType.STATS,
            stats = viewModel.stats
        )

        // 10. Layout-Editor Overlay (#72)
        ChatAdjustOverlay(
            visible = overlay == PlayerOverlayType.LAYOUT_EDITOR,
            appearance = chatAppearance,
            textSizeSp = chatAppearance.textSizeSp,
            onChange = { change -> viewModel.updateChatAppearance(change) },
            onDismiss = { viewModel.backFromOverlay() }
        )

        // Long pause: the whole picture goes dark, chat included
        if (restShowing) {
            RestScreen(
                channel = movie?.displayName.orEmpty(),
                title = movie?.title,
                hint = stringResource(R.string.player_rest_hint)
            )
        }

        if (leaving) PlayerLeavingNote()
    }
}

/** Over everything while the player closes after Back: a black picture and "Leaving the stream …". */
@Composable
private fun PlayerLeavingNote() {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        androidx.tv.material3.Text(
            stringResource(R.string.player_leaving),
            fontFamily = com.shrug.shrugtv.ui.tv.Inter,
            fontSize = 18.sp,
            color = com.shrug.shrugtv.ui.tv.TvColors.TextMuted
        )
    }
}

/** Own recomposition scope: state read inside [content] recomposes only [content], not the caller. */
@Composable
private fun IsolatedScope(content: @Composable () -> Unit) = content()

/** Distance to the live edge, read every second while the bar is open (live only): "Back to live" when far behind. */
@Composable
private fun liveOffsetWhile(barOpen: Boolean, isVod: Boolean, viewModel: PlayerViewModel): androidx.compose.runtime.State<Long> =
    produceState(androidx.media3.common.C.TIME_UNSET, barOpen, isVod) {
        while (barOpen && !isVod) {
            value = viewModel.mainPlayer.currentLiveOffset
            kotlinx.coroutines.delay(1_000L)
        }
    }

private fun isBehindLive(viewModel: PlayerViewModel, isVod: Boolean, liveOffsetMs: Long): Boolean =
    viewModel.offerBackToLive && !isVod && liveOffsetMs != androidx.media3.common.C.TIME_UNSET &&
        liveOffsetMs > PlayerViewModel.BEHIND_LIVE_MS

/**
 * Position and length of the progress bar, read every 500 ms while the bar is open: the timeline of the stream when it
 * has a recording, else the player position (B15) and its length.
 */
@Composable
private fun timelineWhile(barOpen: Boolean, isVod: Boolean, liveTimeline: Boolean, viewModel: PlayerViewModel): LongArray {
    val timeline by produceState(viewModel.timelineMs(), barOpen, liveTimeline) {
        while (barOpen && liveTimeline) {
            value = viewModel.timelineMs()
            kotlinx.coroutines.delay(500L)
        }
    }
    val vodPositionMs by produceState(0L, barOpen, isVod) {
        while (barOpen && isVod) {
            value = viewModel.mainPlayer.currentPosition
            kotlinx.coroutines.delay(500L)
        }
    }
    return if (liveTimeline) timeline else longArrayOf(vodPositionMs, viewModel.mainPlayer.duration.coerceAtLeast(0L))
}

/** Keys whose auto-repeat (holding the key) is ignored in the player: only seeking may repeat (B33). */
private val NO_REPEAT_KEYS = setOf(
    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO,
    KeyEvent.KEYCODE_CAPTIONS, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
    KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
)
