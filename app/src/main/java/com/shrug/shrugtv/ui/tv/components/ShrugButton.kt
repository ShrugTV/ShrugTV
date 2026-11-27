package com.shrug.shrugtv.ui.tv.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Text
import com.shrug.shrugtv.ui.tv.Inter
import com.shrug.shrugtv.ui.tv.TvColors
import com.shrug.shrugtv.ui.tv.TvStyle
import com.shrug.shrugtv.ui.tv.theme.FocusStyle

/**
 * The one button of all pages except the player. Three states that never look alike:
 *
 * - **focused**: Ink surface with background-coloured text, plus the theme's focus style (frame, glow or growth,
 *   Settings → Appearance), the same as cards;
 * - **selected** (the tab, period or filter in effect): an accent frame and bold text on the normal surface, so it stays
 *   recognisable while the focus is elsewhere and never looks like a second focus;
 * - **primary** (the main action of a page, "Watch live"): an accent surface.
 *
 * Before, selected and focused were both Ink, so two white buttons stood side by side whenever the focus was not on
 * the selected one. [whiteButton]/[glassButton] remain for the player only.
 */
@Composable
fun ShrugButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    primary: Boolean = false,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    onFocus: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = CircleShape
    val glow = TvStyle.focusStyle == FocusStyle.GLOW
    Button(
        onClick = onClick,
        modifier = if (onFocus != null) modifier.onFocusChanged { if (it.isFocused) onFocus() } else modifier,
        shape = ButtonDefaults.shape(shape),
        contentPadding = contentPadding,
        colors = ButtonDefaults.colors(
            containerColor = if (primary) TvColors.Accent else TvColors.glass(0.12f),
            contentColor = if (primary) TvColors.OnAccent else if (selected) TvColors.Text else TvColors.TextMedium,
            focusedContainerColor = TvColors.Ink, focusedContentColor = TvColors.Background,
            pressedContainerColor = TvColors.Ink, pressedContentColor = TvColors.Background,
        ),
        scale = ButtonDefaults.scale(focusedScale = TvStyle.focusScale.coerceAtMost(1.06f)),
        glow = if (glow) ButtonDefaults.glow(focusedGlow = Glow(TvColors.Focus.copy(alpha = 0.55f), 10.dp)) else ButtonDefaults.glow(),
        border = ButtonDefaults.border(
            border = if (selected) Border(BorderStroke(2.dp, TvColors.Accent), shape = shape) else Border.None,
            focusedBorder = if (TvStyle.borderWidth > 0.dp) Border(TvStyle.focusStroke, inset = 2.dp, shape = shape)
            else Border(BorderStroke(0.dp, Color.Transparent), shape = shape),
        ),
        content = content,
    )
}

/** A text pill on top of [ShrugButton]: tabs, periods, filters and the entries of a choice list. */
@Composable
fun ShrugChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onFocus: (() -> Unit)? = null,
    onClick: () -> Unit = {},
) {
    ShrugButton(
        onClick = onClick, modifier = modifier, selected = selected, onFocus = onFocus,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(text, fontFamily = Inter, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
    }
}
