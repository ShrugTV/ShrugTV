package com.shrug.shrugtv.notification

/**
 * Quiet hours for "X is live" notices: between [from] and [to] (full hours, 0..23)
 * nothing pops up. A window that crosses midnight (22 to 7) works; from == to means the whole day is quiet.
 */
object QuietHours {
    fun isQuiet(enabled: Boolean, from: Int, to: Int, hour: Int): Boolean {
        if (!enabled) return false
        val f = from.coerceIn(0, 23)
        val t = to.coerceIn(0, 23)
        return when {
            f == t -> true
            f < t -> hour in f until t
            else -> hour >= f || hour < t
        }
    }
}
