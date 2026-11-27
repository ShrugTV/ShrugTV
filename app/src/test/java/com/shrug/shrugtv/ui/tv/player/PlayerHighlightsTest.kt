package com.shrug.shrugtv.ui.tv.player

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.JsonParser
import com.shrug.shrugtv.api.helix.HelixParser
import com.shrug.shrugtv.model.Enums
import com.shrug.shrugtv.model.Movie
import com.shrug.shrugtv.ui.tv.TvHomeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** Highlights of the running stream in the player. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class PlayerHighlightsTest {
    @get:Rule
    val compose = createComposeRule()

    private fun clips(vararg views: Int): List<Movie> {
        val json = views.mapIndexed { i, v ->
            """{"id":"slug$i","title":"Clip $i","broadcaster_id":"42","broadcaster_name":"Papaplatte","view_count":$v,
               "created_at":"2026-11-26T20:${10 + i}:00Z","duration":30}"""
        }.joinToString(",", "[", "]")
        return HelixParser.parseClips(JsonParser.parseString(json).asJsonArray, Enums.RowType.VOD_CHANNEL_HIGHLIGHTS, "")
    }

    @Test
    fun `the five most viewed are picked, most viewed first`() {
        val picked = PlayerHighlights.pick(clips(10, 500, 30, 900, 2, 70, 400))
        assertEquals(listOf(900, 500, 400, 70, 30), picked.map { it.viewCount })
    }

    @Test
    fun `counting starts at the stream start, without one twelve hours back`() {
        val live = Movie().apply { setStartedAt("2026-11-26T18:00:00Z") }
        assertEquals(Instant.parse("2026-11-26T18:00:00Z"), PlayerHighlights.since(live, 0L))
        val now = Instant.parse("2026-11-26T22:00:00.700Z").toEpochMilli()
        assertEquals(Instant.parse("2026-11-26T10:00:00Z"), PlayerHighlights.since(Movie(), now))
    }

    @Test
    fun `time into the stream from the clip's creation`() {
        val start = ZonedDateTime.of(2026, 11, 26, 18, 0, 0, 0, ZoneOffset.UTC)
        val clip = clips(1).first()
        assertEquals(2 * 3600L + 10 * 60L, PlayerHighlights.secondsIntoStream(clip, start))
        assertNull(PlayerHighlights.secondsIntoStream(clip, null))
        assertNull(PlayerHighlights.secondsIntoStream(clip, start.plusDays(1)))
    }

    @Test
    fun `Back from the highlights returns to the bar`() {
        assertEquals(PlayerOverlayType.CONTROLS, PlayerOverlayController.backTarget(PlayerOverlayType.HIGHLIGHTS))
    }

    @Test
    fun `the strip shows loading, empty and error instead of an empty row`() {
        var state by mutableStateOf<Pair<List<Movie>?, Boolean>>(null to false)
        compose.setContent {
            TvHomeTheme {
                PlayerHighlightsOverlay(visible = true, clips = state.first, failed = state.second, streamStart = null, onSelect = {}, onRetry = {})
            }
        }
        compose.onNodeWithText("Loading", substring = true).assertIsDisplayed()
        state = emptyList<Movie>() to false
        compose.onNodeWithText("No clips of this stream yet").assertIsDisplayed()
        state = null to true
        compose.onNodeWithText("Could not load the clips").assertIsDisplayed()
        state = clips(5, 9) to false
        compose.onNodeWithText("Clip 1").assertIsDisplayed()
    }
}
