package com.digifender.adminifender.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.digifender.adminifender.R
import com.digifender.adminifender.core.data.repository.Freshness
import com.digifender.adminifender.core.model.Importance
import com.digifender.adminifender.core.model.Status
import java.text.DateFormat
import java.util.Date

@Composable
fun freshnessText(freshness: Freshness, nowMs: Long): String = ageText(freshness.lastSyncedAtMs, nowMs)

/** "Updated 5 minutes ago" for any read time; "Not synced yet" when there is none. */
@Composable
fun ageText(atMs: Long?, nowMs: Long): String = when (val line = ageLine(atMs, nowMs)) {
    FreshLine.NeverSynced -> stringResource(R.string.fresh_never)
    FreshLine.JustNow -> stringResource(R.string.fresh_just_now)
    is FreshLine.Minutes -> pluralStringResource(R.plurals.fresh_minutes, line.n.toInt(), line.n)
    is FreshLine.Hours -> pluralStringResource(R.plurals.fresh_hours, line.n.toInt(), line.n)
    is FreshLine.Days -> pluralStringResource(R.plurals.fresh_days, line.n.toInt(), line.n)
}

@Composable
fun importanceText(importance: Importance): String = stringResource(
    when (importance) {
        Importance.CRITICAL -> R.string.importance_critical
        Importance.URGENT -> R.string.importance_urgent
        Importance.IMPORTANT -> R.string.importance_important
        Importance.ROUTINE -> R.string.importance_routine
    },
)

@Composable
fun statusText(status: Status): String = stringResource(
    when (status) {
        Status.OPEN -> R.string.status_open
        Status.WAITING -> R.string.status_waiting
        Status.SNOOZED -> R.string.status_snoozed
        Status.RESOLVED -> R.string.status_resolved
    },
)

fun dateTime(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

/** One line for a due entry: the date in its own zone (named when it differs from this phone's), or the unconfirmed wording. */
@Composable
fun dueText(display: DueDisplay): String {
    val at = display.atMs ?: return stringResource(R.string.label_due_unconfirmed, display.unconfirmedText.orEmpty())
    val text = formatInstant(at, display.zone)
    return if (display.showZone) stringResource(R.string.label_due_in_zone, text, display.zone.id) else stringResource(R.string.label_due, text)
}
