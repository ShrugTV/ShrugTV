package com.shrug.shrugtv.ui.tv.player

import android.content.Context
import android.util.Log
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import com.shrug.shrugtv.api.FollowStatusChecker
import com.shrug.shrugtv.database.AppDatabase
import com.shrug.shrugtv.model.Enums
import com.shrug.shrugtv.model.Movie
import com.shrug.shrugtv.model.StreamQuality
import com.shrug.shrugtv.model.VodChapter
import com.shrug.shrugtv.player.PlayerFactory
import com.shrug.shrugtv.player.PlayerMediaSession
import com.shrug.shrugtv.player.PlaybackUrlResolver
import com.shrug.shrugtv.player.zapping.StreamZapper
import com.shrug.shrugtv.player.hls.TwitchAdBlocker
import com.shrug.shrugtv.chat.ChatPrefs
import com.shrug.shrugtv.prefs.Prefs
import com.shrug.shrugtv.util.Threads
import com.shrug.shrugtv.R
import com.shrug.shrugtv.telemetry.CrashReporter
import com.shrug.shrugtv.util.TimeUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map
import com.shrug.shrugtv.hub.HubEvent
import com.shrug.shrugtv.hub.PlaybackStateHub
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Player state management and player pool (slice 4b, 4c, 4d).
 *
 * Manages the main player, media session, VOD progress, chapters, speed and multiview:
 * - Takeover of the hero preview without restart (HeroHandover / adopted player).
 * - Direct handover of the usher master URL to ExoPlayer.
 * - Quality change without reloading through TrackSelectionOverride.
 * - VOD chapter query through GQL and display on the progress bar.
 * - Save and load the VOD playback position through Room (vod_info).
 * - Multiview secondary player with audio switching.
 * - Anbindung an PlayerMediaSession (System-API, kein Fire OS-Crash).
 */
class PlayerViewModel(private val appContext: Context) : ViewModel(), com.shrug.shrugtv.hub.PlayerCommands {
    companion object {
        /** Wait this long after a clip started before the next one is loaded in the background. */
        private const val TAG = "PlayerViewModel"
        private const val HEALTH_TAG = "PlaybackHealth"
        /** Away from the player at least this long: a live stream is resolved again instead of resumed. */
        private const val STALE_AFTER_BACKGROUND_MS = 30_000L
        /**
         * Saving VOD progress must outlive the ViewModel: `viewModelScope` is already cancelled when
         * `onCleared` runs, so a save launched there never ran (docs/bug_review.md B3).
         */
        private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** From this far behind the live edge the player offers "Back to live". */
        const val BEHIND_LIVE_MS = 30_000L
    }

    val chatViewModel = ChatViewModel().apply {
        // Same messages as one line with a counter, read once per player (default off)
        mergeWindowMs = Prefs.chatMergeRepeats.get(appContext).toLong().coerceIn(0L, 60L) * 1000L
    }

    /** Chat clients deliver on their own threads; their events are moved to the main thread with this. */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Settings group "In the player", read once per player. */
    private val playerPrefs = PreferenceManager.getDefaultSharedPreferences(appContext)
    val controlsTimeoutMs: Long =
        Prefs.playerControlsTimeout.get(playerPrefs).toLong().coerceIn(3L, 120L) * 1000L
    val showInfoCorner: Boolean = Prefs.playerInfoCorner.get(playerPrefs)
    /** The title of a clip stays on screen while it plays (setting "Show the clip title"). */
    val showClipTitle: Boolean = Prefs.clipsShowTitle.get(playerPrefs)
    private val _clipTitle = MutableStateFlow<String?>(null)
    val clipTitle: StateFlow<String?> = _clipTitle.asStateFlow()
    /** Place of the clip in the queue (1-based) and the length of the queue; null = no queue. */
    private val _clipNav = MutableStateFlow<Pair<Int, Int>?>(null)
    val clipNav: StateFlow<Pair<Int, Int>?> = _clipNav.asStateFlow()
    val offerBackToLive: Boolean = Prefs.playerBackToLive.get(playerPrefs)
    /** Clock, chat and info corner wander a few pixels. */
    val burnInProtection: Boolean = Prefs.playerBurnIn.get(playerPrefs)
    /** Minutes of pause until the rest screen; 0 = never. */
    val restAfterMinutes: Int = Prefs.playerRestAfter.get(playerPrefs)
    /** Black picture with name and clock in "Audio only". */
    val audioOnlyScreen: Boolean = Prefs.playerAudioOnlyScreen.get(playerPrefs)
    /** countdown | suggest | off */
    private val endOfStreamMode: String = Prefs.playerEndOfStream.get(playerPrefs)
    /** At the end of a recording the next one of the channel follows after a countdown. */
    private val vodNextEnabled: Boolean = Prefs.playerVodNext.get(playerPrefs)

    /** Chat settings, read once per player (B22/C0.4). */
    private val chatPrefs: ChatPrefs = ChatPrefs.from(appContext)
    private val pxPerSp = android.util.TypedValue.applyDimension(
        android.util.TypedValue.COMPLEX_UNIT_SP, 1f, appContext.resources.displayMetrics
    )
    private fun chatTextPx(): Float = chatTextSizeSp * pxPerSp
    private fun chatEmotePx(): Int = ChatPrefs.emoteSizePx(chat.appearance.value.emoteValue, chatTextPx(), pxPerSp)
    private fun chatBadgePx(): Int = (chatTextPx() * 1.15f).toInt().coerceAtLeast(1)

    /** The chat that goes with the item shown, its visibility and its look. */
    private val chat: PlayerChatController<com.shrug.shrugtv.chat.LiveChatClient> = PlayerChatController(
        scope = viewModelScope,
        chat = chatViewModel,
        prefs = chatPrefs,
        initialAppearance = com.shrug.shrugtv.chat.ChatAppearance.from(PreferenceManager.getDefaultSharedPreferences(appContext)),
        saveAppearance = { it.save(PreferenceManager.getDefaultSharedPreferences(appContext)) },
        connector = object : PlayerChatConnector<com.shrug.shrugtv.chat.LiveChatClient> {
            override fun live(movie: Movie, sink: com.shrug.shrugtv.chat.ChatUiSink): com.shrug.shrugtv.chat.LiveChatClient =
                com.shrug.shrugtv.chat.ChatClient.Builder()
                    .context(appContext)
                    .uiSink(sink)
                    .channelId(movie.userId)
                    .channelName(movie.login ?: movie.displayName ?: "")
                    .badgeSize(chatBadgePx())
                    .emoteSize(chatEmotePx())
                    .animatedEmotes(chatPrefs.animatedEmotes)
                    .filterSettings(chatPrefs.filterSettings)
                    // The delay is applied in the sink, for messages and moderation events alike
                    .showPrimeBadge(chatPrefs.showPrimeBadge)
                    .highlights(chatPrefs.highlights)
                    .extras(chatPrefs.extras)
                    .buildLiveChat()

            override fun stopLive(client: com.shrug.shrugtv.chat.LiveChatClient) = client.stopChat()

            override fun vod(movie: Movie, videoId: Long, sink: com.shrug.shrugtv.chat.ChatUiSink): VodChatConnection {
                val client =
                    com.shrug.shrugtv.chat.ChatClient.Builder()
                        .context(appContext)
                        .uiSink(sink)
                        .channelId(movie.userId)
                        .channelName(movie.login ?: movie.displayName ?: "")
                        .vodId(videoId)
                        .badgeSize(chatBadgePx())
                        .emoteSize(chatEmotePx())
                        .animatedEmotes(chatPrefs.animatedEmotes)
                        .filterSettings(chatPrefs.filterSettings)
                        .showPrimeBadge(chatPrefs.showPrimeBadge)
                        .highlights(chatPrefs.highlights)
                        .extras(chatPrefs.extras)
                        .buildVodChat()
                return VodChatConnection(client::requestVodMessages, client::stopChat)
            }

            override fun clipSource(slug: String): com.shrug.shrugtv.chat.ClipSource.Info? =
                com.shrug.shrugtv.chat.ClipSource.fetch(appContext, slug)

            override fun vodOwner(videoId: Long): VodOwner? {
                val json = com.shrug.shrugtv.api.helix.HelixApi.getVideoById(appContext, videoId)
                val obj = json?.getAsJsonArray("data")?.takeIf { it.size() > 0 }?.get(0)?.asJsonObject ?: return null
                val ownerId = obj.get("user_id")?.asString?.toIntOrNull() ?: return null
                return VodOwner(ownerId, { obj.get("user_login")?.asString }, { obj.get("user_name")?.asString })
            }
        },
        isVod = ::isVod,
        current = { _currentMovie.value },
        positionMs = { mainPlayer.currentPosition },
        liveOffsetMs = { mainPlayer.currentLiveOffset },
        onRaid = { cards.onRaid(it) },
        onClipSource = ::fillFromClipSource,
        onError = { CrashReporter.recordException(it) },
        post = { mainHandler.post(it) },
        postDelayed = { r, ms -> mainHandler.postDelayed(r, ms) },
        uptime = { android.os.SystemClock.uptimeMillis() }
    )
    private val chatClient: com.shrug.shrugtv.chat.LiveChatClient? get() = chat.liveClient

    /** Text size from "Adjust chat", changed live. */
    val chatTextSizeSp: Float get() = chat.appearance.value.textSizeSp
    val chatLook: com.shrug.shrugtv.chat.ChatLook get() = chatPrefs.look

    /** Layout, width, position, opacity of the chat; "Adjust chat" changes it live (CA12). */
    val chatAppearance: StateFlow<com.shrug.shrugtv.chat.ChatAppearance> get() = chat.appearance
    val isChatVisible: StateFlow<Boolean> get() = chat.visible
    /** The clip on screen has no chat to show (its recording is gone, or recorded chat is off): the panel stays away. */
    val chatUnavailable: StateFlow<Boolean> get() = chat.unavailable
    /** The recording a clip was cut from is known and still there: the button "Recording" works. */
    val clipSourceReady: StateFlow<Boolean> get() = chat.clipSourceReady

    fun updateChatAppearance(change: (com.shrug.shrugtv.chat.ChatAppearance) -> com.shrug.shrugtv.chat.ChatAppearance) =
        chat.updateAppearance(change)

    /** Shortcut button of the control bar: next to the video <-> over the video, saved like "Adjust chat". */
    fun toggleChatLayout() = chat.toggleLayout()

    /** "Adjust chat" from the More menu: the chat must be visible to see the changes. */
    fun openChatEditor() {
        chat.showForEditor()
        showOverlay(PlayerOverlayType.LAYOUT_EDITOR)
    }

    /** Counts `play()` calls; resolver results of an older call are dropped (B4, fast zapping). */
    private var playRequest = 0

    /** The movie whose media item is actually loaded in [mainPlayer]; VOD progress is saved for it (B3). */
    private var loadedMovie: Movie? = null

    /** Resume positions of recordings in Room (vod_info). */
    private val vodProgress = VodProgressStore(
        dao = { AppDatabase.getInstance(appContext).vodInfoDao() },
        player = { mainPlayer },
        saveScope = saveScope,
        io = Dispatchers.IO,
        now = { TimeUtil.isoNowUtc() },
        onError = { CrashReporter.recordException(it) }
    )

    /** Ad-break sign from [TwitchAdBlocker] as a string resource, null = no break (B13); its own class since . */
    private val adBreak = AdBreakState(PlaybackStateHub::emit) { _currentMovie.value?.login }
    val adBlockLabel: StateFlow<Int?> = adBreak.label

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    /** Stream volume 0-100, set from Home Assistant or the phone (not the TV volume). */
    private val _volume = MutableStateFlow(100)

    /** Profile picture of the current channel for the info corner; looked up if the Movie has none. */
    private val _streamAvatar = MutableStateFlow<String?>(null)
    val streamAvatar: StateFlow<String?> = _streamAvatar.asStateFlow()

    private fun loadStreamAvatar(movie: Movie) {
        _streamAvatar.value = movie.avatarUrl
        if (!movie.avatarUrl.isNullOrEmpty() || movie.userId <= 0) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Blocking off the main thread; fills movie.avatarUrl from its cache or Helix users
                com.shrug.shrugtv.apihelpers.ChannelAvatarLoader.loadAvatars(appContext, listOf(movie))
            } catch (e: Exception) {
                CrashReporter.recordException(e)
            }
            if (_currentMovie.value === movie) _streamAvatar.value = movie.avatarUrl
        }
    }

    /** Resolves the numeric Twitch user ID if the stream was opened by login only (companion watchLogin, deep link). */
    private fun resolveUserId(movie: Movie, request: Int) {
        val login = movie.login ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val json = com.shrug.shrugtv.api.helix.HelixApi.getUsersByQuery(appContext, "?login=$login")
                val user = com.shrug.shrugtv.api.helix.HelixParser.parseFirstUser(json ?: return@launch)
                if (user != null && user.userId > 0) {
                    Log.i(TAG, "Resolved userId=${user.userId} for login=$login")
                    movie.userId = user.userId
                    if (movie.displayName.isNullOrEmpty()) movie.displayName = user.displayName
                    if (movie.avatarUrl.isNullOrEmpty()) movie.avatarUrl = user.avatarUrl
                    withContext(Dispatchers.Main) {
                        if (request == playRequest && _currentMovie.value === movie) {
                            loadStreamAvatar(movie)
                            checkFollowStatus(movie)
                            chat.start(movie)
                        }
                    }
                }
            } catch (e: Exception) {
                CrashReporter.recordException(e)
            }
        }
    }

    /** Automatic retries after a playback error, reset once the stream plays (B8). */
    private var errorRetries = 0

    /** Stall watch; [healthReload] marks a reload it started, so its counter is kept. */
    private val health = com.shrug.shrugtv.player.PlaybackHealth()
    private var healthReload = false

    /** The next clip of the queue or the next recording, ready in its own player. */
    private val preload = PreloadSlot<ExoPlayer>()

    private fun releasePreloaded() = preload.release()

    /** The clip queue with its preload. */
    private val clips = ClipQueueController(
        scope = viewModelScope,
        slot = preload,
        current = { _currentMovie.value },
        nextOf = ::nextClipOf,
        neighbor = { clip, step -> com.shrug.shrugtv.clips.ClipQueue.neighbor(clip, step) },
        markSeen = { com.shrug.shrugtv.clips.ClipsSeen.mark(appContext, it.clipSlug) },
        resolve = { resolveVodQualities(it.playbackId ?: it.clipSlug) },
        createPlayer = { PlayerFactory.createMainPlayer(appContext) },
        play = { clip, player -> play(clip, adoptedPlayer = player) }
    )

    /** Raid offer, "X is live", the suggestion at the end of a stream or recording, notices (docs/ideas.md 2-3). */
    private val cards = PlayerCardsController(
        scope = viewModelScope,
        slot = preload,
        current = { _currentMovie.value },
        isVod = ::isVod,
        endOfStreamMode = endOfStreamMode,
        vodNextEnabled = vodNextEnabled,
        raidCardEnabled = {
            Prefs.streamShowIncomingRaidNotification.get(appContext)
        },
        emit = PlaybackStateHub::emit,
        followedLive = {
            try {
                com.shrug.shrugtv.ui.tv.home.HomeData.followedLive(appContext)
            } catch (e: Exception) {
                CrashReporter.recordException(e)
                null
            }
        },
        nextRecording = { withContext(Dispatchers.IO) { PlayerLookups.nextRecording(appContext, it) } },
        preloadRecording = ::preloadRecording,
        playNextClip = clips::playNext,
        exitArchive = { rewind.exitArchive() },
        play = { movie, player -> play(movie, adoptedPlayer = player) },
        elapsed = { android.os.SystemClock.elapsedRealtime() }
    )
    val pendingRaid: StateFlow<com.shrug.shrugtv.model.RaidNotification?> get() = cards.pendingRaid
    val endSuggestion: StateFlow<Movie?> get() = cards.endSuggestion
    val endCountdown: StateFlow<Int> get() = cards.endCountdown
    val liveCard: StateFlow<Movie?> get() = cards.liveCard
    val notice: StateFlow<ExternalNotice?> get() = cards.notice

    private val liveCardCollector = viewModelScope.launch {
        com.shrug.shrugtv.notification.LiveNotificationTicker.playerLive.collect { cards.onFollowedLive(it) }
    }

    /** OK on the raid card: go to the raided channel (login is looked up from the id). */
    fun followRaid() = cards.followRaid()

    fun dismissRaid() = cards.dismissRaid()

    /** OK on the live card: switch to that channel. */
    fun switchToLiveCard() = cards.switchToLiveCard()

    fun dismissLiveCard() = cards.dismissLiveCard()

    /** OK on the "stream ended" card with a suggestion, or the countdown ran out. */
    fun playEndSuggestion() = cards.playEndSuggestion()

    /** Back on the card: stay; the card falls back to "try again". */
    fun cancelEndSuggestion() = cards.cancelEndSuggestion()

    fun dismissNotice() = cards.dismissNotice()

    /** The clip after [current] in the queue, or null (setting off, end of the list). */
    private fun nextClipOf(current: Movie): Movie? {
        val s = com.shrug.shrugtv.clips.ClipsSettings.load(appContext)
        if (!s.autoplay) return null
        val skip = if (s.queueSkipsSeen) com.shrug.shrugtv.clips.ClipsSeen.all(appContext) else emptySet()
        return com.shrug.shrugtv.clips.ClipQueue.next(current, skip)
    }

    /** The playlists of a recording or clip, looked up on the main thread's resolver; null when there are none. */
    private suspend fun resolveVodQualities(playbackId: String?): List<StreamQuality>? =
        PlayerLookups.vodQualities(appContext, playbackId)

    /** The buttons "Previous clip" (-1) and "Next clip" (+1): the neighbour in the list the clip was opened from. */
    fun playClipStep(step: Int) = clips.step(step)

    /** Loads the first seconds of [next] in a muted, paused player while the countdown runs; [playEndSuggestion] adopts it. */
    private suspend fun preloadRecording(next: Movie) {
        val playbackId = next.playbackId ?: return
        val startMs = savedVodPositionMs(next)
        val qualities = resolveVodQualities(playbackId)
        if (cards.endSuggestion.value !== next || qualities.isNullOrEmpty()) return
        releasePreloaded()
        val player = PlayerFactory.createMainPlayer(appContext)
        QualityController.applyPreference(player, qualityPreference(true))
        next.qualities = ArrayList(qualities)
        preload.hold(next, player, QualityController.master(qualities).url, startMs)
    }

    private val _mainPlayer = MutableStateFlow<ExoPlayer>(PlayerFactory.createMainPlayer(appContext))
    val mainPlayerState: StateFlow<ExoPlayer> = _mainPlayer.asStateFlow()
    val mainPlayer: ExoPlayer get() = _mainPlayer.value

    /** Multiview (slice 4d), its own class since . */
    private val multiview = MultiviewController(
        mainPlayer = { mainPlayer },
        createPlayer = { PlayerFactory.createPreviewPlayer(appContext) },
        resolveUrl = { second, done ->
            val playbackId = second.playbackId ?: second.login
            if (playbackId != null) {
                val resolver = PlaybackUrlResolver(appContext, playbackId, second.playbackType ?: Enums.PlaybackKind.LIVE) { resolved, _ ->
                    if (!resolved.isNullOrEmpty()) resolved[0].url?.let(done)
                }
                resolver.useStreamToken = false
                resolver.start()
            }
        }
    )
    val multiviewPlayer: ExoPlayer? get() = multiview.player

    private var mediaSession: PlayerMediaSession? = null
    private var zapper: StreamZapper? = null

    private val _currentMovie = MutableStateFlow<Movie?>(null)
    val currentMovie: StateFlow<Movie?> = _currentMovie.asStateFlow()

    val multiviewMovie: StateFlow<Movie?> get() = multiview.movie
    val isMultiview: StateFlow<Boolean> get() = multiview.active
    val isPrimaryAudio: StateFlow<Boolean> get() = multiview.primaryAudio

    private val _playbackStatus = MutableStateFlow(PlaybackStatus.IDLE)
    val playbackStatus: StateFlow<PlaybackStatus> = _playbackStatus.asStateFlow()

    /** Quality menu and track selection. */
    private val quality = QualityController(
        player = { mainPlayer },
        isVod = { _currentMovie.value?.let { isVod(it) } },
        preference = ::qualityPreference,
        beforeSelect = { dismissOverlay() }
    )
    val qualities: StateFlow<List<StreamQuality>> get() = quality.qualities
    val currentQuality: StateFlow<String> get() = quality.current

    /** Highlights of the running live stream: null while loading. */
    private val _highlights = MutableStateFlow<List<Movie>?>(null)
    val highlights: StateFlow<List<Movie>?> = _highlights.asStateFlow()
    private val _highlightsFailed = MutableStateFlow(false)
    val highlightsFailed: StateFlow<Boolean> = _highlightsFailed.asStateFlow()
    /** The live stream a highlight was opened from; Back on that clip plays it again. */
    private var highlightsOf: Movie? = null

    private val _chapters = MutableStateFlow<List<VodChapter>>(emptyList())
    val chapters: StateFlow<List<VodChapter>> = _chapters.asStateFlow()

    private val _currentSpeed = MutableStateFlow(1.0f)
    val currentSpeed: StateFlow<Float> = _currentSpeed.asStateFlow()


    private val _isFollowed = MutableStateFlow(false)
    val isFollowed: StateFlow<Boolean> = _isFollowed.asStateFlow()

    /** The open overlay; leaving the chat editor after a size change renders the chat anew. */
    private val overlays = PlayerOverlayController { leaving ->
        if (leaving == PlayerOverlayType.LAYOUT_EDITOR) chat.onEditorClosed()
    }
    val activeOverlay: StateFlow<PlayerOverlayType> get() = overlays.active

    /** The channel of the clip is live right now: the button "Live" works. */
    private val _clipChannelLive = MutableStateFlow(false)
    val clipChannelLive: StateFlow<Boolean> = _clipChannelLive.asStateFlow()

    /** Values of the statistics overlay; its own class since . */
    private val statsController = PlayerStatsController()
    val stats: StateFlow<PlayerStatsState> = statsController.stats

    /** Watch statistics: counts the playing time, writes on [saveScope]. */
    private val watchTracker = com.shrug.shrugtv.stats.WatchSessionTracker(
        elapsedMs = { android.os.SystemClock.elapsedRealtime() },
        epochMs = { System.currentTimeMillis() },
        enabled = { Prefs.watchStats.get(playerPrefs) },
        save = { s -> saveScope.launch { com.shrug.shrugtv.stats.WatchStatsStore.save(appContext, s) } }
    )

    private fun startWatchSession(movie: Movie) {
        watchTracker.start(
            channelId = movie.userId,
            login = movie.login ?: "",
            channelName = movie.displayName.ifBlank { movie.login ?: "" },
            category = movie.gameName,
            // The recording of a live stream counts as live
            isVod = (movie.playbackType == Enums.PlaybackKind.VOD || movie.videoId > 0L) && !movie.archiveOfLive,
            playingNow = mainPlayer.isPlaying,
            isClip = movie.isClip
        )
    }

    /**
     * Once a second: every stall goes to the log (tag PlaybackHealth, the measurement); after
     * 10 s of buffering or a frozen picture the stream is resolved and loaded again, at most three times per item.
     * Ad breaks, loading the recording and switching do not count. Setting pref_player_self_heal (default on).
     */
    private fun checkHealth() {
        val movie = _currentMovie.value ?: return
        val state = mainPlayer.playbackState
        val wants = mainPlayer.playWhenReady && !adBreak.inBreak && !rewind.loading && !rewind.switching
        val position = mainPlayer.currentPosition
        val heal = Prefs.playerSelfHeal.get(playerPrefs)
        when (val v = health.check(android.os.SystemClock.elapsedRealtime(), wants, state == Player.STATE_BUFFERING, state == Player.STATE_READY, position)) {
            is com.shrug.shrugtv.player.PlaybackHealth.Verdict.StallStarted ->
                Log.i(HEALTH_TAG, "stall started: ${v.kind} at ${position / 1000} s (${movie.login})")
            is com.shrug.shrugtv.player.PlaybackHealth.Verdict.StallEnded ->
                Log.i(HEALTH_TAG, "stall ended by itself after ${v.ms} ms: ${v.kind} (${movie.login})")
            is com.shrug.shrugtv.player.PlaybackHealth.Verdict.Reload -> {
                if (!heal) {
                    Log.i(HEALTH_TAG, "stall ${v.ms} ms: ${v.kind}, would reload (setting off)")
                    return
                }
                Log.w(HEALTH_TAG, "stall ${v.ms} ms: ${v.kind}, reloading (attempt ${v.attempt}) ${movie.login}")
                healthReload = true
                play(movie, restartChat = false, resumeAtMs = if (isVod(movie)) position else null)
            }
            is com.shrug.shrugtv.player.PlaybackHealth.Verdict.GiveUp -> {
                Log.w(HEALTH_TAG, "stall ${v.ms} ms: ${v.kind}, reloading did not help, giving up")
                if (heal) {
                    _playbackStatus.value = PlaybackStatus.ERROR
                    cards.offerNext()
                }
            }
            else -> Unit
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            val shown = when (state) {
                Player.STATE_BUFFERING -> PlaybackStatus.BUFFERING
                Player.STATE_READY -> if (mainPlayer.playWhenReady) PlaybackStatus.PLAYING else PlaybackStatus.PAUSED
                Player.STATE_ENDED -> PlaybackStatus.ENDED
                else -> PlaybackStatus.IDLE
            }
            _playbackStatus.value = PlaybackStatusRules.afterStateChange(_playbackStatus.value, shown)
            // Only the recording itself counts: the live item is still READY until the new one is set
            if (state == Player.STATE_READY) rewind.onReady()
            if (state == Player.STATE_ENDED) cards.offerNext(ended = true)
            if (state == Player.STATE_READY || state == Player.STATE_ENDED) {
                saveVodProgress()
            }
            if (state == Player.STATE_READY) errorRetries = 0
            updateStats()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            watchTracker.playing(isPlaying)
            _playbackStatus.value = PlaybackStatusRules.afterPlayingChange(_playbackStatus.value, isPlaying)
            if (!isPlaying) {
                saveVodProgress()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            CrashReporter.recordException(error)
            Log.w(TAG, "onPlayerError: ${error.errorCodeName} (retries so far: $errorRetries)")
            // Live stream paused or stalled too long: the position fell out of the playlist window.
            // Jump back to the live edge instead of showing an error (B8).
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                mainPlayer.seekToDefaultPosition()
                mainPlayer.prepare()
                return
            }
            // Network hiccup or expired token: resolve the stream again, twice, then give up
            val movie = _currentMovie.value
            if (movie != null && errorRetries < 2) {
                errorRetries++
                val resumeAt = if (isVod(movie)) mainPlayer.currentPosition else null
                _playbackStatus.value = PlaybackStatus.BUFFERING
                viewModelScope.launch {
                    delay(2_000L * errorRetries)
                    if (_currentMovie.value === movie) play(movie, restartChat = false, resumeAtMs = resumeAt)
                }
                return
            }
            Log.w(TAG, "Playback failed for good: showing the error")
            _playbackStatus.value = PlaybackStatus.ERROR
            cards.offerNext()
        }

        override fun onTracksChanged(tracks: Tracks) {
            updateStats()
            quality.onTracksChanged()
            for (group in tracks.groups) {
                if (group.type == C.TRACK_TYPE_VIDEO && group.isSelected) {
                    for (i in 0 until group.length) {
                        if (group.isTrackSelected(i)) {
                            val f = group.getTrackFormat(i)
                            Log.i("PlayerViewModel", "Selected video track: ${f.width}x${f.height}@${f.frameRate.toInt()} (${f.bitrate / 1000} kbps)")
                        }
                    }
                }
            }
        }
    }

    init {
        // Checkpoint of the running watch session, so a pulled plug costs at most a minute
        viewModelScope.launch {
            while (true) {
                delay(com.shrug.shrugtv.stats.WatchSessionTracker.CHECKPOINT_MS)
                watchTracker.tick()
            }
        }
        mainPlayer.addListener(playerListener)
        viewModelScope.launch {
            while (true) {
                delay(1_000L)
                checkHealth()
            }
        }
        mediaSession = PlayerMediaSession(appContext, mainPlayer)
        // Called on the main thread; nobody registered it since the player became Compose (B13)
        TwitchAdBlocker.setListener { label -> adBreak.onBlockerLabel(label) }

        // Home Assistant (MQTT) and the phone companion read the player through the hub
        PlaybackStateHub.commands = this
        PlaybackStateHub.chatLines = chatViewModel.lines
        PlaybackStateHub.badgeUrl = { set, version -> chatClient?.emoteManager?.badgeUrl(set, version) }
        viewModelScope.launch {
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            merge(
                _currentMovie.map { }, _playbackStatus.map { }, quality.current.map { }, quality.qualities.map { },
                chat.visible.map { }, chat.appearance.map { }, multiview.active.map { }, _isFollowed.map { },
                _streamAvatar.map { }, _volume.map { }
            ).debounce(300L).collect { publishSnapshot() }
        }
        viewModelScope.launch {
            // Positions and the live delay change without an event
            while (true) {
                delay(10_000L)
                publishSnapshot()
            }
        }
    }

    // region Hub: state for Home Assistant and the phone, commands from them

    private val _closeRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Home Assistant asked to close the player; the screen finishes the activity. */
    val closeRequests: SharedFlow<Unit> = _closeRequests.asSharedFlow()

    private fun publishSnapshot() {
        val m = _currentMovie.value
        val vod = m != null && isVod(m)
        val player = mainPlayer
        PlaybackStateHub.publish(
            HubSnapshot.of(
                HubSnapshot.State(
                    movie = m,
                    vod = vod,
                    status = _playbackStatus.value,
                    positionMs = player.currentPosition,
                    durationMs = player.duration,
                    liveOffsetMs = if (m == null || vod) androidx.media3.common.C.TIME_UNSET else player.currentLiveOffset,
                    avatarUrl = _streamAvatar.value,
                    quality = quality.current.value,
                    qualities = quality.qualities.value.mapNotNull { it.name },
                    chatVisible = chat.visible.value,
                    chatLayout = chat.appearance.value.layout.value,
                    multiview = multiview.active.value,
                    volume = _volume.value,
                    audioOnly = isAudioOnly,
                    followed = _isFollowed.value
                )
            )
        )
    }

    override fun playPause() = togglePlayPause()
    override fun play() { mainPlayer.play() }
    override fun pause() { mainPlayer.pause() }
    override fun close() { _closeRequests.tryEmit(Unit) }

    override fun watchLogin(login: String) {
        val clean = login.trim().removePrefix("@").lowercase()
        if (clean.isEmpty() || clean.length > 25 || !clean.all { it.isLetterOrDigit() || it == '_' }) return
        play(Movie().apply {
            this.login = clean
            displayName = clean
            itemType = Enums.ContentType.STREAM
        })
    }

    override fun watchVod(videoId: Long, positionSeconds: Int?) {
        if (videoId <= 0) return
        val movie = Movie().apply {
            setVideoIdFromString(videoId.toString())
            itemType = Enums.ContentType.VOD
        }
        play(movie, resumeAtMs = positionSeconds?.takeIf { it > 0 }?.let { it * 1000L })
    }

    override fun watchClip(slug: String) {
        if (slug.isBlank()) return
        val clip = Movie().apply {
            setClipSlug(slug)
            itemType = Enums.ContentType.VOD
        }
        com.shrug.shrugtv.clips.ClipQueue.set(listOf(clip))
        play(clip)
    }

    override fun selectQualityByName(name: String) {
        quality.selectByName(name)
    }

    override fun setChatVisible(visible: Boolean) {
        chat.setVisible(visible)
    }

    override fun setChatLayout(value: String) {
        updateChatAppearance { it.copy(layout = com.shrug.shrugtv.chat.ChatLayout.of(value)) }
    }

    override fun setVolume(percent: Int) {
        val p = percent.coerceIn(0, 100)
        _volume.value = p
        mainPlayer.volume = p / 100f
    }

    override fun setAudioOnly(on: Boolean) {
        if (on != isAudioOnly) toggleAudioOnly()
    }

    override fun sendChat(text: String) = sendChatMessage(text)

    override fun showNotice(title: String, text: String?, imageUrl: String?, seconds: Int) =
        cards.showNotice(title, text, imageUrl, seconds)

    // endregion

    /**
     * Starts playback for a stream or VOD.
     * If an [adoptedPlayer] is handed over from the home screen, it is used on seamlessly.
     */
    override fun zap(direction: Int) {
        val movie = _currentMovie.value ?: return
        if (movie.playbackType == Enums.PlaybackKind.VOD || movie.videoId > 0L) return
        if (multiview.active.value) return
        // The zapper needs the row the stream came from; search results, deep links and the
        // channel page have none (B2b)
        if (movie.source == null || movie.source == Enums.RowType.SEARCH_STREAM) {
            seek.showHud(appContext.getString(R.string.player_zap_unavailable))
            return
        }
        if (zapper == null) {
            // The zapper resolves neighbours on a background thread; ExoPlayer may only be used on
            // the main thread (Media3 throws otherwise, B2)
            zapper = StreamZapper(appContext) { neighbour, _, _, _ ->
                if (neighbour != null) {
                    Threads.doOnMainThread { play(neighbour) }
                }
            }
        }
        zapper?.zap(movie, direction, false)
    }

    private fun isVod(movie: Movie): Boolean =
        movie.playbackType == Enums.PlaybackKind.VOD || movie.videoId > 0L

    /** "Default video quality" (live) or "Default VOD video quality": 0 Auto, 1-3 Auto max 1080/720/480p, 4 Source. */
    private fun qualityPreference(vod: Boolean): Int {
        val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
        return (if (vod) Prefs.vodVideoQualityV3 else Prefs.videoQualityV3).get(prefs)
    }

    fun play(movie: Movie, adoptedPlayer: ExoPlayer? = null, restartChat: Boolean = true, resumeAtMs: Long? = null) {
        val request = ++playRequest
        releasePreloaded()
        // Anything but a clip ends the way back from the highlights (a clip may be the next of their queue)
        if (!movie.isClip) highlightsOf = null
        if (movie.isClip) clips.preloadNext(movie)
        _clipTitle.value = if (movie.isClip) movie.title else null
        _clipChannelLive.value = false
        if (movie.isClip) checkClipChannelLive(movie)
        _clipNav.value = if (movie.isClip) com.shrug.shrugtv.clips.ClipQueue.position(movie) else null
        health.reset(newItem = !healthReload)
        // A reload by the stall watch must not end "Audio only" (someone may be asleep to it)
        val keepAudioOnly = healthReload && isAudioOnly
        healthReload = false
        quality.onNewItem()
        rewind.onPlay(movie)
        cancelEndSuggestion()
        dismissRaid()
        dismissLiveCard()
        // Save the outgoing VOD before anything changes (B3)
        saveVodProgress()
        watchTracker.stop()
        if (restartChat || _currentMovie.value?.userId != movie.userId) chat.start(movie)
        mainPlayer.volume = 0f // mute the old stream at once when switching
        _currentMovie.value = movie
        com.shrug.shrugtv.startup.StartResume.remember(appContext, movie)
        startWatchSession(movie)
        loadStreamAvatar(movie)
        checkFollowStatus(movie)
        if (movie.userId <= 0 && !movie.login.isNullOrEmpty()) {
            resolveUserId(movie, request)
        }

        val isVod = movie.playbackType == Enums.PlaybackKind.VOD || movie.videoId > 0L

        if (isVod) {
            loadChapters(movie)
            rewind.onVodStart(movie)
        } else {
            _chapters.value = emptyList()
            rewind.lookup(movie)
        }

        if (adoptedPlayer != null) {
            val old = _mainPlayer.value
            old.removeListener(playerListener)
            old.release()
            adoptedPlayer.addListener(playerListener)
            // An adopted player may be playing already (a preloaded clip): no "started playing" event will come for the watch time
            watchTracker.playing(adoptedPlayer.isPlaying)
            adoptedPlayer.volume = 1.0f
            _mainPlayer.value = adoptedPlayer
            loadedMovie = movie
            mediaSession?.switchPlayer(adoptedPlayer)
            _playbackStatus.value = PlaybackStatus.PLAYING
            _isPlaying.value = adoptedPlayer.isPlaying
            // The preview plays the master playlist: apply the quality setting without reloading,
            // and offer the qualities (the menu stayed empty after a handover, B5)
            quality.onAdopted(adoptedPlayer, isVod)
            if (movie.qualities.isNotEmpty()) {
                quality.setQualities(movie.qualities)
            } else {
                quality.setQualities(emptyList())
                resolveQualitiesOnly(movie, request)
            }
            return
        }

        val playbackId = movie.playbackId ?: movie.login
        if (playbackId == null) {
            // Launcher deep link with only the channel id (B9): look up the login, then start
            if (movie.userId > 0 && !isVod) {
                com.shrug.shrugtv.api.helix.HelixApi.getUsersById(appContext, movie.userId.toString()) { _, json ->
                    val login = (json as? com.google.gson.JsonObject)?.getAsJsonArray("data")?.firstOrNull()
                        ?.asJsonObject?.get("login")?.asString
                    if (login != null && request == playRequest) {
                        movie.login = login
                        if (movie.displayName.isNullOrEmpty()) movie.displayName = login
                        play(movie, restartChat = true)
                    } else if (request == playRequest) {
                        _playbackStatus.value = PlaybackStatus.ERROR
                    }
                }
            } else {
                _playbackStatus.value = PlaybackStatus.ERROR
            }
            return
        }
        val playbackType = movie.playbackType ?: if (isVod) Enums.PlaybackKind.VOD else Enums.PlaybackKind.LIVE

        viewModelScope.launch {
            try {
                _playbackStatus.value = PlaybackStatus.BUFFERING
                // Resume position is read before the stream is set, then passed with the media item;
                // a separate seek used to hit the previous item (B3)
                val startPositionMs = resumeAtMs ?: if (isVod) savedVodPositionMs(movie) else 0L
                if (request != playRequest) return@launch
                val resolver = PlaybackUrlResolver(appContext, playbackId, playbackType) { resolvedQualities, _ ->
                    // Main thread. A newer play() (fast zapping) wins, older results are dropped (B4)
                    if (request == playRequest) {
                        if (!resolvedQualities.isNullOrEmpty()) {
                            // Always the master playlist; the setting works through the track
                            // selection, so ABR stays on and qualities switch without reload (B6)
                            val masterUrl = quality.onResolved(resolvedQualities, isVod, keepAudioOnly)

                            val item = MediaItem.fromUri(Uri.parse(masterUrl))
                            if (startPositionMs > 0L) {
                                mainPlayer.setMediaItem(item, startPositionMs)
                            } else {
                                mainPlayer.setMediaItem(item)
                            }
                            loadedMovie = movie
                            mainPlayer.prepare()
                            mainPlayer.volume = 1.0f
                            mainPlayer.playWhenReady = true
                        } else {
                            // No playlist: the channel is offline (or gone)
                            _playbackStatus.value = PlaybackStatus.ERROR
                            cards.offerNext()
                        }
                    }
                }
                resolver.useStreamToken = false
                resolver.start()
            } catch (e: Exception) {
                _playbackStatus.value = PlaybackStatus.ERROR
                CrashReporter.recordException(e)
            }
        }
    }

    private fun loadChapters(movie: Movie) = loadChaptersFor(movie.videoId)

    private fun loadChaptersFor(videoId: Long) {
        if (videoId <= 0L) return

        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { PlayerLookups.chapters(appContext, videoId) }
            _chapters.value = if (Prefs.vodChapters.get(appContext)) {
                list.sortedBy { it.positionMs }
            } else emptyList()
        }
    }

    /** Saved resume position of a VOD in ms, 0 if none. */
    private suspend fun savedVodPositionMs(movie: Movie): Long = vodProgress.positionMs(movie)

    /**
     * Saves the position of the VOD that is actually loaded in the player ([loadedMovie], not
     * [currentMovie]: while a new stream resolves, the old one still plays; B3). Reads the player on
     * the calling (main) thread, writes on [saveScope], so it also works from [onCleared].
     */
    fun saveVodProgress() {
        vodProgress.save(loadedMovie ?: return)
    }

    /**
     * Switches the quality. On the master playlist this is a track selection override, without
     * reloading the stream (B6); "Auto" returns to adaptive. Audio-only, clips and streams that were
     * started from a single variant still reload with the chosen URL.
     */
    fun selectQuality(selected: StreamQuality) = quality.select(selected)

    /** "Audio only" is the current quality. */
    val isAudioOnly: Boolean get() = quality.isAudioOnly

    /**
     * One press between "Audio only" and the last picture quality. Without an audio-only variant
     * (clips) nothing happens and false is returned.
     */
    fun toggleAudioOnly(): Boolean = quality.toggleAudioOnly()

    /** OK on the error card: start the stream again. */
    fun retry() {
        val movie = _currentMovie.value ?: return
        errorRetries = 0
        play(movie, restartChat = false, resumeAtMs = if (isVod(movie)) mainPlayer.currentPosition else null)
    }

    /** Qualities for the menu after a hero handover, without touching the playing stream. */
    private fun resolveQualitiesOnly(movie: Movie, request: Int) {
        val playbackId = movie.playbackId ?: movie.login ?: return
        val type = movie.playbackType ?: Enums.PlaybackKind.LIVE
        val resolver = PlaybackUrlResolver(appContext, playbackId, type) { resolved, _ ->
            if (request == playRequest && !resolved.isNullOrEmpty()) quality.setQualities(resolved)
        }
        resolver.useStreamToken = false
        resolver.start()
    }

    fun selectSpeed(speed: Float) {
        _currentSpeed.value = speed
        mainPlayer.playbackParameters = PlaybackParameters(speed)
        dismissOverlay()
    }

    fun selectChapter(chapter: VodChapter) {
        rewind.seekToTimeline(chapter.positionMs)
        dismissOverlay()
    }

    // region Multiview (Slice 4d)

    fun startMultiview(second: Movie) = multiview.start(second)

    fun stopMultiview() = multiview.stop()

    fun swapMultiviewAudio() = multiview.swapAudio()

    // endregion

    private fun checkFollowStatus(movie: Movie) {
        val channelId = movie.userId.toString()
        FollowStatusChecker.check(appContext, channelId) { follows, _ ->
            _isFollowed.value = follows
        }
    }

    /**
     * Real values for the statistics overlay; called on player events and every second while the
     * overlay is open. Memory, latency and dropped frames were fixed sample values before.
     */
    fun updateStats() {
        val format = mainPlayer.videoFormat
        val counters = mainPlayer.videoDecoderCounters
        val runtime = Runtime.getRuntime()
        EmoteFrameMeter.roll()
        statsController.update(
            PlayerStatsController.Sample(
                width = format?.width,
                height = format?.height,
                frameRate = format?.frameRate ?: 0f,
                bitrateBps = format?.bitrate ?: 0,
                bufferedMs = mainPlayer.totalBufferedDuration,
                droppedFrames = counters?.droppedBufferCount ?: 0,
                renderedFrames = counters?.renderedOutputBufferCount ?: 0,
                latencyMs = mainPlayer.currentLiveOffset.takeIf { it != C.TIME_UNSET },
                chatDelayMs = chatDelay?.currentMs(),
                memoryMb = ((runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)).toInt(),
                emoteFps = EmoteFrameMeter.framesPerSecond,
                emoteCount = EmoteFrameMeter.emotes
            )
        )
    }

    // region Overlay Steuerung

    /** Opens [overlay], or closes it when it is open already. */
    fun setOverlay(overlay: PlayerOverlayType) = overlays.toggle(overlay)

    fun dismissOverlay() = overlays.dismiss()

    /** Shows [overlay] without the toggle behaviour of [setOverlay]. */
    fun showOverlay(overlay: PlayerOverlayType) = overlays.show(overlay)

    /**
     * Back from an overlay: menus opened from the control bar return to the bar (B34), the bar and
     * the channel switcher return to the video.
     */
    fun backFromOverlay() = overlays.back()

    fun toggleChat() = chat.toggleVisible()

    fun togglePlayPause() {
        if (mainPlayer.isPlaying) {
            mainPlayer.pause()
            saveVodProgress()
        } else {
            mainPlayer.play()
        }
    }

    // region Rewinding a live stream into its recording

    private val rewind: LiveRewindController = LiveRewindController(
        scope = viewModelScope,
        player = { mainPlayer },
        currentMovie = { _currentMovie.value },
        loadedMovie = { loadedMovie },
        isVod = ::isVod,
        play = { movie, atMs -> play(movie, restartChat = true, resumeAtMs = atMs) },
        onBackToLive = { seek.showHud(appContext.getString(R.string.player_back_to_live_hud)) },
        find = { PlayerLookups.liveArchive(appContext, it) },
        onFound = ::loadChaptersFor,
        enabled = { Prefs.liveRewind.get(playerPrefs) }
    )

    /** The recording of the live stream that is playing, once found; null without one or when switched off. */
    val liveArchive: StateFlow<com.shrug.shrugtv.player.LiveArchiveInfo?> get() = rewind.archive

    /** Which switch is loading: 0 none, 1 into the recording, 2 back to live. */
    val archiveSwitch: StateFlow<Int> get() = rewind.switch

    /**
     * Position and length of the timeline in ms: of the recording while it plays, since the start of
     * the stream while live, else of the video.
     */
    fun timelineMs(): LongArray = rewind.timelineMs()

    /** Back from the recording to the live stream. */
    fun exitArchive() = rewind.exitArchive()

    // endregion

    /** Seeking, the seek HUD, instant replay and "Back to live". */
    private val seek: SeekController = SeekController(
        scope = viewModelScope,
        player = { mainPlayer },
        current = { _currentMovie.value },
        isVod = ::isVod,
        rewind = rewind,
        chapters = { _chapters.value },
        replayLengthMs = {
            com.shrug.shrugtv.player.InstantReplay.lengthMs(Prefs.playerReplaySeconds.text(playerPrefs))
        },
        backToLiveText = { appContext.getString(R.string.player_back_to_live_hud) },
        replayText = { seconds -> appContext.getString(R.string.player_replay_hud, seconds) },
        uptime = { android.os.SystemClock.uptimeMillis() }
    )
    val seekHudText: StateFlow<String?> get() = seek.hudText

    /** The "Replay" button shows in live streams while the setting is on. */
    val replayEnabled: Boolean get() = Prefs.playerReplay.get(playerPrefs)

    /** Jumps back by the replay length and returns to the live edge once that part has played again. */
    override fun replay() = seek.replay()

    /** Behind the live edge (pause, stall): back to live. */
    override fun jumpToLive() = seek.jumpToLive()

    /** Left/right on a recording: the step grows while the key is pressed again and again. */
    fun seekStep(direction: Int) = seek.seekStep(direction)

    /** Next (+1) or previous (-1) category of a recording. */
    fun jumpChapter(direction: Int) = seek.jumpChapter(direction)

    fun seekRelative(deltaMs: Long) = seek.seekRelative(deltaMs)

    // endregion

    /** "Reload emotes" in the More menu (B14). */
    fun reloadEmotes() {
        chatClient?.emotesReload(null)
    }

    /** Sends a chat message via Helix; the input in the player dropped the text before (C2.8). */
    fun sendChatMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) chatClient?.sendMessage(trimmed)
    }

    /** True while playback is paused because the activity went to the background. */
    private var pausedForBackground = false
    private var backgroundSince = 0L

    /** Activity stopped (Home button): pause instead of playing on unseen (B1). */
    /**
     * Back leaves the player: silence and stop the picture at once, before the activity closes
     * and the players are torn down, so the press is answered right away.
     */
    fun quietForExit() {
        saveVodProgress()
        mainPlayer.volume = 0f
        mainPlayer.playWhenReady = false
        multiviewPlayer?.let { it.volume = 0f; it.playWhenReady = false }
    }

    fun pauseForBackground() {
        Log.i(TAG, "pauseForBackground (playWhenReady=${mainPlayer.playWhenReady})")
        saveVodProgress()
        // The process may be killed while in the background: write what has been counted so far
        watchTracker.tick()
        if (mainPlayer.playWhenReady) {
            pausedForBackground = true
            backgroundSince = android.os.SystemClock.elapsedRealtime()
            mainPlayer.pause()
            multiviewPlayer?.pause()
        }
    }

    /** Activity visible again: continue; live streams jump back to the live edge. */
    fun resumeFromBackground() {
        if (!pausedForBackground) return
        pausedForBackground = false
        val movie = _currentMovie.value
        val isVod = movie != null && (movie.playbackType == Enums.PlaybackKind.VOD || movie.videoId > 0L)
        val awayMs = android.os.SystemClock.elapsedRealtime() - backgroundSince
        if (!isVod && movie != null && awayMs >= STALE_AFTER_BACKGROUND_MS) {
            // The playlist and the access token are stale after a while: jumping to the live edge of the old
            // session left a frozen picture now and then (Michael, 2026-11-09). Resolve the stream again.
            Log.i(HEALTH_TAG, "back after ${awayMs / 1000} s, reloading the live stream (${movie.login})")
            healthReload = true
            play(movie, restartChat = false)
            return
        }
        if (!isVod) {
            mainPlayer.seekToDefaultPosition()
            multiviewPlayer?.seekToDefaultPosition()
        }
        mainPlayer.play()
        multiviewPlayer?.play()
    }

    override fun onCleared() {
        PlaybackStateHub.clear(this)
        // Save first (reads the player), then release everything this player owns (B1)
        saveVodProgress()
        watchTracker.stop()
        TwitchAdBlocker.setListener(null)
        // Chat events still waiting for their delay (live chat) or for the main thread would reach a cleared chat
        chat.stopLive()
        mainHandler.removeCallbacksAndMessages(null)
        chat.stopVod()
        releasePreloaded()
        mainPlayer.removeListener(playerListener)
        mainPlayer.release()
        multiview.release()
        mediaSession?.release()
        mediaSession = null
        super.onCleared()
    }

    /** What the source of a clip tells: its recording, channel and title; the chat starts after it. */
    private fun fillFromClipSource(movie: Movie, info: com.shrug.shrugtv.chat.ClipSource.Info) {
        movie.clipVideoId = info.videoId
        movie.clipOffsetSeconds = info.offsetSeconds
        if (movie.userId <= 0 && info.broadcasterId > 0) movie.userId = info.broadcasterId
        // Before the login is filled in: only a clip that came without a channel needs the live check
        val hadLogin = !movie.login.isNullOrBlank()
        if (movie.login.isNullOrEmpty()) info.login?.let { movie.login = it }
        if (movie.displayName.isNullOrEmpty()) info.displayName?.let { movie.displayName = it }
        if (!hadLogin && !movie.login.isNullOrBlank()) checkClipChannelLive(movie)
        if (movie.title.isNullOrEmpty()) info.title?.takeIf { it.isNotEmpty() }?.let { movie.title = it; _clipTitle.value = it }
        // A clip opened by link started its watch session without a channel: fill it in
        watchTracker.updateChannel(movie.userId, movie.login ?: "", movie.displayName.ifBlank { movie.login ?: "" })
    }

    /** Asks whether the channel of the clip is live; a clip opened by link has no channel until its source is known. */
    private fun checkClipChannelLive(movie: Movie) {
        val login = movie.login?.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val live = com.shrug.shrugtv.chat.ClipSource.fetchLive(appContext, login)
            withContext(Dispatchers.Main) { if (_currentMovie.value === movie) _clipChannelLive.value = live }
        }
    }

    /** The button "Live" on a clip: the channel's live stream. */
    fun watchClipChannel() {
        val clip = _currentMovie.value?.takeIf { it.isClip && !it.login.isNullOrBlank() } ?: return
        val stream = Movie().apply {
            login = clip.login
            userId = clip.userId
            displayName = clip.displayName
            itemType = Enums.ContentType.STREAM
        }
        // The queue of clips ends here
        com.shrug.shrugtv.clips.ClipQueue.clear()
        play(stream)
    }

    /** Whether [movie] is a live stream (not a recording, its rewound archive or a clip): the highlights belong to it. */
    private fun isLive(movie: Movie?) = movie != null && !movie.isClip && !movie.archiveOfLive &&
        movie.playbackType != Enums.PlaybackKind.VOD && movie.videoId <= 0L

    /** The button "Highlights" of a live stream: opens the strip and loads its clips. */
    fun openHighlights(force: Boolean = false) {
        val live = _currentMovie.value?.takeIf { isLive(it) } ?: return
        overlays.show(PlayerOverlayType.HIGHLIGHTS)
        _highlights.value = null
        _highlightsFailed.value = false
        viewModelScope.launch {
            val found = PlayerHighlights.load(appContext, live, force)
            if (_currentMovie.value !== live) return@launch
            if (found == null) _highlightsFailed.value = true else _highlights.value = found
        }
    }

    /** OK on a highlight: plays it here with the other highlights as its queue; Back on it returns to the live stream. */
    fun playHighlight(clip: Movie) {
        val live = _currentMovie.value?.takeIf { isLive(it) } ?: return
        val list = _highlights.value ?: return
        com.shrug.shrugtv.clips.ClipQueue.set(list)
        overlays.dismiss()
        clips.startClip(clip)
        highlightsOf = live
    }

    /** Back while a clip from the highlights plays: the live stream again; false when there is no way back. */
    fun backToLiveFromHighlight(): Boolean {
        val live = highlightsOf ?: return false
        highlightsOf = null
        if (_currentMovie.value?.isClip != true) return false
        com.shrug.shrugtv.clips.ClipQueue.clear()
        play(Movie().apply {
            login = live.login
            userId = live.userId
            displayName = live.displayName
            avatarUrl = live.avatarUrl
            itemType = Enums.ContentType.STREAM
        })
        return true
    }

    /** The button "Recording" on a clip: the recording it was cut from, at the second where the clip starts. */
    fun jumpToClipSource() {
        val clip = _currentMovie.value?.takeIf { it.isClip && it.clipVideoId > 0L } ?: return
        val recording = Movie().apply {
            setVideoIdFromString(clip.clipVideoId.toString())
            itemType = Enums.ContentType.VOD
            userId = clip.userId
            login = clip.login
            displayName = clip.displayName
        }
        // Leaving the clips: the queue does not carry on in the recording
        com.shrug.shrugtv.clips.ClipQueue.clear()
        play(recording, resumeAtMs = clip.clipOffsetSeconds * 1000L)
    }

    /** Delay of the running live chat. */
    val chatDelay: com.shrug.shrugtv.chat.ChatDelay? get() = chat.delay
}
