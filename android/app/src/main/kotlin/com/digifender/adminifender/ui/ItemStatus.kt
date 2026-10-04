package com.digifender.adminifender.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.digifender.adminifender.R
import com.digifender.adminifender.core.model.Concern
import com.digifender.adminifender.core.model.Status
import com.digifender.adminifender.ui.theme.Glyphs
import com.digifender.adminifender.ui.theme.LedgerTones
import com.digifender.adminifender.ui.theme.Tone

/** The one state an item's pill shows, most pressing first. */
sealed interface ItemStatus {
    data class Overdue(val dueAtMs: Long) : ItemStatus

    data class DueSoon(val dueAtMs: Long) : ItemStatus

    data class DueLater(val dueAtMs: Long) : ItemStatus

    data class Waiting(val party: String?) : ItemStatus

    data object Done : ItemStatus
}

const val DUE_SOON_MS = 7L * 24 * 60 * 60 * 1000

fun itemStatus(concern: Concern, nowMs: Long): ItemStatus? {
    if (concern.status == Status.RESOLVED) return ItemStatus.Done
    val due = concern.due?.atMs
    return when {
        due != null && due < nowMs -> ItemStatus.Overdue(due)
        due != null && due - nowMs <= DUE_SOON_MS -> ItemStatus.DueSoon(due)
        concern.status == Status.WAITING -> ItemStatus.Waiting(concern.waiting?.awaitedParty?.takeIf { it.isNotBlank() })
        due != null -> ItemStatus.DueLater(due)
        else -> null
    }
}

@Composable
fun StatusPill(status: ItemStatus, modifier: Modifier = Modifier) {
    val (tone, icon, text) = when (status) {
        is ItemStatus.Overdue -> Triple(LedgerTones.overdue, Glyphs.alert, stringResource(R.string.status_overdue, dateTime(status.dueAtMs)))
        is ItemStatus.DueSoon -> Triple(LedgerTones.dueSoon, Glyphs.clock, stringResource(R.string.label_due, dateTime(status.dueAtMs)))
        is ItemStatus.DueLater -> Triple(LedgerTones.neutral, Glyphs.clock, stringResource(R.string.label_due, dateTime(status.dueAtMs)))
        is ItemStatus.Waiting -> Triple(LedgerTones.waiting, Glyphs.hourglass, status.party?.let { stringResource(R.string.status_waiting_on, it) } ?: stringResource(R.string.status_waiting))
        ItemStatus.Done -> Triple(LedgerTones.neutral, Glyphs.check, stringResource(R.string.status_done))
    }
    Pill(tone, icon, text, modifier)
}

@Composable
private fun Pill(tone: Tone, icon: ImageVector, text: String, modifier: Modifier) {
    Row(
        modifier.heightIn(min = 28.dp).background(tone.container, RoundedCornerShape(14.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The word carries the meaning; the icon is decoration for sighted readers.
        Icon(icon, contentDescription = null, tint = tone.content, modifier = Modifier.size(14.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = tone.content)
    }
}
