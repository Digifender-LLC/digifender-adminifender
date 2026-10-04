package com.digifender.adminifender.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.digifender.adminifender.core.data.network.BriefingDto
import com.digifender.adminifender.core.data.network.CallResult
import com.digifender.adminifender.core.data.network.DeviceDto
import com.digifender.adminifender.core.data.network.ExportDto
import com.digifender.adminifender.core.data.network.OwnerApi
import com.digifender.adminifender.core.data.network.ReadResult
import com.digifender.adminifender.core.data.network.ReviewDto
import com.digifender.adminifender.core.data.repository.ConfirmOutcome
import com.digifender.adminifender.core.data.repository.ReviewFlow
import com.digifender.adminifender.core.data.repository.ReviewOutcome
import com.digifender.adminifender.core.data.session.PasskeyAuthenticator
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Why an online owner screen shows nothing new; cached state elsewhere stays readable. */
enum class OnlineProblem { OFFLINE, SIGN_IN, REFUSED }

private fun <T> problem(r: ReadResult<T>) = when (r) {
    ReadResult.SessionExpired -> OnlineProblem.SIGN_IN
    else -> OnlineProblem.OFFLINE
}

data class BriefingUiState(val loading: Boolean, val briefing: BriefingDto?, val problem: OnlineProblem?, val reviewed: Boolean)

/** The current briefing (lld.md#briefings). Mark reviewed covers exactly the snapshot shown and resolves nothing. */
class BriefingViewModel(private val api: OwnerApi) : ViewModel() {
    private val _state = MutableStateFlow(BriefingUiState(true, null, null, false))
    val state: StateFlow<BriefingUiState> = _state.asStateFlow()
    private var reviewKey: String? = null

    init {
        load()
    }

    fun load() {
        _state.value = _state.value.copy(loading = true, problem = null)
        viewModelScope.launch {
            _state.value = when (val r = api.briefing()) {
                is ReadResult.Ok -> BriefingUiState(false, r.value, null, false).also { reviewKey = null }
                else -> _state.value.copy(loading = false, problem = problem(r))
            }
        }
    }

    fun markReviewed() {
        val briefing = _state.value.briefing ?: return
        // One key per shown snapshot, so a resend after a lost answer is the same request.
        val key = reviewKey ?: UUID.randomUUID().toString().also { reviewKey = it }
        viewModelScope.launch {
            _state.value = when (val r = api.reviewBriefing(briefing.id, key)) {
                is CallResult.Ok -> _state.value.copy(reviewed = true, problem = null)
                is CallResult.Refused -> if (r.code == "already_reviewed") _state.value.copy(reviewed = true) else _state.value.copy(problem = OnlineProblem.REFUSED)
                CallResult.NoAnswer -> _state.value.copy(problem = OnlineProblem.OFFLINE)
                CallResult.SessionExpired -> _state.value.copy(problem = OnlineProblem.SIGN_IN)
            }
        }
    }
}

sealed interface ReviewUiState {
    data object Idle : ReviewUiState

    data object Preparing : ReviewUiState

    data class Ready(val review: ReviewDto, val confirming: Boolean = false, val note: ReviewNote? = null) : ReviewUiState

    data class Done(val operationId: String) : ReviewUiState

    data class Failed(val note: ReviewNote) : ReviewUiState
}

enum class ReviewNote { OFFLINE, SIGN_IN, REFUSED, REVIEW_AGAIN, PASSKEY_CANCELLED, UNKNOWN }

/**
 * One reviewed effect at a time. The confirmation key is kept for the shown review, so retrying after no answer can
 * never apply the effect twice; a changed target needs a fresh review.
 */
class ReviewViewModel(private val flow: ReviewFlow) : ViewModel() {
    private val _state = MutableStateFlow<ReviewUiState>(ReviewUiState.Idle)
    val state: StateFlow<ReviewUiState> = _state.asStateFlow()
    private var confirmKey: String? = null

    fun deleteSource(id: String) = prepare { flow.deleteSources(listOf(id)) }

    fun revokeDevice(id: String) = prepare { flow.revokeDevice(id) }

    private fun prepare(block: suspend () -> ReviewOutcome) {
        _state.value = ReviewUiState.Preparing
        confirmKey = null
        viewModelScope.launch {
            _state.value = when (val r = block()) {
                is ReviewOutcome.Ready -> ReviewUiState.Ready(r.review)
                is ReviewOutcome.Refused -> ReviewUiState.Failed(ReviewNote.REFUSED)
                ReviewOutcome.NoAnswer -> ReviewUiState.Failed(ReviewNote.OFFLINE)
                ReviewOutcome.SignInRequired -> ReviewUiState.Failed(ReviewNote.SIGN_IN)
            }
        }
    }

    fun confirm(passkeys: PasskeyAuthenticator) {
        val ready = _state.value as? ReviewUiState.Ready ?: return
        if (ready.confirming) return
        val key = confirmKey ?: UUID.randomUUID().toString().also { confirmKey = it }
        _state.value = ready.copy(confirming = true, note = null)
        viewModelScope.launch {
            _state.value = when (val r = flow.confirm(ready.review, key, passkeys)) {
                is ConfirmOutcome.Done -> ReviewUiState.Done(r.operationId)
                ConfirmOutcome.ReviewAgain -> ReviewUiState.Failed(ReviewNote.REVIEW_AGAIN)
                ConfirmOutcome.PasskeyCancelled -> ready.copy(note = ReviewNote.PASSKEY_CANCELLED)
                ConfirmOutcome.Unknown -> ready.copy(note = ReviewNote.UNKNOWN)
                ConfirmOutcome.SignInRequired -> ready.copy(note = ReviewNote.SIGN_IN)
                is ConfirmOutcome.Refused -> ready.copy(note = ReviewNote.REFUSED)
            }
        }
    }

    fun dismiss() {
        _state.value = ReviewUiState.Idle
        confirmKey = null
    }
}

data class OwnerSettingsState(val devices: List<DeviceDto>?, val devicesProblem: OnlineProblem?, val export: ExportDto?, val exportProblem: OnlineProblem?, val saving: Boolean, val saved: SaveNote?)

enum class SaveNote { SAVED, FAILED, EXPIRED }

/** Devices and archive export in Settings; both are online reads that never claim more than the server said. */
class OwnerSettingsViewModel(private val api: OwnerApi, private val save: suspend (String) -> SaveNote) : ViewModel() {
    private val _state = MutableStateFlow(OwnerSettingsState(null, null, null, null, false, null))
    val state: StateFlow<OwnerSettingsState> = _state.asStateFlow()
    private var exportKey: String? = null

    init {
        loadDevices()
    }

    fun loadDevices() {
        viewModelScope.launch {
            _state.value = when (val r = api.devices()) {
                is ReadResult.Ok -> _state.value.copy(devices = r.value.devices.filter { !it.revoked }, devicesProblem = null)
                else -> _state.value.copy(devicesProblem = problem(r))
            }
        }
    }

    /** Creates an export, then checks it with backoff until it is ready, failed or the screen goes away. */
    fun createExport() {
        val key = exportKey ?: UUID.randomUUID().toString().also { exportKey = it }
        viewModelScope.launch {
            val created = when (val r = api.createExport(key)) {
                is CallResult.Ok -> r.value
                CallResult.SessionExpired -> return@launch run { _state.value = _state.value.copy(exportProblem = OnlineProblem.SIGN_IN) }
                is CallResult.Refused -> return@launch run { _state.value = _state.value.copy(exportProblem = OnlineProblem.REFUSED) }
                CallResult.NoAnswer -> return@launch run { _state.value = _state.value.copy(exportProblem = OnlineProblem.OFFLINE) }
            }
            exportKey = null
            _state.value = _state.value.copy(export = created, exportProblem = null, saved = null)
            for (wait in EXPORT_POLL_MS) {
                if (_state.value.export?.state in FINAL_EXPORT_STATES) break
                delay(wait)
                val r = api.export(created.id)
                if (r is ReadResult.Ok) _state.value = _state.value.copy(export = r.value)
            }
        }
    }

    fun saveExport() {
        val export = _state.value.export ?: return
        _state.value = _state.value.copy(saving = true, saved = null)
        viewModelScope.launch { _state.value = _state.value.copy(saving = false, saved = save(export.id)) }
    }

    private companion object {
        val EXPORT_POLL_MS = listOf(2_000L, 5_000L, 10_000L, 20_000L, 30_000L, 60_000L)
        val FINAL_EXPORT_STATES = setOf("ready", "failed", "invalidated", "expired")
    }
}

data class OperationsUiState(val loading: Boolean, val overview: com.digifender.adminifender.core.data.network.OperationsDto?, val problem: OnlineProblem?, val enabled: Boolean)

/**
 * The Operations view (lld.md#android): shown only when the deployment has reports enabled and a service exists.
 * Freshness is about report expectations, never a live health probe.
 */
class OperationsViewModel(private val api: OwnerApi) : ViewModel() {
    private val _state = MutableStateFlow(OperationsUiState(true, null, null, false))
    val state: StateFlow<OperationsUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.value = when (val r = api.operations()) {
                is ReadResult.Ok -> OperationsUiState(false, r.value, null, r.value.services.isNotEmpty())
                is ReadResult.Unavailable -> if (r.code == "feature_disabled") OperationsUiState(false, null, null, false) else _state.value.copy(loading = false, problem = OnlineProblem.OFFLINE)
                else -> _state.value.copy(loading = false, problem = problem(r))
            }
        }
    }
}
