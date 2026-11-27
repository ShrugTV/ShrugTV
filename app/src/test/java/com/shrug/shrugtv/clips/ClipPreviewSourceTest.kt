package com.shrug.shrugtv.clips

import com.shrug.shrugtv.model.StreamQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The file the muted preview of the clips page plays. */
class ClipPreviewSourceTest {
    private fun q(name: String) = StreamQuality(name, "https://clips.example/$name.mp4")

    @Test
    fun `the biggest quality up to 720 lines is chosen`() {
        val list = listOf(q("Auto"), q("1080p60"), q("720p60"), q("480p30"), q("360p30"))
        assertEquals("https://clips.example/720p60.mp4", ClipPreviewSource.pick(list))
    }

    @Test
    fun `without a small one the smallest there is plays`() {
        assertEquals("https://clips.example/1080p60.mp4", ClipPreviewSource.pick(listOf(q("Auto"), q("1440p60"), q("1080p60"))))
    }

    @Test
    fun `names without a size fall back to the first address`() {
        assertEquals("https://clips.example/Auto.mp4", ClipPreviewSource.pick(listOf(q("Auto"), q("source"))))
    }

    @Test
    fun `no qualities or no addresses give nothing`() {
        assertNull(ClipPreviewSource.pick(null))
        assertNull(ClipPreviewSource.pick(emptyList()))
        assertNull(ClipPreviewSource.pick(listOf(StreamQuality("720p", null), StreamQuality("480p", ""))))
    }

    @Test
    fun `the height is read from the quality name`() {
        assertEquals(1080, ClipPreviewSource.height("1080p60"))
        assertEquals(480, ClipPreviewSource.height("480p"))
        assertNull(ClipPreviewSource.height("Auto"))
        assertNull(ClipPreviewSource.height("audio_only"))
        assertNull(ClipPreviewSource.height(null))
    }
}
