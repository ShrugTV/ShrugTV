package com.shrug.shrugtv.player

/**
 * Which recording of the channel follows when a recording ends.
 *
 * The player does not know the list a recording was opened from (the pages hand none over), so it asks the channel's
 * past broadcasts and continues in time: the oldest recording that is newer than the one that ended, so a run of
 * broadcasts plays in order. If the one that ended is the newest, the newest older one follows. Recordings watched to
 * [SEEN_PERCENT] or more are skipped either way.
 */
object VodNext {

    /** From this share of the length on a recording counts as watched (the mark of ). */
    const val SEEN_PERCENT = 90

    class Candidate(val videoId: Long, val publishedAtMs: Long?, val progressPercent: Int) {
        val seen: Boolean get() = progressPercent >= SEEN_PERCENT
    }

    /**
     * The recording to play after [currentId] (published at [currentPublishedMs], or looked up in [candidates]), or null:
     * no time to order by, or nothing left that has not been watched.
     */
    fun pick(currentId: Long, currentPublishedMs: Long?, candidates: List<Candidate>): Candidate? {
        val at = currentPublishedMs ?: candidates.firstOrNull { it.videoId == currentId }?.publishedAtMs ?: return null
        val open = candidates.filter { it.videoId != currentId && !it.seen && it.publishedAtMs != null }
        return open.filter { it.publishedAtMs!! > at }.minByOrNull { it.publishedAtMs!! }
            ?: open.filter { it.publishedAtMs!! < at }.maxByOrNull { it.publishedAtMs!! }
    }
}
