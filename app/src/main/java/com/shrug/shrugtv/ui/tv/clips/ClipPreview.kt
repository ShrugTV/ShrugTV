package com.shrug.shrugtv.ui.tv.clips

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.SurfaceView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.shrug.shrugtv.clips.ClipPreviewSource
import com.shrug.shrugtv.model.Movie
import com.shrug.shrugtv.ui.tv.home.NetImage
import kotlinx.coroutines.delay

/**
 * The one muted player of the clips page (setting "Play the focused clip"): the layouts show the
 * focused clip running instead of its picture. One player for the whole page, handed from clip to clip; the activity starts it
 * when it comes to the front and releases it when it goes to the back (the clip player then has the decoder to itself).
 * Clips are MP4 files, so it needs no HLS parser; the sound track is switched off, not only muted.
 */
class ClipPreviewPlayer(private val context: Context) {
    /** The player while the page is in front, null otherwise; the layouts recompose when it comes and goes. */
    var player by mutableStateOf<ExoPlayer?>(null)
        private set

    /** Addresses already looked up on this page, so coming back to a clip starts at once. */
    private val urls = HashMap<String, String>()

    fun start() {
        if (player == null) player = build()
    }

    fun release() {
        val p = player ?: return
        player = null
        p.release()
        urls.clear()
    }

    /** The address of the preview file of [m], looked up like the clip player does it; null when the clip has none. */
    suspend fun url(m: Movie): String? {
        val id = m.playbackId ?: m.clipSlug
        urls[id]?.let { return it }
        val qualities = com.shrug.shrugtv.ui.tv.player.PlayerLookups.vodQualities(context, id)
        return ClipPreviewSource.pick(qualities)?.also { urls[id] = it }
    }

    /** A looked-up address that failed (an expired signature) is asked again next time. */
    fun forget(m: Movie) {
        urls.remove(m.playbackId ?: m.clipSlug)
    }

    private fun build(): ExoPlayer {
        val data = DefaultHttpDataSource.Factory()
            .setUserAgent("ShrugTV")
            .setDefaultRequestProperties(mapOf("Referer" to "https://player.twitch.tv", "Origin" to "https://player.twitch.tv"))
        // A preview needs little buffer; it starts after half a second of data
        val load = DefaultLoadControl.Builder().setBufferDurationsMs(2_000, 8_000, 500, 1_000).build()
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(ProgressiveMediaSource.Factory(data))
            .setLoadControl(load)
            .build().apply {
                volume = 0f
                trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            }
    }

    companion object {
        /** The focus rests this long on a clip before its preview loads. */
        const val START_DELAY_MS = 800L
        const val TAG = "ClipPreview"
    }
}

/** The preview player of the page; null when the setting is off or the page is in the back. */
val LocalClipPreview = staticCompositionLocalOf<ClipPreviewPlayer?> { null }

/**
 * The picture of [m], and with the setting on its muted clip over it once the focus rested on it for
 * [ClipPreviewPlayer.START_DELAY_MS]. The picture stays until the first frame is drawn (no black while it loads) and comes back
 * when the clip changes. [onEnded] null: the clip repeats; otherwise it is called at the end (Clip TV goes on with the next clip).
 *
 * A SurfaceView, like the hero of the home screen: the system composes it, the page does not redraw for every video frame. It is
 * a rectangle; rounded corners of the place it sits in do not cut it.
 */
@Composable
fun ClipVideo(m: Movie?, modifier: Modifier = Modifier, width: Int = 800, height: Int = 450, onEnded: (() -> Unit)? = null) {
    val host = LocalClipPreview.current
    val player = host?.player
    if (host == null || player == null || m == null) {
        NetImage(m?.cardImageUrl, modifier, width = width, height = height)
        return
    }
    var ready by remember { mutableStateOf(false) }
    val shown by animateFloatAsState(if (ready) 1f else 0f, tween(400), label = "clipPreview")
    var surface by remember { mutableStateOf<SurfaceView?>(null) }
    val ended by rememberUpdatedState(onEnded)
    val current by rememberUpdatedState(m)
    // When the current clip was handed to the player, for the start time in the log (measuring )
    val handedAt = remember { longArrayOf(0L) }

    DisposableEffect(player, surface) {
        val s = surface
        val l = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                ready = true
                Log.i(ClipPreviewPlayer.TAG, "first frame " + (android.os.SystemClock.elapsedRealtime() - handedAt[0]) + " ms after the lookup started")
            }
            override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) ended?.invoke() }
            override fun onPlayerError(error: PlaybackException) {
                Log.w(ClipPreviewPlayer.TAG, "Error: " + error.errorCodeName)
                ready = false
                host.forget(current)
            }
        }
        player.addListener(l)
        if (s != null) player.setVideoSurfaceView(s)
        onDispose {
            player.removeListener(l)
            // Released already when the page went to the back
            if (host.player === player) {
                if (s != null) player.clearVideoSurfaceView(s)
                player.stop()
                player.clearMediaItems()
            }
        }
    }
    // Repeat or go on: can change while the same clip runs (Clip TV: the focus moves from the channels to the strip)
    val repeat = onEnded == null
    LaunchedEffect(repeat, player) { player.repeatMode = if (repeat) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF }
    LaunchedEffect(m.clipSlug, player, surface) {
        ready = false
        player.stop()
        player.clearMediaItems()
        if (surface == null) return@LaunchedEffect
        delay(ClipPreviewPlayer.START_DELAY_MS)
        handedAt[0] = android.os.SystemClock.elapsedRealtime()
        val url = host.url(m) ?: return@LaunchedEffect
        if (host.player !== player) return@LaunchedEffect
        player.setMediaItem(MediaItem.fromUri(Uri.parse(url)))
        player.prepare()
        player.playWhenReady = true
    }

    Box(modifier) {
        BoxWithConstraints(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
            AndroidView(
                factory = { ctx -> SurfaceView(ctx).also { surface = it } },
                modifier = Modifier.requiredSize(maxWidth, maxWidth * 9f / 16f),
            )
        }
        NetImage(m.cardImageUrl, Modifier.fillMaxSize().alpha(1f - shown), width = width, height = height)
    }
}
