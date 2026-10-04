package com.digifender.adminifender.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.digifender.adminifender.R
import com.digifender.adminifender.core.data.network.CoverageDto
import com.digifender.adminifender.core.data.network.ServiceReportDto
import java.time.Instant

/** Signed service reports and verification issues; every card says how fresh and how complete its evidence is. */
@Composable
fun OperationsScreen(state: OperationsUiState, onRefresh: () -> Unit, onOpenSource: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.ops_note), style = MaterialTheme.typography.bodySmall)
        state.problem?.let { Text(onlineProblem(it), color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = onRefresh) { Text(stringResource(R.string.refresh)) }
        val overview = state.overview ?: return@Column
        overview.services.forEach { s ->
            Text(s.name, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.ops_freshness, freshnessWord(s.freshness), s.expectedEverySeconds / 3600), style = MaterialTheme.typography.bodySmall)
            if (s.reports.isEmpty()) Text(stringResource(R.string.ops_no_reports))
            s.reports.forEach { ReportCard(it, onOpenSource) }
        }
        if (overview.verificationIssues.isNotEmpty()) {
            Text(stringResource(R.string.ops_issues), style = MaterialTheme.typography.titleMedium)
            overview.verificationIssues.forEach { i ->
                Text(
                    stringResource(R.string.ops_issue, i.code, dateTime(Instant.parse(i.createdAt).toEpochMilli())),
                    modifier = Modifier.clickable(role = Role.Button) { onOpenSource(i.sourceId) },
                )
            }
        }
    }
}

@Composable
private fun ReportCard(r: ServiceReportDto, onOpenSource: (String) -> Unit) {
    val source = r.sourceIds.firstOrNull()
    val mod = if (source != null) Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpenSource(source) } else Modifier.fillMaxWidth()
    Card(mod) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(r.summary, style = MaterialTheme.typography.titleSmall)
            Text("${r.kind} · ${r.concernKey}", style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(R.string.ops_times, dateTime(Instant.parse(r.reportedAt).toEpochMilli()), dateTime(Instant.parse(r.receivedAt).toEpochMilli()), freshnessWord(r.freshness)),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(coverageText(r.coverage), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun freshnessWord(f: String) = stringResource(
    when (f) {
        "current" -> R.string.ops_current
        "stale" -> R.string.ops_stale
        "unavailable" -> R.string.ops_unavailable
        else -> R.string.ops_unobserved
    },
)

/** Partial or unknown coverage is said out loud: a report never implies it saw everything (lld.md#reports). */
@Composable
private fun coverageText(c: CoverageDto): String {
    val scope = stringResource(if (c.scope == "single_observation") R.string.ops_scope_single else R.string.ops_scope_snapshot)
    val status = when (c.status) {
        "complete" -> stringResource(R.string.ops_complete)
        "partial" -> c.omittedCount?.let { pluralStringResource(R.plurals.ops_partial_count, it, it) } ?: stringResource(R.string.ops_partial)
        else -> stringResource(R.string.ops_unknown_coverage)
    }
    return "$scope · $status"
}
