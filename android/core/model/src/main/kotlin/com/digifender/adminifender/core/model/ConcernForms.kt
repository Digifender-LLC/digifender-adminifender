package com.digifender.adminifender.core.model

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/** Argument limits the Worker enforces for `wait` (lld.md#concerns); checked here so the owner sees a problem before a change is queued. */
const val WAIT_NOTE_MAX = 500
const val WAIT_PARTY_MAX = 200

/** Dates outside these years are almost always a typing slip, and would otherwise sit overdue or never due. */
private const val MIN_YEAR = 2000
private const val MAX_YEAR = 2099
private const val MINUTE_MS = 60_000L

/** Which occurrence the owner means when a local time happens twice because clocks go back. */
enum class Fold { EARLIER, LATER }

enum class FormProblem {
    NOTE_TOO_LONG,
    PARTY_TOO_LONG,
    BAD_DATE,
    BAD_TIME,

    /** A due date needs a date. A follow-up without one simply means no follow-up. */
    DATE_MISSING,
    FOLLOW_UP_IN_PAST,

    /** The local time does not exist on that date because clocks go forward. */
    TIME_SKIPPED,

    /** The local time happens twice on that date; [FormOutcome.Invalid.earlierMs] and `laterMs` are the two instants. */
    TIME_REPEATS,
}

sealed interface FormOutcome {
    data class Ready(val action: ConcernAction) : FormOutcome

    data class Invalid(val problems: Set<FormProblem>, val earlierMs: Long? = null, val laterMs: Long? = null) : FormOutcome
}

/** A wall-clock time in a zone, resolved to the instant it means, or why it does not mean exactly one. */
sealed interface Resolved {
    data class At(val ms: Long) : Resolved

    data object Skipped : Resolved

    data class Repeats(val earlierMs: Long, val laterMs: Long) : Resolved
}

fun resolveLocal(local: LocalDateTime, zone: ZoneId, fold: Fold? = null): Resolved {
    val offsets = zone.rules.getValidOffsets(local)
    return when (offsets.size) {
        0 -> Resolved.Skipped
        1 -> Resolved.At(local.toInstant(offsets[0]).toEpochMilli())
        else -> {
            // Two valid offsets: the first is the one before the clocks went back, so it names the earlier instant.
            val earlier = local.toInstant(offsets[0]).toEpochMilli()
            val later = local.toInstant(offsets[1]).toEpochMilli()
            when (fold) {
                Fold.EARLIER -> Resolved.At(earlier)
                Fold.LATER -> Resolved.At(later)
                null -> Resolved.Repeats(earlier, later)
            }
        }
    }
}

private val DATE_TEXT = Regex("^\\d{4}-\\d{2}-\\d{2}$")
private val TIME_TEXT = Regex("^\\d{1,2}:\\d{2}$")

/** Strict `yyyy-MM-dd`; anything the calendar does not have, or a year outside 2000 to 2099, is null. */
fun parseDate(text: String): LocalDate? {
    val t = text.trim()
    if (!DATE_TEXT.matches(t)) return null
    return try {
        LocalDate.parse(t).takeIf { it.year in MIN_YEAR..MAX_YEAR }
    } catch (_: DateTimeParseException) {
        null
    }
}

/** 24-hour `H:mm` or `HH:mm`. */
fun parseTime(text: String): LocalTime? {
    val t = text.trim()
    if (!TIME_TEXT.matches(t)) return null
    val (h, m) = t.split(':').map(String::toInt)
    return if (h in 0..23 && m in 0..59) LocalTime.of(h, m) else null
}

private val WIRE_ZONE = Regex("^[A-Za-z0-9_+\\-/]{1,64}$")

/**
 * The zone name to send with a due date, or null when the phone's zone is a fixed offset or a name the Worker's
 * validator would refuse. The instant is still exact without it.
 */
fun wireZone(zone: ZoneId): String? = zone.id.takeIf { WIRE_ZONE.matches(it) && it in ZoneId.getAvailableZoneIds() }

private class Moment(val problems: Set<FormProblem>, val resolved: Resolved?)

private fun moment(dateText: String, timeText: String, zone: ZoneId, fold: Fold?, optional: Boolean): Moment {
    if (dateText.isBlank()) {
        // The time field is usually pre-filled, so a time alone does not make a follow-up.
        return if (optional) Moment(emptySet(), null) else Moment(setOf(FormProblem.DATE_MISSING), null)
    }
    val date = parseDate(dateText)
    val time = parseTime(timeText)
    val problems = buildSet {
        if (date == null) add(FormProblem.BAD_DATE)
        if (time == null) add(FormProblem.BAD_TIME)
    }
    if (date == null || time == null) return Moment(problems, null)
    return Moment(emptySet(), resolveLocal(LocalDateTime.of(date, time), zone, fold))
}

/**
 * Validates the wait form. Empty note and party are sent as absent. A follow-up the owner enters must be in the
 * future; a local time that does not exist, or happens twice, is returned for the owner to settle rather than guessed.
 *
 * A wait replaces the whole record, so leaving the follow-up out would erase it. [currentFollowUpMs] is the follow-up
 * the concern already has: when the fields still read as that instant (to the minute, which is all the form shows) it
 * is sent back exactly as it was, even when it has passed, so editing only the note keeps an overdue follow-up. Any
 * other date is new and must be in the future, and an emptied date field is the owner clearing the follow-up.
 */
fun buildWait(
    note: String,
    awaitedParty: String,
    followUpDate: String,
    followUpTime: String,
    zone: ZoneId,
    fold: Fold?,
    nowMs: Long,
    currentFollowUpMs: Long? = null,
): FormOutcome {
    val n = note.trim().ifEmpty { null }
    val p = awaitedParty.trim().ifEmpty { null }
    val problems = linkedSetOf<FormProblem>()
    if (n != null && n.length > WAIT_NOTE_MAX) problems += FormProblem.NOTE_TOO_LONG
    if (p != null && p.length > WAIT_PARTY_MAX) problems += FormProblem.PARTY_TOO_LONG
    val m = moment(followUpDate, followUpTime, zone, fold, optional = true)
    problems += m.problems
    var followUpMs: Long? = null
    var earlier: Long? = null
    var later: Long? = null
    // The form shows the current follow-up to the minute, so that is the precision it can be recognised at.
    fun unchanged(ms: Long) = currentFollowUpMs != null && Math.floorDiv(currentFollowUpMs, MINUTE_MS) == Math.floorDiv(ms, MINUTE_MS)
    when (val r = m.resolved) {
        is Resolved.At -> when {
            unchanged(r.ms) -> followUpMs = currentFollowUpMs
            r.ms <= nowMs -> problems += FormProblem.FOLLOW_UP_IN_PAST
            else -> followUpMs = r.ms
        }
        Resolved.Skipped -> problems += FormProblem.TIME_SKIPPED
        is Resolved.Repeats -> when {
            // Either reading of a repeated clock time can be the one already stored; keep it rather than ask again.
            unchanged(r.earlierMs) || unchanged(r.laterMs) -> followUpMs = currentFollowUpMs
            else -> {
                problems += FormProblem.TIME_REPEATS
                earlier = r.earlierMs
                later = r.laterMs
            }
        }
        null -> Unit
    }
    return if (problems.isEmpty()) FormOutcome.Ready(ConcernAction.Wait(n, p, followUpMs)) else FormOutcome.Invalid(problems, earlier, later)
}

/**
 * Validates the due-date form. The instant is exact, the zone is the phone's, and the source wording is left as it
 * was (a null `sourceText` keeps it on the server). A due date in the past is allowed: it makes the item overdue.
 */
fun buildDue(dateText: String, timeText: String, zone: ZoneId, fold: Fold?): FormOutcome {
    val m = moment(dateText, timeText, zone, fold, optional = false)
    if (m.problems.isNotEmpty()) return FormOutcome.Invalid(m.problems)
    return when (val r = m.resolved) {
        is Resolved.At -> FormOutcome.Ready(ConcernAction.SetDue(r.ms, wireZone(zone), null))
        Resolved.Skipped -> FormOutcome.Invalid(setOf(FormProblem.TIME_SKIPPED))
        is Resolved.Repeats -> FormOutcome.Invalid(setOf(FormProblem.TIME_REPEATS), r.earlierMs, r.laterMs)
        null -> FormOutcome.Invalid(setOf(FormProblem.DATE_MISSING))
    }
}

/**
 * Removes the confirmed date and zone. The Worker keeps the original wording (it refuses an empty replacement), so
 * a date the email proposed comes back as an unconfirmed date the owner can confirm again.
 */
fun clearDue(): ConcernAction.SetDue = ConcernAction.SetDue(null, null, null)

/** True for a due entry that has wording but no confirmed date: the owner must confirm before any timed reminder. */
fun Due.isUnconfirmed(): Boolean = atMs == null && !sourceText.isNullOrBlank()

/** The date to show for a concern, or null when it has neither a confirmed date nor wording. */
fun Due?.hasContent(): Boolean = this != null && (atMs != null || !sourceText.isNullOrBlank())
