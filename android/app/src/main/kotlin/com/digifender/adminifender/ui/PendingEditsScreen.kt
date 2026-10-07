package com.digifender.adminifender.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.digifender.adminifender.R
import com.digifender.adminifender.core.data.repository.PendingEditView
import com.digifender.adminifender.core.data.sync.MutationState
import com.digifender.adminifender.core.model.ActionKind
import com.digifender.adminifender.core.model.ConcernAction
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun PendingEditsScreen(edits: List<PendingEditView>, onReapply: (String) -> Unit, onDiscard: (String) -> Unit) {
    LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(stringResource(R.string.pending_edits_title), style = MaterialTheme.typography.headlineSmall) }
        if (edits.isEmpty()) item { Text(stringResource(R.string.pending_edits_empty)) }
        items(edits, key = { it.queued.mutation.idempotencyKey }) { edit ->
            val queued = edit.queued
            val mutation = queued.mutation
            val action = when (mutation.action.kind) {
                ActionKind.ACKNOWLEDGE -> R.string.action_acknowledge
                ActionKind.RESOLVE -> R.string.action_resolve
                ActionKind.REOPEN -> R.string.action_reopen
                ActionKind.SNOOZE -> R.string.pending_action_snooze
                ActionKind.WAIT -> R.string.action_wait
                ActionKind.SET_DUE -> R.string.action_edit_due
                ActionKind.MARK_READ -> R.string.pending_action_read
            }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(action), style = MaterialTheme.typography.titleMedium)
                    Text(edit.title ?: stringResource(if (queued.errorCode == "source_deleted") R.string.pending_deleted_item else R.string.pending_uncached_item))
                    Text(stringResource(R.string.pending_queued_at, dateTime(mutation.createdAtMs)), style = MaterialTheme.typography.bodySmall)
                    // Exact owner intent is visible before it can overwrite a newer server value. If the target
                    // was deleted, keep its old details hidden along with the title and disable reapplication.
                    val current = edit.current
                    if (current != null) {
                        when (val change = mutation.action) {
                            is ConcernAction.SetDue -> {
                                Text(stringResource(R.string.pending_requested_due, change.atMs?.let { pendingDateTime(it, change.zone) } ?: stringResource(R.string.pending_none)))
                                change.zone?.let { Text(stringResource(R.string.pending_zone, it)) }
                                change.sourceText?.let { Text(stringResource(R.string.pending_evidence, it)) }
                                Text(stringResource(R.string.pending_current_due, current.due?.atMs?.let { pendingDateTime(it, current.due?.zone) } ?: stringResource(R.string.pending_none)))
                                current.due?.zone?.let { Text(stringResource(R.string.pending_current_zone, it)) }
                            }
                            is ConcernAction.Snooze -> Text(stringResource(R.string.pending_snooze_until, dateTime(change.untilMs)))
                            is ConcernAction.Wait -> {
                                Text(stringResource(R.string.pending_wait_party, change.awaitedParty ?: stringResource(R.string.pending_none)))
                                Text(stringResource(R.string.pending_wait_note, change.note ?: stringResource(R.string.pending_none)))
                                Text(stringResource(R.string.pending_follow_up, change.followUpAtMs?.let(::dateTime) ?: stringResource(R.string.pending_none)))
                                current.waiting?.let { current ->
                                    Text(stringResource(R.string.pending_current_wait_party, current.awaitedParty ?: stringResource(R.string.pending_none)))
                                    Text(stringResource(R.string.pending_current_wait_note, current.note ?: stringResource(R.string.pending_none)))
                                    Text(stringResource(R.string.pending_current_follow_up, current.followUpAtMs?.let(::dateTime) ?: stringResource(R.string.pending_none)))
                                }
                            }
                            else -> Unit
                        }
                    }
                    Text(stringResource(when (queued.state) {
                        MutationState.QUEUED -> R.string.pending_waiting_sync
                        MutationState.HELD -> R.string.pending_needs_signin
                        MutationState.CONFLICT -> R.string.pending_needs_review
                        MutationState.FAILED -> R.string.pending_failed
                    }))
                    if (queued.state in setOf(MutationState.CONFLICT, MutationState.FAILED)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { onReapply(mutation.idempotencyKey) }, enabled = edit.canReapply) { Text(stringResource(R.string.conflict_reapply)) }
                            TextButton(onClick = { onDiscard(mutation.idempotencyKey) }) { Text(stringResource(R.string.conflict_discard)) }
                        }
                    }
                }
            }
        }
    }
}

/** Deadline clocks use their recorded zone, so the preview cannot silently reinterpret the requested instant. */
internal fun pendingDateTime(ms: Long, zone: String?): String {
    val selected = runCatching { zone?.let(ZoneId::of) ?: ZoneId.systemDefault() }.getOrDefault(ZoneId.of("UTC"))
    return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z").withZone(selected).format(Instant.ofEpochMilli(ms))
}
