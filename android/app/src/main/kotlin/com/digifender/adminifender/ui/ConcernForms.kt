package com.digifender.adminifender.ui

import com.digifender.adminifender.core.model.ConcernAction
import java.time.DateTimeException
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Calendar input stays in an explicit zone. A DST gap is never silently moved to another hour. */
data class ConcernTimeFields(val date: String = "", val time: String = "", val zone: String = ZoneId.systemDefault().id, val offset: String = "")

enum class ConcernFormError { DATE_TIME, ZONE, NONEXISTENT_TIME, AMBIGUOUS_TIME, OFFSET, WAIT_REASON, PARTY_TOO_LONG, NOTE_TOO_LONG }

sealed interface ConcernFormResult {
    data class Valid(val action: ConcernAction) : ConcernFormResult
    data class Invalid(val error: ConcernFormError, val offsets: List<String> = emptyList()) : ConcernFormResult
}

private sealed interface TimeResult {
    data class Valid(val atMs: Long?, val zone: String?) : TimeResult
    data class Invalid(val error: ConcernFormResult.Invalid) : TimeResult
}

private fun parseTime(fields: ConcernTimeFields, optional: Boolean): TimeResult {
    val date = fields.date.trim()
    val time = fields.time.trim()
    if (optional && date.isEmpty() && time.isEmpty()) return TimeResult.Valid(null, null)
    val local = try {
        require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(date) && Regex("[0-9]{2}:[0-9]{2}").matches(time))
        LocalDate.parse(date).atTime(LocalTime.parse(time))
    } catch (_: IllegalArgumentException) {
        return TimeResult.Invalid(ConcernFormResult.Invalid(ConcernFormError.DATE_TIME))
    } catch (_: DateTimeException) {
        return TimeResult.Invalid(ConcernFormResult.Invalid(ConcernFormError.DATE_TIME))
    }
    // Match the server's IANA-zone contract; fixed offsets use a named zone such as Etc/GMT+5.
    val zone = try {
        val id = fields.zone.trim()
        require(id.length <= 64 && Regex("[A-Za-z0-9_+\\-/]+").matches(id) && id in ZoneId.getAvailableZoneIds())
        ZoneId.of(id)
    } catch (_: IllegalArgumentException) {
        return TimeResult.Invalid(ConcernFormResult.Invalid(ConcernFormError.ZONE))
    } catch (_: DateTimeException) {
        return TimeResult.Invalid(ConcernFormResult.Invalid(ConcernFormError.ZONE))
    }
    val offsets = zone.rules.getValidOffsets(local)
    if (offsets.isEmpty()) return TimeResult.Invalid(ConcernFormResult.Invalid(ConcernFormError.NONEXISTENT_TIME))
    val requested = fields.offset.trim()
    if (offsets.size > 1 && requested.isEmpty()) return TimeResult.Invalid(ConcernFormResult.Invalid(ConcernFormError.AMBIGUOUS_TIME, offsets.map { it.id }))
    val offset = if (requested.isEmpty()) offsets.single() else try {
        ZoneOffset.of(requested).takeIf { it in offsets } ?: return TimeResult.Invalid(ConcernFormResult.Invalid(ConcernFormError.OFFSET))
    } catch (_: DateTimeException) {
        return TimeResult.Invalid(ConcernFormResult.Invalid(ConcernFormError.OFFSET))
    }
    return TimeResult.Valid(local.toInstant(offset).toEpochMilli(), zone.id)
}

fun dueForm(fields: ConcernTimeFields): ConcernFormResult = when (val time = parseTime(fields, optional = false)) {
    is TimeResult.Invalid -> time.error
    // Null sourceText means preserve the original wording on both phone and server.
    is TimeResult.Valid -> ConcernFormResult.Valid(ConcernAction.SetDue(time.atMs, time.zone, null))
}

fun waitForm(party: String, note: String, fields: ConcernTimeFields): ConcernFormResult {
    val person = party.trim().ifEmpty { null }
    val reason = note.trim().ifEmpty { null }
    if (person == null && reason == null) return ConcernFormResult.Invalid(ConcernFormError.WAIT_REASON)
    if ((person?.length ?: 0) > 200) return ConcernFormResult.Invalid(ConcernFormError.PARTY_TOO_LONG)
    if ((reason?.length ?: 0) > 500) return ConcernFormResult.Invalid(ConcernFormError.NOTE_TOO_LONG)
    return when (val time = parseTime(fields, optional = true)) {
        is TimeResult.Invalid -> time.error
        is TimeResult.Valid -> ConcernFormResult.Valid(ConcernAction.Wait(reason, person, time.atMs))
    }
}

/** The displayed clock time and zone always agree, even when the phone uses another zone. */
fun dueDateTime(ms: Long, zone: String?, locale: Locale = Locale.getDefault()): String {
    val selected = try { zone?.let(ZoneId::of) ?: ZoneId.systemDefault() } catch (_: DateTimeException) { ZoneId.of("UTC") }
    return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).withZone(selected).format(Instant.ofEpochMilli(ms)) + " (${selected.id})"
}
