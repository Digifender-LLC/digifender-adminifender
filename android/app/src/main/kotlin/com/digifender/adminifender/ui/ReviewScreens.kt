package com.digifender.adminifender.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
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
import java.time.Instant

@Composable
fun DeletionReviewScreen(state: DeletionUiState, nowMs: Long, onConfirm: () -> Unit, onReviewAgain: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.deletion_title), style = MaterialTheme.typography.headlineSmall)
        if (state.phase == DeletionPhase.SUCCEEDED) {
            Text(stringResource(R.string.deletion_succeeded))
            state.receipt?.let { Text(stringResource(R.string.deletion_receipt, it.operationId, it.receipt.ledgerSeq)) }
            return@Column
        }
        val review = state.review
        if (review != null) {
            val preview = review.preview
            Text(stringResource(R.string.deletion_warning, preview.expectedCount, preview.attachments))
            preview.sources.forEach { source ->
                Text(source.subject?.ifBlank { null } ?: stringResource(R.string.no_subject), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.deletion_source_identity, source.id, review.preconditions.sources.getValue(source.id)), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.source_received, dateTime(Instant.parse(source.receivedAt).toEpochMilli())))
                if (source.admission == "quarantine") Text(stringResource(R.string.deletion_quarantine))
            }
            Text(stringResource(R.string.deletion_concerns, preview.concernsErased.size, preview.concernsDetached.size))
            (preview.concernsErased + preview.concernsDetached).forEach { id ->
                Text(stringResource(R.string.deletion_concern_identity, id, review.preconditions.concerns.getValue(id)), style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(R.string.deletion_derivatives))
            Text(stringResource(R.string.deletion_expiry, dateTime(requireNotNull(state.expiresAtMs))))
            Text(stringResource(R.string.deletion_digest, review.paramsDigest), style = MaterialTheme.typography.bodySmall)
        }
        state.code?.let { TransferMessage(it) }
        when (state.phase) {
            DeletionPhase.LOADING -> Text(stringResource(R.string.deletion_loading))
            DeletionPhase.AUTHENTICATING -> Text(stringResource(R.string.deletion_authenticating))
            DeletionPhase.SENDING -> Text(stringResource(R.string.deletion_sending))
            DeletionPhase.UNKNOWN -> {
                Text(stringResource(R.string.deletion_unknown))
                Button(onClick = onConfirm) { Text(stringResource(R.string.deletion_retry_same)) }
            }
            DeletionPhase.REVIEW -> {
                var understood by remember(review?.reviewId) { mutableStateOf(false) }
                val expired = state.expiresAtMs?.let { it <= nowMs } != false
                if (expired) {
                    Text(stringResource(R.string.deletion_expired))
                    TextButton(onClick = onReviewAgain) { Text(stringResource(R.string.deletion_review_again)) }
                } else {
                    Row {
                        Checkbox(checked = understood, onCheckedChange = { understood = it })
                        Text(stringResource(R.string.deletion_acknowledge), modifier = Modifier.padding(top = 12.dp))
                    }
                    Button(onClick = onConfirm, enabled = understood) { Text(stringResource(R.string.deletion_confirm)) }
                }
            }
            DeletionPhase.ERROR, DeletionPhase.NEEDS_REVIEW -> {
                Text(stringResource(R.string.deletion_fresh_review))
                TextButton(onClick = onReviewAgain) { Text(stringResource(R.string.deletion_review_again)) }
            }
            DeletionPhase.SUCCEEDED -> Unit
        }
    }
}

@Composable
fun ExportScreen(state: ExportUiState, nowMs: Long, onCreate: () -> Unit, onRefresh: () -> Unit, onDownload: () -> Unit, onRemove: () -> Unit, onNew: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.export_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.export_scope))
        val export = state.export
        if (export == null) {
            if (state.unknownCreation) Text(stringResource(R.string.export_unknown))
            Button(onClick = onCreate, enabled = !state.busy) { Text(stringResource(if (state.unknownCreation) R.string.export_retry_same else R.string.export_create)) }
        } else {
            Text(stringResource(R.string.export_snapshot, export.snapshotSeq))
            val expired = Instant.parse(export.expiresAt).toEpochMilli() <= nowMs
            Text(stringResource(when {
                expired || export.state == "expired" -> R.string.export_expired
                export.state == "ready" -> R.string.export_ready
                export.state == "invalidated" -> R.string.export_invalidated
                export.state == "failed" -> R.string.export_failed
                else -> R.string.export_pending
            }))
            Text(stringResource(R.string.export_expiry, dateTime(Instant.parse(export.expiresAt).toEpochMilli())))
            if (!expired && export.state == "ready") {
                export.counts?.let { Text(stringResource(R.string.export_counts, it.sources, it.attachments, it.concerns, it.rules, it.services)) }
                val omissions = export.omissions
                if (omissions != null) {
                    Text(stringResource(R.string.export_omissions, omissions.size))
                    omissions.forEach { Text(stringResource(R.string.export_omitted_source, it.sourceId)) }
                }
                if (state.saved) {
                    Text(stringResource(R.string.export_saved))
                    TextButton(onClick = onRemove, enabled = !state.busy) { Text(stringResource(R.string.transfer_remove)) }
                } else Button(onClick = onDownload, enabled = !state.busy) { Text(stringResource(R.string.export_download)) }
            }
            TextButton(onClick = onRefresh, enabled = !state.busy) { Text(stringResource(R.string.export_refresh)) }
            TextButton(onClick = onNew, enabled = !state.busy) { Text(stringResource(R.string.export_new)) }
        }
        if (state.busy) Text(stringResource(R.string.transfer_busy))
        state.code?.let { TransferMessage(it) }
    }
}

@Composable
private fun TransferMessage(code: String) {
    Text(stringResource(when (code) {
        "sign_in", "unauthenticated" -> R.string.transfer_sign_in
        "auth_cancelled" -> R.string.transfer_auth_cancelled
        "auth_unavailable", "recent_auth_required" -> R.string.transfer_auth_unavailable
        "review_expired" -> R.string.deletion_expired
        "stale_review", "review_mismatch", "invalid_review" -> R.string.deletion_changed
        "ledger_unavailable" -> R.string.deletion_ledger_unavailable
        "integrity_mismatch", "unsafe_content" -> R.string.transfer_integrity
        "file_too_large" -> R.string.transfer_too_large
        "deleted", "download_invalidated", "invalidated" -> R.string.transfer_invalidated
        "expired" -> R.string.export_expired
        "local_cleanup_pending" -> R.string.transfer_cleanup_pending
        "journal_unavailable", "journal_unreadable" -> R.string.transfer_journal_unavailable
        else -> R.string.transfer_unavailable
    }))
}
