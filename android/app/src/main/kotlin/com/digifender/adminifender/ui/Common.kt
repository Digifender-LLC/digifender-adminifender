package com.digifender.adminifender.ui

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.digifender.adminifender.R
import com.digifender.adminifender.core.data.repository.Freshness
import com.digifender.adminifender.core.model.Importance
import com.digifender.adminifender.core.model.Status
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

@Composable
fun freshnessText(freshness: Freshness, nowMs: Long): String = when (val line = freshLine(freshness, nowMs)) {
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

/** A compact list date: the time for today, "Oct 3" this year, and "Oct 3, 2025" before that. */
@Composable
fun shortDate(ms: Long, nowMs: Long = System.currentTimeMillis()): String {
    val cal = { t: Long -> Calendar.getInstance().apply { timeInMillis = t } }
    val (now, then) = cal(nowMs) to cal(ms)
    val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
    val flags = when {
        sameYear && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR) -> DateUtils.FORMAT_SHOW_TIME
        sameYear -> DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR
        else -> DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_YEAR
    }
    return DateUtils.formatDateTime(LocalContext.current, ms, flags)
}
