package com.shrug.shrugtv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VodNextTest {

    private fun c(id: Long, at: Long?, percent: Int = 0) = VodNext.Candidate(id, at, percent)

    @Test
    fun `the oldest newer recording follows`() {
        val list = listOf(c(5, 500), c(4, 400), c(3, 300), c(2, 200))
        assertEquals(3L, VodNext.pick(2, 200, list)?.videoId)
    }

    @Test
    fun `the time of the current recording is taken from the list`() {
        val list = listOf(c(5, 500), c(4, 400), c(3, 300))
        assertEquals(5L, VodNext.pick(4, null, list)?.videoId)
    }

    @Test
    fun `watched recordings are skipped`() {
        val list = listOf(c(5, 500), c(4, 400, 95), c(3, 300, 90), c(2, 200))
        assertEquals(5L, VodNext.pick(2, 200, list)?.videoId)
    }

    @Test
    fun `a recording below the mark still follows`() {
        val list = listOf(c(3, 300, 89), c(2, 200))
        assertEquals(3L, VodNext.pick(2, 200, list)?.videoId)
    }

    @Test
    fun `after the newest the newest older one follows`() {
        val list = listOf(c(5, 500), c(4, 400, 100), c(3, 300), c(2, 200))
        assertEquals(3L, VodNext.pick(5, 500, list)?.videoId)
    }

    @Test
    fun `nothing without a time or without open recordings`() {
        assertNull(VodNext.pick(9, null, listOf(c(5, 500))))
        assertNull(VodNext.pick(5, 500, listOf(c(5, 500), c(4, 400, 100))))
        assertNull(VodNext.pick(5, 500, listOf(c(4, null))))
    }
}
