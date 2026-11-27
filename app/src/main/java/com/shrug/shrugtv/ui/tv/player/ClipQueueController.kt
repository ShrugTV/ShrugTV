package com.shrug.shrugtv.ui.tv.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.shrug.shrugtv.model.Movie
import com.shrug.shrugtv.model.StreamQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * One item loaded ahead in a muted, paused player of its own: the next clip of the queue or the next recording
 *; split from PlayerViewModel. Whatever is not taken over is released.
 */
class PreloadSlot<P : Player> {
    private var held: Pair<Movie, P>? = null
    private var job: Job? = null

    /** The item that is loaded ahead, or null. */
    val movie: Movie? get() = held?.first

    /** Releases what is held, then starts [loading], the job that fills the slot (released first, so a job that is done at once keeps what it held). */
    fun start(loading: () -> Job) {
        release()
        job = loading()
    }

    /** Lets [player] load the first seconds of [movie] from [url] (at [startMs]), muted and paused, and holds it. */
    fun hold(movie: Movie, player: P, url: String?, startMs: Long? = null) {
        held?.second?.release()
        player.volume = 0f
        player.playWhenReady = false
        val item = MediaItem.fromUri(Uri.parse(url))
        if (startMs != null) player.setMediaItem(item, startMs) else player.setMediaItem(item)
        player.prepare()
        held = movie to player
    }

    /** Hands over the held item if [matches] accepts it; the slot is empty afterwards. */
    fun take(matches: (Movie) -> Boolean): Pair<Movie, P>? {
        val h = held?.takeIf { matches(it.first) } ?: return null
        held = null
        return h
    }

    fun release() {
        job?.cancel()
        job = null
        held?.second?.release()
        held = null
    }
}

/**
 * The clip queue: the next clip of the list a clip was opened from starts when it ends, the
 * buttons step through the list, and the next clip is loaded ahead in [slot] so the change is almost instant; split
 * from PlayerViewModel.
 *
 * [current] is the item shown, [nextOf] the next clip after it (null: setting off or end of the list), [neighbor] the
 * clip [step] places away, [markSeen] counts a clip as opened, [resolve] looks up the playlists of a clip, [play] starts
 * a clip, with the player taken over from the preload or null.
 */
class ClipQueueController<P : Player>(
    private val scope: CoroutineScope,
    private val slot: PreloadSlot<P>,
    private val current: () -> Movie?,
    private val nextOf: (Movie) -> Movie?,
    private val neighbor: (Movie, Int) -> Movie?,
    private val markSeen: (Movie) -> Unit,
    private val resolve: suspend (Movie) -> List<StreamQuality>?,
    private val createPlayer: () -> P,
    private val play: (Movie, P?) -> Unit,
    private val preloadDelayMs: Long = PRELOAD_DELAY_MS,
    private val fallbackDelayMs: Long = FALLBACK_DELAY_MS
) {
    /**
     * A few seconds after a clip started (so it does not compete for the line): look up the address of the next clip of
     * the queue and let a second player load its first seconds paused. [startClip] takes it over.
     */
    fun preloadNext(clip: Movie) {
        slot.start {
            scope.launch {
                delay(preloadDelayMs)
                if (current() !== clip) return@launch
                val next = nextOf(clip) ?: return@launch
                val qualities = resolve(next)
                if (current() !== clip || qualities.isNullOrEmpty()) return@launch
                next.qualities = ArrayList(qualities)
                slot.hold(next, createPlayer(), QualityController.master(qualities).url)
            }
        }
    }

    /**
     * A clip ended: the next clip of the list starts, at once from the preload, else after a short pause. Returns the job
     * of that pause (the caller treats it like the countdown at the end of a stream), or null.
     */
    fun playNext(clip: Movie): Job? {
        val next = nextOf(clip) ?: return null
        if (slot.movie?.clipSlug == next.clipSlug) {
            startClip(next)
            return null
        }
        Log.i(TAG, "Next clip not preloaded (${if (slot.movie == null) "nothing ready" else "other clip"}), loading it now")
        return scope.launch {
            delay(fallbackDelayMs)
            if (current() !== clip) return@launch
            startClip(next)
        }
    }

    /** Starts [clip] (counts as opened); from the preload if that holds it: in its own player, ready, no pause and no new lookup. */
    fun startClip(clip: Movie) {
        markSeen(clip)
        val ready = slot.take { it.clipSlug == clip.clipSlug }
        if (ready == null) {
            play(clip, null)
            return
        }
        val (movie, player) = ready
        Log.i(TAG, "Next clip taken over from the preload, ${player.bufferedPosition} ms already loaded")
        player.volume = 1.0f
        player.playWhenReady = true
        play(movie, player)
    }

    /** The buttons "Previous clip" (-1) and "Next clip" (+1): the neighbour in the list the clip was opened from. */
    fun step(step: Int) {
        val clip = current()?.takeIf { it.isClip } ?: return
        startClip(neighbor(clip, step) ?: return)
    }

    companion object {
        private const val TAG = "PlayerViewModel"
        const val PRELOAD_DELAY_MS = 3_000L
        const val FALLBACK_DELAY_MS = 1_200L
    }
}
