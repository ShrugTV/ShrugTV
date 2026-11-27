package com.shrug.shrugtv.ui.tv.player

import android.app.Application
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shrug.shrugtv.chat.ChatAppearance
import com.shrug.shrugtv.chat.ChatLayout
import com.shrug.shrugtv.chat.ChatMessage
import com.shrug.shrugtv.chat.ChatPrefs
import com.shrug.shrugtv.chat.ChatUiEvent
import com.shrug.shrugtv.chat.ChatUiSink
import com.shrug.shrugtv.chat.ClipSource
import com.shrug.shrugtv.model.Movie
import com.shrug.shrugtv.model.RaidNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The chat of the player as a controller of its own: which chat goes with which item. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class PlayerChatControllerTest {

    private class Live(val movie: Movie, val sink: ChatUiSink) { var stopped = false }

    private class Vod(val movie: Movie, val videoId: Long, val sink: ChatUiSink) {
        var stopped = false
        val pages = ArrayList<Long>()
    }

    private val lives = ArrayList<Live>()
    private val vods = ArrayList<Vod>()
    private var clipInfo: ClipSource.Info? = null
    private val clipLookups = ArrayList<String>()
    private var owner: VodOwner? = null
    private val ownerLookups = ArrayList<Long>()
    private val raids = ArrayList<RaidNotification>()
    private val filled = ArrayList<Movie>()
    private val saved = ArrayList<ChatAppearance>()
    private val errors = ArrayList<Exception>()
    private var current: Movie? = null

    private val connector = object : PlayerChatConnector<Live> {
        override fun live(movie: Movie, sink: ChatUiSink) = Live(movie, sink).also { lives.add(it) }
        override fun stopLive(client: Live) { client.stopped = true }
        override fun vod(movie: Movie, videoId: Long, sink: ChatUiSink): VodChatConnection {
            val v = Vod(movie, videoId, sink).also { vods.add(it) }
            return VodChatConnection({ v.pages.add(it) }, { v.stopped = true })
        }
        override fun clipSource(slug: String): ClipSource.Info? { clipLookups.add(slug); return clipInfo }
        override fun vodOwner(videoId: Long): VodOwner? { ownerLookups.add(videoId); return owner }
    }

    private val scope = CoroutineScope(Dispatchers.Unconfined + Job())
    private val chatLines = ChatViewModel()
    private val basePrefs = ChatPrefs.from(
        PreferenceManager.getDefaultSharedPreferences(ApplicationProvider.getApplicationContext()), 1f
    ).copy(joinChat = true, showChat = true, vodChat = true)
    private val appearance = ChatAppearance(ChatLayout.SIDE_BY_SIDE, 0, 6, false)

    private fun controller(prefs: ChatPrefs = basePrefs) = PlayerChatController(
        scope = scope,
        chat = chatLines,
        prefs = prefs,
        initialAppearance = appearance,
        saveAppearance = { saved.add(it) },
        connector = connector,
        isVod = { it.videoId > 0L },
        current = { current },
        positionMs = { 0L },
        liveOffsetMs = { 4_000L },
        onRaid = { raids.add(it) },
        onClipSource = { m, info -> m.clipVideoId = info.videoId; m.clipOffsetSeconds = info.offsetSeconds; filled.add(m) },
        onError = { errors.add(it) },
        post = { it() },
        postDelayed = { r, _ -> r() },
        uptime = { 1_000L },
        io = Dispatchers.Unconfined,
        main = Dispatchers.Unconfined
    )

    private fun live(id: Int = 7) = Movie().apply { userId = id; login = "chan$id" }
    private fun vod(id: Long, owner: Int = 7) = Movie().apply { setVideoIdFromString(id.toString()); userId = owner; login = "chan" }
    private fun clip(slug: String) = Movie().apply { setClipSlug(slug); userId = 7; login = "chan" }

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `a live stream starts the live chat with a delay and delivers its events`() {
        val c = controller()
        val m = live()
        current = m
        c.start(m)
        val client = lives.single()
        assertSame(m, client.movie)
        assertSame(client, c.liveClient)
        assertNotNull(c.delay)
        client.sink.onEvent(ChatUiEvent.Message(ChatMessage.Builder().text("hi").historical(true).build()))
        assertEquals(1, chatLines.lines.value.size)
        client.sink.onEvent(ChatUiEvent.Raid(RaidNotification("chan7", "Target", "42")))
        assertEquals("42", raids.single().targetUserId)
    }

    @Test
    fun `a new channel ends the old live chat, without joining or a channel there is none`() {
        val c = controller()
        c.start(live(7))
        c.start(live(8))
        assertTrue(lives[0].stopped)
        assertEquals(2, lives.size)
        controller(basePrefs.copy(joinChat = false)).start(live(9))
        c.start(Movie().apply { login = "nobody" })
        assertEquals(2, lives.size)
        assertTrue(lives[1].stopped)
        assertNull(c.liveClient)
    }

    @Test
    fun `a recording starts its chat once, a quality change keeps it running`() {
        val c = controller()
        val m = vod(100L)
        current = m
        c.start(m)
        assertEquals(100L, vods.single().videoId)
        c.start(m)
        assertEquals(1, vods.size)
        assertFalse(vods.single().stopped)
        c.stopVod()
        assertTrue(vods.single().stopped)
    }

    @Test
    fun `a recording opened by link looks up its owner first`() {
        val c = controller()
        val m = vod(100L, owner = 0).apply { login = null }
        current = m
        owner = VodOwner(42, { "owner" }, { "Owner" })
        c.start(m)
        assertEquals(listOf(100L), ownerLookups)
        assertEquals(42, m.userId)
        assertEquals("owner", m.login)
        assertEquals("Owner", m.displayName)
        assertEquals(100L, vods.single().videoId)
    }

    @Test
    fun `a failing owner lookup is recorded and starts nothing`() {
        val c = controller()
        val m = vod(100L, owner = 0)
        current = m
        val broken = VodOwner(42, { throw IllegalStateException("bad json") }, { null })
        owner = broken
        m.login = null
        c.start(m)
        assertEquals(1, errors.size)
        assertTrue(vods.isEmpty())
    }

    @Test
    fun `a clip with a known recording plays that chat from its offset, or stays away without recorded chat`() {
        val c = controller()
        val m = clip("a").apply { clipVideoId = 100L; clipOffsetSeconds = 30 }
        current = m
        c.start(m)
        assertTrue(c.clipSourceReady.value)
        assertFalse(c.unavailable.value)
        assertEquals(100L, vods.single().videoId)

        val off = controller(basePrefs.copy(vodChat = false))
        off.start(clip("b").apply { clipVideoId = 101L })
        assertTrue(off.clipSourceReady.value)
        assertTrue(off.unavailable.value)
        assertEquals(1, vods.size)
    }

    @Test
    fun `a clip without its recording looks it up, fills the clip and starts the chat`() {
        val c = controller()
        val m = clip("a")
        current = m
        clipInfo = ClipSource.Info(100L, 30, 7, "chan", "Chan", "Title")
        c.start(m)
        assertEquals(listOf("a"), clipLookups)
        assertSame(m, filled.single())
        assertTrue(c.clipSourceReady.value)
        assertEquals(100L, vods.single().videoId)
    }

    @Test
    fun `a clip whose recording is gone has no chat`() {
        val c = controller()
        val m = clip("a")
        current = m
        clipInfo = null
        c.start(m)
        assertFalse(c.clipSourceReady.value)
        assertTrue(c.unavailable.value)
        assertTrue(filled.isEmpty())
        assertTrue(vods.isEmpty())
    }

    @Test
    fun `the look is saved, a size change restarts the chat when the editor closes`() {
        val c = controller()
        val m = vod(100L)
        current = m
        c.start(m)
        c.toggleLayout()
        assertEquals(ChatLayout.OVERLAY, c.appearance.value.layout)
        assertEquals(1, saved.size)
        c.onEditorClosed()
        assertEquals(1, vods.size)
        c.updateAppearance { it.copy(fontValue = 4) }
        c.onEditorClosed()
        assertTrue(vods[0].stopped)
        assertEquals(2, vods.size)
        c.onEditorClosed()
        assertEquals(2, vods.size)
    }

    @Test
    fun `visibility follows the settings`() {
        val c = controller()
        assertTrue(c.visible.value)
        c.toggleVisible()
        assertFalse(c.visible.value)
        c.showForEditor()
        assertTrue(c.visible.value)
        val notJoined = controller(basePrefs.copy(joinChat = false))
        assertFalse(notJoined.visible.value)
        notJoined.setVisible(true)
        assertFalse(notJoined.visible.value)
        notJoined.showForEditor()
        assertFalse(notJoined.visible.value)
        c.setVisible(false)
        assertFalse(c.visible.value)
    }
}
