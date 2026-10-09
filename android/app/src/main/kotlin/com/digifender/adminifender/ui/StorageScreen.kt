package com.digifender.adminifender.ui

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.digifender.adminifender.R
import com.digifender.adminifender.core.data.export.ExportDownload
import com.digifender.adminifender.core.data.export.ExportIntent
import com.digifender.adminifender.core.data.export.ExportPhase
import com.digifender.adminifender.core.data.files.ATTACHMENT_CACHE_BYTES
import com.digifender.adminifender.core.data.files.FileState
import com.digifender.adminifender.core.data.files.LocalFile
import com.digifender.adminifender.core.data.network.ExportState
import com.digifender.adminifender.core.data.review.DeletionPhase
import com.digifender.adminifender.core.data.review.DeletionReview

@Composable
fun StorageScreen(
    state: StorageUiState,
    onRemoveFile: (String) -> Unit,
    onClearFiles: () -> Unit,
    onOpenSource: (String) -> Unit,
    onOpenExport: (String) -> Unit,
    onNewExport: () -> Unit,
    onOpenDeletion: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmingClear by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    Column(modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.storage_title), style = MaterialTheme.typography.headlineSmall)

        Text(stringResource(R.string.storage_files_heading), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.storage_files_used, Formatter.formatShortFileSize(context, state.usedBytes), Formatter.formatShortFileSize(context, ATTACHMENT_CACHE_BYTES)))
        Text(stringResource(R.string.storage_files_note), style = MaterialTheme.typography.bodySmall)
        if (state.files.isEmpty()) Text(stringResource(R.string.storage_files_none))
        state.files.forEach { file -> FileCard(file, onRemoveFile, onOpenSource) }
        if (state.files.isNotEmpty()) OutlinedButton(onClick = { confirmingClear = true }) { Text(stringResource(R.string.storage_clear_files)) }

        Text(stringResource(R.string.storage_exports_heading), style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = onNewExport) { Text(stringResource(R.string.storage_new_export)) }
        if (state.exports.isEmpty()) Text(stringResource(R.string.storage_exports_none))
        state.exports.forEach { ExportCard(it, onOpenExport) }

        val deletions = state.deletions.filter { it.phase != DeletionPhase.STOPPED }
        if (deletions.isNotEmpty()) {
            Text(stringResource(R.string.storage_deletions_heading), style = MaterialTheme.typography.titleMedium)
            deletions.forEach { DeletionCard(it, onOpenDeletion) }
        }
        TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
    }
    if (confirmingClear) {
        AlertDialog(
            onDismissRequest = { confirmingClear = false },
            title = { Text(stringResource(R.string.storage_clear_title)) },
            text = { Text(stringResource(R.string.storage_clear_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingClear = false
                    onClearFiles()
                }) { Text(stringResource(R.string.storage_clear_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmingClear = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun FileCard(file: LocalFile, onRemove: (String) -> Unit, onOpenSource: (String) -> Unit) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpenSource(file.sourceId) }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(file.name, style = MaterialTheme.typography.titleSmall)
            val size = Formatter.formatShortFileSize(context, file.sizeBytes)
            Text(
                when (file.state) {
                    FileState.READY -> stringResource(R.string.storage_file_ready, size)
                    FileState.QUEUED -> stringResource(R.string.attachment_state_queued)
                    FileState.DOWNLOADING -> stringResource(R.string.attachment_state_downloading)
                    FileState.HELD -> stringResource(R.string.attachment_state_held)
                    FileState.FAILED -> stringResource(R.string.storage_file_failed, fileErrorText(file.errorCode))
                },
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { onRemove(file.attachmentId) }) { Text(stringResource(if (file.state == FileState.READY) R.string.attachment_remove else R.string.attachment_cancel)) }
        }
    }
}

@Composable
private fun ExportCard(intent: ExportIntent, onOpen: (String) -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(intent.localId) }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(scopeText(intent.scope), style = MaterialTheme.typography.titleSmall)
            Text(dateTime(intent.createdAtMs), style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(
                    when {
                        intent.download == ExportDownload.VERIFIED -> R.string.storage_export_verified
                        intent.download == ExportDownload.DOWNLOADING -> R.string.storage_export_downloading
                        intent.download == ExportDownload.INTERRUPTED -> R.string.storage_export_interrupted
                        intent.download == ExportDownload.FAILED -> R.string.storage_export_download_failed
                        intent.phase == ExportPhase.CREATING -> R.string.storage_export_unconfirmed
                        intent.phase == ExportPhase.REFUSED -> R.string.storage_export_refused
                        intent.phase == ExportPhase.STOPPED -> R.string.storage_export_stopped
                        else -> when (intent.status?.state) {
                            ExportState.READY -> R.string.storage_export_ready
                            ExportState.FAILED -> R.string.storage_export_failed
                            ExportState.EXPIRED -> R.string.storage_export_expired
                            ExportState.INVALIDATED -> R.string.storage_export_invalidated
                            else -> R.string.storage_export_preparing
                        }
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DeletionCard(review: DeletionReview, onOpen: (String) -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(review.localId) }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(pluralStringResource(R.plurals.deletion_selected, review.sourceIds.size, review.sourceIds.size), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(
                    when (review.phase) {
                        DeletionPhase.PREPARING -> R.string.storage_deletion_preparing
                        DeletionPhase.PREPARED, DeletionPhase.CONFIRMING -> R.string.storage_deletion_open
                        DeletionPhase.SENDING -> R.string.storage_deletion_unknown
                        DeletionPhase.SUCCEEDED -> R.string.storage_deletion_done
                        DeletionPhase.REJECTED -> R.string.storage_deletion_rejected
                        DeletionPhase.STOPPED -> R.string.storage_deletion_stopped
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
