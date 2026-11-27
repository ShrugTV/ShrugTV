package com.shrug.shrugtv.ui.tv.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import com.shrug.shrugtv.model.StreamQuality
import com.shrug.shrugtv.player.QualityMatch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Quality menu and track selection of the main player; split from PlayerViewModel.
 * The main stream always plays the master playlist ("Auto"): the setting caps the video size and keeps ABR on, a
 * chosen quality is a track override on it, so it switches without a reload (B6). Only "Audio only" and qualities
 * the master does not offer load a playlist of their own.
 *
 * [player] is the current main player, [isVod] tells whether the playing item is a recording (null: nothing plays),
 * [preference] reads the setting "Default (VOD) video quality" (0 Auto, 1-3 Auto max 1080/720/480p, 4 Source) and
 * [beforeSelect] runs before every choice (closes the menu).
 */
class QualityController(
    private val player: () -> Player,
    private val isVod: () -> Boolean?,
    private val preference: (vod: Boolean) -> Int,
    private val beforeSelect: () -> Unit = {}
) {
    private val _qualities = MutableStateFlow<List<StreamQuality>>(emptyList())
    val qualities: StateFlow<List<StreamQuality>> = _qualities.asStateFlow()

    private val _current = MutableStateFlow("Auto")
    val current: StateFlow<String> = _current.asStateFlow()

    /** Last picture quality before "Audio only", so one press returns to it. */
    private var lastVideoQuality = "Auto"

    /** A quality picked after a reload of the master playlist, applied once its video tracks are known. */
    private var pendingTrack: Pair<Int, Int>? = null

    /** "Audio only" is the current quality. */
    val isAudioOnly: Boolean get() = QualityMatch.isAudioOnly(_current.value)

    /** The qualities of the menu, e.g. after a hero handover or a fresh lookup. */
    fun setQualities(list: List<StreamQuality>) {
        _qualities.value = list
    }

    /** A new item starts: a quality waiting for its tracks belongs to the old one. */
    fun onNewItem() {
        pendingTrack = null
    }

    /**
     * The playlists of a new item are known: applies the setting and returns the address to load, the master playlist
     * or, when a reload must keep "Audio only" ([keepAudioOnly]), the audio playlist.
     */
    fun onResolved(resolved: List<StreamQuality>, vod: Boolean, keepAudioOnly: Boolean): String? {
        _qualities.value = resolved
        val pref = preference(vod)
        applyPreference(player(), pref)
        var url = master(resolved).url
        _current.value = if (pref == SOURCE) "Source" else "Auto"
        if (keepAudioOnly) {
            resolved.firstOrNull { QualityMatch.isAudioOnly(it.name) }?.let {
                url = it.url
                _current.value = it.name ?: "audio_only"
            }
        } else {
            lastVideoQuality = "Auto"
        }
        return url
    }

    /** A preview player was taken over (hero handover): it plays the master playlist, so only the setting is applied. */
    fun onAdopted(adopted: Player, vod: Boolean) {
        val pref = preference(vod)
        applyPreference(adopted, pref)
        _current.value = if (pref == SOURCE) "Source" else "Auto"
    }

    /** The tracks of the main player changed: a waiting quality can be fixed now. */
    fun onTracksChanged() {
        pendingTrack?.let { if (overrideVideoTrack(it)) pendingTrack = null }
    }

    fun select(quality: StreamQuality) {
        beforeSelect()
        val url = quality.url ?: return
        Log.d(TAG, "selectQuality ${quality.name} -> $url")
        val p = player()
        val playingUrl = p.currentMediaItem?.localConfiguration?.uri?.toString()
        val master = _qualities.value.firstOrNull { it.name == "Auto" }
        val onMaster = master != null && playingUrl == master.url

        if (onMaster && quality.name == "Auto") {
            _current.value = "Auto"
            applyPreference(p, currentPreference())
            return
        }
        val wanted = QualityMatch.parse(quality.name)
        if (!QualityMatch.isAudioOnly(quality.name)) lastVideoQuality = quality.name ?: "Auto"
        if (onMaster && wanted != null && overrideVideoTrack(wanted)) {
            _current.value = quality.name ?: ""
            return
        }
        // Fallback: load the chosen playlist
        pendingTrack = null
        if (url == master?.url) {
            // Back on the master playlist (e.g. from "Audio only"): restore the adaptive setting or the chosen height
            if (quality.name == "Auto") applyPreference(p, currentPreference()) else pendingTrack = wanted
        }
        _current.value = quality.name ?: ""
        if (playingUrl != url) {
            val item = MediaItem.fromUri(Uri.parse(url))
            if (isVod() == true) p.setMediaItem(item, p.currentPosition) else p.setMediaItem(item)
            p.prepare()
            p.playWhenReady = true
        }
    }

    /** Remote control, phone: a quality by its name, ignoring case. */
    fun selectByName(name: String) {
        _qualities.value.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { select(it) }
    }

    /**
     * One press between "Audio only" and the last picture quality. Without an audio-only variant
     * (clips) nothing happens and false is returned.
     */
    fun toggleAudioOnly(): Boolean {
        val list = _qualities.value
        if (isAudioOnly) {
            val back = list.firstOrNull { it.name == lastVideoQuality } ?: list.firstOrNull { it.name == "Auto" } ?: return false
            select(back)
            return true
        }
        val audio = list.firstOrNull { QualityMatch.isAudioOnly(it.name) } ?: return false
        select(audio)
        return true
    }

    private fun currentPreference(): Int = isVod()?.let(preference) ?: 0

    /** Fixes the video track of the master playlist that matches [wanted] (height, fps). */
    private fun overrideVideoTrack(wanted: Pair<Int, Int>): Boolean {
        val p = player()
        for (group in p.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_VIDEO) continue
            val formats = (0 until group.length).map { i ->
                val f = group.getTrackFormat(i)
                f.height to f.frameRate
            }
            val index = QualityMatch.bestTrack(formats, wanted)
            if (index >= 0) {
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setForceHighestSupportedBitrate(false)
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
                    .build()
                return true
            }
        }
        return false
    }

    companion object {
        private const val TAG = "PlayerViewModel"
        private const val SOURCE = 4

        /**
         * Applies the quality setting to the track selection of [player], which plays the master
         * playlist: the Auto variants cap the video size and stay adaptive, Source forces the highest
         * variant (B6). Clears a manually chosen quality.
         */
        fun applyPreference(player: Player, pref: Int) {
            val (w, h) = when (pref) {
                2 -> 1280 to 720
                3 -> 854 to 480
                else -> 1920 to 1080
            }
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                .setMaxVideoSize(w, h)
                .setForceHighestSupportedBitrate(true)
                .build()
        }

        /** The master playlist ("Auto"); clips have no master, their first entry is used. */
        fun master(qualities: List<StreamQuality>): StreamQuality =
            qualities.firstOrNull { it.name == "Auto" } ?: qualities[0]
    }
}
