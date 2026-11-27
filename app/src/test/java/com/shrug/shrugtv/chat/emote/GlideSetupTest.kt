package com.shrug.shrugtv.chat.emote

import android.os.Process
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Emote animation threads run at normal priority, not at Glide's background priority. */
@RunWith(RobolectricTestRunner::class)
class GlideSetupTest {
    @Test
    fun `animation threads run at normal priority`() {
        var seen = Int.MIN_VALUE
        val t = GlideSetup.animationThreadFactory().newThread { seen = Process.getThreadPriority(Process.myTid()) }
        t.start(); t.join(2000)
        assertEquals(Process.THREAD_PRIORITY_DEFAULT, seen)
        assertEquals(0, GlideSetup.ANIMATION_PRIORITY)
    }
}
