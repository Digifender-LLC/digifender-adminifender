package com.digifender.adminifender.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.digifender.adminifender.R
import com.digifender.adminifender.core.data.repository.CachedReason
import com.digifender.adminifender.core.data.repository.ReviewPhase
import java.time.ZoneId

@Composable
fun BriefingScreen(
    state: BriefingUiState,
    nowMs: Long,
    onRefresh: () -> Unit,
    onMarkReviewed: () -> Unit,
    onOpenConcern: (String) -> Unit,
    onOpenSource: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = state.content
    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.briefing_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 8.dp))
                TextButton(onClick = onRefresh, enabled = !state.loading && !state.sending) { Text(stringResource(R.string.refresh)) }
            }
        }
        if (content == null) {
            item { Text(stringResource(R.string.briefing_loading)) }
            return@LazyColumn
        }
        item { BriefingStatus(state, content, nowMs) }
        when (content) {
            is BriefingContent.FromPhone -> {
                section(R.string.briefing_section_overdue, content.overdue, onOpenConcern)
                section(R.string.briefing_section_unacknowledged, content.unacknowledged, onOpenConcern)
            }
            is BriefingContent.FromServer -> {
                item { ReviewControls(state, content, onMarkReviewed) }
                val s = content.sections
                if (s.notOnPhone > 0) item { Text(pluralStringResource(R.plurals.briefing_not_on_phone, s.notOnPhone, s.notOnPhone), style = MaterialTheme.typography.bodyMedium) }
                val empty = s.newItems.isEmpty() && s.changed.isEmpty() && s.overdue.isEmpty() && s.unacknowledged.isEmpty() && content.newSources.isEmpty()
                if (empty && s.notOnPhone == 0) item { Text(stringResource(R.string.briefing_nothing)) }
                section(R.string.briefing_section_new, s.newItems, onOpenConcern)
                section(R.string.briefing_section_changed, s.changed, onOpenConcern)
                section(R.string.briefing_section_overdue, s.overdue, onOpenConcern)
                section(R.string.briefing_section_unacknowledged, s.unacknowledged, onOpenConcern)
                if (content.newSources.isNotEmpty()) {
                    item { Text(stringResource(R.string.briefing_section_emails), style = MaterialTheme.typography.titleMedium) }
                    items(content.newSources.size) { SourceCard(content.newSources[it], onOpenSource) }
                }
            }
        }
    }
}

private fun LazyListScope.section(title: Int, cards: List<BriefingCard>, onOpen: (String) -> Unit) {
    if (cards.isEmpty()) return
    item { Text(stringResource(title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
    items(cards.size) { BriefingCardView(cards[it], onOpen) }
}

@Composable
private fun BriefingStatus(state: BriefingUiState, content: BriefingContent, nowMs: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (content) {
            is BriefingContent.FromServer -> {
                Text(stringResource(R.string.briefing_loaded, ageText(content.fetchedAtMs, nowMs)), style = MaterialTheme.typography.labelLarge)
                Text(stringResource(R.string.briefing_through, dateTime(content.throughTimeMs)), style = MaterialTheme.typography.bodySmall)
                Text(
                    content.lastReviewedAtMs?.let { stringResource(R.string.briefing_since, dateTime(it)) } ?: stringResource(R.string.briefing_first),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (content.hasMore) Text(stringResource(R.string.briefing_more), style = MaterialTheme.typography.bodyMedium)
            }
            is BriefingContent.FromPhone -> Text(stringResource(R.string.briefing_phone_only), style = MaterialTheme.typography.bodyMedium)
        }
        state.problem?.let { reason ->
            val fromServer = content is BriefingContent.FromServer
            Text(
                stringResource(
                    when (reason) {
                        CachedReason.OFFLINE -> if (fromServer) R.string.briefing_offline else R.string.briefing_phone_offline
                        CachedReason.SIGN_IN -> if (fromServer) R.string.briefing_sign_in else R.string.briefing_phone_sign_in
                        CachedReason.UNAVAILABLE -> if (fromServer) R.string.briefing_unavailable else R.string.briefing_phone_unavailable
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ReviewControls(state: BriefingUiState, content: BriefingContent.FromServer, onMarkReviewed: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (content.review.phase) {
            ReviewPhase.DONE -> Text(
                content.review.reviewedAtMs?.let { stringResource(R.string.briefing_review_done_at, dateTime(it)) } ?: stringResource(R.string.briefing_review_done),
                style = MaterialTheme.typography.titleSmall,
            )
            ReviewPhase.UNCONFIRMED -> Text(stringResource(R.string.briefing_review_unconfirmed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            ReviewPhase.NONE -> Unit
        }
        if (content.review.phase != ReviewPhase.DONE) {
            Button(onClick = onMarkReviewed, enabled = content.canReview && !state.loading && !state.sending) { Text(stringResource(R.string.briefing_review_button)) }
            content.reviewBlock?.takeIf { content.review.phase == ReviewPhase.NONE }?.let {
                Text(
                    stringResource(if (it == ReviewBlock.BEHIND) R.string.briefing_review_blocked_behind else R.string.briefing_review_blocked_missing),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(stringResource(R.string.briefing_review_note), style = MaterialTheme.typography.bodySmall)
        }
        // A polite live region so a screen reader announces the result of the tap without moving focus.
        state.message?.let { Text(stringResource(reviewMessageText(it)), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    }
}

private fun reviewMessageText(message: ReviewMessage): Int = when (message) {
    ReviewMessage.REVIEWED -> R.string.review_msg_reviewed
    ReviewMessage.ALREADY -> R.string.review_msg_already
    ReviewMessage.NOT_CONFIRMED -> R.string.review_msg_not_confirmed
    ReviewMessage.SIGN_IN -> R.string.review_msg_sign_in
    ReviewMessage.GONE -> R.string.review_msg_gone
    ReviewMessage.REFUSED -> R.string.review_msg_refused
    ReviewMessage.CHANGED -> R.string.review_msg_changed
}

@Composable
private fun BriefingCardView(card: BriefingCard, onOpen: (String) -> Unit) {
    val c = card.concern
    Card(Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(c.id) }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(c.title, style = MaterialTheme.typography.titleMedium)
            // State is spelled out in words, never by colour alone (NFR-006).
            val labels = buildList {
                add(statusText(c.status))
                add(importanceText(c.importance))
                dueDisplay(c.due, ZoneId.systemDefault())?.let { add(dueText(it)) }
                if (c.acknowledgedAtMs != null) add(stringResource(R.string.label_acknowledged))
                if (card.pending) add(stringResource(R.string.label_pending))
                if (card.needsOwner) add(pluralStringResource(R.plurals.needs_owner, 1, 1))
                if (card.behind) add(stringResource(R.string.briefing_behind))
            }
            Text(labels.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
