package com.digifender.adminifender.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.digifender.adminifender.core.data.network.DeletionPreviewSource
import com.digifender.adminifender.core.data.repository.ArchiveRepository
import com.digifender.adminifender.core.data.review.ConfirmOutcome
import com.digifender.adminifender.core.data.review.DeletionRepository
import com.digifender.adminifender.core.data.review.DeletionReview
import com.digifender.adminifender.core.data.review.PrepareOutcome
import com.digifender.adminifender.core.data.review.ReadbackOutcome
import com.digifender.adminifender.core.data.review.UnsettledReason
import com.digifender.adminifender.core.data.session.PasskeyAuthenticator
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val STOP_TIMEOUT_MS = 5_000L

/** A screen opens either on a selection the owner just made, or on a review the phone already holds. */
sealed interface DeletionTarget {
    data class Selection(val ids: List<String>) : DeletionTarget

    data class Existing(val localId: String) : DeletionTarget
}

/** What the last action came to; the screen turns each into its own plain sentence. */
enum class DeletionNote {
    NOT_ON_PHONE, TOO_MANY, SIGN_IN, WAITING, REFUSED, MISMATCH, SUPERSEDED,
    AUTH_CANCELLED, AUTH_FAILED, EXPIRED, CHANGED, REJECTED, NOT_COMPLETED, UNKNOWN,
    READ_OPEN, READ_EXPIRED_RECENTLY, READ_UNSETTLED, READ_NOT_KNOWN, READ_UNAVAILABLE, READ_MISMATCH,
    DELETED, UNUSED,
}

/** One email in the review. A subject is the Worker's reading at preparation, else the phone's cached one, else absent. */
data class DeletionItem(val sourceId: String, val subject: String?, val receivedAtMs: Long?, val quarantined: Boolean?, val onPhone: Boolean)

data class DeletionUiState(
    val review: DeletionReview?,
    val items: List<DeletionItem>,
    val note: DeletionNote?,
    val noteCode: String?,
    val busy: Boolean,
)

/**
 * The exact-review screen for archive deletion. It shows what the Worker read, asks for the passkey only on the
 * owner's confirmation, and never sends anything by itself: an unknown outcome is read back, not resent.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeletionViewModel(
    private val target: DeletionTarget,
    private val deletions: DeletionRepository,
    private val archive: ArchiveRepository,
) : ViewModel() {
    private val localId = MutableStateFlow<String?>((target as? DeletionTarget.Existing)?.localId)
    private val note = MutableStateFlow<Pair<DeletionNote, String?>?>(null)
    private val busy = MutableStateFlow(target is DeletionTarget.Selection)
    private val serverSubjects = MutableStateFlow<List<DeletionPreviewSource>>(emptyList())

    private val review = localId.flatMapLatest { id -> if (id == null) flowOf(null) else deletions.review(id) }
    private val cached = review.flatMapLatest { r -> if (r == null) flowOf(emptyList()) else archive.sources(r.sourceIds) }

    val state: StateFlow<DeletionUiState> = combine(review, cached, serverSubjects, note, busy) { r, held, subjects, n, b ->
        val ids = r?.sourceIds ?: (target as? DeletionTarget.Selection)?.ids.orEmpty()
        val byServer = subjects.associateBy { it.id }
        val byCache = held.associateBy { it.id }
        val items = ids.map { id ->
            val server = byServer[id]
            val local = byCache[id]
            DeletionItem(id, server?.subject ?: local?.subject, server?.receivedAtMs ?: local?.receivedAtMs, server?.quarantined ?: local?.quarantined, local != null)
        }
        DeletionUiState(r, items, n?.first, n?.second, b)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), DeletionUiState(null, emptyList(), null, null, busy.value))

    init {
        (target as? DeletionTarget.Selection)?.let { selection -> viewModelScope.launch { begin(selection.ids) } }
        (target as? DeletionTarget.Existing)?.let { existing -> viewModelScope.launch { serverSubjects.value = deletions.serverSubjects(existing.localId) } }
    }

    private suspend fun begin(ids: List<String>) {
        // Coming back to a selection adopts the review that is already there; it never prepares a second one by itself.
        val existing = deletions.active(ids)
        if (existing != null) {
            localId.value = existing.localId
            serverSubjects.value = deletions.serverSubjects(existing.localId)
            busy.value = false
            return
        }
        prepare(ids)
    }

    private suspend fun prepare(ids: List<String>) {
        busy.value = true
        try {
            applyPrepared(deletions.prepare(ids))
        } finally {
            busy.value = false
        }
    }

    private fun applyPrepared(outcome: PrepareOutcome) {
        when (outcome) {
            is PrepareOutcome.Ready -> {
                localId.value = outcome.review.localId
                serverSubjects.value = outcome.subjects
            }
            is PrepareOutcome.Waiting -> {
                localId.value = outcome.review.localId
                note.value = DeletionNote.WAITING to outcome.code
            }
            is PrepareOutcome.Refused -> {
                localId.value = outcome.review.localId
                note.value = DeletionNote.REFUSED to outcome.code
            }
            is PrepareOutcome.Mismatch -> {
                localId.value = outcome.review.localId
                note.value = DeletionNote.MISMATCH to null
            }
            PrepareOutcome.Empty -> note.value = DeletionNote.NOT_ON_PHONE to null
            is PrepareOutcome.TooMany -> note.value = DeletionNote.TOO_MANY to outcome.limit.toString()
            is PrepareOutcome.NotOnPhone -> note.value = DeletionNote.NOT_ON_PHONE to outcome.missing.size.toString()
            PrepareOutcome.SignIn -> note.value = DeletionNote.SIGN_IN to null
            PrepareOutcome.Superseded -> note.value = DeletionNote.SUPERSEDED to null
        }
    }

    private fun run(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                block()
            } finally {
                busy.value = false
            }
        }
    }

    /** The owner asks for a fresh review of the same selection, for example after one ran out. */
    fun prepareAgain() {
        val ids = state.value.review?.sourceIds ?: (target as? DeletionTarget.Selection)?.ids ?: return
        run {
            note.value = null
            localId.value = null
            prepare(ids)
        }
    }

    fun retryPrepare() {
        val id = localId.value ?: return
        run {
            note.value = null
            applyPrepared(deletions.retryPrepare(id))
        }
    }

    fun confirmable(review: DeletionReview, nowMs: Long) = deletions.confirmable(review, nowMs)

    /** Asks for the passkey and, only then, confirms. The passkey prompt is the owner's explicit approval of exactly what is on screen. */
    fun confirm(passkeys: PasskeyAuthenticator) {
        val id = localId.value ?: return
        run {
            note.value = null
            note.value = when (val out = deletions.confirm(id, passkeys)) {
                is ConfirmOutcome.Deleted -> DeletionNote.DELETED to null
                ConfirmOutcome.AuthCancelled -> DeletionNote.AUTH_CANCELLED to null
                is ConfirmOutcome.AuthFailed -> DeletionNote.AUTH_FAILED to out.code
                ConfirmOutcome.SignIn -> DeletionNote.SIGN_IN to null
                ConfirmOutcome.Expired -> DeletionNote.EXPIRED to null
                ConfirmOutcome.Changed -> DeletionNote.CHANGED to null
                is ConfirmOutcome.Rejected -> DeletionNote.REJECTED to out.review.errorCode
                is ConfirmOutcome.NotCompleted -> DeletionNote.NOT_COMPLETED to out.code
                is ConfirmOutcome.Unknown -> DeletionNote.UNKNOWN to out.code
                is ConfirmOutcome.NotAllowed -> null
                ConfirmOutcome.Superseded -> DeletionNote.SUPERSEDED to null
            }
        }
    }

    /** Read-only: asks the Worker what became of a confirmation that got no answer. Nothing is sent again. */
    fun checkStatus() {
        val id = localId.value ?: return
        run {
            note.value = when (val out = deletions.readback(id)) {
                is ReadbackOutcome.Deleted -> DeletionNote.DELETED to null
                is ReadbackOutcome.Unused -> DeletionNote.UNUSED to null
                is ReadbackOutcome.Unsettled -> when (out.reason) {
                    UnsettledReason.OPEN -> DeletionNote.READ_OPEN to null
                    UnsettledReason.EXPIRED_RECENTLY -> DeletionNote.READ_EXPIRED_RECENTLY to null
                    UnsettledReason.OPERATION_UNSETTLED -> DeletionNote.READ_UNSETTLED to out.review.errorCode
                    UnsettledReason.NOT_KNOWN -> DeletionNote.READ_NOT_KNOWN to null
                    UnsettledReason.UNAVAILABLE -> DeletionNote.READ_UNAVAILABLE to out.review.errorCode
                    UnsettledReason.MISMATCH -> DeletionNote.READ_MISMATCH to null
                }
                ReadbackOutcome.SignIn -> DeletionNote.SIGN_IN to null
                ReadbackOutcome.NothingToRead -> null
            }
        }
    }

    fun refreshCleanup() {
        val id = localId.value ?: return
        run { deletions.refreshCleanup(id) }
    }

    /** Gives up tracking an outcome that stayed unknown. The deletion may still have happened; sync will show it. */
    fun stopTracking() {
        val id = localId.value ?: return
        viewModelScope.launch { deletions.stopTracking(id) }
    }

    /** Removes a finished or abandoned review from the list. Returns through [onGone] when it is gone. */
    fun dismiss(onGone: () -> Unit) {
        val id = localId.value
        viewModelScope.launch {
            if (id == null || deletions.dismiss(id)) onGone()
        }
    }

    fun noteShown() {
        note.value = null
    }
}
