package com.shrug.shrugtv.ui.tv.components

import android.view.KeyEvent
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onKeyEvent

/**
 * One rule for the menu key (≡) on every page:
 *
 * 1. If the focused element has a menu of its own (a card, channel, category or clip), the menu key opens it: the same
 *    menu as a long press on OK ([Destinations.contextMenu]).
 * 2. Otherwise the key does the page's own option: the next view on the statistics page, the next layout on the clips
 *    page, "More" in the player.
 *
 * The element gets the key first because Compose hands key events to the focused element before its parents and the
 * activity; whatever it does not take reaches the page. The action runs once on release (ACTION_UP), so holding the key
 * repeats nothing. The press is taken (down and up) so the page does not act on it as well.
 */
fun Modifier.menuKey(action: (() -> Unit)?): Modifier = if (action == null) this else onKeyEvent { e ->
    val k = e.nativeKeyEvent
    if (k.keyCode != KeyEvent.KEYCODE_MENU) false
    else {
        if (k.action == KeyEvent.ACTION_UP && !k.isCanceled) action()
        true
    }
}

/** True for the release of the menu key, the moment a page option runs (see [menuKey]). */
fun isMenuRelease(e: KeyEvent?): Boolean =
    e != null && e.keyCode == KeyEvent.KEYCODE_MENU && e.action == KeyEvent.ACTION_UP && !e.isCanceled
