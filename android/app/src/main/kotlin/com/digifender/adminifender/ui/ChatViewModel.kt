package com.digifender.adminifender.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.digifender.adminifender.core.data.chat.ChatCitation
import com.digifender.adminifender.core.data.chat.ChatCitationResult
import com.digifender.adminifender.core.data.chat.ChatRepository
import com.digifender.adminifender.core.data.chat.ChatScope
import com.digifender.adminifender.core.data.chat.ChatSnapshot
import com.digifender.adminifender.core.data.chat.MAX_CHAT_QUESTION_CHARS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatUiState(
    val scope: ChatScope,
    val snapshot: ChatSnapshot? = null,
    val draft: String = "",
    val busy: Boolean = false,
    val message: String? = null,
    val citation: ChatCitationResult? = null,
) {
    val waiting: Boolean get() = snapshot?.session?.requestId != null || snapshot?.turns?.any { it.waiting } == true
    val canSend: Boolean get() = snapshot != null && !busy && !waiting && draft.isNotBlank() && draft.length <= MAX_CHAT_QUESTION_CHARS
}

/** Bounded foreground polling only: 1, 2, 5, 10 seconds, then the next foreground or explicit refresh. */
class ChatViewModel(private val scope: ChatScope, private val chat: ChatRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(ChatUiState(scope))
    val state = mutableState.asStateFlow()
    private var visible = false
    private var action: Job? = null
    private var polling: Job? = null
    private var refreshPending = false

    fun onForeground() {
        visible = true
        refresh()
    }

    fun onBackground() {
        visible = false
        polling?.cancel()
        // Clear revalidated content at the lifecycle boundary. Durable draft/request data remains in Room.
        mutableState.update { it.copy(snapshot = null, citation = null) }
    }

    fun editDraft(value: String) {
        if (state.value.busy) return
        val draft = value.take(MAX_CHAT_QUESTION_CHARS)
        mutableState.update { it.copy(draft = draft) }
        viewModelScope.launch {
            try {
                chat.saveDraft(scope, draft)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(message = "storage_unavailable") }
            }
        }
    }

    fun refresh() {
        if (action?.isActive == true || state.value.busy) {
            refreshPending = true
            // A sync may have learned a deletion after the in-flight read began. Do not show its old answer.
            mutableState.update { it.copy(snapshot = null, citation = null) }
        } else perform { chat.load(scope) }
    }

    fun send() {
        val current = state.value
        if (!current.canSend) return
        perform { chat.send(scope, current.draft) }
    }

    fun retryMissing() = perform { chat.retryMissing(scope) }

    fun cancel(turnId: String) = perform { chat.cancel(scope, turnId) }

    fun older() {
        val previous = state.value.snapshot ?: return
        val before = previous.turns.firstOrNull()?.turnId ?: return
        if (!previous.hasMore) return
        perform {
            val older = chat.load(scope, before)
            // Every visible page is refreshed before it is shown again; no old answers are resurrected.
            older
        }
    }

    fun openCitation(turnId: String, citation: ChatCitation) {
        if (state.value.busy) return
        polling?.cancel()
        viewModelScope.launch {
            mutableState.update { it.copy(busy = true, citation = null) }
            try {
                val result = chat.citation(scope, turnId, citation)
                if (visible && !refreshPending) mutableState.update { it.copy(citation = result) }
                if (result !is ChatCitationResult.Available) {
                    val refreshed = chat.load(scope)
                    if (visible && !refreshPending) apply(refreshed)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(citation = ChatCitationResult.Unavailable) }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
            if (visible && refreshPending) {
                refreshPending = false
                refresh()
            }
        }
    }

    fun dismissCitation() = mutableState.update { it.copy(citation = null) }

    private fun perform(block: suspend () -> ChatSnapshot) {
        if (action?.isActive == true || state.value.busy) return
        polling?.cancel()
        mutableState.update { it.copy(busy = true, snapshot = null, message = null, citation = null) }
        action = viewModelScope.launch {
            try {
                val snapshot = block()
                if (visible && !refreshPending) apply(snapshot)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                mutableState.update { it.copy(message = "storage_unavailable") }
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
            action = null
            if (visible && refreshPending) {
                refreshPending = false
                refresh()
            } else if (visible && state.value.waiting) poll()
        }
    }

    private fun apply(snapshot: ChatSnapshot) = mutableState.update {
        it.copy(snapshot = snapshot, draft = snapshot.session.draft, message = snapshot.message)
    }

    private fun poll() {
        polling = viewModelScope.launch {
            for (ms in listOf(1_000L, 2_000L, 5_000L, 10_000L)) {
                delay(ms)
                if (!visible || !state.value.waiting) break
                try {
                    val snapshot = chat.load(scope)
                    if (visible && !refreshPending) apply(snapshot)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    mutableState.update { it.copy(message = "storage_unavailable") }
                    break
                }
            }
        }
    }
}
