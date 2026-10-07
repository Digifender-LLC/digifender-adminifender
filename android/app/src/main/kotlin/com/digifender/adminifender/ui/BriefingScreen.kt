package com.digifender.adminifender.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.digifender.adminifender.R
import com.digifender.adminifender.core.model.Concern

@Composable
fun BriefingScreen(state: BriefingUiState, onRefresh: () -> Unit, onReview: () -> Unit, onOpenConcern: (String) -> Unit, onOpenSource: (String) -> Unit, modifier: Modifier = Modifier) {
    val visible = state.briefing
    val briefing = visible?.snapshot?.briefing
    LazyColumn(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(stringResource(R.string.nav_briefing), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onRefresh, enabled = !state.loading && state.review != BriefingReviewState.SENDING) { Text(stringResource(R.string.refresh)) }
            if (state.loading) Text(stringResource(R.string.briefing_loading))
            state.message?.let { message ->
                Text(stringResource(when (message) {
                    BriefingMessage.SIGN_IN -> R.string.briefing_sign_in
                    BriefingMessage.LOAD_FAILED -> if (briefing == null) R.string.briefing_unavailable else R.string.briefing_refresh_failed
                    BriefingMessage.REVIEW_UNKNOWN -> R.string.briefing_review_unknown
                    BriefingMessage.REVIEW_REFUSED -> R.string.briefing_review_refused
                }), color = MaterialTheme.colorScheme.error)
            }
        }
        if (briefing != null) {
            item {
                Text(stringResource(R.string.briefing_snapshot, dateTime(briefing.throughTimeMs)))
                Text(briefing.lastReviewedAtMs?.let { stringResource(R.string.briefing_last_review, dateTime(it)) } ?: stringResource(R.string.briefing_first_review))
                if (briefing.hasMore) Text(stringResource(R.string.briefing_bounded))
                if (visible.hiddenConcerns) Text(stringResource(R.string.briefing_cache_changed), color = MaterialTheme.colorScheme.error)
            }
            section("new", R.string.briefing_new, briefing.newConcerns, onOpenConcern)
            section("changed", R.string.briefing_changed, briefing.changedConcerns, onOpenConcern)
            section("overdue", R.string.briefing_overdue, briefing.overdue, onOpenConcern)
            section("unacknowledged", R.string.briefing_unacknowledged, briefing.unacknowledged, onOpenConcern)
            item { Text(stringResource(R.string.briefing_new_sources), style = MaterialTheme.typography.titleMedium) }
            if (briefing.newSourceIds.isEmpty()) item { Text(stringResource(R.string.briefing_empty_section)) }
            items(briefing.newSourceIds.size, key = { "source-${briefing.newSourceIds[it]}" }) { index ->
                TextButton(onClick = { onOpenSource(briefing.newSourceIds[index]) }) { Text(stringResource(R.string.briefing_source, index + 1)) }
            }
            item {
                Text(stringResource(R.string.briefing_review_explanation), style = MaterialTheme.typography.bodySmall)
                if (state.review == BriefingReviewState.REVIEWED) Text(stringResource(R.string.briefing_reviewed))
                else Button(onClick = onReview, enabled = !state.loading && state.review != BriefingReviewState.SENDING && !visible.hiddenConcerns) {
                    Text(stringResource(if (state.review == BriefingReviewState.SENDING) R.string.briefing_reviewing else R.string.briefing_review))
                }
            }
        }
    }
}

private fun LazyListScope.section(key: String, title: Int, concerns: List<Concern>, onOpen: (String) -> Unit) {
    item(key = "$key-title") { Text(stringResource(title), style = MaterialTheme.typography.titleMedium) }
    if (concerns.isEmpty()) item(key = "$key-empty") { Text(stringResource(R.string.briefing_empty_section)) }
    items(concerns, key = { "$key-${it.id}" }) { concern ->
        Card(Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(concern.id) }) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(concern.title, style = MaterialTheme.typography.titleMedium)
                Text(listOf(importanceText(concern.importance), statusText(concern.status)).joinToString(" · "))
                concern.due?.atMs?.let { Text(stringResource(R.string.label_due, dueDateTime(it, concern.due?.zone))) }
            }
        }
    }
}
