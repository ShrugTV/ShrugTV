package com.shrug.shrugtv.clips

import com.shrug.shrugtv.model.StreamQuality

/**
 * Which file of a clip the muted preview on the clips page plays: not the biggest one, the preview is
 * small or sits under shades, and a weak device decodes it beside the page.
 */
object ClipPreviewSource {
    /** The preview stays at or below this many lines when the clip offers it. */
    const val MAX_HEIGHT = 720

    /**
     * The highest quality up to [maxHeight] lines ("720p60", "480p"), else the smallest one there is; "Auto" (the first entry,
     * the same file as the best) only when no entry says its size. Null when there is no address at all.
     */
    fun pick(qualities: List<StreamQuality>?, maxHeight: Int = MAX_HEIGHT): String? {
        val usable = qualities.orEmpty().filter { !it.url.isNullOrEmpty() }
        val sized = usable.mapNotNull { q -> height(q.name)?.let { it to q } }
        if (sized.isEmpty()) return usable.firstOrNull()?.url
        val fits = sized.filter { it.first <= maxHeight }.maxByOrNull { it.first } ?: sized.minByOrNull { it.first }
        return fits?.second?.url
    }

    /** The lines of a quality name ("1080p60" -> 1080), null for "Auto" and other names without a size. */
    fun height(name: String?): Int? {
        if (name == null) return null
        val at = name.indexOf('p')
        return if (at > 0) name.substring(0, at).toIntOrNull() else null
    }
}
