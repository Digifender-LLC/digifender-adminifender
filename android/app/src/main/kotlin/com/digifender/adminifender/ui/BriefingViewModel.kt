package com.digifender.adminifender.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.digifender.adminifender.core.data.network.BriefingReviewResult
import com.digifender.adminifender.core.data.network.ReadResult
import com.digifender.adminifender.core.data.repository.BriefingRepository
import com.digifender.adminifender.core.data.repository.BriefingSnapshot
import com.digifender.adminifender.core.data.repository.VisibleBriefing
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class BriefingMessage { SIGN_IN, LOAD_FAILED, REVIEW_UNKNOWN, REVIEW_REFUSED }
enum class BriefingReviewState { NOT_REVIEWED, SENDING, REVIEWED }

data class BriefingUiState(
    val briefing: VisibleBriefing? = null,
    val loading: Boolean = false,
    val review: BriefingReviewState = BriefingReviewState.NOT_REVIEWED,
    val message: BriefingMessage? = null,
)

/** Opening/refreshing is a read. Only the owner's review button can advance meaningful-review state. */
class BriefingViewModel(private val repository: BriefingRepository) : ViewModel() {
    private val _state = MutableStateFlow(BriefingUiState())
    val state: StateFlow<BriefingUiState> = _state.asStateFlow()
    private var snapshot: BriefingSnapshot? = null
    private var observation: Job? = null

    init { refresh() }

    fun refresh() {
        if (_state.value.loading || _state.value.review == BriefingReviewState.SENDING) return
        _state.value = _state.value.copy(loading = true, message = null)
        viewModelScope.launch {
            try {
                when (val result = repository.load()) {
                    is ReadResult.Ok -> {
                        observation?.cancel()
                        snapshot = result.value
                        _state.value = BriefingUiState(loading = true)
                        observation = viewModelScope.launch {
                            repository.visible(result.value).collect { visible -> _state.value = _state.value.copy(briefing = visible) }
                        }
                    }
                    ReadResult.SessionExpired -> _state.value = _state.value.copy(message = BriefingMessage.SIGN_IN)
                    else -> _state.value = _state.value.copy(message = BriefingMessage.LOAD_FAILED)
                }
            } finally {
                _state.value = _state.value.copy(loading = false)
            }
        }
    }

    fun review() {
        val current = snapshot ?: return
        val ui = _state.value
        if (ui.loading || ui.review != BriefingReviewState.NOT_REVIEWED || ui.briefing == null || ui.briefing.hiddenConcerns) return
        _state.value = ui.copy(review = BriefingReviewState.SENDING, message = null)
        viewModelScope.launch {
            try {
                when (repository.review(current)) {
                    is BriefingReviewResult.Reviewed, BriefingReviewResult.AlreadyReviewed -> _state.value = _state.value.copy(review = BriefingReviewState.REVIEWED)
                    BriefingReviewResult.SessionExpired -> _state.value = _state.value.copy(message = BriefingMessage.SIGN_IN)
                    BriefingReviewResult.NoAnswer -> _state.value = _state.value.copy(message = BriefingMessage.REVIEW_UNKNOWN)
                    is BriefingReviewResult.Rejected -> _state.value = _state.value.copy(message = BriefingMessage.REVIEW_REFUSED)
                }
            } finally {
                if (_state.value.review == BriefingReviewState.SENDING) _state.value = _state.value.copy(review = BriefingReviewState.NOT_REVIEWED)
            }
        }
    }
}
