package com.digifender.adminifender.ui

import android.text.format.Formatter
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.digifender.adminifender.R
import com.digifender.adminifender.core.data.network.ChatScope
import com.digifender.adminifender.core.data.network.ScopeKind
import com.digifender.adminifender.core.data.repository.AttachmentInfo

@Composable
fun SourceScreen(state: SourceUiState, busy: Boolean, onRetry: () -> Unit, onOpenConcern: (String) -> Unit, onAsk: (ChatScope) -> Unit, modifier: Modifier = Modifier) {
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
            text.attachments.forEach { AttachmentRow(it) }
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
private fun AttachmentRow(a: AttachmentInfo) {
    val context = LocalContext.current
    val size = Formatter.formatShortFileSize(context, a.sizeBytes)
    Column {
        Text(a.name, style = MaterialTheme.typography.bodyMedium)
        Text(listOfNotNull(a.declaredType, size, extractionText(a.extraction)).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun extractionText(state: String) = stringResource(
    when (state) {
        "ready" -> R.string.extraction_ready
        "queued", "extracting", "deferred" -> R.string.extraction_pending
        "unsupported" -> R.string.extraction_unsupported
        "failed" -> R.string.extraction_failed
        else -> R.string.extraction_stored
    },
)
