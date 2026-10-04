package com.digifender.adminifender.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import com.digifender.adminifender.ui.theme.Glyphs
import com.digifender.adminifender.ui.theme.LedgerCard
import com.digifender.adminifender.ui.theme.ScreenHeader
import com.digifender.adminifender.ui.theme.SectionLabel
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
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
    Column(modifier) {
        // The scope is shown before anything is asked (FR-025).
        ScreenHeader(stringResource(R.string.chat_title), icon = Glyphs.ask, subtitle = scopeText(state.scope))
        Column(Modifier.weight(1f).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.message?.let { Text(chatMessage(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            if (state.threadId == null) {
                if (state.opening) Text(stringResource(R.string.chat_opening)) else TextButton(onClick = onOpen) { Text(stringResource(R.string.try_again)) }
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (state.turns.isEmpty()) item { Text(stringResource(R.string.chat_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(state.turns, key = { it.turnId }) { Turn(it, onResend, onCheck, onCancel, onSource) }
                }
            }
        }
        if (state.threadId != null) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = draft,
                    onValueChange = { draft = it.take(ChatRepository.MAX_QUESTION_CHARS) },
                    placeholder = { Text(stringResource(R.string.chat_input)) },
                    shape = RoundedCornerShape(28.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.weight(1f),
                )
                FilledIconButton(onClick = { if (onAsk(draft)) draft = "" }, modifier = Modifier.size(56.dp)) {
                    Icon(Glyphs.send, contentDescription = stringResource(R.string.chat_send))
                }
            }
        }
    }
}

@Composable
private fun Turn(turn: ChatTurn, onResend: (String) -> Unit, onCheck: (String) -> Unit, onCancel: (String) -> Unit, onSource: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
            modifier = Modifier.align(Alignment.End).widthIn(max = 300.dp),
        ) { Text(turn.content, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
        Text(turnStateText(turn), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.End))
        Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!turn.sent && turn.state == TurnState.SENDING && turn.errorCode != null) TextButton(onClick = { onResend(turn.turnId) }) { Text(stringResource(R.string.chat_send_again)) }
            if (turn.sent && !turn.state.finished) TextButton(onClick = { onCheck(turn.turnId) }) { Text(stringResource(R.string.chat_check)) }
            if (turn.state == TurnState.PENDING || (turn.state == TurnState.SENDING && turn.errorCode != null)) TextButton(onClick = { onCancel(turn.turnId) }) { Text(stringResource(R.string.chat_cancel)) }
        }
        turn.answer?.let { answer ->
            val sources = numberedCitations(turn.claims)
            Column(Modifier.widthIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = LedgerCard.border,
                    shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp),
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(answer, style = MaterialTheme.typography.bodyLarge)
                        turn.claims.forEach { Claim(it, sources) }
                        turn.gaps.forEach { Text(stringResource(R.string.chat_gap, it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                if (sources.isNotEmpty()) {
                    SectionLabel(stringResource(R.string.chat_sources))
                    sources.forEachIndexed { i, c -> SourceCard(i + 1, c, onSource) }
                }
            }
        }
    }
}

/** Each distinct cited range once, numbered in order of first use. */
internal fun numberedCitations(claims: List<ClaimDto>): List<CitationDto> =
    claims.flatMap { it.citations }.distinctBy { Triple(it.chunkId, it.start, it.end) }

@Composable
private fun Claim(claim: ClaimDto, sources: List<CitationDto>) {
    // Inference and hypothesis are labelled as reasoning, never as established fact.
    val refs = claim.citations.map { c -> sources.indexOfFirst { it.chunkId == c.chunkId && it.start == c.start && it.end == c.end } + 1 }.filter { it > 0 }.distinct()
    val marks = if (refs.isEmpty()) "" else " " + refs.joinToString("") { "[$it]" }
    Text("${basisText(claim.basis)}: ${claim.text}$marks", style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun SourceCard(number: Int, c: CitationDto, onSource: (String) -> Unit) {
    val excerpt = c.excerpt?.let { "“$it”" } ?: stringResource(R.string.citation_unavailable)
    val page = c.page?.let { stringResource(R.string.hit_page, it) }
    val sourceId = c.sourceId
    val open = sourceId != null && c.excerpt != null
    val mod = if (open) Modifier.clickable(role = Role.Button) { onSource(sourceId!!) } else Modifier
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer).then(mod).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Text("$number", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Column(Modifier.weight(1f)) {
            Text(excerpt, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (page != null) Text(page, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
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
