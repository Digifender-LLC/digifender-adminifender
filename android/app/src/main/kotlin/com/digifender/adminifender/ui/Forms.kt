package com.digifender.adminifender.ui

import com.digifender.adminifender.core.model.ActionKind
import com.digifender.adminifender.core.model.Due
import com.digifender.adminifender.core.model.Status
import com.digifender.adminifender.core.model.Waiting
import com.digifender.adminifender.core.model.availableActions
import com.digifender.adminifender.core.model.hasContent
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Controls that open an input form instead of acting at once. Mark-read happens on open and is not offered. */
fun formActions(status: Status): List<ActionKind> = availableActions(status).filter { it == ActionKind.WAIT || it == ActionKind.SET_DUE }

/**
 * How a due entry reads on screen. A confirmed date is shown in the zone the email gave (or this phone's zone), and the
 * zone is named only when its clock differs from this phone's at that moment. Wording without a date is shown as
 * unconfirmed: it never reads as a reminder date until the owner confirms it.
 */
data class DueDisplay(val atMs: Long?, val zone: ZoneId, val showZone: Boolean, val unconfirmedText: String?)

fun dueDisplay(due: Due?, device: ZoneId): DueDisplay? {
    val d = due ?: return null
    if (!d.hasContent()) return null
    val zone = d.zone?.let { name -> try { ZoneId.of(name) } catch (_: DateTimeException) { null } } ?: device
    val at = d.atMs
    val differs = at != null && Instant.ofEpochMilli(at).let { zone.rules.getOffset(it) != device.rules.getOffset(it) }
    return DueDisplay(at, zone, differs, if (at == null) d.sourceText?.trim() else null)
}

fun formatInstant(ms: Long, zone: ZoneId, locale: Locale = Locale.getDefault()): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).format(Instant.ofEpochMilli(ms).atZone(zone))

/**
 * `yyyy-MM-dd` and `HH:mm` for [ms] in [zone], the form's starting values for an existing date. These are text the owner
 * edits and [com.digifender.adminifender.core.model.parseTime] reads back, so the digits are always ASCII whatever the
 * phone's language; only [formatInstant] is localized.
 */
fun formFields(ms: Long, zone: ZoneId): Pair<String, String> {
    val local = Instant.ofEpochMilli(ms).atZone(zone).toLocalDateTime()
    return local.toLocalDate().toString() to String.format(Locale.ROOT, "%02d:%02d", local.hour, local.minute)
}

/**
 * Starting follow-up fields for the wait form: the follow-up the concern already has, even after it has passed. A wait
 * replaces the whole record, so an overdue follow-up must stay in the form (and be kept by [com.digifender.adminifender.core.model.buildWait])
 * or an edit to the note alone would erase it.
 */
fun waitStartFields(waiting: Waiting?, zone: ZoneId): Pair<String, String>? = waiting?.followUpAtMs?.let { formFields(it, zone) }

/** The date [days] after today in [zone], for the quick-pick buttons. */
fun datePreset(nowMs: Long, zone: ZoneId, days: Long): String = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().plusDays(days).toString()

/** Default time for a new date, the same 09:00 the snooze-until-tomorrow control uses. */
const val DEFAULT_FORM_TIME = "09:00"
