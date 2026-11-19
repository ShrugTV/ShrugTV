package com.shrug.shrugtv.ui.tv.clips

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.shrug.shrugtv.ui.tv.TvHomeTheme
import com.shrug.shrugtv.ui.tv.components.Destinations

/**
 * The best clips of the channels you follow, with source, period and sort chosen right on the page (the
 * same settings as under Settings → Home screen → Clips, so a change here is kept).
 *
 * Four layouts over the same list, chosen in the settings: Feinschliff (one row of choice menus over a grid),
 * Stage (the focused clip fills the background, themed rows below), Charts (a ranked list beside a large picture) and Clip TV
 * (categories as channels, the queue as a strip). Loading, choices and the queue are in [rememberClipsState].
 */
class ClipsActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.shrug.shrugtv.util.AppLanguage.wrap(newBase))
    }

    /** Counts up whenever the page comes back (from the player), so opened clips are dimmed or gone right away. */
    private var resumes by mutableStateOf(0)

    override fun onResume() {
        super.onResume()
        resumes++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(1024, 1024)
        window.setBackgroundDrawable(null)
        val layout = ClipsLayout.load(this)
        setContent {
            TvHomeTheme {
                val ui = rememberClipsState(resumes, open = { Destinations.open(this, it) }, contextMenu = { Destinations.contextMenu(this, it) })
                when (layout) {
                    ClipsLayout.FINE -> ClipsFineScreen(ui)
                    ClipsLayout.STAGE -> ClipsStageScreen(ui)
                    ClipsLayout.CHARTS -> ClipsChartsScreen(ui)
                    ClipsLayout.TV -> ClipTvScreen(ui)
                }
            }
        }
    }
}
