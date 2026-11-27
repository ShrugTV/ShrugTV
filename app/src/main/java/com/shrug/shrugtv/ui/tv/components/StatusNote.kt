package com.shrug.shrugtv.ui.tv.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.shrug.shrugtv.R
import com.shrug.shrugtv.ui.tv.Inter
import com.shrug.shrugtv.ui.tv.TvColors

/** What a list shows instead of its entries. */
enum class ListStatus { LOADING, EMPTY, ERROR }

/**
 * The one note for a list without entries, on every page: "Loading …", why it is empty ([emptyText]),
 * or that loading failed ([errorText] when the cause is known), with a "Try again" button when [retry] is given. A failure is never shown as "nothing found":
 * before, search and category turned errors into an empty list.
 */
@Composable
fun StatusNote(
    status: ListStatus, emptyText: String, modifier: Modifier = Modifier, retry: (() -> Unit)? = null, errorText: String? = null,
) {
    val text = when (status) {
        ListStatus.LOADING -> stringResource(R.string.status_loading)
        ListStatus.EMPTY -> emptyText
        ListStatus.ERROR -> errorText ?: stringResource(R.string.status_error)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text, fontFamily = Inter, fontSize = 13.sp, color = if (status == ListStatus.ERROR) TvColors.Text else TvColors.TextMuted)
        if (status == ListStatus.ERROR && retry != null) {
            ShrugButton(onClick = retry) { Text(stringResource(R.string.status_retry), fontFamily = Inter, fontSize = 12.sp) }
        }
    }
}

/** The status of a list from what is known about it; null while it has entries. */
fun listStatus(loading: Boolean, failed: Boolean, empty: Boolean): ListStatus? = when {
    !empty -> null
    loading -> ListStatus.LOADING
    failed -> ListStatus.ERROR
    else -> ListStatus.EMPTY
}
