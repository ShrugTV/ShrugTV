package com.shrug.shrugtv.clips

import android.content.Context
import com.shrug.shrugtv.api.helix.HelixApi
import com.shrug.shrugtv.api.helix.HelixParser
import com.shrug.shrugtv.apihelpers.FollowedChannels
import com.shrug.shrugtv.model.Enums
import com.shrug.shrugtv.model.Movie
import com.shrug.shrugtv.prefs.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/** How far back clips count. [days] null = no limit. */
enum class ClipsPeriod(val key: String, val days: Long?) {
    DAY("day", 1), WEEK("week", 7), MONTH("month", 30), YEAR("year", 365), ALL("all", null);

    companion object {
        fun of(key: String?) = values().firstOrNull { it.key == key } ?: WEEK
    }
}

/** Whose clips are shown: every followed channel or only the starred ones. */
enum class ClipsSource(val key: String) {
    FOLLOWED("followed"), FAVORITES("favorites");

    companion object {
        fun of(key: String?) = values().firstOrNull { it.key == key } ?: FOLLOWED
    }
}

/** What happens to clips that were opened before. */
enum class ClipsSeenMode(val key: String) {
    SHOW("show"), DIM("dim"), HIDE("hide");

    companion object {
        /** Dimmed is the default (Michael, 2026-11-14). */
        fun of(key: String?) = values().firstOrNull { it.key == key } ?: DIM
    }
}

enum class ClipsSort(val key: String) {
    /** [RELATIVE]: views compared with the other clips of the same channel, so big and small channels meet on equal terms. */
    VIEWS("views"), RELATIVE("relative"), NEWEST("newest"), SHORTEST("shortest");

    companion object {
        fun of(key: String?) = values().firstOrNull { it.key == key } ?: VIEWS
    }
}

/** The settings of the clips page and row, read from the preferences (pref_clips_*). */
data class ClipsSettings(
    val source: ClipsSource = ClipsSource.FOLLOWED,
    val period: ClipsPeriod = ClipsPeriod.WEEK,
    val sort: ClipsSort = ClipsSort.VIEWS,
    val count: Int = 20,
    /** Show, dim or hide clips that were opened before. */
    val seen: ClipsSeenMode = ClipsSeenMode.DIM,
    /** After a clip ends, the next one of the list starts. */
    val autoplay: Boolean = true,
    /** Clips that were opened before are left out of that queue. */
    val queueSkipsSeen: Boolean = true,
    /** Only clips in the stream language of the app settings ("app"), or every language ("all"). */
    val onlyAppLanguage: Boolean = false,
) {
    companion object {
        /** The first version of this option (1.45.0) was a switch: on meant hide. */
        const val PREF_HIDE_SEEN_OLD = "pref_clips_hide_seen"

        fun load(ctx: Context): ClipsSettings {
            val p = Prefs.of(ctx)
            return ClipsSettings(
                ClipsSource.of(Prefs.clipsSource.get(p)),
                ClipsPeriod.of(Prefs.clipsPeriod.get(p)),
                ClipsSort.of(Prefs.clipsSort.get(p)),
                Prefs.clipsCount.get(p).coerceIn(10, 100),
                // Unset matters here: then the old switch of 1.45.0 decides, so this one stays a raw read
                p.getString(Prefs.clipsSeen.key, null)?.let { ClipsSeenMode.of(it) }
                    ?: if (p.getBoolean(PREF_HIDE_SEEN_OLD, false)) ClipsSeenMode.HIDE else ClipsSeenMode.DIM,
                Prefs.clipsAutoplay.get(p),
                Prefs.clipsQueueSeen.get(p) != "include",
                Prefs.clipsLanguage.get(p) == "app",
            )
        }

        fun rowOn(ctx: Context) = Prefs.clipsRow.get(ctx)

        /** The row "Surprise hits": the clips of the last 7 days that stand out most from the usual of their own channel (off by default). */
        fun surpriseRowOn(ctx: Context) = Prefs.clipsSurpriseRow.get(ctx)

        /** The choices behind that row: the settings of the page, but always last week and relative to the channel. */
        fun surprise(ctx: Context) = load(ctx).copy(period = ClipsPeriod.WEEK, sort = ClipsSort.RELATIVE)
    }
}

/**
 * The best clips of the channels you follow, in one list. Twitch hands out clips for one channel per
 * request, so this asks for each channel (a few at a time), puts the answers together and sorts them. The pure parts
 * ([startedAt], [channels], [merge]) are tested; [load] is the network wrapper around them.
 */
object ClipsFeed {
    /** More channels than this are not asked: favorites first, then the rest in the order of the follow list. */
    const val MAX_CHANNELS = 100

    /** Clips asked for per channel; together they make the pool the page and the queue draw from. */
    const val PER_CHANNEL = 30

    /** Sorting relative to the channel needs more clips of each channel to know what is usual there. */
    const val PER_CHANNEL_RELATIVE = 50

    /** The pool is cut to the best this many clips after merging, so memory stays small. */
    const val POOL_LIMIT = 500

    /** How many clips show after the page grew by one step: [step] more, never more than the pool holds. */
    fun grow(shown: Int, step: Int, total: Int): Int = minOf(total, shown + step.coerceAtLeast(1))
    private const val PARALLEL = 6
    private const val CACHE_MS = 10 * 60 * 1000L

    /** RFC 3339 start of the period for the `started_at` parameter; null = no limit. */
    fun startedAt(period: ClipsPeriod, now: Instant): String? =
        period.days?.let { now.minus(Duration.ofDays(it)).truncatedTo(ChronoUnit.SECONDS).toString() }

    /** The channels to ask: favorites only, or all, at most [MAX_CHANNELS] and favorites in front. */
    fun channels(followed: List<Movie>, source: ClipsSource): List<Movie> {
        val usable = followed.filter { it.userId > 0 }
        val chosen = if (source == ClipsSource.FAVORITES) usable.filter { it.isFavorite } else usable
        return (chosen.filter { it.isFavorite } + chosen.filter { !it.isFavorite }).take(MAX_CHANNELS)
    }

    /**
     * The answers of all channels as one list: every clip once, without [seen] ones and, with [language], only clips in
     * that language (blank = any), sorted, at most [limit].
     */
    fun merge(lists: List<List<Movie>>, sort: ClipsSort, limit: Int, seen: Set<String> = emptySet(), language: String = ""): List<Movie> {
        val score = if (sort == ClipsSort.RELATIVE) relativeScores(lists) else emptyMap()
        val all = lists.flatten().distinctBy { it.clipSlug }
            .filter { it.clipSlug !in seen }
            .filter { language.isBlank() || it.languageCode.equals(language, ignoreCase = true) }
        val sorted = when (sort) {
            ClipsSort.VIEWS -> all.sortedByDescending { it.viewCount }
            ClipsSort.RELATIVE -> all.sortedByDescending { score[it.clipSlug] ?: 0.0 }
            ClipsSort.NEWEST -> all.sortedByDescending { it.publishedAt?.toInstant() ?: Instant.EPOCH }
            // Clips without a length (0) go last
            ClipsSort.SHORTEST -> all.sortedBy { if (it.durationSeconds > 0) it.durationSeconds else Int.MAX_VALUE }
        }
        return sorted.take(limit)
    }

    /**
     * Views of each clip divided by what is usual for its channel: the middle value of that channel's clips in the period
     * (with fewer than five clips the average). A clip with 3 000 views in a small channel whose clips usually have 300 then
     * beats one with 20 000 views in a big channel where 10 000 is normal.
     */
    internal fun relativeScores(lists: List<List<Movie>>): Map<String, Double> {
        val out = HashMap<String, Double>()
        for (list in lists) {
            if (list.isEmpty()) continue
            val views = list.map { it.viewCount.toDouble() }.sorted()
            val usual = maxOf(1.0, if (views.size >= 5) views[(views.size - 1) / 2] else views.average())
            for (c in list) out.putIfAbsent(c.clipSlug, c.viewCount / usual)
        }
        return out
    }

    /** What the pages added up to for one set of choices: the clips per channel, where the next page of each channel starts. */
    private class PoolState(
        val at: Long,
        val settings: ClipsSettings,
        val from: String?,
        val channels: List<Movie>,
        val lists: MutableMap<Int, List<Movie>> = HashMap(),
        val cursors: MutableMap<Int, String> = HashMap(),
        var pool: List<Movie> = emptyList(),
        var limit: Int = POOL_LIMIT,
    )

    /** The pool never grows past this many clips, however far the list is scrolled. */
    const val HARD_LIMIT = 2000

    /** The last few pools by settings, so switching back and forth between the choices on the page is instant. */
    private val cache = object : LinkedHashMap<String, PoolState>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PoolState>?) = size > 4
    }
    @Volatile private var lastPool: List<Movie> = emptyList()
    private val gameNames = java.util.concurrent.ConcurrentHashMap<Long, String>()

    /** The same settings within ten minutes answer from memory (the home screen and the page share it). */
    fun forget() { synchronized(cache) { cache.clear() } }

    /** The pool of the last [load] (empty before). */
    fun pool(): List<Movie> = lastPool

    /** The pool for [s] if it was loaded (and not forgotten) before; no network. The queue of a home row plays on through it. */
    fun cachedPool(ctx: Context, s: ClipsSettings): List<Movie> = synchronized(cache) { cache[keyOf(ctx, s)] }?.pool ?: emptyList()

    /** Categories that clips of the pool belong to, the one with most clips first: the choices of the category button. */
    fun categories(pool: List<Movie>): List<Pair<Long, String>> =
        pool.filter { it.gameId > 0 && it.gameName.isNotBlank() }
            .groupBy { it.gameId }
            .entries.sortedByDescending { it.value.size }
            .map { it.key to it.value.first().gameName }

    private fun languageOf(s: ClipsSettings) = if (s.onlyAppLanguage) com.shrug.shrugtv.session.AppState.streamLanguageCode.orEmpty() else ""

    private fun keyOf(ctx: Context, s: ClipsSettings): String {
        val seenSize = if (s.seen != ClipsSeenMode.SHOW) ClipsSeen.all(ctx).size else 0
        return "${com.shrug.shrugtv.session.AppState.userId}|${s.source}|${s.period}|${s.sort}|${languageOf(s)}|${s.seen}|$seenSize"
    }

    /** The pool out of what was fetched so far: merged, filtered, sorted, cut to the limit, with the dimming marks. */
    private fun rebuild(ctx: Context, st: PoolState) {
        val s = st.settings
        val seenAll = if (s.seen != ClipsSeenMode.SHOW) ClipsSeen.all(ctx) else emptySet()
        val seen = if (s.seen == ClipsSeenMode.HIDE) seenAll else emptySet()
        val pool = merge(st.lists.values.toList(), s.sort, st.limit, seen, languageOf(s))
        for (c in pool) c.clipSeen = s.seen == ClipsSeenMode.DIM && c.clipSlug in seenAll
        st.pool = pool
    }

    /** Looks up the names of the categories of [pool] (one call per hundred) and writes them into the clips. */
    private suspend fun nameGames(ctx: Context, pool: List<Movie>) {
        val missing = pool.map { it.gameId }.filter { it > 0 && !gameNames.containsKey(it) }.distinct()
        if (missing.isNotEmpty()) withContext(Dispatchers.IO) {
            runCatching {
                missing.chunked(100).forEach { chunk ->
                    HelixApi.getGamesByIds(ctx, chunk)?.getAsJsonArray("data")?.forEach { e ->
                        val o = e.asJsonObject
                        val id = o.get("id")?.asString?.toLongOrNull()
                        val name = o.get("name")?.asString
                        if (id != null && !name.isNullOrBlank()) gameNames[id] = name
                    }
                }
            }
        }
        for (c in pool) gameNames[c.gameId]?.let { c.gameName = it }
    }

    /** One page of clips of one channel and where the next one starts (null = no more). */
    private fun fetchPage(ctx: Context, ch: Movie, from: String?, first: Int, after: String?): Pair<List<Movie>, String?> =
        fetchPageOrNull(ctx, ch, from, first, after) ?: (emptyList<Movie>() to null)

    /** Like [fetchPage], but null if the request failed (no answer or an exception) instead of an empty page. */
    private fun fetchPageOrNull(ctx: Context, ch: Movie, from: String?, first: Int, after: String?): Pair<List<Movie>, String?>? = try {
        val json = HelixApi.getClipsOfBroadcasterSync(ctx, ch.userId.toString(), from, first, after)
            ?: throw java.io.IOException("no answer")
        val data = json.get("data")
        val clips = if (data != null && data.isJsonArray) HelixParser.parseClips(data.asJsonArray, Enums.RowType.VOD_CHANNEL_HIGHLIGHTS, ch.avatarUrl ?: "") else emptyList<Movie>()
        val cursor = json.get("pagination")?.takeIf { it.isJsonObject }?.asJsonObject?.get("cursor")?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotEmpty() }
        clips to (if (clips.size >= first) cursor else null)
    } catch (e: Exception) {
        null
    }

    /** The pool of a load, and whether the channels asked all stayed without an answer (an error, not "no clips"). */
    class Loaded(val pool: List<Movie>, val failed: Boolean)

    suspend fun load(ctx: Context, s: ClipsSettings, force: Boolean = false): List<Movie> = loadResult(ctx, s, force).pool

    suspend fun loadResult(ctx: Context, s: ClipsSettings, force: Boolean = false): Loaded {
        val key = keyOf(ctx, s)
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(cache) { cache[key] }?.let { st ->
            if (!force && now - st.at < CACHE_MS) { lastPool = st.pool; return Loaded(st.pool, false) }
        }
        val followedResult = FollowedChannels.getListResult(ctx)
        // Signed out, the followed list always fails: that is no network error, the page stays "no clips"
        val followedFailed = followedResult.failed && com.shrug.shrugtv.session.AppState.accessToken.isNotEmpty() && !com.shrug.shrugtv.session.AppState.userLoggedOut
        val followed = followedResult.list.toList()
        val asked = channels(followed, s.source)
        val st = PoolState(now, s, startedAt(s.period, Instant.now()), asked)
        val gate = Semaphore(PARALLEL)
        val first = if (s.sort == ClipsSort.RELATIVE) PER_CHANNEL_RELATIVE else PER_CHANNEL
        val pages = withContext(Dispatchers.IO) {
            coroutineScope { asked.map { ch -> async { gate.withPermit { fetchPageOrNull(ctx, ch, st.from, first, null) } } }.awaitAll() }
        }
        asked.zip(pages).forEach { (ch, page) ->
            st.lists[ch.userId] = page?.first ?: emptyList()
            page?.second?.let { st.cursors[ch.userId] = it }
        }
        rebuild(ctx, st)
        nameGames(ctx, st.pool)
        // A failed run (no answer from any channel) is not kept, the next call asks again
        if (st.pool.isNotEmpty()) {
            synchronized(cache) { cache[key] = st }
            lastPool = st.pool
        }
        // Failed: no channel answered, or the followed channels themselves could not be read
        return Loaded(st.pool, st.pool.isEmpty() && ((asked.isNotEmpty() && pages.all { it == null }) || (asked.isEmpty() && followedFailed)))
    }

    /** Whether the channels have more pages of clips to fetch for [s] (and the pool may still grow). */
    fun canLoadMore(ctx: Context, s: ClipsSettings): Boolean =
        synchronized(cache) { cache[keyOf(ctx, s)] }?.let { it.cursors.isNotEmpty() && it.limit < HARD_LIMIT } == true

    /**
     * The next page of every channel that has one, added to the pool: "keeps going" does not stop at the
     * best clips of the first request. Returns the new pool; the old one if there is nothing more.
     */
    suspend fun more(ctx: Context, s: ClipsSettings): List<Movie> {
        val st = synchronized(cache) { cache[keyOf(ctx, s)] } ?: return emptyList()
        if (st.cursors.isEmpty() || st.limit >= HARD_LIMIT) return st.pool
        val gate = Semaphore(PARALLEL)
        val ids = st.cursors.keys.toList()
        val pages = withContext(Dispatchers.IO) {
            coroutineScope {
                ids.map { id ->
                    async {
                        gate.withPermit {
                            val ch = st.channels.firstOrNull { it.userId == id }
                            if (ch == null) emptyList<Movie>() to null else fetchPage(ctx, ch, st.from, PER_CHANNEL, st.cursors[id])
                        }
                    }
                }.awaitAll()
            }
        }
        ids.zip(pages).forEach { (id, page) ->
            st.lists[id] = st.lists[id].orEmpty() + page.first
            if (page.second != null) st.cursors[id] = page.second!! else st.cursors.remove(id)
        }
        st.limit = minOf(HARD_LIMIT, st.limit + POOL_LIMIT)
        rebuild(ctx, st)
        nameGames(ctx, st.pool)
        lastPool = st.pool
        return st.pool
    }
}
