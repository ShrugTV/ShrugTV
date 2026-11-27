package com.shrug.shrugtv.prefs

import android.content.SharedPreferences

/**
 * One-time moves of values whose setting was folded into another one. Keys are never renamed
 * (`docs/entwicklung.md`, "Adding a setting"); a key that is given up is read once into the one that
 * stays and then removed, so the step runs only while the old key is still there.
 */
object PrefsMigration {

    /**
     * "Try to predict chat bots" (moderators whose name ends in "bot") was a second switch next to
     * "Hide bots" until ; both mean "keep bots out of the chat", so `pref_filter_bots`
     * now covers it. Whoever had the old switch on gets "Hide bots" on.
     */
    const val LEGACY_FILTER_PREDICT_BOTS = "pref_filter_predit_bots"

    /** Runs every pending step; cheap when there is nothing to do. */
    @JvmStatic
    fun run(p: SharedPreferences) {
        if (!p.contains(LEGACY_FILTER_PREDICT_BOTS)) return
        val e = p.edit()
        val predicted = runCatching { p.getBoolean(LEGACY_FILTER_PREDICT_BOTS, false) }.getOrDefault(false)
        if (predicted) Prefs.filterBots.put(e, true)
        e.remove(LEGACY_FILTER_PREDICT_BOTS).apply()
    }
}
