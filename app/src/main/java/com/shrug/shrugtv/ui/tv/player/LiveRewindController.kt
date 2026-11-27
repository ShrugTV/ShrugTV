package com.shrug.shrugtv.ui.tv.player

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import com.shrug.shrugtv.model.Enums
import com.shrug.shrugtv.model.Movie
import com.shrug.shrugtv.player.LiveArchiveInfo
import com.shrug.shrugtv.player.LiveRewind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Rewinding a live stream into its recording; split from PlayerViewModel
 *. Finds the recording of the running stream, keeps the timeline since the start of the stream,
 * and switches between the live stream and its recording.
 *
 * [currentMovie] is the item shown, [loadedMovie] the one whose stream is set in the player; [play] starts an item with
 * the chat at the given position, [onBackToLive] is told when it goes back to live. [find] looks up the recording
 * (network, runs on [io]), [onFound] gets its video id (categories), [enabled] reads the setting.
 */
class LiveRewindController(
    private val scope: CoroutineScope,
    private val player: () -> Player,
    private val currentMovie: () -> Movie?,
    private val loadedMovie: () -> Movie?,
    private val isVod: (Movie) -> Boolean,
    private val play: (Movie, Long?) -> Unit,
    private val onBackToLive: () -> Unit,
    private val find: suspend (Movie) -> LiveArchiveInfo?,
    private val onFound: (Long) -> Unit,
    private val enabled: () -> Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis
) {
    private val _archive = MutableStateFlow<LiveArchiveInfo?>(null)

    /** The recording of the live stream that is playing, once found; null without one or when switched off. */
    val archive: StateFlow<LiveArchiveInfo?> = _archive.asStateFlow()
    private var lookupJob: Job? = null

    /** The live stream while its recording is played. */
    private var liveOrigin: Movie? = null

    /** True while the recording loads after a switch into it. */
    var loading = false
        private set

    /** Position the recording should start at; moves while keys are pressed during loading. */
    private var seekTarget: Long? = null
    private var watcher: Job? = null

    private val _switch = MutableStateFlow(SWITCH_NONE)

    /** Which switch is loading: [SWITCH_NONE], [SWITCH_TO_ARCHIVE] or [SWITCH_TO_LIVE]. */
    val switch: StateFlow<Int> = _switch.asStateFlow()
    private var switchTarget: Movie? = null

    /** True between the start of a switch and the first picture of its target. */
    val switching: Boolean get() = switchTarget != null

    /** A new item starts: clears what belongs to the live stream or to another switch. */
    fun onPlay(movie: Movie) {
        if (!movie.archiveOfLive) {
            liveOrigin = null
            loading = false
            seekTarget = null
        }
        if (switchTarget !== movie) {
            switchTarget = null
            _switch.value = SWITCH_NONE
        }
    }

    /** A recording starts: one that is not the recording of the live stream ends the search. */
    fun onVodStart(movie: Movie) {
        if (!movie.archiveOfLive) {
            lookupJob?.cancel()
            _archive.value = null
        }
    }

    /** Looks for the recording of the live stream: once now, then every minute for five minutes. */
    fun lookup(movie: Movie) {
        lookupJob?.cancel()
        if (!enabled()) {
            _archive.value = null
            return
        }
        // Zapping to the same channel (or back from the recording) keeps what was found
        val keep = _archive.value?.takeIf { movie.userId > 0 && it.userId == movie.userId }
        if (keep == null) _archive.value = null else onFound(keep.videoId)
        lookupJob = scope.launch {
            repeat(LOOKUPS) { i ->
                if (i > 0) delay(LOOKUP_INTERVAL_MS)
                val found = withContext(io) { find(movie) }
                if (found != null) {
                    Log.i(TAG, "Recording of the live stream found: video ${found.videoId}")
                    _archive.value = found
                    onFound(found.videoId)
                    return@launch
                }
            }
        }
    }

    /** The player is ready: a finished switch ends, and presses made while the recording loaded move it there. */
    fun onReady() {
        if (switchTarget != null && loadedMovie() === switchTarget) {
            switchTarget = null
            _switch.value = SWITCH_NONE
        }
        if (loading && loadedMovie()?.archiveOfLive == true) {
            loading = false
            val wanted = seekTarget
            seekTarget = null
            val p = player()
            if (wanted != null && kotlin.math.abs(p.currentPosition - wanted) > 2_000L) p.seekTo(wanted)
        }
    }

    /** True if left/right on the timeline rewinds: a live stream with a recording, or that recording itself. */
    fun isActive(): Boolean {
        val movie = currentMovie() ?: return false
        return movie.archiveOfLive || (!isVod(movie) && _archive.value != null)
    }

    /**
     * Position and length of the timeline in ms: of the recording while it plays, since the start of
     * the stream while live (the live position is "now" minus the live offset), else of the video.
     */
    fun timelineMs(): LongArray {
        val movie = currentMovie()
        val info = _archive.value
        val p = player()
        if (movie != null && !isVod(movie) && info != null) {
            return LiveRewind.liveTimeline(now(), info.streamStartMs, p.currentLiveOffset)
        }
        if (movie?.archiveOfLive == true && loading && info != null) {
            val duration = LiveRewind.liveTimeline(now(), info.streamStartMs, 0L)[1]
            return longArrayOf(seekTarget ?: 0L, duration)
        }
        return longArrayOf(p.currentPosition, p.duration.coerceAtLeast(0L))
    }

    /** Seeks by [deltaMs] on the timeline; returns where it ends up, null if it went back to live or nothing happened. */
    fun rewindBy(deltaMs: Long): Long? {
        val movie = currentMovie() ?: return null
        val timeline = timelineMs()
        val p = player()
        if (movie.archiveOfLive) {
            return when (val plan = LiveRewind.planInArchive(deltaMs, timeline[0], timeline[1])) {
                is LiveRewind.Plan.InArchive -> {
                    if (loading) seekTarget = plan.targetMs else p.seekTo(plan.targetMs)
                    plan.targetMs
                }
                LiveRewind.Plan.BackToLive -> { exitArchive(); null }
                else -> null
            }
        }
        // currentPosition of a live window can be negative or stale: the position inside the window is its
        // length minus the distance to the live edge (measured 2026-11-04: window 32 s, position -14 s)
        val windowPosition = liveWindowPosition(p)
        Log.i(TAG, "rewind: delta=${deltaMs / 1000} s position=${timeline[0] / 1000} duration=${timeline[1] / 1000} window=${windowPosition / 1000} of ${p.duration / 1000}")
        return when (val plan = LiveRewind.planFromLive(deltaMs, timeline[0], timeline[1], windowPosition)) {
            is LiveRewind.Plan.InWindow -> {
                p.seekTo((windowPosition + plan.deltaMs).coerceAtLeast(0L))
                (timeline[0] + plan.deltaMs).coerceAtLeast(0L)
            }
            is LiveRewind.Plan.IntoArchive -> { enterArchive(plan.targetMs); plan.targetMs }
            else -> null
        }
    }

    /** Seeks to [targetMs] on the timeline (a category of the recording). */
    fun seekToTimeline(targetMs: Long) {
        if (isActive()) rewindBy(targetMs - timelineMs()[0]) else player().seekTo(targetMs)
    }

    /** Live stream to its recording at [targetMs]; the recording plays like any other one, with its chat and categories. */
    fun enterArchive(targetMs: Long) {
        val info = _archive.value ?: return
        val live = currentMovie() ?: return
        Log.i(TAG, "enterArchive: video ${info.videoId} at ${targetMs / 1000} s")
        val archive = Movie().apply {
            itemType = Enums.ContentType.VOD
            source = Enums.RowType.VOD_CHANNEL_ARCHIVED
            setVideoIdFromString(info.videoId.toString())
            userId = live.userId
            login = live.login
            displayName = live.displayName
            gameName = live.gameName
            title = live.title
            avatarUrl = live.avatarUrl
            archiveOfLive = true
            setStartedAtDateTime(live.startedAt)
        }
        switchTarget = archive
        play(archive, targetMs)
        _switch.value = SWITCH_TO_ARCHIVE
        // play() clears the state of a live stream: set after it, for the recording
        liveOrigin = live
        loading = true
        seekTarget = targetMs
        watcher?.cancel()
        watcher = scope.launch {
            while (currentMovie()?.archiveOfLive == true) {
                delay(1_000L)
                val p = player()
                if (!loading && loadedMovie()?.archiveOfLive == true && LiveRewind.shouldLeaveArchive(p.currentPosition, p.duration)) {
                    exitArchive()
                    return@launch
                }
            }
        }
    }

    /** Back from the recording to the live stream. */
    fun exitArchive() {
        val live = liveOrigin ?: return
        Log.i(TAG, "exitArchive: back to live")
        watcher?.cancel()
        onBackToLive()
        switchTarget = live
        play(live, null)
        _switch.value = SWITCH_TO_LIVE
    }

    companion object {
        private const val TAG = "PlayerViewModel"
        const val SWITCH_NONE = 0
        const val SWITCH_TO_ARCHIVE = 1
        const val SWITCH_TO_LIVE = 2
        private const val LOOKUPS = 6
        private const val LOOKUP_INTERVAL_MS = 60_000L

        /** Position inside the live window: its length minus the distance to the live edge, 0 when unknown. */
        fun liveWindowPosition(p: Player): Long {
            val windowLength = p.duration
            val offset = p.currentLiveOffset
            return if (windowLength > 0 && offset != C.TIME_UNSET) (windowLength - offset).coerceIn(0L, windowLength) else 0L
        }
    }
}
