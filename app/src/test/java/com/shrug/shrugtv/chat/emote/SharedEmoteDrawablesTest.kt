package com.shrug.shrugtv.chat.emote

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** One loaded image per emote, every message a copy sharing it. */
@RunWith(RobolectricTestRunner::class)
class SharedEmoteDrawablesTest {
    private val res = ApplicationProvider.getApplicationContext<android.content.Context>().resources

    @After
    fun tearDown() = SharedEmoteDrawables.clear()

    private fun image() = BitmapDrawable(res, Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888))

    @Test
    fun `many messages with the same emote load it once and share its image`() {
        var loads = 0
        val copies = (1..50).map {
            SharedEmoteDrawables.get("https://cdn.7tv.app/emote/x/2x.webp|50", res) { loads++; SharedEmoteDrawables.Held(null, image()) }!!
        }
        assertEquals(1, loads)
        // each message has its own drawable (own bounds and callback) ...
        assertNotSame(copies[0], copies[1])
        // ... over the same pixels
        assertSame((copies[0] as BitmapDrawable).bitmap, (copies[49] as BitmapDrawable).bitmap)
    }

    @Test
    fun `a failed load is not remembered`() {
        var loads = 0
        assertNull(SharedEmoteDrawables.get("a|50", res) { loads++; null })
        SharedEmoteDrawables.get("a|50", res) { loads++; SharedEmoteDrawables.Held(null, image()) }
        assertEquals(2, loads)
        assertEquals(1, SharedEmoteDrawables.size())
    }

    @Test
    fun `another height is another image, the cache stays within its limit`() {
        SharedEmoteDrawables.get("a|50", res) { SharedEmoteDrawables.Held(null, image()) }
        SharedEmoteDrawables.get("a|40", res) { SharedEmoteDrawables.Held(null, image()) }
        assertEquals(2, SharedEmoteDrawables.size())
        repeat(SharedEmoteDrawables.MAX + 50) { i -> SharedEmoteDrawables.get("e$i|50", res) { SharedEmoteDrawables.Held(null, image()) } }
        assertEquals(SharedEmoteDrawables.MAX, SharedEmoteDrawables.size())
    }
}
