package com.digifender.adminifender.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.digifender.adminifender.R
import com.digifender.adminifender.core.model.Concern
import com.digifender.adminifender.core.model.ConcernAction
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class ConcernForm { WAIT, DUE }

@Composable
fun ConcernFormDialog(form: ConcernForm, concern: Concern, onAct: (ConcernAction, Int?) -> Unit, onDismiss: () -> Unit) {
    // Drafts are memory-only; mail content is never written into the activity's saved-instance state.
    val openingRevision = remember(form, concern.id) { concern.revision }
    val changed = concern.revision != openingRevision
    val zone = remember(form, concern.id) {
        if (form == ConcernForm.DUE) concern.due?.zone ?: ZoneId.systemDefault().id else ZoneId.systemDefault().id
    }
    val initial = remember(form, concern.id) {
        val at = if (form == ConcernForm.DUE) concern.due?.atMs else concern.waiting?.followUpAtMs
        timeFields(at, zone)
    }
    var fields by remember(form, concern.id) { mutableStateOf(initial) }
    var party by remember(concern.id) { mutableStateOf(concern.waiting?.awaitedParty.orEmpty()) }
    var note by remember(concern.id) { mutableStateOf(concern.waiting?.note.orEmpty()) }
    var error by remember { mutableStateOf<ConcernFormResult.Invalid?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (form == ConcernForm.WAIT) R.string.action_wait else R.string.action_edit_due)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (form == ConcernForm.WAIT) {
                    OutlinedTextField(party, { party = it }, label = { Text(stringResource(R.string.concern_wait_party)) })
                    OutlinedTextField(note, { note = it }, label = { Text(stringResource(R.string.concern_wait_note)) })
                    Text(stringResource(R.string.concern_follow_up))
                } else {
                    concern.due?.sourceText?.let { Text(stringResource(R.string.concern_source_due, it)) }
                }
                OutlinedTextField(fields.date, { fields = fields.copy(date = it, offset = "") }, label = { Text(stringResource(R.string.concern_date)) }, singleLine = true)
                OutlinedTextField(fields.time, { fields = fields.copy(time = it, offset = "") }, label = { Text(stringResource(R.string.concern_time)) }, singleLine = true)
                OutlinedTextField(fields.zone, { fields = fields.copy(zone = it, offset = "") }, label = { Text(stringResource(R.string.concern_zone)) }, singleLine = true)
                OutlinedTextField(fields.offset, { fields = fields.copy(offset = it) }, label = { Text(stringResource(R.string.concern_offset)) }, singleLine = true)
                error?.let { Text(formError(it), color = MaterialTheme.colorScheme.error) }
                if (changed) Text(stringResource(R.string.concern_form_changed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = !changed, onClick = {
                when (val result = if (form == ConcernForm.WAIT) waitForm(party, note, fields) else dueForm(fields)) {
                    is ConcernFormResult.Invalid -> error = result
                    is ConcernFormResult.Valid -> { onAct(result.action, openingRevision); onDismiss() }
                }
            }) { Text(stringResource(R.string.concern_form_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.concern_form_cancel)) } },
    )
}

private fun timeFields(at: Long?, zone: String): ConcernTimeFields {
    if (at == null) return ConcernTimeFields(zone = zone)
    return try {
        val local = Instant.ofEpochMilli(at).atZone(ZoneId.of(zone))
        ConcernTimeFields(local.toLocalDate().toString(), local.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")), zone, local.offset.id)
    } catch (_: DateTimeException) {
        ConcernTimeFields(zone = zone)
    }
}

@Composable
private fun formError(error: ConcernFormResult.Invalid): String = if (error.error == ConcernFormError.AMBIGUOUS_TIME) {
    stringResource(R.string.concern_form_overlap_error, error.offsets.joinToString(" or "))
} else stringResource(
    when (error.error) {
        ConcernFormError.DATE_TIME -> R.string.concern_form_date_error
        ConcernFormError.ZONE -> R.string.concern_form_zone_error
        ConcernFormError.NONEXISTENT_TIME -> R.string.concern_form_gap_error
        ConcernFormError.OFFSET -> R.string.concern_form_offset_error
        ConcernFormError.WAIT_REASON -> R.string.concern_form_wait_reason_error
        ConcernFormError.PARTY_TOO_LONG -> R.string.concern_form_party_error
        ConcernFormError.NOTE_TOO_LONG -> R.string.concern_form_note_error
        ConcernFormError.AMBIGUOUS_TIME -> error("handled above")
    },
)
