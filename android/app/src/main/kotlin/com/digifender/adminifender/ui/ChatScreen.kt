package com.digifender.adminifender.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.digifender.adminifender.core.data.chat.ChatCitation
import com.digifender.adminifender.core.data.chat.ChatCitationResult
import com.digifender.adminifender.core.data.chat.ChatRepository
import com.digifender.adminifender.core.data.chat.ChatScope
import com.digifender.adminifender.core.data.chat.ChatTurn
import com.digifender.adminifender.core.data.chat.MAX_CHAT_QUESTION_CHARS

/** Integrates a global or contextual scope without putting question/answer text in navigation saved state. */
@Composable
fun ChatRoute(chat: ChatRepository, scope: ChatScope = ChatScope(), refreshKey: Long? = null, onOpenSource: (String) -> Unit) {
    val model = viewModel(key = "chat-${scope.key}") { ChatViewModel(scope, chat) }
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
        onDispose {
            lifecycle.removeObserver(observer)
            model.onBackground()
        }
    }
    LaunchedEffect(refreshKey) { if (refreshKey != null) model.refresh() }
    ChatScreen(state, model::editDraft, model::send, model::refresh, model::retryMissing, model::cancel, model::older, model::openCitation, model::dismissCitation, onOpenSource)
}

@Composable
fun ChatScreen(
    state: ChatUiState,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onRefresh: () -> Unit,
    onRetryMissing: () -> Unit,
    onCancel: (String) -> Unit,
    onOlder: () -> Unit,
    onCitation: (String, ChatCitation) -> Unit,
    onDismissCitation: () -> Unit,
    onOpenSource: (String) -> Unit,
) {
    val snapshot = state.snapshot
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.chat_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.chat_scope, stringResource(scopeLabel(state.scope))))
        state.scope.id?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        Text(stringResource(R.string.chat_privacy), style = MaterialTheme.typography.bodySmall)
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.message?.let { Text(stringResource(chatMessage(it)), color = MaterialTheme.colorScheme.error) }
        Row {
            TextButton(onClick = onRefresh, enabled = !state.busy) { Text(stringResource(R.string.chat_refresh)) }
            if (snapshot?.hasMore == true) TextButton(onClick = onOlder, enabled = !state.busy) { Text(stringResource(R.string.chat_older)) }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (snapshot?.turns.isNullOrEmpty() && !state.busy) item { Text(stringResource(R.string.chat_empty)) }
            items(snapshot?.turns.orEmpty(), key = { it.turnId }) { turn ->
                ChatTurnCard(turn, !state.busy, onCancel, onCitation)
            }
            val pending = snapshot?.session
            if (pending?.requestId != null && snapshot.turns.none { it.turnId == pending.requestId }) item {
                Card {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(pending.requestContent.orEmpty())
                        Text(stringResource(R.string.chat_request_unconfirmed))
                        if (snapshot.requestMissing) TextButton(onClick = onRetryMissing, enabled = !state.busy) { Text(stringResource(R.string.chat_retry_same)) }
                    }
                }
            }
        }
        OutlinedTextField(
            value = state.draft,
            onValueChange = onDraft,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.busy && !state.waiting,
            label = { Text(stringResource(R.string.chat_question)) },
            supportingText = { Text(stringResource(R.string.chat_question_count, state.draft.length, MAX_CHAT_QUESTION_CHARS)) },
            maxLines = 4,
        )
        Button(onClick = onSend, enabled = state.canSend, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.chat_send)) }
    }
    state.citation?.let { result ->
        AlertDialog(
            onDismissRequest = onDismissCitation,
            title = { Text(stringResource(R.string.chat_citation_title)) },
            text = {
                when (result) {
                    is ChatCitationResult.Available -> Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(result.citation.excerpt.orEmpty())
                        result.citation.page?.let { Text(stringResource(R.string.chat_page, it)) }
                        Text(stringResource(R.string.chat_excerpt_range, result.citation.start, result.citation.end), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.chat_evidence_history), style = MaterialTheme.typography.bodySmall)
                    }
                    ChatCitationResult.SignIn -> Text(stringResource(R.string.chat_sign_in))
                    ChatCitationResult.Unavailable -> Text(stringResource(R.string.chat_citation_unavailable))
                }
            },
            confirmButton = {
                if (result is ChatCitationResult.Available) TextButton(onClick = { onDismissCitation(); result.citation.sourceId?.let(onOpenSource) }) { Text(stringResource(R.string.chat_open_source)) }
                else TextButton(onClick = onDismissCitation) { Text(stringResource(R.string.chat_close)) }
            },
            dismissButton = { if (result is ChatCitationResult.Available) TextButton(onClick = onDismissCitation) { Text(stringResource(R.string.chat_close)) } },
        )
    }
}

@Composable
private fun ChatTurnCard(turn: ChatTurn, enabled: Boolean, onCancel: (String) -> Unit, onCitation: (String, ChatCitation) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(turn.content, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(chatTurnLabel(turn)), style = MaterialTheme.typography.labelLarge)
            if (turn.state == "pending") {
                turn.reason?.let { Text(stringResource(chatMessage(it)), style = MaterialTheme.typography.bodySmall) }
                TextButton(onClick = { onCancel(turn.turnId) }, enabled = enabled) { Text(stringResource(R.string.chat_cancel)) }
            }
            val shown = turn.forDisplay()
            shown.answer?.let { Text(it) }
            shown.claims.forEach { claim ->
                Text(stringResource(claimLabel(claim.basis)), style = MaterialTheme.typography.labelMedium)
                Text(claim.text)
                claim.citations.forEachIndexed { index, citation ->
                    TextButton(onClick = { onCitation(turn.turnId, citation) }, enabled = enabled) { Text(stringResource(R.string.chat_citation_number, index + 1)) }
                }
            }
            if (shown.gaps.isNotEmpty()) {
                Text(stringResource(R.string.chat_gaps), style = MaterialTheme.typography.titleSmall)
                shown.gaps.forEach { Text(it) }
            }
            if (shown.proposals.isNotEmpty()) {
                Text(stringResource(R.string.chat_proposals), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.chat_proposal_review), style = MaterialTheme.typography.bodySmall)
                shown.proposals.forEach { Text(it.description) }
            }
        }
    }
}

internal fun scopeLabel(scope: ChatScope): Int = when (scope.kind) {
    "source" -> R.string.chat_scope_source
    "concern" -> R.string.chat_scope_concern
    "service" -> R.string.chat_scope_service
    else -> R.string.chat_scope_all
}

internal fun claimLabel(basis: String): Int = when (basis) {
    "evidence" -> R.string.chat_basis_evidence
    "report" -> R.string.chat_basis_report
    "hypothesis" -> R.string.chat_basis_hypothesis
    else -> R.string.chat_basis_inference
}

internal fun chatTurnLabel(turn: ChatTurn): Int = when {
    turn.invalidated -> R.string.chat_invalidated
    turn.withheld -> R.string.chat_withheld
    turn.state == "pending" -> R.string.chat_pending
    turn.state == "running" -> R.string.chat_running
    turn.state == "uncertain" -> R.string.chat_uncertain
    turn.state == "failed" -> R.string.chat_failed
    turn.state == "cancelled" -> R.string.chat_cancelled
    turn.state == "complete" -> R.string.chat_complete
    else -> R.string.chat_unavailable
}

internal fun chatMessage(code: String): Int = when (code) {
    "session_expired" -> R.string.chat_sign_in
    "already_dispatched", "not_cancellable" -> R.string.chat_cannot_cancel
    "request_not_confirmed" -> R.string.chat_request_unconfirmed
    "scope_unavailable", "not_found" -> R.string.chat_scope_unavailable
    "storage_unavailable" -> R.string.chat_storage_unavailable
    "local_only", "device_unavailable", "local_only_waiting", "device_wait" -> R.string.chat_device_wait
    "budget_exhausted", "model_rate_unknown", "account_headroom_unknown" -> R.string.chat_budget_wait
    "cloud_unavailable" -> R.string.chat_cloud_wait
    "invalid_question" -> R.string.chat_invalid_question
    else -> R.string.chat_unavailable
}
