package com.digifender.adminifender.ui

import com.digifender.adminifender.core.data.repository.Freshness
import com.digifender.adminifender.core.model.ActionKind
import com.digifender.adminifender.core.model.availableActions
import com.digifender.adminifender.core.model.Status
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/** How fresh the cached list is, without implying a sync that has not happened (FR-002). */
sealed interface FreshLine {
    data object NeverSynced : FreshLine

    data object JustNow : FreshLine

    data class Minutes(val n: Long) : FreshLine

    data class Hours(val n: Long) : FreshLine

    data class Days(val n: Long) : FreshLine
}

fun freshLine(freshness: Freshness, nowMs: Long): FreshLine {
    val last = freshness.lastSyncedAtMs ?: return FreshLine.NeverSynced
    val minutes = maxOf(0, nowMs - last) / 60_000
    return when {
        minutes < 1 -> FreshLine.JustNow
        minutes < 60 -> FreshLine.Minutes(minutes)
        minutes < 48 * 60 -> FreshLine.Hours(minutes / 60)
        else -> FreshLine.Days(minutes / (24 * 60))
    }
}

/**
 * One-tap controls shown on the detail screen. Waiting and due dates have separate input forms; mark-read
 * happens on open.
 */
fun quickActions(status: Status): List<ActionKind> =
    availableActions(status).filter { it != ActionKind.WAIT && it != ActionKind.SET_DUE && it != ActionKind.MARK_READ }

/** "Until tomorrow" snooze: 09:00 local time on the next calendar day. */
fun tomorrowMorning(nowMs: Long, zone: ZoneId): Long =
    ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone).toLocalDate().plusDays(1).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
