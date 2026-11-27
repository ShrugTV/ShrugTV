package com.shrug.shrugtv.ui.tv.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.shrug.shrugtv.model.Movie
import com.shrug.shrugtv.prefs.Prefs

/**
 * Recordings you have watched, in lists (list part): like opened clips (`pref_clips_seen`), a
 * recording counts as watched from 90 % on and is drawn normally, dimmed (default) or left out, chosen under
 * Settings → Home → "Recordings I watched". Applies to the channel page and the home rows; the progress comes from the
 * saved resume point ([Movie.resumeProgressPercent]).
 */
object VodSeen {
    const val WATCHED_PERCENT = 90

    enum class Mode(val key: String) { SHOW("show"), DIM("dim"), HIDE("hide") }

    fun mode(ctx: Context): Mode {
        val v = Prefs.vodsSeen.get(ctx)
        return Mode.values().firstOrNull { it.key == v } ?: Mode.DIM
    }

    /** A recording (not a clip, not live) watched to at least [WATCHED_PERCENT]. */
    fun watched(m: Movie): Boolean =
        !m.isClip && m.itemType != com.shrug.shrugtv.model.Enums.ContentType.STREAM &&
            m.itemType != com.shrug.shrugtv.model.Enums.ContentType.CHANNEL &&
            m.itemType != com.shrug.shrugtv.model.Enums.ContentType.GAME &&
            m.resumeProgressPercent >= WATCHED_PERCENT

    /** [list] without the watched recordings when the setting says "hide", else unchanged. */
    fun filter(ctx: Context, list: List<Movie>): List<Movie> =
        if (mode(ctx) == Mode.HIDE) list.filterNot { watched(it) } else list

    /** Whether [m] is drawn dimmed. */
    fun dimmed(mode: Mode, m: Movie): Boolean = mode == Mode.DIM && watched(m)
}

/** The setting, read once per composition of the caller. */
@Composable
fun rememberVodSeenMode(): VodSeen.Mode {
    val ctx = LocalContext.current
    return remember { VodSeen.mode(ctx) }
}
