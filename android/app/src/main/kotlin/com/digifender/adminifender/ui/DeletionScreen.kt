package com.digifender.adminifender.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.digifender.adminifender.R
import com.digifender.adminifender.core.data.network.CleanupState
import com.digifender.adminifender.core.data.network.ReviewState
import com.digifender.adminifender.core.data.review.DeletionPhase
import com.digifender.adminifender.core.data.review.DeletionReview

/** Callbacks of the deletion review. Only [onConfirm] can delete, and it asks for the passkey first. */
class DeletionActions(
    val onConfirm: () -> Unit,
    val onRetryPrepare: () -> Unit,
    val onPrepareAgain: () -> Unit,
    val onCheckStatus: () -> Unit,
    val onRefreshCleanup: () -> Unit,
    val onStopTracking: () -> Unit,
    val onDismiss: () -> Unit,
    val onBack: () -> Unit,
    val onNoteShown: () -> Unit,
)

/** How much of the review's SHA-256 fingerprint is shown; the whole digest is what the confirmation sends. */
private const val DIGEST_SHOWN = 16

@Composable
fun DeletionScreen(state: DeletionUiState, nowMs: Long, confirmable: Boolean, actions: DeletionActions, modifier: Modifier = Modifier) {
    val review = state.review
    Column(modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.deletion_title), style = MaterialTheme.typography.headlineSmall)
        state.note?.let { NoteLine(it, state.noteCode, actions.onNoteShown) }
        if (state.busy && review == null) Text(stringResource(R.string.deletion_preparing))
        if (review == null) {
            if (!state.busy) OutlinedButton(onClick = actions.onBack) { Text(stringResource(R.string.back)) }
            return@Column
        }
        when (review.phase) {
            DeletionPhase.PREPARING -> PreparingBody(review, state, actions)
            DeletionPhase.PREPARED, DeletionPhase.CONFIRMING -> ReviewBody(review, state, nowMs, confirmable, actions)
            DeletionPhase.SENDING -> UnknownBody(review, state, nowMs, confirmable, actions)
            DeletionPhase.SUCCEEDED -> DoneBody(review, state, actions)
            DeletionPhase.REJECTED, DeletionPhase.STOPPED -> EndedBody(review, state, actions)
        }
    }
}

@Composable
private fun NoteLine(note: DeletionNote, code: String?, onShown: () -> Unit) {
    val detail = code.orEmpty()
    val text = when (note) {
        DeletionNote.NOT_ON_PHONE -> stringResource(R.string.deletion_note_not_on_phone)
        DeletionNote.TOO_MANY -> stringResource(R.string.deletion_note_too_many, detail)
        DeletionNote.SIGN_IN -> stringResource(R.string.deletion_note_sign_in)
        DeletionNote.WAITING -> stringResource(R.string.deletion_note_waiting)
        DeletionNote.REFUSED -> stringResource(R.string.deletion_note_refused, detail)
        DeletionNote.MISMATCH -> stringResource(R.string.deletion_note_mismatch)
        DeletionNote.SUPERSEDED -> stringResource(R.string.deletion_note_superseded)
        DeletionNote.AUTH_CANCELLED -> stringResource(R.string.deletion_note_auth_cancelled)
        DeletionNote.AUTH_FAILED -> stringResource(R.string.deletion_note_auth_failed)
        DeletionNote.EXPIRED -> stringResource(R.string.deletion_note_expired)
        DeletionNote.CHANGED -> stringResource(R.string.deletion_note_changed)
        DeletionNote.REJECTED -> stringResource(R.string.deletion_note_rejected, detail)
        DeletionNote.NOT_COMPLETED -> stringResource(R.string.deletion_note_not_completed)
        DeletionNote.UNKNOWN -> stringResource(R.string.deletion_note_unknown)
        DeletionNote.READ_OPEN -> stringResource(R.string.deletion_note_read_open)
        DeletionNote.READ_EXPIRED_RECENTLY -> stringResource(R.string.deletion_note_read_expired_recently)
        DeletionNote.READ_UNSETTLED -> stringResource(R.string.deletion_note_read_unsettled)
        DeletionNote.READ_NOT_KNOWN -> stringResource(R.string.deletion_note_read_not_known)
        DeletionNote.READ_UNAVAILABLE -> stringResource(R.string.deletion_note_read_unavailable)
        DeletionNote.READ_MISMATCH -> stringResource(R.string.deletion_note_read_mismatch)
        DeletionNote.DELETED -> stringResource(R.string.deletion_note_deleted)
        DeletionNote.UNUSED -> stringResource(R.string.deletion_note_unused)
    }
    val problem = note != DeletionNote.DELETED && note != DeletionNote.READ_OPEN && note != DeletionNote.UNUSED
    Text(text, color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    TextButton(onClick = onShown) { Text(stringResource(R.string.dismiss)) }
}

@Composable
private fun Items(state: DeletionUiState) {
    state.items.forEach { item ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.subject?.ifBlank { null } ?: stringResource(if (item.onPhone) R.string.no_subject else R.string.deletion_item_not_on_phone), style = MaterialTheme.typography.titleSmall)
                item.receivedAtMs?.let { Text(dateTime(it), style = MaterialTheme.typography.bodySmall) }
                if (item.quarantined == true) Text(stringResource(R.string.label_quarantined), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun PreparingBody(review: DeletionReview, state: DeletionUiState, actions: DeletionActions) {
    Text(pluralStringResource(R.plurals.deletion_selected, review.sourceIds.size, review.sourceIds.size))
    Items(state)
    Text(stringResource(R.string.deletion_not_sent_yet), style = MaterialTheme.typography.bodySmall)
    if (!state.busy) {
        Button(onClick = actions.onRetryPrepare) { Text(stringResource(R.string.deletion_retry)) }
        TextButton(onClick = actions.onDismiss) { Text(stringResource(R.string.deletion_drop)) }
    }
}

@Composable
private fun ReviewBody(review: DeletionReview, state: DeletionUiState, nowMs: Long, confirmable: Boolean, actions: DeletionActions) {
    val preview = review.preview
    Text(stringResource(R.string.deletion_exact_intro), style = MaterialTheme.typography.titleMedium)
    Items(state)
    if (preview != null) {
        Text(pluralStringResource(R.plurals.deletion_files, preview.attachments, preview.attachments))
        if (preview.concernsErased.isNotEmpty()) Text(pluralStringResource(R.plurals.deletion_concerns_erased, preview.concernsErased.size, preview.concernsErased.size))
        if (preview.concernsDetached.isNotEmpty()) Text(pluralStringResource(R.plurals.deletion_concerns_detached, preview.concernsDetached.size, preview.concernsDetached.size))
        if (preview.derived.isNotEmpty()) Text(stringResource(R.string.deletion_derived, preview.derived.joinToString(", ")))
    }
    Text(stringResource(R.string.deletion_loss), style = MaterialTheme.typography.bodyMedium)
    Text(stringResource(R.string.deletion_irreversible), style = MaterialTheme.typography.bodyMedium)
    review.paramsDigest?.let { Text(stringResource(R.string.deletion_digest, it.take(DIGEST_SHOWN)), style = MaterialTheme.typography.bodySmall) }
    review.expiresAtMs?.let { Text(if (nowMs >= it) stringResource(R.string.deletion_expired) else stringResource(R.string.deletion_expires, dateTime(it)), style = MaterialTheme.typography.bodySmall) }
    if (confirmable) {
        Button(onClick = actions.onConfirm, enabled = !state.busy) { Text(stringResource(R.string.deletion_confirm)) }
    } else {
        Button(onClick = actions.onPrepareAgain, enabled = !state.busy) { Text(stringResource(R.string.deletion_review_again)) }
    }
    TextButton(onClick = actions.onDismiss, enabled = !state.busy) { Text(stringResource(R.string.deletion_cancel)) }
}

@Composable
private fun UnknownBody(review: DeletionReview, state: DeletionUiState, nowMs: Long, confirmable: Boolean, actions: DeletionActions) {
    Text(stringResource(R.string.deletion_unknown_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
    Text(stringResource(R.string.deletion_unknown_body))
    Items(state)
    review.readback?.let { read ->
        Text(
            stringResource(
                when (read.state) {
                    ReviewState.OPEN -> R.string.deletion_readback_open
                    ReviewState.EXPIRED -> R.string.deletion_readback_expired
                    ReviewState.CONSUMED -> R.string.deletion_readback_consumed
                },
                dateTime(read.checkedAtMs),
            ),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Button(onClick = actions.onCheckStatus, enabled = !state.busy) { Text(stringResource(R.string.deletion_check_status)) }
    if (confirmable) OutlinedButton(onClick = actions.onConfirm, enabled = !state.busy) { Text(stringResource(R.string.deletion_confirm_again)) }
    Text(stringResource(R.string.deletion_stop_tracking_body), style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = actions.onStopTracking, enabled = !state.busy) { Text(stringResource(R.string.deletion_stop_tracking)) }
}

@Composable
private fun DoneBody(review: DeletionReview, state: DeletionUiState, actions: DeletionActions) {
    Text(stringResource(R.string.deletion_done_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.deletion_done_body, review.receipt?.ledgerSeq ?: 0L))
    Text(pluralStringResource(R.plurals.deletion_selected, review.sourceIds.size, review.sourceIds.size))
    val cleanup = review.cleanup
    Text(stringResource(R.string.deletion_cleanup_heading), style = MaterialTheme.typography.titleSmall)
    if (cleanup == null) {
        Text(stringResource(R.string.deletion_cleanup_not_read))
    } else {
        Text(
            when (cleanup.state) {
                CleanupState.PENDING -> stringResource(R.string.deletion_cleanup_pending)
                CleanupState.PARTIAL -> stringResource(R.string.deletion_cleanup_partial, cleanup.completed, cleanup.total)
                CleanupState.COMPLETE -> stringResource(R.string.deletion_cleanup_complete)
                CleanupState.FAILED -> stringResource(R.string.deletion_cleanup_failed, cleanup.failed)
                CleanupState.UNKNOWN -> stringResource(R.string.deletion_cleanup_unknown, cleanup.unrecorded)
            },
            color = if (cleanup.state == CleanupState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        review.cleanupCheckedAtMs?.let { Text(stringResource(R.string.deletion_cleanup_checked, dateTime(it)), style = MaterialTheme.typography.bodySmall) }
    }
    // The scope is stated every time: this is the live archive, not backups or export files.
    Text(stringResource(R.string.deletion_cleanup_scope), style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = actions.onRefreshCleanup, enabled = !state.busy) { Text(stringResource(R.string.deletion_cleanup_check)) }
    TextButton(onClick = actions.onDismiss) { Text(stringResource(R.string.deletion_done_close)) }
}

@Composable
private fun EndedBody(review: DeletionReview, state: DeletionUiState, actions: DeletionActions) {
    Text(
        stringResource(if (review.phase == DeletionPhase.STOPPED) R.string.deletion_stopped_title else R.string.deletion_rejected_title),
        style = MaterialTheme.typography.titleMedium,
    )
    Text(if (review.phase == DeletionPhase.STOPPED) stringResource(R.string.deletion_stopped_body) else stringResource(R.string.deletion_rejected_body, review.errorCode.orEmpty()))
    Items(state)
    if (review.phase == DeletionPhase.REJECTED) Button(onClick = actions.onPrepareAgain, enabled = !state.busy) { Text(stringResource(R.string.deletion_review_again)) }
    TextButton(onClick = actions.onDismiss) { Text(stringResource(R.string.deletion_drop)) }
}
