package com.digifender.adminifender.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.digifender.adminifender.R
import com.digifender.adminifender.core.data.network.ChatScope
import com.digifender.adminifender.core.data.network.ScopeKind

@Composable
fun SourceScreen(
    state: SourceUiState,
    busy: Boolean,
    files: AttachmentsUiState,
    actions: AttachmentActions,
    onRetry: () -> Unit,
    onOpenConcern: (String) -> Unit,
    onAsk: (ChatScope) -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val summary = state.summary
        if (state.status == TextStatus.GONE) {
            Text(stringResource(if (summary == null) R.string.source_deleted else R.string.source_missing))
            return@Column
        }
        if (summary == null && state.text == null) {
            if (state.loaded && state.status != TextStatus.LOADING) {
                Text(stringResource(R.string.source_missing))
                if (!busy) TextButton(onClick = onRetry) { Text(stringResource(R.string.source_retry)) }
            }
            return@Column
        }
        if (summary != null) {
            Text(summary.subject?.ifBlank { null } ?: stringResource(R.string.no_subject), style = MaterialTheme.typography.headlineSmall)
            if (summary.from.isNotEmpty()) Text(stringResource(R.string.source_from, summary.from.joinToString(", ")))
            Text(stringResource(R.string.source_received, dateTime(summary.receivedAtMs)), style = MaterialTheme.typography.labelLarge)
            Text(sourceLabels(summary), style = MaterialTheme.typography.bodySmall)
            // Quarantined, unfinished and AI-restricted emails are never offered as a chat scope.
            if (askable(summary)) OutlinedButton(onClick = { onAsk(ChatScope(ScopeKind.SOURCE, summary.id)) }) { Text(stringResource(R.string.chat_ask_email)) }
            // Deleting is an exact review of this one email first; nothing is deleted by this button.
            OutlinedButton(onClick = { onDelete(summary.id) }) { Text(stringResource(R.string.source_delete)) }
        }

        Text(stringResource(R.string.source_text), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(
                when (state.status) {
                    TextStatus.LOADING -> R.string.source_text_loading
                    TextStatus.SAVED -> R.string.source_text_saved
                    TextStatus.NOT_SAVED -> R.string.source_text_not_saved
                    TextStatus.OFFLINE -> if (state.textFromCache) R.string.source_text_cached else R.string.source_text_offline
                    TextStatus.SIGN_IN -> if (state.textFromCache) R.string.source_text_cached else R.string.source_text_sign_in
                    TextStatus.UNAVAILABLE -> if (state.textFromCache) R.string.source_text_cached else R.string.source_text_unavailable
                    TextStatus.GONE -> R.string.source_deleted
                },
            ),
            style = MaterialTheme.typography.bodySmall,
        )
        if (!busy && state.status in setOf(TextStatus.OFFLINE, TextStatus.SIGN_IN, TextStatus.UNAVAILABLE)) TextButton(onClick = onRetry) { Text(stringResource(R.string.source_retry)) }
        val text = state.text
        if (text != null) {
            if (!text.complete) Text(stringResource(R.string.source_text_partial), style = MaterialTheme.typography.bodySmall)
            // Email text is shown as plain text: nothing in it is a link, command or markup the app acts on.
            SelectionContainer { Text(text.text?.ifBlank { null } ?: stringResource(R.string.source_text_empty), style = MaterialTheme.typography.bodyMedium) }

            Text(stringResource(R.string.source_attachments), style = MaterialTheme.typography.titleMedium)
            if (text.attachments.isEmpty()) Text(stringResource(R.string.source_no_attachments))
            if (text.attachments.isNotEmpty()) Text(stringResource(R.string.attachment_storage_note), style = MaterialTheme.typography.bodySmall)
            text.attachments.forEach { AttachmentRow(it, files.files[it.id], files.busyId != null, actions) }
            AttachmentNoticeAndPreview(files, actions)
        }

        Text(stringResource(R.string.source_concerns), style = MaterialTheme.typography.titleMedium)
        if (state.concerns.isEmpty()) Text(stringResource(R.string.source_no_concerns))
        state.concerns.forEach { c ->
            Card(Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpenConcern(c.id) }) {
                Column(Modifier.padding(16.dp)) {
                    Text(c.title, style = MaterialTheme.typography.titleSmall)
                    Text(listOf(statusText(c.status), importanceText(c.importance)).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
fun extractionText(state: String, detail: String? = null) = stringResource(
    when {
        state == "ready" && detail == "limit" -> R.string.extraction_ready_limited
        state == "ready" -> R.string.extraction_ready
        state == "extracting" -> R.string.extraction_reading
        state == "deferred" && detail == "device_unavailable" -> R.string.extraction_waiting_for_phone
        state == "queued" || state == "deferred" -> R.string.extraction_pending
        state == "unsupported" && detail == "encrypted" -> R.string.extraction_encrypted
        state == "unsupported" && detail == "policy_blocked" -> R.string.extraction_policy_blocked
        state == "unsupported" -> R.string.extraction_unsupported
        state == "failed" -> R.string.extraction_failed
        else -> R.string.extraction_stored
    },
)
