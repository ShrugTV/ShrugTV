package com.shrug.shrugtv.ui.tv.player

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.SideEffect
import com.shrug.shrugtv.BuildConfig
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Debug-only recomposition counter for the player. [CountRecompositions] counts every
 * successful composition of the calling scope; [RecompositionLogger] prints the totals since the app started every
 * 5 s with the log tag `Recompose` (the difference of two lines is the count in between). Release builds compile it away (`BuildConfig.DEBUG` is a constant).
 * Read with `tools/emu_test.sh log Recompose`.
 */
internal object RecompositionProbe {
    private val counts = ConcurrentHashMap<String, AtomicInteger>()

    fun hit(name: String) {
        counts.getOrPut(name) { AtomicInteger() }.incrementAndGet()
    }

    /** The totals, sorted by name. */
    fun totals(): String = counts.keys.sorted().joinToString(" ") { "$it=${counts[it]?.get() ?: 0}" }

    const val INTERVAL_MS = 5_000L
}

// Non-restartable, so it is never skipped and runs every time the caller recomposes
@Composable
@NonRestartableComposable
internal fun CountRecompositions(name: String) {
    if (BuildConfig.DEBUG) SideEffect { RecompositionProbe.hit(name) }
}

@Composable
internal fun RecompositionLogger() {
    if (!BuildConfig.DEBUG) return
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(RecompositionProbe.INTERVAL_MS)
            Log.d("Recompose", "totals ${RecompositionProbe.totals()}")
        }
    }
}
