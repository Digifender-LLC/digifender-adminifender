package com.digifender.adminifender.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.digifender.adminifender.core.data.repository.ActResult
import com.digifender.adminifender.core.data.repository.ConcernRepository
import com.digifender.adminifender.core.data.repository.ConcernView
import com.digifender.adminifender.core.data.repository.Freshness
import com.digifender.adminifender.core.data.repository.InboxFilter
import com.digifender.adminifender.core.data.session.PasskeyAuthenticator
import com.digifender.adminifender.core.data.session.SessionManager
import com.digifender.adminifender.core.data.session.SessionState
import com.digifender.adminifender.core.data.session.SignInOutcome
import com.digifender.adminifender.core.model.ConcernAction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val STOP_TIMEOUT_MS = 5_000L

/** Sign-in screen state. [busy] covers the whole passkey round trip so the button cannot start a second one. */
data class SessionUiState(val session: SessionState, val busy: Boolean = false, val message: SignInMessage? = null, val signOutFailed: Boolean = false)

enum class SignInMessage { CANCELLED, NO_PASSKEY, REFUSED, UNAVAILABLE }

class SessionViewModel(private val session: SessionManager, private val onSignedIn: () -> Unit, private val signOutAll: suspend () -> Unit,
    private val cleanupPending: () -> Boolean = { false },
) : ViewModel() {
    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<SignInMessage?>(null)
    private val signOutFailed = MutableStateFlow(false)
    val state: StateFlow<SessionUiState> = combine(session.state, busy, message, signOutFailed) { s, b, m, failed -> SessionUiState(s, b, m, failed) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, SessionUiState(SessionState.Unknown))

    init {
        viewModelScope.launch {
            try {
                if (cleanupPending()) signOut()
                else if (session.restore() is SessionState.SignedIn) onSignedIn()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message.value = SignInMessage.UNAVAILABLE
            }
        }
    }

    fun signIn(passkeys: PasskeyAuthenticator) {
        if (busy.value) return
        busy.value = true
        message.value = null
        viewModelScope.launch {
            try {
                // Dismissing a cleanup error cannot reopen private stores over retained previous-session data.
                if (cleanupPending()) {
                    try { signOutAll() } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        signOutFailed.value = true
                        return@launch
                    }
                    if (cleanupPending()) {
                        signOutFailed.value = true
                        return@launch
                    }
                }
                signOutFailed.value = false
                message.value = when (session.signIn(passkeys)) {
                    is SignInOutcome.SignedIn -> null.also { onSignedIn() }
                    SignInOutcome.Cancelled -> SignInMessage.CANCELLED
                    SignInOutcome.NoPasskey -> SignInMessage.NO_PASSKEY
                    is SignInOutcome.Rejected -> SignInMessage.REFUSED
                    is SignInOutcome.Unavailable -> SignInMessage.UNAVAILABLE
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message.value = SignInMessage.UNAVAILABLE
            } finally { busy.value = false }
        }
    }

    fun signOut() {
        if (busy.value) return
        busy.value = true
        signOutFailed.value = false
        viewModelScope.launch {
            try { signOutAll() } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                signOutFailed.value = true
            } finally { busy.value = false }
        }
    }

    fun dismissSignOutError() { signOutFailed.value = false }
}

data class InboxUiState(val filter: InboxFilter, val items: List<ConcernView>?, val freshness: Freshness?)

@OptIn(ExperimentalCoroutinesApi::class)
class InboxViewModel(private val concerns: ConcernRepository) : ViewModel() {
    private val filter = MutableStateFlow(InboxFilter.ACTIVE)
    private val items = filter.flatMapLatest { f -> concerns.inbox(f).map { f to it } }

    val state: StateFlow<InboxUiState> = combine(items, concerns.freshness()) { (f, list), fresh -> InboxUiState(f, list, fresh) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), InboxUiState(InboxFilter.ACTIVE, null, null))

    fun select(next: InboxFilter) {
        filter.value = next
    }

    fun refresh() = concerns.refresh()
}

enum class ActMessage { QUEUED, NOT_AVAILABLE, SNOOZE_IN_PAST, GONE }

data class ConcernUiState(val loaded: Boolean, val view: ConcernView?)

class ConcernViewModel(private val id: String, private val concerns: ConcernRepository) : ViewModel() {
    private var markedRead = false
    private val _message = MutableStateFlow<ActMessage?>(null)
    val message: StateFlow<ActMessage?> = _message.asStateFlow()

    val state: StateFlow<ConcernUiState> = concerns.concern(id).map { view ->
        // Opening a concern marks it read once; that is not a meaningful review and never notifies (FR-030).
        if (view != null && view.shown.readAtMs == null && !markedRead) {
            markedRead = true
            concerns.act(id, ConcernAction.MarkRead)
        }
        ConcernUiState(true, view)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ConcernUiState(false, null))

    fun act(action: ConcernAction, expectedRevision: Int? = null) {
        viewModelScope.launch {
            _message.value = when (concerns.act(id, action, expectedRevision)) {
                ActResult.Queued -> ActMessage.QUEUED
                ActResult.NotAvailable -> ActMessage.NOT_AVAILABLE
                ActResult.SnoozeInPast -> ActMessage.SNOOZE_IN_PAST
                ActResult.NotCached -> ActMessage.GONE
            }
        }
    }

    fun messageShown() {
        _message.value = null
    }

    fun reapply(key: String) {
        viewModelScope.launch { concerns.reapply(key) }
    }

    fun discard(key: String) {
        viewModelScope.launch { concerns.discard(key) }
    }
}
