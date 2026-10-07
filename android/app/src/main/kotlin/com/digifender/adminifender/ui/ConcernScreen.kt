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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.digifender.adminifender.R
import com.digifender.adminifender.core.data.network.SourceSummary
import com.digifender.adminifender.core.data.sync.QueuedMutation
import com.digifender.adminifender.core.model.ActionKind
import com.digifender.adminifender.core.model.ConcernAction
import com.digifender.adminifender.core.model.availableActions
import com.digifender.adminifender.core.model.Status
import java.time.ZoneId

@Composable
fun ConcernScreen(
    state: ConcernUiState,
    nowMs: Long,
    onAct: (ConcernAction, Int?) -> Unit,
    onReapply: (String) -> Unit,
    onDiscard: (String) -> Unit,
    sources: List<SourceSummary>,
    onOpenSource: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = state.view
    var form by remember(view?.server?.id) { mutableStateOf<ConcernForm?>(null) }
    if (view != null) form?.let { ConcernFormDialog(it, view.shown, onAct) { form = null } }
    Column(modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (view == null) {
            if (state.loaded) Text(stringResource(R.string.concern_missing))
            return@Column
        }
        val c = view.shown
        Text(c.title, style = MaterialTheme.typography.headlineSmall)
        Text(listOf(statusText(c.status), importanceText(c.importance)).joinToString(" · "), style = MaterialTheme.typography.labelLarge)
        c.due?.atMs?.let { Text(stringResource(R.string.label_due, dueDateTime(it, c.due?.zone))) }
        c.due?.zone?.let { Text(stringResource(R.string.concern_due_zone, it)) }
        c.due?.sourceText?.let { Text(stringResource(R.string.concern_source_due, it)) }
        c.snoozeUntilMs?.let { Text(stringResource(R.string.label_snoozed_until, dateTime(it))) }
        if (c.status == Status.WAITING) {
            c.waiting?.awaitedParty?.let { Text(stringResource(R.string.label_waiting_on, it)) }
            c.waiting?.note?.let { Text(it) }
            c.waiting?.followUpAtMs?.let { Text(stringResource(R.string.concern_follow_up_at, dateTime(it))) }
        }
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
                OutlinedButton(onClick = { onAct(action, null) }) { Text(stringResource(label)) }
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ActionKind.WAIT in availableActions(c.status)) {
                OutlinedButton(onClick = { form = ConcernForm.WAIT }) { Text(stringResource(if (c.status == Status.WAITING) R.string.action_edit_wait else R.string.action_wait)) }
            }
            OutlinedButton(onClick = { form = ConcernForm.DUE }) { Text(stringResource(R.string.action_edit_due)) }
            if (c.due?.atMs != null) {
                TextButton(onClick = { onAct(ConcernAction.SetDue(null, c.due?.zone, null), c.revision) }) { Text(stringResource(R.string.action_clear_due)) }
            }
        }

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
