package com.digifender.adminifender.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.digifender.adminifender.R
import com.digifender.adminifender.core.data.network.ChatScope
import com.digifender.adminifender.core.data.network.MAX_QUESTION_CHARS
import com.digifender.adminifender.core.data.network.ScopeKind
import com.digifender.adminifender.core.data.network.SourceSummary
import com.digifender.adminifender.core.data.network.isRecordId
import com.digifender.adminifender.core.data.repository.CancelNote
import com.digifender.adminifender.core.data.repository.ChatAnswer
import com.digifender.adminifender.core.data.repository.ChatCitation
import com.digifender.adminifender.core.data.repository.ChatClaim
import com.digifender.adminifender.core.data.repository.ChatTurn
import com.digifender.adminifender.core.data.repository.ClaimBasis
import com.digifender.adminifender.core.data.repository.CreateState
import com.digifender.adminifender.core.data.repository.TurnPhase
import com.digifender.adminifender.core.data.repository.cancellable
import com.digifender.adminifender.core.data.repository.phase

private enum class Picker { NONE, CONCERN, SOURCE, SERVICE }

@Composable
fun scopeText(label: ScopeLabel): String = when (label.kind) {
    ScopeKind.ALL -> stringResource(R.string.chat_scope_all)
    ScopeKind.CONCERN -> if (label.missing) stringResource(R.string.chat_scope_concern_missing) else stringResource(R.string.chat_scope_concern, label.name.orEmpty())
    ScopeKind.SOURCE -> if (label.missing) {
        stringResource(R.string.chat_scope_source_missing)
    } else {
        stringResource(R.string.chat_scope_source, label.name ?: stringResource(R.string.no_subject))
    }
    ScopeKind.SERVICE -> stringResource(R.string.chat_scope_service, label.name.orEmpty())
}

/** The titles of the concerns that point at a service, or null when there is nothing to add to its label. */
@Composable
fun scopeDetail(label: ScopeLabel): String? =
    if (label.kind == ScopeKind.SERVICE && label.linked.isNotEmpty()) stringResource(R.string.chat_service_concerns, label.linked.joinToString(", ")) else null

/**
 * The Chat tab: conversations kept on this phone, and a choice of what a new one is about. The choices come from
 * the synced cache only: the Worker has no call that lists services, so a service is offered only when a cached
 * concern points at it, and it is named by a short ID because no name is synced.
 */
@Composable
fun ChatListScreen(state: ChatListState, onOpen: (String) -> Unit, onNew: (ChatScope) -> Unit, modifier: Modifier = Modifier) {
    var picker by rememberSaveable { mutableStateOf(Picker.NONE) }
    LazyColumn(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(stringResource(R.string.chat_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 8.dp)) }
        item { Text(stringResource(R.string.chat_intro), style = MaterialTheme.typography.bodyMedium) }
        item { Text(stringResource(R.string.chat_new_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onNew(ChatScope(ScopeKind.ALL)) }) { Text(stringResource(R.string.chat_pick_all)) }
                OutlinedButton(onClick = { picker = Picker.CONCERN }) { Text(stringResource(R.string.chat_pick_concern)) }
                OutlinedButton(onClick = { picker = Picker.SOURCE }) { Text(stringResource(R.string.chat_pick_source)) }
                OutlinedButton(onClick = { picker = Picker.SERVICE }) { Text(stringResource(R.string.chat_pick_service)) }
            }
        }
        if (picker != Picker.NONE) {
            val options = when (picker) {
                Picker.CONCERN -> state.concerns
                Picker.SOURCE -> state.sources
                else -> state.services
            }
            item {
                Text(
                    stringResource(
                        when (picker) {
                            Picker.CONCERN -> R.string.chat_choose_concern
                            Picker.SOURCE -> R.string.chat_choose_source
                            else -> R.string.chat_choose_service
                        },
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            if (options.isEmpty()) {
                item {
                    Text(
                        stringResource(
                            when (picker) {
                                Picker.CONCERN -> R.string.chat_none_concern
                                Picker.SOURCE -> R.string.chat_none_source
                                else -> R.string.chat_none_service
                            },
                        ),
                    )
                }
            }
            items(options.size) { index ->
                val option = options[index]
                Card(
                    Modifier.fillMaxWidth().clickable(role = Role.Button) {
                        picker = Picker.NONE
                        onNew(option.scope)
                    },
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(scopeText(option.label), style = MaterialTheme.typography.titleSmall)
                        scopeDetail(option.label)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                        if (option.scope.kind == ScopeKind.SERVICE) Text(pluralStringResource(R.plurals.chat_service_linked, option.detail, option.detail), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item { Text(stringResource(R.string.chat_conversations), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
        val threads = state.items
        if (threads == null) {
            item { Text(stringResource(R.string.chat_loading)) }
        } else {
            if (threads.isEmpty()) item { Text(stringResource(R.string.chat_no_conversations)) }
            items(threads.size) { index ->
                val item = threads[index]
                Card(Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(item.thread.localId) }) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(scopeText(item.label), style = MaterialTheme.typography.titleSmall)
                        scopeDetail(item.label)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Text(stringResource(R.string.chat_last_used, dateTime(item.thread.updatedAtMs)), style = MaterialTheme.typography.bodySmall)
                        if (item.thread.draft.isNotEmpty()) Text(stringResource(R.string.chat_has_draft), style = MaterialTheme.typography.bodySmall)
                        if (item.thread.createState == CreateState.REFUSED) Text(stringResource(R.string.chat_thread_refused), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
fun ChatThreadScreen(
    state: ChatThreadState,
    draft: String,
    nowMs: Long,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onRefresh: () -> Unit,
    onLoadEarlier: () -> Unit,
    onCancel: (String) -> Unit,
    onEditAgain: (String) -> Unit,
    onOpenSource: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val thread = state.thread
    val listState = rememberLazyListState()
    // Item 0 is the "earlier" row; the turns follow it, so the newest is at index turns.size. The list follows the newest
    // question only when that question changes: loading earlier turns adds items above and keeps the owner's place
    // (the list keeps its position by item key), and an answer arriving does not pull the view away from what is read.
    val newest = state.turns.lastOrNull()?.turnId
    LaunchedEffect(newest) {
        if (newest != null) listState.scrollToItem(state.turns.size)
    }
    Column(modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!state.loaded || thread == null) {
            Text(stringResource(if (state.loaded) R.string.chat_missing else R.string.chat_loading))
            return@Column
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.chat_asking_about), style = MaterialTheme.typography.labelMedium)
                state.label?.let {
                    Text(scopeText(it), style = MaterialTheme.typography.titleMedium)
                    scopeDetail(it)?.let { detail -> Text(detail, style = MaterialTheme.typography.bodySmall) }
                }
            }
            TextButton(onClick = onRefresh) { Text(stringResource(R.string.refresh)) }
        }
        Text(stringResource(R.string.chat_scope_fixed), style = MaterialTheme.typography.bodySmall)
        when (thread.createState) {
            CreateState.UNCONFIRMED -> Text(stringResource(R.string.chat_thread_unconfirmed), style = MaterialTheme.typography.bodySmall)
            CreateState.REFUSED -> Text(stringResource(R.string.chat_thread_refused_detail, thread.createError ?: "unknown"), style = MaterialTheme.typography.bodySmall)
            else -> Unit
        }
        state.problem?.let {
            Text(
                stringResource(if (it == ChatProblem.SIGN_IN) R.string.chat_problem_sign_in else R.string.chat_problem_unreachable),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        state.message?.let {
            Text(chatMessageText(it), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                if (thread.olderAvailable) TextButton(onClick = onLoadEarlier) { Text(stringResource(R.string.chat_load_earlier)) }
            }
            if (state.turns.isEmpty()) item { Text(stringResource(R.string.chat_empty_thread)) }
            items(state.turns.size, key = { state.turns[it].turnId }) { index ->
                TurnCard(state.turns[index], state.sources, nowMs, onCancel, onEditAgain, onOpenSource)
            }
        }
        OutlinedTextField(
            value = draft,
            onValueChange = onDraft,
            label = { Text(stringResource(R.string.chat_question_label)) },
            supportingText = { Text(pluralStringResource(R.plurals.chat_counter, draft.length, draft.length, MAX_QUESTION_CHARS)) },
            isError = draft.length > MAX_QUESTION_CHARS,
            minLines = 1,
            maxLines = 5,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = onSend,
            enabled = draft.isNotBlank() && draft.length <= MAX_QUESTION_CHARS && thread.createState != CreateState.REFUSED,
            modifier = Modifier.padding(bottom = 8.dp),
        ) { Text(stringResource(R.string.chat_send)) }
    }
}

@Composable
private fun chatMessageText(message: ChatMessage): String = stringResource(
    when (message) {
        ChatMessage.EMPTY -> R.string.chat_msg_empty
        ChatMessage.TOO_LONG -> R.string.chat_msg_too_long
        ChatMessage.SCOPE_REFUSED -> R.string.chat_msg_scope_refused
        ChatMessage.GONE -> R.string.chat_msg_gone
        ChatMessage.SIGN_IN -> R.string.chat_msg_sign_in
        ChatMessage.NOT_CONFIRMED -> R.string.chat_msg_not_confirmed
        ChatMessage.CANCELLED -> R.string.chat_msg_cancelled
        ChatMessage.WITHDRAWN -> R.string.chat_msg_withdrawn
        ChatMessage.ALREADY_DISPATCHED -> R.string.chat_msg_already_dispatched
        ChatMessage.NOT_CANCELLABLE -> R.string.chat_msg_not_cancellable
        ChatMessage.CANCEL_REFUSED -> R.string.chat_msg_cancel_refused
        ChatMessage.EARLIER_UNAVAILABLE -> R.string.chat_msg_earlier_unavailable
        ChatMessage.DRAFT_IN_USE -> R.string.chat_msg_draft_in_use
    },
)

@Composable
private fun TurnCard(
    turn: ChatTurn,
    sources: Map<String, SourceSummary>,
    nowMs: Long,
    onCancel: (String) -> Unit,
    onEditAgain: (String) -> Unit,
    onOpenSource: (String) -> Unit,
) {
    val phase = turn.phase()
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.chat_you_asked, dateTime(turn.createdAtMs)), style = MaterialTheme.typography.labelMedium)
            // The question is shown exactly as submitted, as plain text.
            SelectionContainer { Text(turn.content, style = MaterialTheme.typography.bodyLarge) }
            Text(statusLine(turn, phase), style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            turn.checkedAtMs?.let { if (phase == TurnPhase.WAITING || phase == TurnPhase.RUNNING || phase == TurnPhase.UNCONFIRMED) Text(stringResource(R.string.chat_checked, ageText(it, nowMs)), style = MaterialTheme.typography.bodySmall) }
            when (turn.cancelNote) {
                CancelNote.ALREADY_DISPATCHED -> Text(stringResource(R.string.chat_msg_already_dispatched), style = MaterialTheme.typography.bodySmall)
                CancelNote.NOT_CANCELLABLE -> Text(stringResource(R.string.chat_msg_not_cancellable), style = MaterialTheme.typography.bodySmall)
                null -> Unit
            }
            if (phase == TurnPhase.RUNNING) Text(stringResource(R.string.chat_running_cannot_cancel), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (turn.cancellable) TextButton(onClick = { onCancel(turn.turnId) }) { Text(stringResource(R.string.chat_cancel)) }
                if (phase == TurnPhase.REFUSED || phase == TurnPhase.WITHDRAWN) TextButton(onClick = { onEditAgain(turn.turnId) }) { Text(stringResource(R.string.chat_edit_again)) }
            }
            val answer = turn.answer
            if (phase == TurnPhase.COMPLETE && answer != null) {
                // Reports and "right now" claims are read against when the answer was made.
                turn.completedAtMs?.let { Text(stringResource(R.string.chat_answered_at, dateTime(it)), style = MaterialTheme.typography.bodySmall) }
                AnswerView(answer, sources, onOpenSource)
            }
        }
    }
}

@Composable
private fun statusLine(turn: ChatTurn, phase: TurnPhase): String = when (phase) {
    TurnPhase.NOT_SENT -> stringResource(R.string.chat_status_not_sent)
    TurnPhase.UNCONFIRMED -> stringResource(R.string.chat_status_unconfirmed)
    TurnPhase.WAITING -> waitingText(turn.reason)
    TurnPhase.RUNNING -> stringResource(R.string.chat_status_running)
    TurnPhase.COMPLETE -> if (turn.reason == "no_evidence") stringResource(R.string.chat_status_no_evidence) else stringResource(R.string.chat_status_complete)
    TurnPhase.FAILED -> failedText(turn.errorCode)
    TurnPhase.UNCERTAIN -> stringResource(R.string.chat_status_uncertain)
    TurnPhase.CANCELLED -> stringResource(R.string.chat_status_cancelled)
    TurnPhase.INVALIDATED -> stringResource(R.string.chat_status_invalidated)
    TurnPhase.WITHHELD -> stringResource(R.string.chat_status_withheld)
    TurnPhase.UNVERIFIED -> stringResource(R.string.chat_status_unverified)
    TurnPhase.ANSWER_REJECTED -> stringResource(R.string.chat_status_answer_rejected)
    TurnPhase.REFUSED -> stringResource(R.string.chat_status_refused, turn.dispatchError ?: "unknown")
    TurnPhase.WITHDRAWN -> stringResource(R.string.chat_status_withdrawn)
}

@Composable
private fun waitingText(reason: String?): String = when (reason) {
    null -> stringResource(R.string.chat_status_waiting)
    // Every one of these routes the question to a model on the phone, which this version of the app cannot run yet.
    "local_only", "cloud_unavailable", "model_rate_unknown", "account_headroom_unknown", "budget_exhausted" -> stringResource(R.string.chat_wait_needs_phone_model)
    else -> stringResource(R.string.chat_wait_other, reason)
}

@Composable
private fun failedText(code: String?): String = when (code) {
    "not_allowed" -> stringResource(R.string.chat_fail_not_allowed)
    "evidence_changed" -> stringResource(R.string.chat_fail_evidence_changed)
    "invalid_output" -> stringResource(R.string.chat_fail_invalid_output)
    else -> stringResource(R.string.chat_fail_other, code ?: "unknown")
}

@Composable
private fun basisText(basis: ClaimBasis): String = stringResource(
    when (basis) {
        ClaimBasis.EVIDENCE -> R.string.chat_basis_evidence
        ClaimBasis.REPORT -> R.string.chat_basis_report
        ClaimBasis.INFERENCE -> R.string.chat_basis_inference
        ClaimBasis.HYPOTHESIS -> R.string.chat_basis_hypothesis
    },
)

/**
 * The answer, claims with their basis and cited passages, gaps and suggestions. Everything here is plain text: model
 * output is never turned into a link, command or markup, and a suggestion has no control that sends or changes
 * anything. The only actions are opening an email already on this phone.
 */
@Composable
private fun AnswerView(answer: ChatAnswer, sources: Map<String, SourceSummary>, onOpenSource: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.chat_answer), style = MaterialTheme.typography.titleSmall)
        SelectionContainer { Text(answer.text, style = MaterialTheme.typography.bodyLarge) }
        if (answer.claims.isNotEmpty()) {
            Text(stringResource(R.string.chat_claims), style = MaterialTheme.typography.titleSmall)
            answer.claims.forEach { ClaimView(it, sources, onOpenSource) }
        }
        if (answer.gaps.isNotEmpty()) {
            Text(stringResource(R.string.chat_gaps), style = MaterialTheme.typography.titleSmall)
            answer.gaps.forEach { SelectionContainer { Text(stringResource(R.string.chat_bullet, it), style = MaterialTheme.typography.bodyMedium) } }
        }
        if (answer.proposals.isNotEmpty()) {
            Text(stringResource(R.string.chat_proposals), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.chat_proposals_note), style = MaterialTheme.typography.bodySmall)
            answer.proposals.forEach { proposal ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (proposal.kind == "email.reply") stringResource(R.string.chat_proposal_reply) else stringResource(R.string.chat_proposal_other),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    SelectionContainer { Text(proposal.description, style = MaterialTheme.typography.bodyMedium) }
                    // Only an ID that names an email already on this phone becomes a control, and it only opens that email.
                    if (isRecordId(proposal.targetId) && sources[proposal.targetId] != null) {
                        TextButton(onClick = { onOpenSource(proposal.targetId) }) { Text(stringResource(R.string.chat_open_email)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClaimView(claim: ChatClaim, sources: Map<String, SourceSummary>, onOpenSource: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(basisText(claim.basis), style = MaterialTheme.typography.labelLarge)
        SelectionContainer { Text(claim.text, style = MaterialTheme.typography.bodyMedium) }
        claim.citations.forEach { CitationView(it, sources, onOpenSource) }
    }
}

@Composable
private fun CitationView(citation: ChatCitation, sources: Map<String, SourceSummary>, onOpenSource: (String) -> Unit) {
    val source = citation.sourceId?.let { sources[it] }
    Column(Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SelectionContainer {
            Text(
                citation.excerpt?.let { stringResource(R.string.chat_excerpt, it) } ?: stringResource(R.string.chat_excerpt_unreadable),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // The page is copied to a local: it is a property of a class from another module, which cannot be smart cast.
        val page = citation.page
        val where = when {
            citation.attachmentId == null -> stringResource(R.string.chat_cite_body)
            page != null -> stringResource(R.string.chat_cite_attachment_page, page)
            else -> stringResource(R.string.chat_cite_attachment)
        }
        val from = source?.let { it.subject?.ifBlank { null } ?: stringResource(R.string.no_subject) } ?: stringResource(R.string.chat_cite_not_on_phone)
        Text(stringResource(R.string.chat_cite_where, where, from), style = MaterialTheme.typography.labelSmall)
        // Who sent it and when it arrived, so a "reported as of" claim can be told from a current one.
        if (source != null) {
            val sender = source.from.firstOrNull()
            Text(
                if (sender != null) stringResource(R.string.chat_cite_sender_received, sender, dateTime(source.receivedAtMs)) else stringResource(R.string.chat_cite_received, dateTime(source.receivedAtMs)),
                style = MaterialTheme.typography.labelSmall,
            )
        }
        if (source != null) TextButton(onClick = { onOpenSource(source.id) }) { Text(stringResource(R.string.chat_open_email)) }
    }
}
