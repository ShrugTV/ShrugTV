package com.shrug.shrugtv.ui.tv.player

import android.os.SystemClock
import android.util.Log

/**
 * Counts the frames animated chat emotes deliver, shown in the player's stats overlay:
 * frames per second over all emotes and the number of different emotes that moved, so "each" can be
 * read off. Main thread only (drawable callbacks and the stats timer both run there).
 */
object EmoteFrameMeter {
    private const val WINDOW_MS = 3_000L
    private var windowStart = SystemClock.uptimeMillis()
    private var frames = 0
    /** Frames per emote in the current window: one emote's own rate can be compared with its stored rate. */
    private val perEmote = HashMap<Int, Int>()
    /** Animations started and not stopped yet; weak, so a forgotten one still counts until it is collected. */
    private val running = java.util.WeakHashMap<Any, Boolean>()
    private var windows = 0

    /** Last finished window: frames per second, number of emotes. */
    var framesPerSecond = 0f
        private set
    var emotes = 0
        private set
    /** Last finished window: the median and the highest frame rate of a single emote. */
    var median = 0f
        private set
    var fastest = 0f
        private set

    fun started(drawable: Any) { running[drawable] = true }
    fun stopped(drawable: Any) { running.remove(drawable) }

    fun tick(drawable: Any) {
        roll()
        frames++
        val id = System.identityHashCode(drawable)
        perEmote[id] = (perEmote[id] ?: 0) + 1
    }

    /** Called by the stats timer, so a stalled chat shows 0 instead of the last value. */
    fun roll() {
        val now = SystemClock.uptimeMillis()
        val span = now - windowStart
        if (span >= WINDOW_MS) {
            framesPerSecond = frames * 1000f / span
            emotes = perEmote.size
            // Each emote's own rate: the mean over all is pulled down by emotes that were visible only part of the window
            val rates = perEmote.values.map { it * 1000f / span }.sorted()
            fastest = rates.lastOrNull() ?: 0f
            median = if (rates.isEmpty()) 0f else rates[rates.size / 2]
            frames = 0
            perEmote.clear()
            windowStart = now
            // Every 30 s one line, readable without the stats overlay: logcat -s EmoteMeter
            if (++windows % 10 == 0 || Log.isLoggable("EmoteMeter", Log.DEBUG)) {
                Log.i("EmoteMeter", "fps=%.1f emotes=%d median=%.1f fastest=%.1f running=%d".format(framesPerSecond, emotes, median, fastest, running.size))
            }
        }
    }
}
