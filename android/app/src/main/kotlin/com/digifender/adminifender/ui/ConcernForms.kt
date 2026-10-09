package com.digifender.adminifender.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.digifender.adminifender.R
import com.digifender.adminifender.core.model.ConcernAction
import com.digifender.adminifender.core.model.Due
import com.digifender.adminifender.core.model.Fold
import com.digifender.adminifender.core.model.FormOutcome
import com.digifender.adminifender.core.model.FormProblem
import com.digifender.adminifender.core.model.Waiting
import com.digifender.adminifender.core.model.WAIT_NOTE_MAX
import com.digifender.adminifender.core.model.WAIT_PARTY_MAX
import com.digifender.adminifender.core.model.buildDue
import com.digifender.adminifender.core.model.buildWait
import com.digifender.adminifender.core.model.clearDue
import java.time.ZoneId

@Composable
private fun problemText(problem: FormProblem): String = stringResource(
    when (problem) {
        FormProblem.NOTE_TOO_LONG -> R.string.problem_note_too_long
        FormProblem.PARTY_TOO_LONG -> R.string.problem_party_too_long
        FormProblem.BAD_DATE -> R.string.problem_bad_date
        FormProblem.BAD_TIME -> R.string.problem_bad_time
        FormProblem.DATE_MISSING -> R.string.problem_date_missing
        FormProblem.FOLLOW_UP_IN_PAST -> R.string.problem_follow_up_past
        FormProblem.TIME_SKIPPED -> R.string.problem_time_skipped
        FormProblem.TIME_REPEATS -> R.string.problem_time_repeats
    },
)

/** Date and time text fields with quick date picks. Problems are shown in words under the fields, never by colour alone. */
@Composable
private fun DateTimeFields(
    dateLabel: String,
    date: String,
    time: String,
    onDate: (String) -> Unit,
    onTime: (String) -> Unit,
    nowMs: Long,
    zone: ZoneId,
    problems: Set<FormProblem>,
) {
    OutlinedTextField(
        value = date,
        onValueChange = onDate,
        label = { Text(dateLabel) },
        singleLine = true,
        isError = FormProblem.BAD_DATE in problems || FormProblem.DATE_MISSING in problems,
        // A number pad has no '-' or ':'; the ASCII keyboard does, and keeps locale digits out of the strict text.
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onDate(datePreset(nowMs, zone, 0)) }) { Text(stringResource(R.string.form_today)) }
        OutlinedButton(onClick = { onDate(datePreset(nowMs, zone, 1)) }) { Text(stringResource(R.string.form_tomorrow)) }
        OutlinedButton(onClick = { onDate(datePreset(nowMs, zone, 7)) }) { Text(stringResource(R.string.form_in_a_week)) }
    }
    OutlinedTextField(
        value = time,
        onValueChange = onTime,
        label = { Text(stringResource(R.string.form_time)) },
        singleLine = true,
        isError = FormProblem.BAD_TIME in problems,
        // A number pad has no '-' or ':'; the ASCII keyboard does, and keeps locale digits out of the strict text.
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
    )
    Text(stringResource(R.string.form_zone, zone.id), style = MaterialTheme.typography.bodySmall)
}

/** Which of two clock readings to use, offered when a local time happens twice. */
@Composable
private fun FoldChoice(outcome: FormOutcome.Invalid, zone: ZoneId, onChoose: (Fold) -> Unit) {
    val earlier = outcome.earlierMs ?: return
    val later = outcome.laterMs ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onChoose(Fold.EARLIER) }) { Text(stringResource(R.string.form_pick_earlier, formatInstant(earlier, zone))) }
        OutlinedButton(onClick = { onChoose(Fold.LATER) }) { Text(stringResource(R.string.form_pick_later, formatInstant(later, zone))) }
    }
}

@Composable
private fun Problems(outcome: FormOutcome.Invalid?) {
    outcome?.problems?.forEach { Text(problemText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

/**
 * Wait form. Starting values come from the current wait when there is one. Saving queues the change like any other
 * edit; nothing here claims the server has accepted it.
 */
@Composable
fun WaitDialog(initial: Waiting?, zone: ZoneId, nowMs: Long, onSave: (ConcernAction) -> Unit, onDismiss: () -> Unit) {
    val startFields = waitStartFields(initial, zone)
    var note by rememberSaveable { mutableStateOf(initial?.note.orEmpty()) }
    var party by rememberSaveable { mutableStateOf(initial?.awaitedParty.orEmpty()) }
    var date by rememberSaveable { mutableStateOf(startFields?.first.orEmpty()) }
    var time by rememberSaveable { mutableStateOf(startFields?.second ?: DEFAULT_FORM_TIME) }
    // Not saved across rotation: the typed fields are, and saving again recomputes the problems.
    var invalid by remember { mutableStateOf<FormOutcome.Invalid?>(null) }

    fun save(fold: Fold?) {
        when (val outcome = buildWait(note, party, date, time, zone, fold, nowMs, currentFollowUpMs = initial?.followUpAtMs)) {
            is FormOutcome.Ready -> {
                onSave(outcome.action)
                onDismiss()
            }
            is FormOutcome.Invalid -> invalid = outcome
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.form_wait_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.form_wait_body), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = party,
                    onValueChange = { party = it.take(WAIT_PARTY_MAX + 50) },
                    label = { Text(stringResource(R.string.form_party)) },
                    singleLine = true,
                    isError = FormProblem.PARTY_TOO_LONG in invalid?.problems.orEmpty(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(WAIT_NOTE_MAX + 50) },
                    label = { Text(stringResource(R.string.form_note)) },
                    isError = FormProblem.NOTE_TOO_LONG in invalid?.problems.orEmpty(),
                )
                if (initial?.followUpAtMs?.let { it <= nowMs } == true) Text(stringResource(R.string.form_follow_up_passed), style = MaterialTheme.typography.bodySmall)
                DateTimeFields(stringResource(R.string.form_follow_up_date), date, time, { date = it; invalid = null }, { time = it; invalid = null }, nowMs, zone, invalid?.problems.orEmpty())
                Problems(invalid)
                invalid?.takeIf { FormProblem.TIME_REPEATS in it.problems }?.let { FoldChoice(it, zone) { fold -> save(fold) } }
            }
        },
        confirmButton = { TextButton(onClick = { save(null) }) { Text(stringResource(R.string.form_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Due-date form. A date the email proposed without a confirmed instant is shown as wording and starts empty: saving
 * confirms it. Removing the date keeps the email's wording on the server, so it comes back as unconfirmed.
 */
@Composable
fun DueDialog(initial: Due?, zone: ZoneId, nowMs: Long, onSave: (ConcernAction) -> Unit, onDismiss: () -> Unit) {
    val startFields = initial?.atMs?.let { formFields(it, zone) }
    var date by rememberSaveable { mutableStateOf(startFields?.first.orEmpty()) }
    var time by rememberSaveable { mutableStateOf(startFields?.second ?: DEFAULT_FORM_TIME) }
    // Not saved across rotation: the typed fields are, and saving again recomputes the problems.
    var invalid by remember { mutableStateOf<FormOutcome.Invalid?>(null) }

    fun save(fold: Fold?) {
        when (val outcome = buildDue(date, time, zone, fold)) {
            is FormOutcome.Ready -> {
                onSave(outcome.action)
                onDismiss()
            }
            is FormOutcome.Invalid -> invalid = outcome
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.form_due_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                initial?.sourceText?.takeIf { it.isNotBlank() }?.let { Text(stringResource(R.string.form_due_email_said, it), style = MaterialTheme.typography.bodySmall) }
                DateTimeFields(stringResource(R.string.form_due_date), date, time, { date = it; invalid = null }, { time = it; invalid = null }, nowMs, zone, invalid?.problems.orEmpty())
                Problems(invalid)
                invalid?.takeIf { FormProblem.TIME_REPEATS in it.problems }?.let { FoldChoice(it, zone) { fold -> save(fold) } }
                if (initial?.atMs != null) {
                    TextButton(onClick = {
                        onSave(clearDue())
                        onDismiss()
                    }) { Text(stringResource(R.string.form_remove_due)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { save(null) }) { Text(stringResource(R.string.form_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
