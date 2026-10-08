package com.digifender.adminifender.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.digifender.adminifender.R
import com.digifender.adminifender.core.data.repository.ConcernView
import com.digifender.adminifender.core.data.repository.InboxFilter
import java.time.ZoneId

@Composable
fun InboxScreen(state: InboxUiState, nowMs: Long, onFilter: (InboxFilter) -> Unit, onRefresh: () -> Unit, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                state.freshness?.let { f ->
                    Text(freshnessText(f, nowMs), style = MaterialTheme.typography.labelLarge)
                    if (f.needsFullDownload && f.lastSyncedAtMs != null) Text(stringResource(R.string.fresh_full_download), style = MaterialTheme.typography.bodySmall)
                    if (f.queued > 0) Text(pluralStringResource(R.plurals.queued_changes, f.queued, f.queued), style = MaterialTheme.typography.bodySmall)
                    if (f.needsOwner > 0) Text(pluralStringResource(R.plurals.needs_owner, f.needsOwner, f.needsOwner), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
            TextButton(onClick = onRefresh) { Text(stringResource(R.string.refresh)) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            InboxFilter.entries.forEach { f ->
                FilterChip(selected = state.filter == f, onClick = { onFilter(f) }, label = { Text(filterText(f)) })
            }
        }
        val items = state.items
        when {
            items == null -> Text(stringResource(R.string.inbox_loading), Modifier.padding(vertical = 24.dp))
            items.isEmpty() -> Text(stringResource(R.string.inbox_empty), Modifier.padding(vertical = 24.dp))
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                items(items, key = { it.server.id }) { ConcernCard(it, onOpen) }
            }
        }
    }
}

@Composable
private fun filterText(f: InboxFilter) = stringResource(
    when (f) {
        InboxFilter.ACTIVE -> R.string.filter_active
        InboxFilter.WAITING -> R.string.filter_waiting
        InboxFilter.SNOOZED -> R.string.filter_snoozed
        InboxFilter.RESOLVED -> R.string.filter_resolved
    },
)

@Composable
private fun ConcernCard(view: ConcernView, onOpen: (String) -> Unit) {
    val c = view.shown
    Card(Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(c.id) }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(c.title, style = MaterialTheme.typography.titleMedium)
            // State is spelled out in words, never by color alone (NFR-006).
            val labels = buildList {
                add(importanceText(c.importance))
                if (c.readAtMs == null) add(stringResource(R.string.label_unread))
                dueDisplay(c.due, ZoneId.systemDefault())?.let { add(dueText(it)) }
                if (view.pending > 0) add(stringResource(R.string.label_pending))
                if (view.conflicts.isNotEmpty() || view.failed.isNotEmpty()) add(pluralStringResource(R.plurals.needs_owner, view.conflicts.size + view.failed.size, view.conflicts.size + view.failed.size))
            }
            Text(labels.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
