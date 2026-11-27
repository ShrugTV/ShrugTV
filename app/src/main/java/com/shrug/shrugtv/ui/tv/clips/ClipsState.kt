package com.shrug.shrugtv.ui.tv.clips

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.preference.PreferenceManager
import com.shrug.shrugtv.clips.ClipQueue
import com.shrug.shrugtv.clips.ClipsFeed
import com.shrug.shrugtv.clips.ClipsSeen
import com.shrug.shrugtv.clips.ClipsSeenMode
import com.shrug.shrugtv.clips.ClipsSettings
import com.shrug.shrugtv.clips.ClipsSort
import com.shrug.shrugtv.model.Movie
import com.shrug.shrugtv.prefs.Prefs

/**
 * How the clips page looks: four layouts over the same list, chosen under Settings → Home screen →
 * Clips → Layout. Everything but the look (loading, choices, seen clips, loading more, the queue) is in [rememberClipsState].
 */
enum class ClipsLayout(val key: String) {
    FINE("fine"), STAGE("stage"), CHARTS("charts"), TV("tv");

    companion object {
        fun of(key: String?) = values().firstOrNull { it.key == key } ?: FINE
        fun load(ctx: android.content.Context) = of(Prefs.clipsLayout.get(ctx))
    }
}

/** What every layout of the clips page reads and calls; built by [rememberClipsState]. */
class ClipsUi(
    val settings: ClipsSettings,
    /** The whole pool after category filter and seen setting; the queue is taken from it. */
    val shown: List<Movie>,
    /** The part of [shown] on screen so far; grows near the end, see [Watch]. */
    val visible: List<Movie>,
    val loading: Boolean,
    /** Every channel asked stayed without an answer: an error with "Try again", not "No clips". */
    val failed: Boolean,
    /** Loads the list again, bypassing the cache. */
    val retry: () -> Unit,
    val categories: List<Pair<Long, String>>,
    val gameFilter: Long,
    /** True when the order is a ranking (views or relative), so a rank number means something. */
    val ranked: Boolean,
    /** Counts the returns from the player; key the cards with it so dimmed clips update. */
    val resumes: Int,
    /** The layout puts this on what should have the focus when the page opens. */
    val firstFocus: FocusRequester,
    val change: (key: String, value: String) -> Unit,
    val pickCategory: (Long) -> Unit,
    private val openClip: (Movie) -> Unit,
    val menu: (Movie) -> Unit,
    private val watch: @Composable (Int) -> Unit,
) {
    /** Plays [m]; the clips after it in [queue] (default: the whole list as shown) play by themselves after it. */
    fun play(m: Movie, queue: List<Movie> = shown) {
        ClipQueue.set(queue)
        openClip(m)
    }

    /** Call inside the item at [index] of [visible]: near the end it grows the list and, if needed, fetches the next page. */
    @Composable
    fun Watch(index: Int) = watch(index)

    /** The next category after the chosen one: all → first → … → all. */
    fun nextCategory(): Long {
        val at = categories.indexOfFirst { it.first == gameFilter }
        return if (at + 1 < categories.size) categories[at + 1].first else 0L
    }

    fun categoryName(all: String): String = categories.firstOrNull { it.first == gameFilter }?.second ?: all
}

@Composable
fun rememberClipsState(resumes: Int, open: (Movie) -> Unit, contextMenu: (Movie) -> Unit): ClipsUi {
    val ctx = LocalContext.current.applicationContext
    val prefs = remember { PreferenceManager.getDefaultSharedPreferences(ctx) }
    var settings by remember { mutableStateOf(ClipsSettings.load(ctx)) }
    var clips by remember { mutableStateOf<List<Movie>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var retryTick by remember { mutableStateOf(0) }
    val firstFocus = remember { FocusRequester() }

    // The focus goes to the first card once, when the page opens; after a change of the choices it stays on the button, so the
    // next button can be pressed right away
    var focusedFirst by remember { mutableStateOf(false) }
    LaunchedEffect(settings, retryTick) {
        loading = true
        failed = false
        // A choice was changed: the old list goes away at once, otherwise it looks as if nothing happened while it loads
        clips = emptyList()
        val loaded = ClipsFeed.loadResult(ctx, settings, force = retryTick > 0)
        clips = loaded.pool
        failed = loaded.failed
        loading = false
    }
    LaunchedEffect(clips) {
        if (clips.isNotEmpty() && !focusedFirst) {
            focusedFirst = true
            // The target is laid out a moment after the list arrives (a cached list arrives at once): ask until it is there
            for (attempt in 1..15) {
                kotlinx.coroutines.delay(100)
                if (runCatching { firstFocus.requestFocus() }.isSuccess) break
            }
        }
    }
    // The page starts with one load of clips and grows by one load whenever you get near the end; the queue is the whole pool
    var visibleCount by remember(clips, settings) { mutableStateOf(settings.count) }
    // The category: all, or one of the categories the clips belong to (not remembered, a way to browse)
    var gameFilter by remember { mutableStateOf(0L) }
    val categories = remember(clips) { ClipsFeed.categories(clips) }
    if (gameFilter != 0L && categories.none { it.first == gameFilter }) gameFilter = 0L
    // What was opened since the list was built: hidden or dimmed according to the setting
    val shown = remember(clips, settings.seen, resumes, gameFilter) {
        val seen = if (settings.seen == ClipsSeenMode.SHOW) emptySet() else ClipsSeen.all(ctx)
        for (c in clips) c.clipSeen = settings.seen == ClipsSeenMode.DIM && c.clipSlug in seen
        val inGame = if (gameFilter == 0L) clips else clips.filter { it.gameId == gameFilter }
        if (settings.seen == ClipsSeenMode.HIDE) inGame.filter { it.clipSlug !in seen } else inGame
    }
    var loadingMore by remember { mutableStateOf(false) }
    val visible = shown.take(visibleCount)

    return ClipsUi(
        settings = settings,
        shown = shown,
        visible = visible,
        loading = loading,
        failed = failed,
        retry = { retryTick++ },
        categories = categories,
        gameFilter = gameFilter,
        ranked = settings.sort == ClipsSort.VIEWS || settings.sort == ClipsSort.RELATIVE,
        resumes = resumes,
        firstFocus = firstFocus,
        change = { key, value ->
            prefs.edit().putString(key, value).apply()
            settings = ClipsSettings.load(ctx)
        },
        pickCategory = { gameFilter = it },
        openClip = open,
        menu = contextMenu,
        watch = { i ->
            // Two rows before the end: the next load is already there when you get to it
            if (i >= visible.size - 10 && visible.size < shown.size) {
                LaunchedEffect(visible.size) { visibleCount = ClipsFeed.grow(visible.size, settings.count, shown.size) }
            }
            // Everything of the pool is on screen: ask the channels for their next page of clips
            // (not with a category chosen: that list is what the pool holds, fetching more for it would never stop)
            if (i >= visible.size - 10 && visible.size >= shown.size && gameFilter == 0L && !loadingMore && ClipsFeed.canLoadMore(ctx, settings)) {
                LaunchedEffect(visible.size, clips.size) {
                    if (!ClipsFeed.canLoadMore(ctx, settings)) return@LaunchedEffect
                    loadingMore = true
                    val more = ClipsFeed.more(ctx, settings)
                    if (more.size > clips.size) clips = more
                    loadingMore = false
                }
            }
        },
    )
}
