package com.shrug.shrugtv.ui.tv.clips

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.shrug.shrugtv.R
import com.shrug.shrugtv.ui.tv.Inter
import com.shrug.shrugtv.ui.tv.TvColors
import com.shrug.shrugtv.ui.tv.TvHomeTheme
import com.shrug.shrugtv.ui.tv.TvShapes
import com.shrug.shrugtv.ui.tv.components.Destinations

/**
 * The best clips of the channels you follow, with source, period and sort chosen right on the page (the
 * same settings as under Settings → Home screen → Clips, so a change here is kept).
 *
 * Four layouts over the same list: Feinschliff (one row of choice menus over a grid), Stage (the focused
 * clip fills the background, themed rows below), Charts (a ranked list beside a large picture) and Clip TV (categories as
 * channels, the queue as a strip). Loading, choices and the queue are in [rememberClipsState]. The menu key switches the layout
 * right on the page and keeps the choice when the focus is not on a clip; a small
 * note in the corner says so.
 *
 * With the setting "Play the focused clip" the page holds one muted [ClipPreviewPlayer]: started when the
 * page comes to the front, released when it goes to the back, so the clip player never shares the decoder with it.
 */
class ClipsActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.shrug.shrugtv.util.AppLanguage.wrap(newBase))
    }

    /** Counts up whenever the page comes back (from the player), so opened clips are dimmed or gone right away. */
    private var resumes by mutableStateOf(0)

    private var layout by mutableStateOf(ClipsLayout.FINE)

    private val preview by lazy { ClipPreviewPlayer(applicationContext) }

    /** The setting, read again on every return (it may have been changed in the settings meanwhile). */
    private var previewOn by mutableStateOf(false)

    override fun onResume() {
        super.onResume()
        resumes++
        previewOn = com.shrug.shrugtv.prefs.Prefs.clipsLivePreview.get(this)
        if (previewOn) preview.start()
    }

    override fun onPause() {
        preview.release()
        super.onPause()
    }

    /**
     * Menu key on the page: a clip with the focus takes the key itself and opens its menu
     * ([com.shrug.shrugtv.ui.tv.components.menuKey]); anywhere else (the choice menus, the category row) the key switches to the
     * next layout and keeps the choice. Once per press, on release, so holding the key does not race through the layouts.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (com.shrug.shrugtv.ui.tv.components.isMenuRelease(event)) {
                val all = ClipsLayout.values()
                layout = all[(layout.ordinal + 1) % all.size]
                com.shrug.shrugtv.prefs.Prefs.clipsLayout.set(this, layout.key)
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(1024, 1024)
        window.setBackgroundDrawable(null)
        layout = ClipsLayout.load(this)
        setContent {
            TvHomeTheme {
                val ui = rememberClipsState(resumes, open = { Destinations.open(this, it) }, contextMenu = { Destinations.contextMenu(this, it) })
                androidx.compose.runtime.CompositionLocalProvider(LocalClipPreview provides if (previewOn) preview else null) {
                    Box(Modifier.fillMaxSize()) {
                        when (layout) {
                            ClipsLayout.FINE -> ClipsFineScreen(ui)
                            ClipsLayout.STAGE -> ClipsStageScreen(ui)
                            ClipsLayout.CHARTS -> ClipsChartsScreen(ui)
                            ClipsLayout.TV -> ClipTvScreen(ui)
                        }
                        val names = stringArrayResource(R.array.display_clips_layout)
                        Text(
                            stringResource(R.string.clips_layout_switch, names.getOrElse(layout.ordinal) { layout.key }),
                            fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, color = TvColors.TextMedium,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(14.dp).clip(TvShapes.r(8.dp))
                                .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }
        }
    }
}
