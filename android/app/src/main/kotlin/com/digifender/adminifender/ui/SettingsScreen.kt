package com.digifender.adminifender.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.unit.dp
import com.digifender.adminifender.R
import com.digifender.adminifender.core.data.repository.ChatUnsent
import com.digifender.adminifender.core.data.repository.Freshness
import com.digifender.adminifender.core.data.review.DeletionPending
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.google.firebase.FirebaseApp

@Composable
fun SettingsScreen(
    freshness: Freshness?,
    chatUnsent: ChatUnsent,
    nowMs: Long,
    onSyncNow: () -> Unit,
    onSignOut: () -> Unit,
    onNotificationsChanged: () -> Unit,
    onOpenStorage: () -> Unit,
    deletionPending: DeletionPending,
    modifier: Modifier = Modifier,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    val configured = remember { FirebaseApp.getApps(context).isNotEmpty() }
    // The owner may change the permission in system settings; re-read it whenever the screen resumes.
    LifecycleResumeEffect(Unit) {
        val now = NotificationManagerCompat.from(context).areNotificationsEnabled()
        if (now != allowed) {
            allowed = now
            onNotificationsChanged()
        }
        onPauseOrDispose {}
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = granted
        onNotificationsChanged()
    }
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.settings_notifications), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(
                when {
                    !configured -> R.string.notifications_unconfigured
                    allowed -> R.string.notifications_on
                    else -> R.string.notifications_off
                },
            ),
        )
        if (configured && !allowed) {
            OutlinedButton(onClick = {
                // Android 13+ asks at runtime; on 12 notifications were switched off in system settings.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    request.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }
            }) { Text(stringResource(R.string.notifications_allow)) }
        }
        Text(stringResource(R.string.settings_storage), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.settings_storage_body), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onOpenStorage) { Text(stringResource(R.string.settings_storage_open)) }
        Text(stringResource(R.string.settings_sync), style = MaterialTheme.typography.titleMedium)
        freshness?.let { Text(freshnessText(it, nowMs)) }
        OutlinedButton(onClick = onSyncNow) { Text(stringResource(R.string.settings_sync_now)) }
        Text(stringResource(R.string.settings_sign_out_body), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { confirming = true }) { Text(stringResource(R.string.settings_sign_out)) }
    }
    if (confirming) {
        // Unsynced edits are named before they are dropped (lld.md#sync), and so are chat questions the server has not
        // confirmed, unsent drafts and questions the server is still answering.
        val unsent = (freshness?.queued ?: 0) + (freshness?.needsOwner ?: 0)
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.sign_out_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (unsent > 0) pluralStringResource(R.plurals.sign_out_unsynced, unsent, unsent) else stringResource(R.string.sign_out_cached))
                    if (chatUnsent.unconfirmedQuestions > 0) Text(pluralStringResource(R.plurals.sign_out_chat_unconfirmed, chatUnsent.unconfirmedQuestions, chatUnsent.unconfirmedQuestions))
                    if (chatUnsent.drafts > 0) Text(pluralStringResource(R.plurals.sign_out_chat_drafts, chatUnsent.drafts, chatUnsent.drafts))
                    if (deletionPending.unknown > 0) Text(pluralStringResource(R.plurals.sign_out_deletion_unknown, deletionPending.unknown, deletionPending.unknown))
                    if (deletionPending.open > 0) Text(pluralStringResource(R.plurals.sign_out_deletion_open, deletionPending.open, deletionPending.open))
                    Text(stringResource(R.string.sign_out_files))
                    if (chatUnsent.atWorker > 0) Text(pluralStringResource(R.plurals.sign_out_chat_at_worker, chatUnsent.atWorker, chatUnsent.atWorker))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onSignOut()
                }) { Text(stringResource(R.string.sign_out_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
