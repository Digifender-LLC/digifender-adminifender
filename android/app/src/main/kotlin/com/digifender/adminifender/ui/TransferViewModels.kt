package com.digifender.adminifender.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.digifender.adminifender.core.data.network.ArchiveExport
import com.digifender.adminifender.core.data.network.ConfirmedDeletion
import com.digifender.adminifender.core.data.network.DeleteReview
import com.digifender.adminifender.core.data.network.TransferResult
import com.digifender.adminifender.core.data.repository.AttachmentRepository
import com.digifender.adminifender.core.data.repository.ExportRepository
import com.digifender.adminifender.core.data.repository.ExportRequest
import com.digifender.adminifender.core.data.repository.PreparedDeletion
import com.digifender.adminifender.core.data.repository.ReviewRepository
import com.digifender.adminifender.core.data.session.PasskeyAuthenticator
import com.digifender.adminifender.core.data.session.RecentAuthOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AttachmentDownloadState { DOWNLOADING, SAVED, REMOVED, SIGN_IN, FAILED, REMOVE_FAILED }

class AttachmentViewModel(private val sourceId: String, private val attachments: AttachmentRepository) : ViewModel() {
    private val _state = MutableStateFlow<Map<String, AttachmentDownloadState>>(emptyMap())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            attachments.changes.collect {
                val saved = attachments.saved(sourceId)
                val updated = _state.value.mapValues { (id, state) ->
                    if (state in setOf(AttachmentDownloadState.SAVED, AttachmentDownloadState.REMOVE_FAILED) && id !in saved) AttachmentDownloadState.REMOVED else state
                }.toMutableMap()
                saved.forEach { id -> if (updated[id] != AttachmentDownloadState.DOWNLOADING) updated[id] = AttachmentDownloadState.SAVED }
                _state.value = updated
            }
        }
    }

    fun download(id: String) {
        if (_state.value[id] == AttachmentDownloadState.DOWNLOADING) return
        _state.value += id to AttachmentDownloadState.DOWNLOADING
        viewModelScope.launch {
            val next = when (attachments.download(sourceId, id)) {
                is TransferResult.Ok -> AttachmentDownloadState.SAVED
                TransferResult.SignIn -> AttachmentDownloadState.SIGN_IN
                else -> AttachmentDownloadState.FAILED
            }
            _state.value += id to next
        }
    }

    fun remove(id: String) {
        if (_state.value[id] == AttachmentDownloadState.DOWNLOADING) return
        viewModelScope.launch {
            val result = attachments.remove(sourceId, id)
            _state.value += id to if (result is TransferResult.Ok) AttachmentDownloadState.REMOVED else AttachmentDownloadState.REMOVE_FAILED
        }
    }
}

enum class DeletionPhase { LOADING, REVIEW, AUTHENTICATING, SENDING, SUCCEEDED, NEEDS_REVIEW, UNKNOWN, ERROR }

data class DeletionUiState(val phase: DeletionPhase = DeletionPhase.LOADING, val review: DeleteReview? = null, val expiresAtMs: Long? = null, val receipt: ConfirmedDeletion? = null, val code: String? = null) {
    val busy: Boolean get() = phase in setOf(DeletionPhase.LOADING, DeletionPhase.AUTHENTICATING, DeletionPhase.SENDING)
}

class DeletionViewModel(private val sourceIds: List<String>, private val reviews: ReviewRepository, private val recentAuth: suspend (PasskeyAuthenticator) -> RecentAuthOutcome) : ViewModel() {
    private val _state = MutableStateFlow(DeletionUiState())
    val state = _state.asStateFlow()
    private var prepared: PreparedDeletion? = null

    init { prepare() }

    fun prepare() {
        // Never discard an unanswered consequential request just to get a fresh review.
        if (_state.value.phase in setOf(DeletionPhase.AUTHENTICATING, DeletionPhase.SENDING, DeletionPhase.UNKNOWN, DeletionPhase.SUCCEEDED)) return
        _state.value = DeletionUiState()
        viewModelScope.launch {
            _state.value = when (val result = reviews.prepare(sourceIds)) {
                is TransferResult.Ok -> {
                    prepared = result.value
                    DeletionUiState(if (result.value.needsReadback) DeletionPhase.UNKNOWN else DeletionPhase.REVIEW, result.value.review, result.value.expiresAtMs)
                }
                is TransferResult.Rejected -> DeletionUiState(DeletionPhase.ERROR, code = result.code)
                is TransferResult.Unavailable -> DeletionUiState(DeletionPhase.ERROR, code = result.code)
                TransferResult.SignIn -> DeletionUiState(DeletionPhase.ERROR, code = "sign_in")
            }
        }
    }

    fun confirm(passkeys: PasskeyAuthenticator) {
        val review = prepared ?: return
        val previous = _state.value
        if (previous.phase !in setOf(DeletionPhase.REVIEW, DeletionPhase.UNKNOWN)) return
        _state.value = previous.copy(phase = DeletionPhase.AUTHENTICATING, code = null)
        viewModelScope.launch {
            val auth = recentAuth(passkeys)
            if (auth !is RecentAuthOutcome.Confirmed) {
                _state.value = previous.copy(code = when (auth) {
                    RecentAuthOutcome.Cancelled -> "auth_cancelled"
                    RecentAuthOutcome.SignInRequired -> "sign_in"
                    else -> "auth_unavailable"
                })
                return@launch
            }
            _state.value = _state.value.copy(phase = DeletionPhase.SENDING)
            _state.value = when (val result = reviews.confirmAfterPasskey(review)) {
                is TransferResult.Ok -> DeletionUiState(DeletionPhase.SUCCEEDED, receipt = result.value)
                is TransferResult.Unavailable -> previous.copy(phase = DeletionPhase.UNKNOWN, code = result.code)
                TransferResult.SignIn -> previous.copy(phase = DeletionPhase.UNKNOWN, code = "sign_in")
                is TransferResult.Rejected -> previous.copy(phase = if (result.code == "recent_auth_required") previous.phase else DeletionPhase.NEEDS_REVIEW, code = result.code)
            }
        }
    }
}

data class ExportUiState(val busy: Boolean = false, val export: ArchiveExport? = null, val saved: Boolean = false, val unknownCreation: Boolean = false, val code: String? = null)

class ExportViewModel(private val exports: ExportRepository) : ViewModel() {
    private val _state = MutableStateFlow(ExportUiState(busy = true))
    val state = _state.asStateFlow()
    private var request: ExportRequest? = null

    init {
        viewModelScope.launch {
            exports.changes.collect {
                val id = _state.value.export?.id ?: return@collect
                val saved = exports.saved(id)
                if (_state.value.export?.id == id) _state.value = _state.value.copy(saved = saved)
            }
        }
        restore()
    }

    private fun restore() {
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            _state.value = when (val restored = exports.restore()) {
                is TransferResult.Ok -> {
                    request = restored.value.request
                    ExportUiState(export = restored.value.export, unknownCreation = restored.value.unknownCreation, saved = restored.value.export?.let { exports.saved(it.id) } ?: false)
                }
                else -> ExportUiState(code = "journal_unavailable")
            }
        }
    }

    /** The explicit scope confirmation button also retries an unanswered create with the same key. */
    fun create() {
        if (_state.value.busy || _state.value.export != null) return
        val currentRequest = request ?: run { restore(); return }
        _state.value = _state.value.copy(busy = true, code = null)
        viewModelScope.launch {
            _state.value = when (val result = exports.create(currentRequest)) {
                is TransferResult.Ok -> ExportUiState(export = result.value)
                is TransferResult.Rejected -> ExportUiState(code = result.code)
                is TransferResult.Unavailable -> ExportUiState(unknownCreation = true, code = result.code)
                TransferResult.SignIn -> _state.value.copy(busy = false, code = "sign_in")
            }
        }
    }

    fun refresh() {
        val current = _state.value
        val export = current.export ?: return
        if (current.busy) return
        _state.value = current.copy(busy = true, code = null)
        viewModelScope.launch {
            _state.value = when (val result = exports.refresh(export.id)) {
                is TransferResult.Ok -> current.copy(busy = false, export = result.value, saved = current.saved && result.value.state == "ready", code = null)
                else -> current.copy(busy = false, code = transferCode(result))
            }
        }
    }

    fun download() {
        val current = _state.value
        val export = current.export ?: return
        if (current.busy || export.state != "ready") return
        _state.value = current.copy(busy = true, code = null)
        viewModelScope.launch {
            val result = exports.download(export.id)
            _state.value = current.copy(busy = false, saved = result is TransferResult.Ok, code = if (result is TransferResult.Ok) null else transferCode(result))
        }
    }

    fun remove() {
        val current = _state.value
        val export = current.export ?: return
        if (current.busy) return
        _state.value = current.copy(busy = true)
        viewModelScope.launch {
            val result = exports.remove(export.id)
            _state.value = current.copy(saved = if (result is TransferResult.Ok) false else current.saved, busy = false, code = if (result is TransferResult.Ok) null else transferCode(result))
        }
    }

    /** Leaving a completed/failed snapshot is an explicit choice; unknown creates retain their request identity. */
    fun newExport() {
        if (_state.value.busy || _state.value.unknownCreation) return
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            when (val result = exports.forgetCurrent()) {
                is TransferResult.Ok -> { request = exports.request(); _state.value = ExportUiState() }
                else -> { _state.value = _state.value.copy(busy = false, code = transferCode(result)) }
            }
        }
    }
}

private fun transferCode(result: TransferResult<*>): String = when (result) {
    is TransferResult.Rejected -> result.code
    is TransferResult.Unavailable -> result.code
    TransferResult.SignIn -> "sign_in"
    is TransferResult.Ok -> ""
}
