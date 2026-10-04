package com.digifender.adminifender.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.digifender.adminifender.core.data.network.CallResult
import com.digifender.adminifender.core.data.repository.AskResult
import com.digifender.adminifender.core.data.repository.ChatRepository
import com.digifender.adminifender.core.data.repository.ChatScope
import com.digifender.adminifender.core.data.repository.ChatTurn
import com.digifender.adminifender.core.data.repository.ThreadResult
import com.digifender.adminifender.core.data.repository.TurnState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val STOP_TIMEOUT_MS = 5_000L
private const val LOCAL_ONLY = "local_only"

enum class ChatMessage { OFFLINE, SIGN_IN, SCOPE_UNAVAILABLE, EMPTY, TOO_LONG, CANCEL_TOO_LATE, CANCEL_UNKNOWN }

data class ChatUiState(val scope: ChatScope, val threadId: String?, val opening: Boolean, val turns: List<ChatTurn>, val message: ChatMessage?)

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(private val scope: ChatScope, private val chat: ChatRepository, private val onLocalWait: () -> Unit = {}) : ViewModel() {
    private val threadId = MutableStateFlow<String?>(null)
    private val opening = MutableStateFlow(false)
    private val message = MutableStateFlow<ChatMessage?>(null)
    private val turns = threadId.flatMapLatest { id -> if (id == null) emptyFlow() else chat.turns(id) }

    val state: StateFlow<ChatUiState> = combine(threadId, opening, turns, message) { id, o, t, m -> ChatUiState(scope, id, o, t, m) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ChatUiState(scope, null, true, emptyList(), null))

    init {
        open()
        // A question waiting for local-only evidence can be answered by this phone while the app is open.
        viewModelScope.launch {
            turns.map { list -> list.any { it.state == TurnState.PENDING && it.reason == LOCAL_ONLY } }.distinctUntilChanged().filter { it }.collect { onLocalWait() }
        }
    }

    fun open() {
        if (opening.value || threadId.value != null) return
        opening.value = true
        viewModelScope.launch {
            when (val r = chat.openThread(scope)) {
                is ThreadResult.Ready -> {
                    threadId.value = r.threadId
                    message.value = null
                    // Answers that arrived while the app was closed are read back now.
                    chat.refreshUnfinished()
                }
                is ThreadResult.Refused -> message.value = ChatMessage.SCOPE_UNAVAILABLE
                ThreadResult.NoAnswer -> message.value = ChatMessage.OFFLINE
                ThreadResult.SessionExpired -> message.value = ChatMessage.SIGN_IN
            }
            opening.value = false
        }
    }

    /** Returns true when the question was accepted for sending, so the input can be cleared. */
    fun ask(content: String): Boolean {
        val id = threadId.value ?: return false
        if (content.isBlank()) {
            message.value = ChatMessage.EMPTY
            return false
        }
        if (content.trim().length > ChatRepository.MAX_QUESTION_CHARS) {
            message.value = ChatMessage.TOO_LONG
            return false
        }
        message.value = null
        viewModelScope.launch {
            when (chat.ask(id, content)) {
                is AskResult.Accepted -> Unit
                AskResult.Empty -> message.value = ChatMessage.EMPTY
                AskResult.TooLong -> message.value = ChatMessage.TOO_LONG
            }
        }
        return true
    }

    fun resend(turnId: String) {
        viewModelScope.launch { chat.send(turnId) }
    }

    fun checkAgain(turnId: String) {
        viewModelScope.launch { chat.refresh(turnId) }
    }

    fun cancel(turnId: String) {
        viewModelScope.launch {
            message.value = when (val r = chat.cancel(turnId)) {
                is CallResult.Ok -> null
                is CallResult.Refused -> if (r.code == "already_dispatched") ChatMessage.CANCEL_TOO_LATE else ChatMessage.CANCEL_UNKNOWN
                CallResult.NoAnswer -> ChatMessage.CANCEL_UNKNOWN
                CallResult.SessionExpired -> ChatMessage.SIGN_IN
            }
        }
    }
}

