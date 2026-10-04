package com.digifender.adminifender.ui

import com.digifender.adminifender.inference.ModelState
import com.digifender.adminifender.core.data.device.ArtifactCheck
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
import com.digifender.adminifender.core.data.repository.Freshness
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.google.firebase.FirebaseApp

/** The local model as the owner sees it, with the actions Settings offers for it. */
data class ModelUi(val state: ModelState, val onChoose: () -> Unit, val onRemove: () -> Unit)

@Composable
private fun modelText(state: ModelState): String = when (state) {
    ModelState.NotConfigured -> stringResource(R.string.model_not_configured)
    is ModelState.NotInstalled -> stringResource(R.string.model_not_installed, state.modelId)
    is ModelState.Installing -> stringResource(R.string.model_installing, state.modelId)
    is ModelState.Installed -> stringResource(R.string.model_installed, state.modelId)
    is ModelState.Rejected -> stringResource(if (state.check == ArtifactCheck.WRONG_HASH) R.string.model_wrong_hash else R.string.model_wrong_size, state.modelId)
}

@Composable
fun SettingsScreen(
    freshness: Freshness?,
    nowMs: Long,
    onSyncNow: () -> Unit,
    onSignOut: () -> Unit,
    onNotificationsChanged: () -> Unit,
    model: ModelUi,
    footer: @Composable () -> Unit,
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
    Column(modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.settings_local_model), style = MaterialTheme.typography.titleMedium)
        Text(modelText(model.state))
        when (model.state) {
            is ModelState.NotInstalled, is ModelState.Rejected -> OutlinedButton(onClick = model.onChoose) { Text(stringResource(R.string.model_choose)) }
            is ModelState.Installed -> OutlinedButton(onClick = model.onRemove) { Text(stringResource(R.string.model_remove)) }
            else -> Unit
        }
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
        Text(stringResource(R.string.settings_sync), style = MaterialTheme.typography.titleMedium)
        freshness?.let { Text(freshnessText(it, nowMs)) }
        OutlinedButton(onClick = onSyncNow) { Text(stringResource(R.string.settings_sync_now)) }
        Text(stringResource(R.string.settings_sign_out_body), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { confirming = true }) { Text(stringResource(R.string.settings_sign_out)) }
        footer()
    }
    if (confirming) {
        // Unsynced edits are named before they are dropped (lld.md#sync).
        val unsent = (freshness?.queued ?: 0) + (freshness?.needsOwner ?: 0)
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.sign_out_confirm_title)) },
            text = { Text(if (unsent > 0) pluralStringResource(R.plurals.sign_out_unsynced, unsent, unsent) else stringResource(R.string.sign_out_cached)) },
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
