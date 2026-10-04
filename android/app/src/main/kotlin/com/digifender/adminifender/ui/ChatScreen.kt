package com.digifender.adminifender.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.digifender.adminifender.R
import com.digifender.adminifender.core.data.network.CitationDto
import com.digifender.adminifender.core.data.network.ClaimDto
import com.digifender.adminifender.core.data.repository.ChatRepository
import com.digifender.adminifender.core.data.repository.ChatScope
import com.digifender.adminifender.core.data.repository.ChatTurn
import com.digifender.adminifender.core.data.repository.TurnState

@Composable
fun ChatScreen(
    state: ChatUiState,
    onOpen: () -> Unit,
    onAsk: (String) -> Boolean,
    onResend: (String) -> Unit,
    onCheck: (String) -> Unit,
    onCancel: (String) -> Unit,
    onSource: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    Column(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The scope is shown before anything is asked (FR-025).
        Text(scopeText(state.scope), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
        state.message?.let { Text(chatMessage(it), color = MaterialTheme.colorScheme.error) }
        if (state.threadId == null) {
            if (state.opening) Text(stringResource(R.string.chat_opening)) else TextButton(onClick = onOpen) { Text(stringResource(R.string.try_again)) }
            return@Column
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.turns.isEmpty()) item { Text(stringResource(R.string.chat_empty)) }
            items(state.turns, key = { it.turnId }) { Turn(it, onResend, onCheck, onCancel, onSource) }
        }
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.take(ChatRepository.MAX_QUESTION_CHARS) },
            label = { Text(stringResource(R.string.chat_input)) },
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(onClick = { if (onAsk(draft)) draft = "" }, modifier = Modifier.padding(bottom = 8.dp)) { Text(stringResource(R.string.chat_send)) }
    }
}

@Composable
private fun Turn(turn: ChatTurn, onResend: (String) -> Unit, onCheck: (String) -> Unit, onCancel: (String) -> Unit, onSource: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth()) {
            Text(turn.content, Modifier.padding(12.dp))
        }
        Text(turnStateText(turn), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!turn.sent && turn.state == TurnState.SENDING && turn.errorCode != null) TextButton(onClick = { onResend(turn.turnId) }) { Text(stringResource(R.string.chat_send_again)) }
            if (turn.sent && !turn.state.finished) TextButton(onClick = { onCheck(turn.turnId) }) { Text(stringResource(R.string.chat_check)) }
            if (turn.state == TurnState.PENDING || (turn.state == TurnState.SENDING && turn.errorCode != null)) TextButton(onClick = { onCancel(turn.turnId) }) { Text(stringResource(R.string.chat_cancel)) }
        }
        turn.answer?.let { answer ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(answer)
                    turn.claims.forEach { Claim(it, onSource) }
                    turn.gaps.forEach { Text(stringResource(R.string.chat_gap, it), style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
private fun Claim(claim: ClaimDto, onSource: (String) -> Unit) {
    // Inference and hypothesis are labelled as reasoning, never as established fact.
    Text("${basisText(claim.basis)}: ${claim.text}", style = MaterialTheme.typography.bodyMedium)
    claim.citations.forEach { Citation(it, onSource) }
}

@Composable
private fun Citation(c: CitationDto, onSource: (String) -> Unit) {
    val excerpt = c.excerpt?.let { "“$it”" } ?: stringResource(R.string.citation_unavailable)
    val page = c.page?.let { " · " + stringResource(R.string.hit_page, it) } ?: ""
    val sourceId = c.sourceId
    val mod = if (sourceId != null && c.excerpt != null) Modifier.clickable(role = Role.Button) { onSource(sourceId) } else Modifier
    Text(excerpt + page, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = mod.padding(start = 12.dp))
}

@Composable
private fun basisText(basis: String) = stringResource(
    when (basis) {
        "evidence" -> R.string.basis_evidence
        "report" -> R.string.basis_report
        "inference" -> R.string.basis_inference
        else -> R.string.basis_hypothesis
    },
)

@Composable
private fun turnStateText(turn: ChatTurn): String = when {
    turn.state == TurnState.SENDING && turn.errorCode == ChatRepository.SIGN_IN -> stringResource(R.string.turn_sign_in)
    turn.state == TurnState.SENDING && turn.errorCode != null -> stringResource(R.string.turn_not_confirmed)
    turn.state == TurnState.SENDING -> stringResource(R.string.turn_sending)
    turn.state == TurnState.PENDING -> turn.reason?.let { stringResource(R.string.turn_waiting_reason, it) } ?: stringResource(R.string.turn_waiting)
    turn.state == TurnState.RUNNING -> stringResource(R.string.turn_running)
    turn.state == TurnState.COMPLETE && turn.invalidated -> stringResource(R.string.turn_invalidated)
    turn.state == TurnState.COMPLETE && turn.withheld -> stringResource(R.string.turn_withheld)
    turn.state == TurnState.COMPLETE -> turn.completedAtMs?.let { stringResource(R.string.turn_answered, dateTime(it)) } ?: stringResource(R.string.turn_answered_plain)
    turn.state == TurnState.FAILED -> stringResource(R.string.turn_failed, turn.errorCode ?: "unknown")
    turn.state == TurnState.UNCERTAIN -> stringResource(R.string.turn_uncertain)
    turn.state == TurnState.CANCELLED -> stringResource(R.string.turn_cancelled)
    else -> stringResource(R.string.turn_refused, turn.errorCode ?: "unknown")
}

@Composable
private fun scopeText(scope: ChatScope) = stringResource(
    when (scope.kind) {
        ChatScope.Kind.ALL -> R.string.scope_all
        ChatScope.Kind.CONCERN -> R.string.scope_concern
        ChatScope.Kind.SOURCE -> R.string.scope_source
    },
)

@Composable
private fun chatMessage(m: ChatMessage) = stringResource(
    when (m) {
        ChatMessage.OFFLINE -> R.string.chat_offline
        ChatMessage.SIGN_IN -> R.string.chat_sign_in
        ChatMessage.SCOPE_UNAVAILABLE -> R.string.chat_scope_unavailable
        ChatMessage.EMPTY -> R.string.chat_empty_question
        ChatMessage.TOO_LONG -> R.string.chat_too_long
        ChatMessage.CANCEL_TOO_LATE -> R.string.chat_cancel_too_late
        ChatMessage.CANCEL_UNKNOWN -> R.string.chat_cancel_unknown
    },
)
