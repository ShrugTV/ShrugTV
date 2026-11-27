package com.shrug.shrugtv.chat.emote

import android.content.Context
import android.os.Process
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.load.engine.executor.GlideExecutor
import java.util.concurrent.ThreadFactory

/**
 * Glide with animation threads at normal priority.
 *
 * Glide decodes the frames of animated emotes on its animation threads at background priority (nice 9). On the Fire
 * TV the load is 7 to 15 on four cores while a stream plays; video, UI and render threads run at -4 to -16, so the
 * frame decodes waited, and emotes stored at 25 fps (median of ohnePixel's 7TV set) ran at 10 to 15 fps although
 * the CPU was not busy on average. At nice 0 the animation threads get about nine times the share they had.
 */
object GlideSetup {
    /** Priority of the animation threads: normal, still below the UI and the video. */
    const val ANIMATION_PRIORITY = Process.THREAD_PRIORITY_DEFAULT

    internal fun animationThreadFactory(): ThreadFactory = ThreadFactory { r ->
        Thread({
            Process.setThreadPriority(ANIMATION_PRIORITY)
            r.run()
        }, "emote-animation")
    }

    /** Before anything else asks for Glide (MainApp.onCreate). */
    fun init(context: Context) {
        val animation = GlideExecutor.newAnimationBuilder().setThreadFactory(animationThreadFactory()).build()
        Glide.init(context, GlideBuilder().setAnimationExecutor(animation))
    }
}
