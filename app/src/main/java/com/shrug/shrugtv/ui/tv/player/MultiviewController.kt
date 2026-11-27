package com.shrug.shrugtv.ui.tv.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.shrug.shrugtv.model.Movie
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Multiview (slice 4d): a second stream beside the main one, of which only one is heard; split from PlayerViewModel
 *. [resolveUrl] looks up the address of a stream and calls back on the main thread.
 */
class MultiviewController<P : Player>(
    private val mainPlayer: () -> Player,
    private val createPlayer: () -> P,
    private val resolveUrl: (Movie, (String) -> Unit) -> Unit
) {
    private val _movie = MutableStateFlow<Movie?>(null)
    val movie: StateFlow<Movie?> = _movie.asStateFlow()

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _primaryAudio = MutableStateFlow(true)

    /** True while the main stream is heard. */
    val primaryAudio: StateFlow<Boolean> = _primaryAudio.asStateFlow()

    /** The player of the second stream, null outside multiview. */
    var player: P? = null
        private set

    fun start(second: Movie) {
        _movie.value = second
        _active.value = true
        val p = player ?: createPlayer().also { player = it }
        p.volume = 0f // the second player starts muted
        resolveUrl(second) { url ->
            // Multiview may have ended while the address was looked up
            player?.apply {
                setMediaItem(MediaItem.fromUri(Uri.parse(url)))
                prepare()
                playWhenReady = true
            }
        }
    }

    fun stop() {
        _active.value = false
        _movie.value = null
        release()
        mainPlayer().volume = 1f
        _primaryAudio.value = true
    }

    /** Hear the other stream. */
    fun swapAudio() {
        val newPrimary = !_primaryAudio.value
        _primaryAudio.value = newPrimary
        mainPlayer().volume = if (newPrimary) 1.0f else 0.0f
        player?.volume = if (newPrimary) 0.0f else 1.0f
    }

    /** Releases the second player (the model is cleared); the state stays as it is. */
    fun release() {
        player?.release()
        player = null
    }
}
