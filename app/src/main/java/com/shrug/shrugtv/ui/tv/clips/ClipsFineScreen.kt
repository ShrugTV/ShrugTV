package com.shrug.shrugtv.ui.tv.clips

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.shrug.shrugtv.R
import com.shrug.shrugtv.ui.tv.Inter
import com.shrug.shrugtv.ui.tv.TvColors
import com.shrug.shrugtv.ui.tv.themeBackground

/**
 * Layout "Feinschliff" (design 1): title and count on the left, one row of choice menus on the right
 * ("Time frame 7 days ▾"), below a grid of four larger cards with rank, views, length and a two-line title.
 */
@Composable
fun ClipsFineScreen(ui: ClipsUi) {
    Column(Modifier.fillMaxSize().themeBackground().padding(start = 64.dp, top = 26.dp, end = 40.dp)) {
        // Title first, then the menus in the space that is left: a long value shortens its own pill instead of covering the title
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.clips_title_short), fontFamily = Inter, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = TvColors.Text)
                if (ui.shown.isNotEmpty()) Text(
                    stringResource(R.string.clips_count, ui.shown.size), fontFamily = Inter, fontSize = 12.sp, color = TvColors.TextMuted,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ChoiceMenu(stringResource(R.string.settings_clips_period), periodChoices(ui))
                ChoiceMenu(stringResource(R.string.settings_clips_source), sourceChoices(ui))
                if (ui.categories.isNotEmpty()) ChoiceMenu(stringResource(R.string.clips_category), categoryChoices(ui))
                ChoiceMenu(stringResource(R.string.settings_clips_sort), sortChoices(ui))
            }
        }
        Box(Modifier.padding(top = 14.dp)) {
            EmptyNote(ui)
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                contentPadding = PaddingValues(top = 10.dp, bottom = 48.dp, start = 4.dp, end = 4.dp),
            ) {
                itemsIndexed(ui.visible, key = { _, m -> m.clipSlug }) { i, m ->
                    ui.Watch(i)
                    androidx.compose.runtime.key(ui.resumes) {
                        ClipCard(
                            m, rank = if (ui.ranked) i + 1 else 0, click = { ui.play(m) }, longPress = { ui.menu(m) },
                            focus = if (i == 0) ui.firstFocus else null,
                        )
                    }
                }
            }
        }
    }
}
