package com.shrug.shrugtv.ui.tv.components

import com.shrug.shrugtv.model.Enums
import com.shrug.shrugtv.model.Movie
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which recordings count as watched in lists. */
class VodSeenTest {

    private fun vod(at: Int, length: Int = 100) = Movie().apply {
        itemType = Enums.ContentType.VOD
        durationSeconds = length
        setResumePositionSeconds(at)
    }

    @Test
    fun `a recording counts as watched from 90 percent on`() {
        assertTrue(VodSeen.watched(vod(90)))
        assertTrue(VodSeen.watched(vod(100)))
        assertFalse(VodSeen.watched(vod(89)))
        assertFalse(VodSeen.watched(vod(0)))
    }

    @Test
    fun `clips and live streams never count`() {
        assertFalse(VodSeen.watched(vod(95).apply { setClipSlug("x") }))
        assertFalse(VodSeen.watched(vod(95).apply { itemType = Enums.ContentType.STREAM }))
    }

    @Test
    fun `only the dim setting dims`() {
        assertTrue(VodSeen.dimmed(VodSeen.Mode.DIM, vod(95)))
        assertFalse(VodSeen.dimmed(VodSeen.Mode.SHOW, vod(95)))
        assertFalse(VodSeen.dimmed(VodSeen.Mode.HIDE, vod(95)))
    }
}
