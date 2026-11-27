package com.shrug.shrugtv.ui.tv.player

import android.app.Application
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shrug.shrugtv.model.StreamQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/** Quality menu and track selection as a controller of its own. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class QualityControllerTest {

    /** A player that knows its media item, track selection and tracks. */
    private class FakePlayer {
        var params: TrackSelectionParameters = TrackSelectionParameters.DEFAULT
        var item: MediaItem? = null
        var startMs: Long? = null
        var tracks: Tracks = Tracks.EMPTY
        var prepared = 0
        val player: Player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, m, args ->
            when (m.name) {
                "getTrackSelectionParameters" -> params
                "setTrackSelectionParameters" -> { params = args[0] as TrackSelectionParameters; null }
                "getCurrentMediaItem" -> item
                "setMediaItem" -> { item = args[0] as MediaItem; startMs = (args.getOrNull(1) as? Long); null }
                "getCurrentTracks" -> tracks
                "getCurrentPosition" -> 42_000L
                "prepare" -> { prepared++; null }
                else -> defaultOf(m.returnType)
            }
        } as Player

        private fun defaultOf(t: Class<*>): Any? = when (t) {
            java.lang.Boolean.TYPE -> false
            Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            else -> null
        }
    }

    private val fake = FakePlayer()
    private var vod: Boolean? = false
    private var pref = 0
    private var closed = 0
    private val qc = QualityController({ fake.player }, { vod }, { pref }, { closed++ })

    private val auto = StreamQuality("Auto", "https://example.invalid/master.m3u8")
    private val p720 = StreamQuality("720p60", "https://example.invalid/720.m3u8")
    private val audio = StreamQuality("audio_only", "https://example.invalid/audio.m3u8")
    private val list = listOf(auto, p720, audio)

    private fun videoTracks(vararg heights: Int): Tracks {
        val formats = heights.map { Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setHeight(it).setFrameRate(60f).build() }
        val group = TrackGroup(*formats.toTypedArray())
        return Tracks(listOf(Tracks.Group(group, false, IntArray(heights.size) { androidx.media3.common.C.FORMAT_HANDLED }, BooleanArray(heights.size))))
    }

    @Test
    fun `a new stream plays the master playlist with the setting as cap`() {
        pref = 2
        val url = qc.onResolved(list, vod = false, keepAudioOnly = false)
        assertEquals(auto.url, url)
        assertEquals("Auto", qc.current.value)
        assertEquals(list, qc.qualities.value)
        assertEquals(720, fake.params.maxVideoHeight)
        assertTrue(fake.params.forceHighestSupportedBitrate)
    }

    @Test
    fun `the setting Source shows as Source`() {
        pref = 4
        qc.onResolved(list, vod = false, keepAudioOnly = false)
        assertEquals("Source", qc.current.value)
    }

    @Test
    fun `a reload keeps audio only`() {
        val url = qc.onResolved(list, vod = false, keepAudioOnly = true)
        assertEquals(audio.url, url)
        assertTrue(qc.isAudioOnly)
    }

    @Test
    fun `a quality of the master is a track override without reload`() {
        qc.onResolved(list, vod = false, keepAudioOnly = false)
        fake.item = MediaItem.fromUri(auto.url!!)
        fake.tracks = videoTracks(1080, 720, 480)
        qc.select(p720)
        assertEquals("720p60", qc.current.value)
        assertEquals(1, closed)
        assertEquals(0, fake.prepared)
        assertEquals(1, fake.params.overrides.size)
        assertFalse(fake.params.forceHighestSupportedBitrate)
    }

    @Test
    fun `audio only loads its playlist and the toggle returns to the last picture`() {
        qc.onResolved(list, vod = false, keepAudioOnly = false)
        fake.item = MediaItem.fromUri(auto.url!!)
        fake.tracks = videoTracks(1080, 720)
        qc.select(p720)
        assertTrue(qc.toggleAudioOnly())
        assertTrue(qc.isAudioOnly)
        assertEquals(audio.url, fake.item?.localConfiguration?.uri.toString())
        assertNull(fake.startMs) // live: no position
        assertEquals(1, fake.prepared)
        // Back: off the master, the playlist of the last picture quality is loaded
        assertTrue(qc.toggleAudioOnly())
        assertEquals("720p60", qc.current.value)
        assertEquals(p720.url, fake.item?.localConfiguration?.uri.toString())
        assertEquals(2, fake.prepared)
    }

    @Test
    fun `Auto from audio only returns to the master with the setting`() {
        pref = 3
        qc.onResolved(list, vod = false, keepAudioOnly = true)
        fake.item = MediaItem.fromUri(audio.url!!)
        assertTrue(qc.toggleAudioOnly())
        assertEquals("Auto", qc.current.value)
        assertEquals(auto.url, fake.item?.localConfiguration?.uri.toString())
        assertEquals(480, fake.params.maxVideoHeight)
    }

    @Test
    fun `a recording keeps its position when a playlist is loaded`() {
        vod = true
        qc.onResolved(list, vod = true, keepAudioOnly = false)
        fake.item = MediaItem.fromUri(auto.url!!)
        qc.select(audio)
        assertEquals(42_000L, fake.startMs)
    }

    @Test
    fun `without an audio variant the toggle does nothing`() {
        qc.setQualities(listOf(StreamQuality("1080p", "https://example.invalid/clip.mp4")))
        assertFalse(qc.toggleAudioOnly())
        assertEquals(0, closed)
    }

    @Test
    fun `a quality by name ignores case`() {
        qc.onResolved(list, vod = false, keepAudioOnly = false)
        qc.selectByName("AUDIO_ONLY")
        assertTrue(qc.isAudioOnly)
    }
}
