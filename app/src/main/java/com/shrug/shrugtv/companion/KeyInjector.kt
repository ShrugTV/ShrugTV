package com.shrug.shrugtv.companion

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import java.lang.ref.WeakReference

/**
 * Remote keys from the phone: sent as key down + up to ShrugTV's own foreground activity, so they
 * work in every screen like the real remote. Cannot press keys in other apps (intended).
 */
object KeyInjector : Application.ActivityLifecycleCallbacks {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var resumed: WeakReference<Activity>? = null

    /** Names the phone may send. */
    val KEYS = mapOf(
        "up" to KeyEvent.KEYCODE_DPAD_UP, "down" to KeyEvent.KEYCODE_DPAD_DOWN,
        "left" to KeyEvent.KEYCODE_DPAD_LEFT, "right" to KeyEvent.KEYCODE_DPAD_RIGHT,
        "ok" to KeyEvent.KEYCODE_DPAD_CENTER, "back" to KeyEvent.KEYCODE_BACK,
        "menu" to KeyEvent.KEYCODE_MENU, "play_pause" to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        "ff" to KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, "rw" to KeyEvent.KEYCODE_MEDIA_REWIND,
        "info" to KeyEvent.KEYCODE_INFO, "captions" to KeyEvent.KEYCODE_CAPTIONS,
        "del" to KeyEvent.KEYCODE_DEL, "enter" to KeyEvent.KEYCODE_ENTER
    )

    fun init(app: Application) = app.registerActivityLifecycleCallbacks(this)

    /** True if a ShrugTV screen is in front. */
    val hasForegroundActivity: Boolean get() = resumed?.get() != null

    /** The ShrugTV screen in front, if any (main thread only for UI work). */
    val foreground: Activity? get() = resumed?.get()

    fun press(name: String): Boolean {
        val code = KEYS[name] ?: return false
        main.post {
            val a = resumed?.get() ?: return@post
            val t = SystemClock.uptimeMillis()
            a.dispatchKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
            a.dispatchKeyEvent(KeyEvent(t, t + 60, KeyEvent.ACTION_UP, code, 0))
        }
        return true
    }

    /**
     * Text from the phone into the field that has the focus. First as the keyboard would commit it
     * (works for umlauts and emoji), if the field offers no input connection as key presses (letters and digits only).
     */
    fun typeText(text: String) {
        main.post {
            val a = resumed?.get() ?: return@post
            val focus = a.currentFocus
            val ic = try { focus?.onCreateInputConnection(EditorInfo()) } catch (e: Exception) { null }
            if (ic != null) {
                ic.commitText(text, 1)
                return@post
            }
            val events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(text.toCharArray()) ?: return@post
            for (e in events) a.dispatchKeyEvent(e)
        }
    }

    override fun onActivityResumed(activity: Activity) { resumed = WeakReference(activity) }
    override fun onActivityPaused(activity: Activity) { if (resumed?.get() === activity) resumed = null }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
