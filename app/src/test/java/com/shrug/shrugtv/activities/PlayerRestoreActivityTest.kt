package com.shrug.shrugtv.activities

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shrug.shrugtv.ui.tv.home.HomeActivity
import com.shrug.shrugtv.ui.tv.player.PlayerRestore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The player restored by Android after the process ended: a saved state, but a new ViewModel.
 * Before, nothing played and the picture stayed black.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class PlayerRestoreActivityTest {
    private fun restored(leftAgoMs: Long): PlayerActivity {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("shrugtv://stream/papaplatte"))
            .setClass(org.robolectric.RuntimeEnvironment.getApplication(), PlayerActivity::class.java)
        val saved = Bundle().apply { putLong(PlayerRestore.KEY_LEFT_AT, System.currentTimeMillis() - leftAgoMs) }
        return Robolectric.buildActivity(PlayerActivity::class.java, intent).create(saved).get()
    }

    @Test
    fun `shortly after leaving the stream plays again`() {
        val a = restored(60_000L)
        assertFalse(a.isFinishing)
        assertEquals("papaplatte", a.viewModelForTest().currentMovie.value?.login)
    }

    @Test
    fun `hours later the app starts on the home screen`() {
        val a = restored(3 * 3_600_000L)
        assertTrue(a.isFinishing)
        assertEquals(HomeActivity::class.java.name, shadowOf(a).nextStartedActivity.component?.className)
    }

    @Test
    fun `Back on a player opened by a link opens the home screen instead of leaving the app`() {
        val a = restored(60_000L)
        a.leaveToApp()
        assertTrue(a.isFinishing)
        assertEquals(HomeActivity::class.java.name, shadowOf(a).nextStartedActivity.component?.className)
    }
}
