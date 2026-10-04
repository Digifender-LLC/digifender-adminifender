package com.digifender.adminifender.ui

import java.time.LocalTime
import java.time.LocalDate
import com.digifender.adminifender.core.model.ConcernAction
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

/** One-tap controls shown on the detail screen. Waiting and due dates have their own forms; mark-read happens on open. */
fun quickActions(status: Status): List<ActionKind> =
    availableActions(status).filter { it != ActionKind.WAIT && it != ActionKind.SET_DUE && it != ActionKind.MARK_READ }

/** "Until tomorrow" snooze: 09:00 local time on the next calendar day. */
fun tomorrowMorning(nowMs: Long, zone: ZoneId): Long =
    ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone).toLocalDate().plusDays(1).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()

const val AWAITED_PARTY_MAX = 200
const val WAIT_NOTE_MAX = 500

/** Follow-up choices on the waiting form, in days; null means no follow-up reminder. */
val FOLLOW_UP_DAYS = listOf(null, 3, 7, 14)

/**
 * The waiting form as an action. Blank fields become null and text is cut to the server's limits; a follow-up is
 * 09:00 local time [followUpDays] after today.
 */
fun waitAction(party: String, note: String, followUpDays: Int?, nowMs: Long, zone: ZoneId): ConcernAction.Wait {
    val followUp = followUpDays?.let {
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone).toLocalDate().plusDays(it.toLong()).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
    }
    return ConcernAction.Wait(note.trim().take(WAIT_NOTE_MAX).ifEmpty { null }, party.trim().take(AWAITED_PARTY_MAX).ifEmpty { null }, followUp)
}

/** Default time of day for a due date picked without a time. */
val DUE_TIME: LocalTime = LocalTime.of(17, 0)

/** A due date at [time] in [zone], recorded with the zone so reminders keep local time across DST; null clears it. */
fun dueAction(date: LocalDate?, zone: ZoneId, time: LocalTime = DUE_TIME): ConcernAction.SetDue =
    if (date == null) ConcernAction.SetDue(null, null, null) else ConcernAction.SetDue(date.atTime(time).atZone(zone).toInstant().toEpochMilli(), zone.id, null)

/** Material date pickers report the chosen day as UTC midnight. */
fun pickedDate(utcMidnightMs: Long): LocalDate = Instant.ofEpochMilli(utcMidnightMs).atZone(ZoneId.of("UTC")).toLocalDate()
