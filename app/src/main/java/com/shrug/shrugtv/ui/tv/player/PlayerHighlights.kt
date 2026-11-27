package com.shrug.shrugtv.ui.tv.player

import android.content.Context
import com.shrug.shrugtv.api.helix.HelixApi
import com.shrug.shrugtv.api.helix.HelixParser
import com.shrug.shrugtv.model.Enums
import com.shrug.shrugtv.model.Movie
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * "Highlights of this stream": the most viewed clips of the running broadcast, so someone who
 * tunes in late sees what happened so far. Twitch's own recaps need its service; clips are the local stand-in.
 * One Helix request per channel and broadcast, kept for [CACHE_MS].
 */
internal object PlayerHighlights {
    const val COUNT = 5
    /** Helix sorts by views; a few more than shown, in case some have no picture or title. */
    private const val FETCH = 20
    private const val CACHE_MS = 2 * 60_000L
    /** Without a known start (rare): the last hours. */
    private val FALLBACK_WINDOW: Duration = Duration.ofHours(12)

    private class Entry(val at: Long, val clips: List<Movie>)
    private val cache = HashMap<String, Entry>()

    /** Where the clips of [live] start counting: its start time, else [FALLBACK_WINDOW] back from [now]. */
    internal fun since(live: Movie, now: Long): Instant =
        (live.startedAt?.toInstant() ?: Instant.ofEpochMilli(now).minus(FALLBACK_WINDOW)).truncatedTo(ChronoUnit.SECONDS)

    /** The most viewed [COUNT] of [list]. */
    internal fun pick(list: List<Movie>): List<Movie> =
        list.filter { it.clipSlug.isNotEmpty() }.sortedByDescending { it.viewCount }.take(COUNT)

    /** Seconds into the broadcast at which [clip] was made, or null when a time is missing. */
    internal fun secondsIntoStream(clip: Movie, start: ZonedDateTime?): Long? {
        val made = clip.publishedAt ?: return null
        val from = start ?: return null
        return Duration.between(from, made).seconds.takeIf { it >= 0 }
    }

    /**
     * The highlights of [live], most viewed first, at most [COUNT]; an empty list when there are none yet, null when
     * Twitch did not answer (the overlay then offers "Try again").
     */
    suspend fun load(ctx: Context, live: Movie, force: Boolean = false, now: Long = System.currentTimeMillis()): List<Movie>? {
        val id = live.userId.takeIf { it > 0 }?.toString() ?: return emptyList()
        val since = since(live, now)
        val key = "$id@$since"
        if (!force) synchronized(cache) { cache[key]?.takeIf { now - it.at < CACHE_MS }?.let { return it.clips } }
        val json = withContext(Dispatchers.IO) {
            runCatching { HelixApi.getClipsOfBroadcasterSync(ctx, id, since.toString(), FETCH) }.getOrNull()
        } ?: return null
        val data = json.get("data")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        val clips = pick(HelixParser.parseClips(data, Enums.RowType.VOD_CHANNEL_HIGHLIGHTS, live.avatarUrl ?: ""))
        synchronized(cache) { cache[key] = Entry(now, clips) }
        return clips
    }
}
