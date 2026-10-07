package com.digifender.adminifender.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.digifender.adminifender.R
import com.digifender.adminifender.core.data.health.HealthArea
import com.digifender.adminifender.core.data.health.HealthPanel
import com.digifender.adminifender.core.data.health.HealthRepository
import com.digifender.adminifender.core.data.health.HealthStatus
import com.digifender.adminifender.core.data.health.HealthValue
import com.digifender.adminifender.core.data.repository.Freshness
import com.digifender.adminifender.core.model.NotificationChannelKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun HealthRoute(health: HealthRepository, probe: HealthDeviceProbe, freshness: Freshness? = null) {
    val model = viewModel(key = "private-health") { HealthViewModel(health, probe) }
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(model, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> model.onForeground()
                Lifecycle.Event.ON_STOP -> model.onBackground()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) model.onForeground()
        onDispose { lifecycle.removeObserver(observer); model.onBackground() }
    }
    HealthScreen(state, freshness, model::refresh, model::loadDiagnostics)
}

@Composable
fun HealthScreen(state: HealthUiState, freshness: Freshness?, onRefresh: () -> Unit, onDiagnostics: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(stringResource(R.string.health_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.health_intro))
            TextButton(onClick = onRefresh, enabled = !state.loading) { Text(stringResource(R.string.health_refresh)) }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.signInRequired) Text(stringResource(R.string.health_sign_in), color = MaterialTheme.colorScheme.error)
            else if (state.unavailable) Text(stringResource(R.string.health_unavailable), color = MaterialTheme.colorScheme.error)
            if (state.report == null && !state.loading && !state.unavailable && !state.signInRequired) Text(stringResource(R.string.health_no_observation))
            state.report?.let { Text(stringResource(R.string.health_generated, healthTime(it.generatedAt))) }
            if (state.oldObservation) Text(stringResource(R.string.health_old_observation), color = MaterialTheme.colorScheme.error)
            state.report?.warnings?.forEach { Text(stringResource(healthWarningLabel(it)), color = MaterialTheme.colorScheme.error) }
        }
        item { HealthLocalCard(state.local, freshness) }
        items(state.report?.panels.orEmpty(), key = { "server-${it.area}-${it.ordinal}" }) { panel ->
            HealthPanelCard(panel, state.now, state.oldObservation)
        }
        item {
            Text(stringResource(R.string.health_diagnostics_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.health_diagnostics_privacy))
            TextButton(onClick = onDiagnostics, enabled = !state.diagnosticsLoading && !state.signInRequired) { Text(stringResource(R.string.health_diagnostics_load)) }
            if (state.diagnosticsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.diagnosticsUnavailable) Text(stringResource(R.string.health_diagnostics_unavailable), color = MaterialTheme.colorScheme.error)
            state.diagnostics?.let {
                Text(stringResource(R.string.health_generated, healthTime(it.generatedAt)))
                if (it.staleAt(state.now)) Text(stringResource(R.string.health_old_observation), color = MaterialTheme.colorScheme.error)
            }
        }
        items(state.diagnostics?.panels.orEmpty(), key = { "diagnostic-${it.area}" }) { panel -> HealthPanelCard(panel, state.now, false) }
    }
}

@Composable
private fun HealthPanelCard(panel: HealthPanel, now: Long, old: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val title = stringResource(healthAreaLabel(panel.area))
            val ordinal = panel.ordinal
            Text(if (ordinal == null) title else stringResource(R.string.health_numbered, title, ordinal), style = MaterialTheme.typography.titleMedium)
            panel.statusAt(now)?.let { status -> Text(stringResource(healthStatusLabel(if (old && status == HealthStatus.OK) HealthStatus.STALE else status))) }
            panel.metrics.forEach { metric -> Text(stringResource(R.string.health_pair, stringResource(healthMetricLabel(metric.label)), healthValue(metric.value))) }
            when (panel.area) {
                HealthArea.NOTIFICATIONS -> Text(stringResource(R.string.health_push_note), style = MaterialTheme.typography.bodySmall)
                HealthArea.ALLOWANCE -> Text(stringResource(R.string.health_budget_note), style = MaterialTheme.typography.bodySmall)
                HealthArea.DEVICE -> Text(stringResource(R.string.health_device_note), style = MaterialTheme.typography.bodySmall)
                HealthArea.DIAGNOSTICS -> Text(stringResource(R.string.health_summary_note), style = MaterialTheme.typography.bodySmall)
                else -> Unit
            }
        }
    }
}

@Composable
private fun HealthLocalCard(local: HealthDeviceSnapshot, freshness: Freshness?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.health_this_device), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.health_local_note), style = MaterialTheme.typography.bodySmall)
            LocalMetric(R.string.health_notifications_enabled, flag(local.notificationsEnabled))
            for (kind in NotificationChannelKind.entries) LocalMetric(when (kind) {
                NotificationChannelKind.CRITICAL -> R.string.health_channel_critical
                NotificationChannelKind.REVIEW_REMINDERS -> R.string.health_channel_review
                NotificationChannelKind.PROCESSING -> R.string.health_channel_processing
            }, flag(local.channels[kind]))
            LocalMetric(R.string.health_local_model, stringResource(healthStatusLabel(local.model)))
            LocalMetric(R.string.health_worker, stringResource(healthWorkerLabel(local.worker)))
            LocalMetric(R.string.health_charging, flag(local.conditions?.charging))
            LocalMetric(R.string.health_battery, local.conditions?.batteryPercent?.let { stringResource(R.string.health_percent, it) } ?: stringResource(R.string.health_unknown))
            LocalMetric(R.string.health_thermal, local.conditions?.thermal?.let { stringResource(healthThermalLabel(it)) } ?: stringResource(R.string.health_unknown))
            LocalMetric(R.string.health_cache_sync, freshness?.lastSyncedAtMs?.let(::healthTime) ?: stringResource(R.string.health_unknown))
            LocalMetric(R.string.health_cache_bootstrap, flag(freshness?.needsFullDownload))
            LocalMetric(R.string.health_cache_queued, freshness?.queued?.toString() ?: stringResource(R.string.health_unknown))
            LocalMetric(R.string.health_cache_owner, freshness?.needsOwner?.toString() ?: stringResource(R.string.health_unknown))
            LocalMetric(R.string.health_cache_held, flag(freshness?.held))
        }
    }
}

@Composable private fun LocalMetric(label: Int, value: String) { Text(stringResource(R.string.health_pair, stringResource(label), value)) }
@Composable private fun flag(value: Boolean?): String = stringResource(when (value) { true -> R.string.health_yes; false -> R.string.health_no; null -> R.string.health_unknown })
@Composable private fun healthValue(value: HealthValue): String = when (value) {
    HealthValue.Unknown -> stringResource(R.string.health_unknown)
    is HealthValue.Number -> value.value.toString()
    is HealthValue.Seconds -> stringResource(R.string.health_seconds, value.value)
    is HealthValue.Milliseconds -> stringResource(R.string.health_milliseconds, value.value)
    is HealthValue.Bytes -> stringResource(R.string.health_bytes, value.value)
    is HealthValue.Time -> healthTime(value.value)
    is HealthValue.Flag -> flag(value.value)
    is HealthValue.Choice -> stringResource(healthChoiceLabel(value.value))
}
private fun healthTime(value: Long): String = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(value))
