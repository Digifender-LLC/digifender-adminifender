package com.digifender.adminifender.ui

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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.digifender.adminifender.R
import com.digifender.adminifender.core.data.repository.Freshness

@Composable
fun SettingsScreen(freshness: Freshness?, nowMs: Long, onSyncNow: () -> Unit, onSignOut: () -> Unit, modifier: Modifier = Modifier) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.settings_sync), style = MaterialTheme.typography.titleMedium)
        freshness?.let { Text(freshnessText(it, nowMs)) }
        OutlinedButton(onClick = onSyncNow) { Text(stringResource(R.string.settings_sync_now)) }
        Text(stringResource(R.string.settings_sign_out_body), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { confirming = true }) { Text(stringResource(R.string.settings_sign_out)) }
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
