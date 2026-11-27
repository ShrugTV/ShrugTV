package com.shrug.shrugtv.chat.emote

import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.util.LruCache

/**
 * One loaded image per emote address and height for the whole app; every chat message gets its own copy of it
 *.
 *
 * Before, every message loaded its emotes from Glide anew, and an animated WebP often came back with its own frame
 * loader: the heap dump of the Fire TV (2026-11-25) showed one `WebpFrameLoader` with its own two frame bitmaps per
 * animated emote per message, so twelve times the same emote in one message were decoded, kept and uploaded to the
 * GPU twelve times per frame. A copy made with `ConstantState.newDrawable()` shares the frame loader (and for a still
 * image the bitmap): one decode per frame for all messages. The original stays here together with what keeps Glide
 * from recycling it ([Held.keep], the request target), and is itself never put into a message.
 */
object SharedEmoteDrawables {
    /** Different emotes kept; a busy chat uses a few hundred. */
    const val MAX = 400

    class Held(val keep: Any?, val drawable: Drawable)

    private val cache = LruCache<String, Held>(MAX)

    /**
     * The copy for one message: from the image held for [key] (address and height), or from [load], which returns the
     * image and what has to stay referenced with it; null if loading failed (not remembered, the next message tries
     * again).
     */
    fun get(key: String, res: Resources?, load: () -> Held?): Drawable? {
        val held = cache.get(key) ?: (load() ?: return null).also { cache.put(key, it) }
        return copy(held.drawable, res)
    }

    private fun copy(d: Drawable, res: Resources?): Drawable =
        d.constantState?.let { if (res != null) it.newDrawable(res) else it.newDrawable() } ?: d

    fun size(): Int = cache.size()

    internal fun clear() = cache.evictAll()
}
