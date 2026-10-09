package com.digifender.adminifender.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.digifender.adminifender.R
import com.digifender.adminifender.core.data.export.ExportDownload
import com.digifender.adminifender.core.data.export.ExportIntent
import com.digifender.adminifender.core.data.export.ExportPhase
import com.digifender.adminifender.core.data.network.ExportAdmission
import com.digifender.adminifender.core.data.network.ExportScope
import com.digifender.adminifender.core.data.network.ExportState
import com.digifender.adminifender.core.data.network.InvalidatedReason
import com.digifender.adminifender.core.data.network.OmissionReason

/** The admission in words, always shown wherever a scope is: quarantine is never implied. */
@Composable
fun admissionText(admission: ExportAdmission) = stringResource(
    when (admission) {
        ExportAdmission.NORMAL -> R.string.export_admission_normal_long
        ExportAdmission.QUARANTINE -> R.string.export_admission_quarantine_long
        ExportAdmission.ALL -> R.string.export_admission_all_long
    },
)

/** A full sentence for a scope, so what an export covers is never left to be inferred. */
@Composable
fun scopeText(scope: ExportScope): String = when (scope) {
    is ExportScope.Archive -> stringResource(R.string.export_scope_archive, admissionText(scope.admission))
    is ExportScope.Sources -> pluralStringResource(R.plurals.export_scope_sources, scope.sourceIds.size, scope.sourceIds.size, admissionText(scope.admission))
    is ExportScope.Filtered -> {
        val f = scope.filter
        val parts = buildList {
            f.fromMs?.let { add(stringResource(R.string.export_scope_from, dateTime(it))) }
            f.toMs?.let { add(stringResource(R.string.export_scope_to, dateTime(it))) }
            f.sender?.let { add(stringResource(R.string.export_scope_sender, it)) }
            f.recipient?.let { add(stringResource(R.string.export_scope_recipient, it)) }
        }
        stringResource(R.string.export_scope_filter, admissionText(scope.admission), parts.joinToString("; "))
    }
}

@Composable
fun ExportBuilderScreen(
    state: ExportBuilderState,
    onKind: (ExportKind) -> Unit,
    onAdmission: (ExportAdmission) -> Unit,
    onFrom: (String) -> Unit,
    onTo: (String) -> Unit,
    onSender: (String) -> Unit,
    onRecipient: (String) -> Unit,
    onCreate: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.export_new_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.export_new_intro), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.export_what), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = state.kind == ExportKind.ARCHIVE, onClick = { onKind(ExportKind.ARCHIVE) }, label = { Text(stringResource(R.string.export_kind_archive)) })
            FilterChip(selected = state.kind == ExportKind.FILTER, onClick = { onKind(ExportKind.FILTER) }, label = { Text(stringResource(R.string.export_kind_filter)) })
            if (state.selectedCount > 0) {
                FilterChip(selected = state.kind == ExportKind.SELECTED, onClick = { onKind(ExportKind.SELECTED) }, label = { Text(pluralStringResource(R.plurals.export_kind_selected, state.selectedCount, state.selectedCount)) })
            }
        }
        Text(stringResource(R.string.export_which_mail), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExportAdmission.entries.forEach { a ->
                FilterChip(
                    selected = state.admission == a,
                    onClick = { onAdmission(a) },
                    label = { Text(stringResource(when (a) { ExportAdmission.NORMAL -> R.string.export_admission_normal; ExportAdmission.QUARANTINE -> R.string.export_admission_quarantine; ExportAdmission.ALL -> R.string.export_admission_all })) },
                )
            }
        }
        Text(admissionText(state.admission), style = MaterialTheme.typography.bodySmall)
        if (state.kind == ExportKind.FILTER) {
            OutlinedTextField(state.from, onFrom, label = { Text(stringResource(R.string.export_field_from)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(state.to, onTo, label = { Text(stringResource(R.string.export_field_to)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(state.sender, onSender, label = { Text(stringResource(R.string.export_field_sender)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
            OutlinedTextField(state.recipient, onRecipient, label = { Text(stringResource(R.string.export_field_recipient)) }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
            Text(stringResource(R.string.export_filter_help), style = MaterialTheme.typography.bodySmall)
        }
        if (state.kind == ExportKind.SELECTED) Text(pluralStringResource(R.plurals.export_selected_all, state.selectedCount, state.selectedCount), style = MaterialTheme.typography.bodySmall)
        if (state.kind == ExportKind.ARCHIVE) Text(stringResource(R.string.export_archive_rules_note), style = MaterialTheme.typography.bodySmall)
        state.problem?.let { Text(stringResource(builderProblemText(it)), color = MaterialTheme.colorScheme.error) }
        state.note?.let { note ->
            Text(
                when (note) {
                    CreateNote.WAITING -> stringResource(R.string.export_create_waiting)
                    CreateNote.REFUSED -> stringResource(R.string.export_create_refused, state.noteCode.orEmpty())
                    CreateNote.SIGN_IN -> stringResource(R.string.export_create_sign_in)
                    CreateNote.INVALID -> stringResource(R.string.export_create_invalid)
                },
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(onClick = onCreate, enabled = state.problem == null && !state.busy) { Text(stringResource(R.string.export_create)) }
        TextButton(onClick = onBack) { Text(stringResource(R.string.cancel)) }
    }
}

private fun builderProblemText(problem: BuilderProblem) = when (problem) {
    BuilderProblem.NO_SOURCES -> R.string.export_problem_no_sources
    BuilderProblem.TOO_MANY_SOURCES -> R.string.export_problem_too_many
    BuilderProblem.EMPTY_FILTER -> R.string.export_problem_empty_filter
    BuilderProblem.BAD_ADDRESS -> R.string.export_problem_address
    BuilderProblem.BAD_RANGE -> R.string.export_problem_range
    BuilderProblem.BAD_FROM_DATE -> R.string.export_problem_from_date
    BuilderProblem.BAD_TO_DATE -> R.string.export_problem_to_date
}

class ExportActions(
    val onSave: (suggestedName: String) -> Unit,
    val onRefresh: () -> Unit,
    val onResend: () -> Unit,
    val onStopTracking: () -> Unit,
    val onDismiss: () -> Unit,
    val onNewExport: () -> Unit,
    val onBack: () -> Unit,
    val onNoteShown: () -> Unit,
)

@Composable
fun ExportScreen(state: ExportUiState, nowMs: Long, actions: ExportActions, modifier: Modifier = Modifier) {
    val intent = state.intent
    Column(modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.export_title), style = MaterialTheme.typography.headlineSmall)
        if (intent == null) {
            if (state.loaded) {
                Text(stringResource(R.string.export_missing))
                OutlinedButton(onClick = actions.onBack) { Text(stringResource(R.string.back)) }
            }
            return@Column
        }
        Text(scopeText(intent.scope), style = MaterialTheme.typography.bodyMedium)
        state.note?.let {
            Text(
                stringResource(
                    when (it) {
                        ExportNote.SIGN_IN -> R.string.export_note_sign_in
                        ExportNote.WAITING -> R.string.export_note_waiting
                        ExportNote.UNREACHABLE -> R.string.export_note_unreachable
                    },
                ),
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = actions.onNoteShown) { Text(stringResource(R.string.dismiss)) }
        }
        StatusBlock(intent, nowMs, state.busy, actions)
        DownloadBlock(intent, actions)
        if (intent.phase != ExportPhase.CREATING && intent.download != ExportDownload.DOWNLOADING) {
            TextButton(onClick = actions.onDismiss) { Text(stringResource(R.string.export_remove_from_list)) }
        }
    }
}

@Composable
private fun StatusBlock(intent: ExportIntent, nowMs: Long, busy: Boolean, actions: ExportActions) {
    when (intent.phase) {
        ExportPhase.CREATING -> {
            Text(stringResource(R.string.export_creating_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.export_creating_body))
            Button(onClick = actions.onResend, enabled = !busy) { Text(stringResource(R.string.export_resend)) }
            TextButton(onClick = actions.onStopTracking) { Text(stringResource(R.string.export_stop_tracking)) }
        }
        ExportPhase.REFUSED -> {
            Text(stringResource(R.string.export_refused_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            Text(if (intent.errorCode == "scope_mismatch") stringResource(R.string.export_refused_scope) else stringResource(R.string.export_refused_body, intent.errorCode.orEmpty()))
            OutlinedButton(onClick = actions.onNewExport) { Text(stringResource(R.string.export_make_new)) }
        }
        ExportPhase.STOPPED -> {
            Text(stringResource(R.string.export_stopped_body))
            OutlinedButton(onClick = actions.onNewExport) { Text(stringResource(R.string.export_make_new)) }
        }
        ExportPhase.CREATED -> {
            val status = intent.status
            Text(stringResource(exportStateText(status?.state)), style = MaterialTheme.typography.titleMedium)
            intent.checkedAtMs?.let { Text(stringResource(R.string.export_checked, ageText(it, nowMs)), style = MaterialTheme.typography.bodySmall) }
            if (status != null) {
                if (status.state == ExportState.READY) {
                    Text(stringResource(R.string.export_expires, dateTime(status.expiresAtMs)), style = MaterialTheme.typography.bodySmall)
                    status.counts?.let { c -> Text(stringResource(R.string.export_counts, c.sources, c.attachments, c.concerns), style = MaterialTheme.typography.bodyMedium) }
                    OmissionsBlock(status.omissions.orEmpty())
                    Text(stringResource(R.string.export_original_note), style = MaterialTheme.typography.bodySmall)
                }
                if (status.state == ExportState.FAILED) {
                    Text(stringResource(R.string.export_failed_body, status.failureCode.orEmpty()), color = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.export_failed_no_partial), style = MaterialTheme.typography.bodySmall)
                }
                if (status.state == ExportState.INVALIDATED) Text(stringResource(invalidatedText(status.invalidatedReason)), color = MaterialTheme.colorScheme.error)
                if (status.state == ExportState.EXPIRED) Text(stringResource(R.string.export_expired_body))
            }
            intent.errorCode?.let { Text(stringResource(R.string.export_status_error, it), style = MaterialTheme.typography.bodySmall) }
            if (status?.state?.settled != true) OutlinedButton(onClick = actions.onRefresh, enabled = !busy) { Text(stringResource(R.string.refresh)) }
            if (status?.state == ExportState.FAILED || status?.state == ExportState.EXPIRED || status?.state == ExportState.INVALIDATED) {
                OutlinedButton(onClick = actions.onNewExport) { Text(stringResource(R.string.export_make_new)) }
            }
        }
    }
}

private fun exportStateText(state: ExportState?) = when (state) {
    null -> R.string.export_state_unread
    ExportState.PENDING -> R.string.export_state_pending
    ExportState.RUNNING -> R.string.export_state_running
    ExportState.READY -> R.string.export_state_ready
    ExportState.FAILED -> R.string.export_state_failed
    ExportState.EXPIRED -> R.string.export_state_expired
    ExportState.INVALIDATED -> R.string.export_state_invalidated
}

private fun invalidatedText(reason: InvalidatedReason?) = when (reason) {
    InvalidatedReason.DELETED -> R.string.export_invalidated_deleted
    InvalidatedReason.QUARANTINED -> R.string.export_invalidated_quarantined
    InvalidatedReason.ADMISSION_CHANGED -> R.string.export_invalidated_admission
    InvalidatedReason.SCOPE_UNKNOWN, null -> R.string.export_invalidated_scope_unknown
}

@Composable
private fun OmissionsBlock(omissions: List<com.digifender.adminifender.core.data.network.Omission>) {
    if (omissions.isEmpty()) {
        Text(stringResource(R.string.export_no_omissions), style = MaterialTheme.typography.bodySmall)
        return
    }
    Text(pluralStringResource(R.plurals.export_omissions, omissions.size, omissions.size), color = MaterialTheme.colorScheme.error)
    OmissionReason.entries.forEach { reason ->
        val n = omissions.count { it.reason == reason }
        if (n > 0) {
            Text(
                pluralStringResource(
                    when (reason) {
                        OmissionReason.DELETED -> R.plurals.export_omitted_deleted
                        OmissionReason.QUARANTINED -> R.plurals.export_omitted_quarantined
                        OmissionReason.ADMISSION_CHANGED -> R.plurals.export_omitted_admission
                        OmissionReason.EXCLUDED_BY_ADMISSION -> R.plurals.export_omitted_excluded
                        OmissionReason.NOT_IN_SNAPSHOT -> R.plurals.export_omitted_snapshot
                    },
                    n,
                    n,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DownloadBlock(intent: ExportIntent, actions: ExportActions) {
    val context = LocalContext.current
    val ready = intent.status?.state == ExportState.READY && intent.phase == ExportPhase.CREATED
    when (intent.download) {
        ExportDownload.NONE -> if (ready) SaveButton(R.string.export_save, intent, actions)
        ExportDownload.DOWNLOADING -> {
            Text(stringResource(R.string.export_downloading, intent.destinationLabel.orEmpty()), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.export_downloading_note), style = MaterialTheme.typography.bodySmall)
        }
        ExportDownload.VERIFIED -> {
            val v = intent.verification
            Text(stringResource(R.string.export_verified_title, intent.destinationLabel.orEmpty()), style = MaterialTheme.typography.titleSmall)
            if (v != null) {
                Text(stringResource(R.string.export_verified_body, v.entries, Formatter.formatShortFileSize(context, v.zipBytes)))
                Text(stringResource(R.string.export_verified_hashes, v.listedFiles), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.export_zip_hash, v.zipSha256), style = MaterialTheme.typography.bodySmall)
                if (v.partialConcerns > 0) Text(pluralStringResource(R.plurals.export_partial_concerns, v.partialConcerns, v.partialConcerns), style = MaterialTheme.typography.bodySmall)
                if (!v.rulesIncluded) Text(stringResource(R.string.export_rules_omitted), style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(R.string.export_outside_note), style = MaterialTheme.typography.bodySmall)
            // A copy that finished while its export was invalidated still holds what it held; say so.
            if (intent.status?.state == ExportState.INVALIDATED) Text(stringResource(R.string.export_verified_but_invalidated), color = MaterialTheme.colorScheme.error)
        }
        ExportDownload.INTERRUPTED, ExportDownload.FAILED -> {
            val interrupted = intent.download == ExportDownload.INTERRUPTED
            Text(stringResource(if (interrupted) R.string.export_interrupted_title else R.string.export_download_failed_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
            Text(stringResource(R.string.export_download_error, downloadErrorText(intent.downloadError)))
            Text(
                stringResource(
                    when (intent.destinationDiscarded) {
                        true -> R.string.export_partial_removed
                        false -> R.string.export_partial_may_remain
                        null -> R.string.export_partial_unknown
                    },
                    intent.destinationLabel.orEmpty(),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(stringResource(R.string.export_not_verified), style = MaterialTheme.typography.bodySmall)
            if (ready) SaveButton(R.string.export_retry_download, intent, actions)
        }
    }
}

@Composable
private fun SaveButton(label: Int, intent: ExportIntent, actions: ExportActions) {
    val name = "adminifender-export-${java.time.Instant.ofEpochMilli(intent.createdAtMs).toString().take(10)}.zip"
    Button(onClick = { actions.onSave(name) }) { Text(stringResource(label)) }
    Text(stringResource(R.string.export_save_note), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun downloadErrorText(code: String?): String {
    val text = when {
        code == null -> null
        code == "network" -> R.string.export_error_network
        code == "cancelled" -> R.string.export_error_cancelled
        code == "process_ended" -> R.string.export_error_process
        code == "destination_write" || code == "destination_short" -> R.string.export_error_destination
        code == "sign_in_required" -> R.string.export_error_sign_in
        code.startsWith("verification_") -> R.string.export_error_verification
        code == "expired" || code == "export_invalidated" -> R.string.export_error_gone
        else -> null
    }
    return if (text != null) stringResource(text) + (if (code != null && code.startsWith("verification_")) " (${code.removePrefix("verification_")})" else "") else code.orEmpty()
}
