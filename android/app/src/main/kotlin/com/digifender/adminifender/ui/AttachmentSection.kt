package com.digifender.adminifender.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.digifender.adminifender.R
import com.digifender.adminifender.core.data.files.AttachmentPreview
import com.digifender.adminifender.core.data.files.FileState
import com.digifender.adminifender.core.data.files.LocalFile
import com.digifender.adminifender.core.data.files.MAX_ATTACHMENT_BYTES
import com.digifender.adminifender.core.data.repository.AttachmentInfo

/** What the owner can do with one attachment; each is an explicit request, and none opens anything outside the app. */
class AttachmentActions(
    val onAsk: (AttachmentInfo) -> Unit,
    val onCancel: (String) -> Unit,
    val onRemove: (String) -> Unit,
    val onShow: (AttachmentInfo) -> Unit,
    val onSave: (AttachmentInfo) -> Unit,
    val onNoticeShown: () -> Unit,
    val onClosePreview: () -> Unit,
)

@Composable
fun AttachmentRow(info: AttachmentInfo, file: LocalFile?, busy: Boolean, actions: AttachmentActions) {
    val context = LocalContext.current
    val size = Formatter.formatShortFileSize(context, info.sizeBytes)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(info.name, style = MaterialTheme.typography.bodyMedium)
        Text(listOfNotNull(info.declaredType, size, extractionText(info.extraction, info.extractionDetail)).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        Text(fileStateText(info, file), style = MaterialTheme.typography.bodySmall, color = if (file?.state == FileState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (file?.state) {
                null, FileState.FAILED -> if (info.sizeBytes <= MAX_ATTACHMENT_BYTES) {
                    OutlinedButton(onClick = { actions.onAsk(info) }) { Text(stringResource(if (file == null) R.string.attachment_download else R.string.attachment_download_again)) }
                }
                FileState.QUEUED, FileState.DOWNLOADING, FileState.HELD -> OutlinedButton(onClick = { actions.onCancel(info.id) }) { Text(stringResource(R.string.attachment_cancel)) }
                FileState.READY -> {
                    if (AttachmentPreview.eligible(info.declaredType, info.name, info.sizeBytes)) {
                        OutlinedButton(onClick = { actions.onShow(info) }, enabled = !busy) { Text(stringResource(R.string.attachment_view_text)) }
                    }
                    OutlinedButton(onClick = { actions.onSave(info) }, enabled = !busy) { Text(stringResource(R.string.attachment_save_copy)) }
                    TextButton(onClick = { actions.onRemove(info.id) }) { Text(stringResource(R.string.attachment_remove)) }
                }
            }
        }
    }
}

@Composable
private fun fileStateText(info: AttachmentInfo, file: LocalFile?): String = when {
    file == null && info.sizeBytes > MAX_ATTACHMENT_BYTES -> stringResource(R.string.attachment_state_too_large)
    file == null && info.sha256.isEmpty() -> stringResource(R.string.attachment_state_needs_refresh)
    file == null -> stringResource(R.string.attachment_state_none)
    file.state == FileState.QUEUED -> stringResource(if (file.attempts > 0) R.string.attachment_state_retrying else R.string.attachment_state_queued)
    file.state == FileState.DOWNLOADING -> stringResource(R.string.attachment_state_downloading)
    file.state == FileState.HELD -> stringResource(R.string.attachment_state_held)
    file.state == FileState.READY -> stringResource(R.string.attachment_state_ready)
    else -> stringResource(R.string.attachment_state_failed, fileErrorText(file.errorCode))
}

@Composable
fun fileErrorText(code: String?): String = stringResource(
    when (code) {
        "too_large" -> R.string.attachment_error_too_large
        "size_mismatch" -> R.string.attachment_error_size
        "hash_mismatch" -> R.string.attachment_error_hash
        "gone" -> R.string.attachment_error_gone
        "no_space" -> R.string.attachment_error_space
        "storage", "storage_key" -> R.string.attachment_error_storage
        "network" -> R.string.attachment_error_network
        else -> R.string.attachment_error_other
    },
)

/** The one-line result of the last attachment action, and the plain-text view when one is open. */
@Composable
fun AttachmentNoticeAndPreview(state: AttachmentsUiState, actions: AttachmentActions) {
    val notice = state.notice
    if (notice != null) {
        val name = state.noticeName.orEmpty()
        Text(
            stringResource(
                when (notice) {
                    AttachmentNotice.TOO_LARGE -> R.string.attachment_notice_too_large
                    AttachmentNotice.NEEDS_REFRESH -> R.string.attachment_notice_needs_refresh
                    AttachmentNotice.SOURCE_GONE -> R.string.attachment_notice_source_gone
                    AttachmentNotice.SAVED -> R.string.attachment_notice_saved
                    AttachmentNotice.SAVE_FAILED -> R.string.attachment_notice_save_failed
                    AttachmentNotice.SAVE_LEFTOVER -> R.string.attachment_notice_save_leftover
                    AttachmentNotice.DAMAGED -> R.string.attachment_notice_damaged
                    AttachmentNotice.NOT_AVAILABLE -> R.string.attachment_notice_not_available
                    AttachmentNotice.NOT_SHOWN -> R.string.attachment_notice_not_shown
                },
                name,
            ),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        TextButton(onClick = actions.onNoticeShown) { Text(stringResource(R.string.dismiss)) }
    }
    val preview = state.preview
    if (preview != null) {
        AlertDialog(
            onDismissRequest = actions.onClosePreview,
            title = { Text(stringResource(R.string.attachment_preview_title, preview.name)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Plain text only: nothing in a file is a link, a command or markup the app acts on.
                    SelectionContainer { Text(preview.text, style = MaterialTheme.typography.bodySmall) }
                    if (preview.truncated) Text(stringResource(R.string.attachment_preview_truncated), style = MaterialTheme.typography.labelSmall)
                }
            },
            confirmButton = { TextButton(onClick = actions.onClosePreview) { Text(stringResource(R.string.close)) } },
        )
    }
}
