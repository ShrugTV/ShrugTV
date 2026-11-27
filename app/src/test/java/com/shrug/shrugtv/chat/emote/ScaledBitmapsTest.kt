package com.shrug.shrugtv.chat.emote

import android.graphics.Bitmap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** One scaled emote bitmap per address and height for all chat messages. */
@RunWith(RobolectricTestRunner::class)
class ScaledBitmapsTest {
    @After
    fun tearDown() = ScaledBitmaps.clear()

    private fun src() = Bitmap.createBitmap(28, 28, Bitmap.Config.ARGB_8888)

    @Test
    fun `the same emote in many messages shares one bitmap`() {
        val first = ScaledBitmaps.get("https://cdn.7tv.app/emote/a/1x.webp|56", src(), 56, 56)
        repeat(500) {
            assertSame(first, ScaledBitmaps.get("https://cdn.7tv.app/emote/a/1x.webp|56", src(), 56, 56))
        }
        assertEquals(56, first.height)
        assertEquals(1, ScaledBitmaps.size())
    }

    @Test
    fun `another emote or another height is its own bitmap`() {
        val a = ScaledBitmaps.get("a|56", src(), 56, 56)
        assertNotSame(a, ScaledBitmaps.get("b|56", src(), 56, 56))
        assertNotSame(a, ScaledBitmaps.get("a|40", src(), 40, 40))
        assertEquals(3, ScaledBitmaps.size())
    }

    @Test
    fun `the cache stays within its limit`() {
        val each = 56 * 56 * 4
        repeat(ScaledBitmaps.MAX_BYTES / each + 200) { ScaledBitmaps.get("e$it|56", src(), 56, 56) }
        assertTrue(ScaledBitmaps.size() <= ScaledBitmaps.MAX_BYTES / each)
    }
}
