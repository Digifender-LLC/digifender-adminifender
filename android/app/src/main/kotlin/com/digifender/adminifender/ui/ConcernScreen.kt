package com.digifender.adminifender.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.digifender.adminifender.R
import com.digifender.adminifender.core.data.network.SourceSummary
import com.digifender.adminifender.core.data.repository.ConcernView
import com.digifender.adminifender.core.data.sync.QueuedMutation
import com.digifender.adminifender.core.model.ActionKind
import com.digifender.adminifender.core.model.ConcernAction
import com.digifender.adminifender.core.model.availableActions
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun ConcernScreen(
    state: ConcernUiState,
    nowMs: Long,
    onAct: (ConcernAction) -> Unit,
    onReapply: (String) -> Unit,
    onDiscard: (String) -> Unit,
    sources: List<SourceSummary>,
    onOpenSource: (String) -> Unit,
    onAsk: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = state.view
    Column(modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (view == null) {
            if (state.loaded) Text(stringResource(R.string.concern_missing))
            return@Column
        }
        val c = view.shown
        Text(c.title, style = MaterialTheme.typography.headlineSmall)
        Text(listOf(statusText(c.status), importanceText(c.importance)).joinToString(" · "), style = MaterialTheme.typography.labelLarge)
        c.due?.atMs?.let { Text(stringResource(R.string.label_due, dateTime(it))) }
        c.snoozeUntilMs?.let { Text(stringResource(R.string.label_snoozed_until, dateTime(it))) }
        c.waiting?.awaitedParty?.let { Text(stringResource(R.string.label_waiting_on, it)) }
        c.waiting?.note?.let { Text(it) }
        if (c.acknowledgedAtMs != null) Text(stringResource(R.string.label_acknowledged))
        Text(pluralStringResource(R.plurals.label_sources, c.sourceIds.size, c.sourceIds.size), style = MaterialTheme.typography.bodySmall)
        if (view.pending > 0) Text(stringResource(R.string.label_pending), style = MaterialTheme.typography.bodySmall)

        view.conflicts.forEach { NeedsOwner(it, conflict = true, onReapply, onDiscard) }
        view.failed.forEach { NeedsOwner(it, conflict = false, onReapply, onDiscard) }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            quickActions(c.status).forEach { kind ->
                val (label, action) = when (kind) {
                    ActionKind.ACKNOWLEDGE -> R.string.action_acknowledge to ConcernAction.Acknowledge
                    ActionKind.RESOLVE -> R.string.action_resolve to ConcernAction.Resolve
                    ActionKind.REOPEN -> R.string.action_reopen to ConcernAction.Reopen
                    ActionKind.SNOOZE -> R.string.action_snooze_tomorrow to ConcernAction.Snooze(tomorrowMorning(nowMs, ZoneId.systemDefault()))
                    else -> return@forEach
                }
                OutlinedButton(onClick = { onAct(action) }) { Text(stringResource(label)) }
            }
        }
        val actions = availableActions(c.status)
        if (ActionKind.WAIT in actions) WaitForm(c.waiting?.awaitedParty, c.waiting?.note) { party, note, days -> onAct(waitAction(party, note, days, nowMs, ZoneId.systemDefault())) }
        if (ActionKind.SET_DUE in actions) DueForm(hasDue = c.due?.atMs != null) { date -> onAct(dueAction(date, ZoneId.systemDefault())) }
        // Chat about this item uses only its own mail (FR-025).
        if (c.sourceIds.isNotEmpty()) OutlinedButton(onClick = onAsk) { Text(stringResource(R.string.ask_about_concern)) }

        if (c.sourceIds.isNotEmpty()) {
            Text(stringResource(R.string.concern_sources), style = MaterialTheme.typography.titleMedium)
            val byId = sources.associateBy { it.id }
            // Listed in the order the server gave; a source not synced yet is named but cannot be opened.
            c.sourceIds.forEach { id ->
                val source = byId[id]
                if (source != null) SourceCard(source, onOpenSource) else Text(stringResource(R.string.concern_source_not_cached), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun WaitForm(party: String?, note: String?, onSave: (String, String, Int?) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    if (!open) {
        OutlinedButton(onClick = { open = true }) { Text(stringResource(R.string.action_wait)) }
        return
    }
    var partyText by rememberSaveable { mutableStateOf(party.orEmpty()) }
    var noteText by rememberSaveable { mutableStateOf(note.orEmpty()) }
    var days by rememberSaveable { mutableStateOf<Int?>(FOLLOW_UP_DAYS[2]) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.wait_title), style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(partyText, { partyText = it.take(AWAITED_PARTY_MAX) }, label = { Text(stringResource(R.string.wait_party)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(noteText, { noteText = it.take(WAIT_NOTE_MAX) }, label = { Text(stringResource(R.string.wait_note)) }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.wait_follow_up), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FOLLOW_UP_DAYS.forEach { d ->
                    FilterChip(
                        selected = days == d,
                        onClick = { days = d },
                        label = { Text(if (d == null) stringResource(R.string.wait_no_follow_up) else pluralStringResource(R.plurals.wait_days, d, d)) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    onSave(partyText, noteText, days)
                    open = false
                }) { Text(stringResource(R.string.save)) }
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DueForm(hasDue: Boolean, onSet: (LocalDate?) -> Unit) {
    var picking by rememberSaveable { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { picking = true }) { Text(stringResource(if (hasDue) R.string.due_change else R.string.due_set)) }
        if (hasDue) TextButton(onClick = { onSet(null) }) { Text(stringResource(R.string.due_clear)) }
    }
    if (picking) {
        val picker = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { onSet(pickedDate(it)) }
                    picking = false
                }, enabled = picker.selectedDateMillis != null) { Text(stringResource(R.string.save)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.cancel)) } },
        ) {
            DatePicker(state = picker, title = { Text(stringResource(R.string.due_picker_title), Modifier.padding(16.dp)) })
        }
    }
}

@Composable
private fun NeedsOwner(q: QueuedMutation, conflict: Boolean, onReapply: (String) -> Unit, onDiscard: (String) -> Unit) {
    val key = q.mutation.idempotencyKey
    val kind = q.mutation.action.kind.wire
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.conflict_title), style = MaterialTheme.typography.titleSmall)
            Text(if (conflict) stringResource(R.string.conflict_body, kind) else stringResource(R.string.failed_body, kind, q.errorCode ?: "unknown"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (conflict) TextButton(onClick = { onReapply(key) }) { Text(stringResource(R.string.conflict_reapply)) }
                TextButton(onClick = { onDiscard(key) }) { Text(stringResource(R.string.conflict_discard)) }
            }
        }
    }
}
