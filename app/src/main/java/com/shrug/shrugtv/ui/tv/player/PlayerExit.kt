package com.shrug.shrugtv.ui.tv.player

import android.os.SystemClock

/**
 * Leaving the player with Back. The way back to the home screen takes a moment (the players are
 * torn down on the main thread), so a Back pressed right after it used to land on the home screen and leave the app.
 * The player marks the moment; the home screen ignores a Back within [GUARD_MS].
 */
object PlayerExit {
    const val GUARD_MS = 1500L

    @Volatile
    private var at = 0L

    fun mark(now: Long = SystemClock.uptimeMillis()) {
        at = now
    }

    /** Whether the player was left less than [GUARD_MS] ago. */
    fun justLeft(now: Long = SystemClock.uptimeMillis()) = at != 0L && now - at in 0 until GUARD_MS
}

/**
 * After Android ended the process in the background it restores the player with an empty ViewModel
 *: the stream is started again only shortly after the player was left, a stream from hours ago is
 * rarely still wanted (and often offline), so then the app starts on the home screen.
 */
object PlayerRestore {
    const val KEY_LEFT_AT = "shrugtv_player_left_at"
    const val RESUME_WINDOW_MS = 15 * 60_000L

    /** [leftAtMs]: wall clock when the state was saved (0 = unknown). */
    fun resume(leftAtMs: Long, nowMs: Long): Boolean = leftAtMs > 0L && nowMs - leftAtMs in 0 until RESUME_WINDOW_MS
}
