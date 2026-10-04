package com.digifender.adminifender.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.digifender.adminifender.ui.theme.Glyphs
import com.digifender.adminifender.ui.theme.LedgerCard
import com.digifender.adminifender.ui.theme.LedgerTones
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.digifender.adminifender.R
import com.digifender.adminifender.core.data.network.ConcernDto
import com.digifender.adminifender.core.data.network.DeviceDto
import com.digifender.adminifender.core.data.network.ReviewDto
import com.digifender.adminifender.core.data.network.toDomain
import com.digifender.adminifender.core.data.repository.previewOrNull
import java.time.Instant

@Composable
fun BriefingScreen(state: BriefingUiState, onRetry: () -> Unit, onReviewed: () -> Unit, onOpenConcern: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.briefing_title), style = MaterialTheme.typography.headlineMedium)
        state.problem?.let {
            Text(onlineProblem(it), color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRetry) { Text(stringResource(R.string.try_again)) }
        }
        if (state.loading) Text(stringResource(R.string.briefing_loading))
        val b = state.briefing ?: return@Column
        Text(
            b.lastReviewedAt?.let { stringResource(R.string.briefing_since, dateTime(Instant.parse(it).toEpochMilli())) } ?: stringResource(R.string.briefing_first),
            style = MaterialTheme.typography.bodySmall,
        )
        if (b.newSourceIds.isNotEmpty()) Text(pluralStringResource(R.plurals.briefing_new_mail, b.newSourceIds.size, b.newSourceIds.size))
        Section(R.string.briefing_new, b.newConcerns, onOpenConcern)
        Section(R.string.briefing_changed, b.changedConcerns, onOpenConcern)
        Section(R.string.briefing_overdue, b.overdue, onOpenConcern)
        Section(R.string.briefing_unacknowledged, b.unacknowledged, onOpenConcern)
        if (state.reviewed) {
            Text(stringResource(R.string.briefing_reviewed))
        } else {
            // Marking reviewed acknowledges and resolves nothing; it only moves the review point (FR-019).
            OutlinedButton(onClick = onReviewed) { Text(stringResource(R.string.briefing_mark_reviewed)) }
            Text(stringResource(R.string.briefing_mark_note), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Section(title: Int, items: List<ConcernDto>, onOpen: (String) -> Unit) {
    Text(stringResource(title), style = MaterialTheme.typography.titleMedium)
    if (items.isEmpty()) Text(stringResource(R.string.briefing_none), style = MaterialTheme.typography.bodySmall)
    items.forEach { dto ->
        val c = dto.toDomain() ?: return@forEach
        Card(Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(c.id) }, colors = LedgerCard.colors(), border = LedgerCard.border) {
            Column(Modifier.padding(12.dp)) {
                Text(c.title, style = MaterialTheme.typography.titleSmall)
                Text(listOfNotNull(statusText(c.status), importanceText(c.importance), c.due?.atMs?.let { stringResource(R.string.label_due, dateTime(it)) }).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun onlineProblem(p: OnlineProblem) = stringResource(
    when (p) {
        OnlineProblem.OFFLINE -> R.string.online_offline
        OnlineProblem.SIGN_IN -> R.string.online_sign_in
        OnlineProblem.REFUSED -> R.string.online_refused
    },
)

/** The server's exact preview of a reviewed effect, then Confirm with a passkey. */
@Composable
fun ReviewPanel(state: ReviewUiState, onConfirm: () -> Unit, onDismiss: () -> Unit, onDone: () -> Unit) {
    if (state is ReviewUiState.Idle) return
    val danger = (state as? ReviewUiState.Ready)?.review?.effect == "archive.delete"
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, border = LedgerCard.border, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (state) {
                ReviewUiState.Preparing -> Text(stringResource(R.string.review_preparing))
                is ReviewUiState.Ready -> if (state.note == ReviewNote.UNKNOWN) {
                    // The request may have been applied: say so plainly; checking re-sends the same key, never a new effect.
                    Box(Modifier.size(56.dp).background(LedgerTones.dueSoon.container, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Glyphs.alert, contentDescription = null, tint = LedgerTones.dueSoon.content, modifier = Modifier.size(28.dp))
                    }
                    Text(stringResource(R.string.review_uncertain_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                    Text(stringResource(R.string.review_unknown), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    previewLines(state.review).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    PillButtons(primary = stringResource(R.string.review_check_status), onPrimary = onConfirm, secondary = stringResource(R.string.review_leave), onSecondary = onDismiss, enabled = !state.confirming)
                } else {
                    Text(stringResource(R.string.review_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                    previewLines(state.review).forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
                    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.medium).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Glyphs.shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Text(stringResource(R.string.review_passkey_note), style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(stringResource(R.string.review_expires, dateTime(Instant.parse(state.review.expiresAt).toEpochMilli())), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.note?.let { Text(reviewNote(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    PillButtons(
                        primary = stringResource(R.string.review_confirm), onPrimary = onConfirm,
                        secondary = stringResource(R.string.cancel), onSecondary = onDismiss,
                        enabled = !state.confirming, primaryEnabled = state.review.previewOrNull() != null, danger = danger,
                    )
                }
                is ReviewUiState.Done -> {
                    Text(stringResource(R.string.review_done), style = MaterialTheme.typography.bodyLarge)
                    OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.review_close)) }
                }
                is ReviewUiState.Failed -> {
                    Text(reviewNote(state.note), style = MaterialTheme.typography.bodyLarge)
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.review_close)) }
                }
                ReviewUiState.Idle -> Unit
            }
        }
    }
}

/** Full-width pill buttons stacked as on the canvas: one filled action, one outlined way out. */
@Composable
private fun PillButtons(primary: String, onPrimary: () -> Unit, secondary: String, onSecondary: () -> Unit, enabled: Boolean, primaryEnabled: Boolean = true, danger: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onPrimary,
            enabled = enabled && primaryEnabled,
            colors = if (danger) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError) else ButtonDefaults.buttonColors(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text(primary) }
        OutlinedButton(onClick = onSecondary, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(secondary) }
    }
}

@Composable
private fun previewLines(review: ReviewDto): List<String> {
    val p = review.previewOrNull() ?: return listOf(stringResource(R.string.review_unreadable))
    return when (review.effect) {
        "archive.delete" -> buildList {
            add(pluralStringResource(R.plurals.review_delete_sources, p.subjects.size, p.subjects.size))
            p.subjects.forEach { add("• " + (it ?: stringResource(R.string.no_subject))) }
            if (p.attachments > 0) add(pluralStringResource(R.plurals.review_delete_attachments, p.attachments, p.attachments))
            if (p.concernsErased > 0) add(pluralStringResource(R.plurals.review_delete_erased, p.concernsErased, p.concernsErased))
            if (p.concernsDetached > 0) add(pluralStringResource(R.plurals.review_delete_detached, p.concernsDetached, p.concernsDetached))
            add(stringResource(R.string.review_delete_derived))
            add(stringResource(R.string.review_delete_permanent))
        }
        else -> buildList {
            add(stringResource(R.string.review_revoke, p.deviceName ?: "?"))
            if (p.currentDevice) add(stringResource(R.string.review_revoke_current))
        }
    }
}

@Composable
private fun reviewNote(n: ReviewNote) = stringResource(
    when (n) {
        ReviewNote.OFFLINE -> R.string.online_offline
        ReviewNote.SIGN_IN -> R.string.online_sign_in
        ReviewNote.REFUSED -> R.string.online_refused
        ReviewNote.REVIEW_AGAIN -> R.string.review_again
        ReviewNote.PASSKEY_CANCELLED -> R.string.review_passkey_cancelled
        ReviewNote.UNKNOWN -> R.string.review_unknown
    },
)

/** Devices and archive export, shown at the end of Settings. */
@Composable
fun OwnerSettingsSection(state: OwnerSettingsState, review: ReviewUiState, onRevoke: (String) -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit, onExport: () -> Unit, onSaveExport: () -> Unit) {
    Text(stringResource(R.string.settings_devices), style = MaterialTheme.typography.titleMedium)
    state.devicesProblem?.let { Text(onlineProblem(it), style = MaterialTheme.typography.bodySmall) }
    state.devices?.forEach { d -> DeviceRow(d, onRevoke) }
    ReviewPanel(review, onConfirm, onDismiss, onDismiss)

    Text(stringResource(R.string.settings_export), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.export_note), style = MaterialTheme.typography.bodySmall)
    state.exportProblem?.let { Text(onlineProblem(it), color = MaterialTheme.colorScheme.error) }
    val export = state.export
    when (export?.state) {
        null, "failed", "invalidated", "expired" -> {
            export?.let { Text(stringResource(R.string.export_state_ended, it.state)) }
            OutlinedButton(onClick = onExport) { Text(stringResource(R.string.export_create)) }
        }
        "ready" -> {
            Text(stringResource(R.string.export_ready, dateTime(Instant.parse(export.expiresAt).toEpochMilli())))
            OutlinedButton(onClick = onSaveExport, enabled = !state.saving) { Text(stringResource(R.string.export_save)) }
        }
        else -> Text(stringResource(R.string.export_preparing))
    }
    state.saved?.let {
        Text(
            stringResource(
                when (it) {
                    SaveNote.SAVED -> R.string.save_done
                    SaveNote.FAILED -> R.string.save_failed
                    SaveNote.EXPIRED -> R.string.save_expired
                },
            ),
        )
    }
}

@Composable
private fun DeviceRow(d: DeviceDto, onRevoke: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) {
            Text(d.name + if (d.current) " " + stringResource(R.string.device_this_phone) else "")
            d.lastSeen?.let { Text(stringResource(R.string.device_last_seen, dateTime(Instant.parse(it).toEpochMilli())), style = MaterialTheme.typography.bodySmall) }
        }
        TextButton(onClick = { onRevoke(d.id) }) { Text(stringResource(R.string.device_revoke)) }
    }
}
