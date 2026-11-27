package com.shrug.shrugtv.chat.emote

import android.graphics.Bitmap
import android.util.LruCache

/**
 * Emote images scaled to the chat size, one per image address and height for the whole app.
 *
 * Before, every chat message scaled its own copy of a small 7TV/BTTV/FFZ emote. Glide hands out hardware bitmaps, so
 * each copy was a hardware bitmap too: graphics memory the Java heap does not see, so nothing pushed the garbage
 * collector, and on the Fire TV the graphics memory grew from 123 to 366 MB in 45 minutes of chat. Now all messages
 * share one bitmap; a bitmap that falls out of the cache is not recycled, a message may still show it.
 */
object ScaledBitmaps {
    /** Room for some thousand emotes at chat size (a 56 px emote is about 12 KB). */
    const val MAX_BYTES = 24 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(MAX_BYTES) {
        // allocationByteCount is not reliable for hardware bitmaps: count four bytes a pixel
        override fun sizeOf(key: String, value: Bitmap) = value.width * value.height * 4
    }

    /** [source] scaled to [width] x [height], made once per [key] (image address and height). */
    fun get(key: String, source: Bitmap, width: Int, height: Int): Bitmap = synchronized(cache) {
        cache.get(key) ?: Bitmap.createScaledBitmap(source, width, height, false).also { cache.put(key, it) }
    }

    /** Number of bitmaps held, for tests and diagnostics. */
    fun size(): Int = synchronized(cache) { cache.snapshot().size }

    internal fun clear() = synchronized(cache) { cache.evictAll() }
}
