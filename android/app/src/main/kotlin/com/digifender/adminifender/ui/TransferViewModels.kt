package com.digifender.adminifender.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.digifender.adminifender.core.data.network.ArchiveExport
import com.digifender.adminifender.core.data.network.ConfirmedDeletion
import com.digifender.adminifender.core.data.network.DeleteReview
import com.digifender.adminifender.core.data.network.TransferResult
import com.digifender.adminifender.core.data.repository.ExportSaveOutcome
import com.digifender.adminifender.core.data.repository.AttachmentRepository
import com.digifender.adminifender.core.data.repository.ExportRepository
import com.digifender.adminifender.core.data.repository.ExportRequest
import com.digifender.adminifender.core.data.repository.PreparedDeletion
import com.digifender.adminifender.core.data.repository.ReviewRepository
import com.digifender.adminifender.core.data.session.PasskeyAuthenticator
import com.digifender.adminifender.core.data.session.RecentAuthOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

enum class AttachmentDownloadState { DOWNLOADING, SAVED, REMOVED, SIGN_IN, FAILED, REMOVE_FAILED }

class AttachmentViewModel(private val sourceId: String, private val attachments: AttachmentRepository) : ViewModel() {
    private val _state = MutableStateFlow<Map<String, AttachmentDownloadState>>(emptyMap())
    val state = _state.asStateFlow()
    private var operations = emptyMap<String, AttachmentDownloadState>()
    private var savedIds = emptySet<String>()

    init {
        viewModelScope.launch {
            attachments.changes.collect {
                savedIds = attachments.saved(sourceId)
                publish()
            }
        }
    }

    // Storage observation owns the saved flag. An older network result cannot restore it after a purge.
    private fun publish() {
        val updated = operations.mapValues { (id, state) ->
            if (state == AttachmentDownloadState.REMOVE_FAILED && id !in savedIds) AttachmentDownloadState.REMOVED else state
        }.toMutableMap()
        savedIds.forEach { id ->
            if (updated[id] !in setOf(AttachmentDownloadState.DOWNLOADING, AttachmentDownloadState.REMOVE_FAILED)) updated[id] = AttachmentDownloadState.SAVED
        }
        _state.value = updated
    }

    private fun update(id: String, state: AttachmentDownloadState) {
        operations += id to state
        publish()
    }

    fun download(id: String) {
        if (_state.value[id] == AttachmentDownloadState.DOWNLOADING) return
        update(id, AttachmentDownloadState.DOWNLOADING)
        viewModelScope.launch {
            val next = when (attachments.download(sourceId, id)) {
                is TransferResult.Ok -> AttachmentDownloadState.REMOVED
                TransferResult.SignIn -> AttachmentDownloadState.SIGN_IN
                else -> AttachmentDownloadState.FAILED
            }
            update(id, next)
        }
    }

    fun remove(id: String) {
        if (_state.value[id] == AttachmentDownloadState.DOWNLOADING) return
        viewModelScope.launch {
            val result = attachments.remove(sourceId, id)
            update(id, if (result is TransferResult.Ok) AttachmentDownloadState.REMOVED else AttachmentDownloadState.REMOVE_FAILED)
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

    init {
        viewModelScope.launch {
            reviews.invalidatedSources.collect { ids ->
                if (sourceIds.any { it in ids }) _state.value = withoutDeletedSubjects(_state.value)
            }
        }
        prepare()
    }

    private fun withoutDeletedSubjects(state: DeletionUiState): DeletionUiState {
        if (sourceIds.none { it in reviews.invalidatedSources.value } || state.phase == DeletionPhase.SUCCEEDED) return state
        val review = state.review
        return if (state.phase in setOf(DeletionPhase.SENDING, DeletionPhase.UNKNOWN)) {
            state.copy(review = review?.copy(preview = review.preview.copy(sources = review.preview.sources.map { it.copy(subject = null) }, derived = emptyList())))
        } else state.copy(phase = DeletionPhase.NEEDS_REVIEW, review = null, expiresAtMs = null, code = "download_invalidated")
    }

    fun prepare() {
        // Never discard an unanswered consequential request just to get a fresh review.
        if (_state.value.phase in setOf(DeletionPhase.AUTHENTICATING, DeletionPhase.SENDING, DeletionPhase.UNKNOWN, DeletionPhase.SUCCEEDED)) return
        _state.value = DeletionUiState()
        viewModelScope.launch {
            _state.value = withoutDeletedSubjects(when (val result = reviews.prepare(sourceIds)) {
                is TransferResult.Ok -> {
                    prepared = result.value
                    DeletionUiState(if (result.value.needsReadback) DeletionPhase.UNKNOWN else DeletionPhase.REVIEW, result.value.review, result.value.expiresAtMs)
                }
                is TransferResult.Rejected -> DeletionUiState(DeletionPhase.ERROR, code = result.code)
                is TransferResult.Unavailable -> DeletionUiState(DeletionPhase.ERROR, code = result.code)
                TransferResult.SignIn -> DeletionUiState(DeletionPhase.ERROR, code = "sign_in")
            })
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
                _state.value = withoutDeletedSubjects(previous.copy(code = when (auth) {
                    RecentAuthOutcome.Cancelled -> "auth_cancelled"
                    RecentAuthOutcome.SignInRequired -> "sign_in"
                    else -> "auth_unavailable"
                }))
                return@launch
            }
            if (previous.phase != DeletionPhase.UNKNOWN && sourceIds.any { it in reviews.invalidatedSources.value }) {
                _state.value = withoutDeletedSubjects(previous)
                return@launch
            }
            _state.value = _state.value.copy(phase = DeletionPhase.SENDING)
            _state.value = withoutDeletedSubjects(when (val result = reviews.confirmAfterPasskey(review)) {
                is TransferResult.Ok -> DeletionUiState(DeletionPhase.SUCCEEDED, receipt = result.value)
                is TransferResult.Unavailable -> previous.copy(phase = DeletionPhase.UNKNOWN, code = result.code)
                TransferResult.SignIn -> previous.copy(phase = DeletionPhase.UNKNOWN, code = "sign_in")
                is TransferResult.Rejected -> previous.copy(phase = if (result.code == "recent_auth_required") previous.phase else DeletionPhase.NEEDS_REVIEW, code = result.code)
            })
        }
    }
}

data class ExportUiState(val busy: Boolean = false, val export: ArchiveExport? = null, val saved: Boolean = false, val unknownCreation: Boolean = false, val code: String? = null, val copySaved: Boolean = false)

class ExportViewModel(private val exports: ExportRepository) : ViewModel() {
    private val _state = MutableStateFlow(ExportUiState(busy = true))
    val state = _state.asStateFlow()
    private var request: ExportRequest? = null
    private var observedSaved: Pair<String, Boolean>? = null

    init {
        viewModelScope.launch {
            combine(_state.map { it.export?.id }.distinctUntilChanged(), exports.changes) { id, _ -> id }.collect { id ->
                observedSaved = id?.let { it to exports.saved(it) }
                publish(_state.value)
            }
        }
        restore()
    }

    // Operations update metadata and status; only the serialized store observer updates saved state.
    private fun publish(state: ExportUiState) {
        _state.value = state.copy(saved = observedSaved?.let { (id, saved) -> id == state.export?.id && saved } ?: false)
    }

    private fun restore() {
        publish(_state.value.copy(busy = true))
        viewModelScope.launch {
            publish(when (val restored = exports.restore()) {
                is TransferResult.Ok -> {
                    request = restored.value.request
                    ExportUiState(export = restored.value.export, unknownCreation = restored.value.unknownCreation)
                }
                else -> ExportUiState(code = "journal_unavailable")
            })
        }
    }

    /** The explicit scope confirmation button also retries an unanswered create with the same key. */
    fun create() {
        if (_state.value.busy || _state.value.export != null) return
        val currentRequest = request ?: run { restore(); return }
        publish(_state.value.copy(busy = true, code = null))
        viewModelScope.launch {
            publish(when (val result = exports.create(currentRequest)) {
                is TransferResult.Ok -> ExportUiState(export = result.value)
                is TransferResult.Rejected -> ExportUiState(code = result.code)
                is TransferResult.Unavailable -> ExportUiState(unknownCreation = true, code = result.code)
                TransferResult.SignIn -> _state.value.copy(busy = false, code = "sign_in")
            })
        }
    }

    fun refresh() {
        val current = _state.value
        val export = current.export ?: return
        if (current.busy) return
        publish(current.copy(busy = true, code = null))
        viewModelScope.launch {
            publish(when (val result = exports.refresh(export.id)) {
                is TransferResult.Ok -> current.copy(busy = false, export = result.value, code = null)
                else -> current.copy(busy = false, code = transferCode(result))
            })
        }
    }

    fun download() {
        val current = _state.value
        val export = current.export ?: return
        if (current.busy || export.state != "ready") return
        publish(current.copy(busy = true, code = null))
        viewModelScope.launch {
            val result = exports.download(export.id)
            publish(current.copy(busy = false, code = if (result is TransferResult.Ok) null else transferCode(result)))
        }
    }

    fun remove() {
        val current = _state.value
        val export = current.export ?: return
        if (current.busy) return
        publish(current.copy(busy = true))
        viewModelScope.launch {
            val result = exports.remove(export.id)
            publish(current.copy(busy = false, code = if (result is TransferResult.Ok) null else transferCode(result)))
        }
    }

    /** [write] is supplied only by the explicit Android CreateDocument result, never by an automatic task. */
    fun saveCopy(write: suspend (String) -> ExportSaveOutcome) {
        val current = _state.value
        val export = current.export ?: return
        if (current.busy) return
        publish(current.copy(busy = true, code = null, copySaved = false))
        viewModelScope.launch {
            publish(when (val result = write(export.id)) {
                ExportSaveOutcome.Saved -> _state.value.copy(busy = false, copySaved = true)
                ExportSaveOutcome.Cancelled -> _state.value.copy(busy = false, code = "save_cancelled")
                is ExportSaveOutcome.Failed -> _state.value.copy(busy = false, code = if (result.partialRemoved) "save_failed_removed" else "save_failed_partial")
            })
        }
    }

    /** Leaving a completed/failed snapshot is an explicit choice; unknown creates retain their request identity. */
    fun newExport() {
        if (_state.value.busy || _state.value.unknownCreation) return
        publish(_state.value.copy(busy = true))
        viewModelScope.launch {
            when (val result = exports.forgetCurrent()) {
                is TransferResult.Ok -> { request = exports.request(); publish(ExportUiState()) }
                else -> { publish(_state.value.copy(busy = false, code = transferCode(result))) }
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
