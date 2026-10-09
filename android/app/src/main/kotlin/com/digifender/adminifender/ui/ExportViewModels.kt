package com.digifender.adminifender.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.digifender.adminifender.core.data.export.CreateOutcome
import com.digifender.adminifender.core.data.export.ExportIntent
import com.digifender.adminifender.core.data.export.ExportRepository
import com.digifender.adminifender.core.data.export.RefreshOutcome
import com.digifender.adminifender.core.data.files.AttachmentRepository
import com.digifender.adminifender.core.data.files.Destination
import com.digifender.adminifender.core.data.files.LocalFile
import com.digifender.adminifender.core.data.network.ExportAdmission
import com.digifender.adminifender.core.data.network.ExportFilter
import com.digifender.adminifender.core.data.network.ExportScope
import com.digifender.adminifender.core.data.network.MAX_EXPORT_SOURCES
import com.digifender.adminifender.core.data.network.ScopeProblem
import com.digifender.adminifender.core.data.network.problem
import com.digifender.adminifender.core.data.review.DeletionRepository
import com.digifender.adminifender.core.data.review.DeletionReview
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val STOP_TIMEOUT_MS = 5_000L

/** Foreground polling of an unsettled export: quick at first, then every minute, and bounded so a stuck export never polls forever. */
val EXPORT_POLL_DELAYS_MS: List<Long> = listOf(3_000L, 5_000L, 10_000L, 15_000L, 30_000L) + List(30) { 60_000L }

enum class ExportKind { ARCHIVE, FILTER, SELECTED }

/** Why the form cannot be sent yet; shown next to the field, never turned into a smaller export. */
enum class BuilderProblem { NO_SOURCES, TOO_MANY_SOURCES, EMPTY_FILTER, BAD_ADDRESS, BAD_RANGE, BAD_FROM_DATE, BAD_TO_DATE }

enum class CreateNote { WAITING, REFUSED, SIGN_IN, INVALID }

data class ExportBuilderState(
    val kind: ExportKind,
    val admission: ExportAdmission,
    val from: String,
    val to: String,
    val sender: String,
    val recipient: String,
    val selectedCount: Int,
    val problem: BuilderProblem?,
    val busy: Boolean,
    val note: CreateNote?,
    val noteCode: String?,
    val created: String?,
)

/**
 * The export form. Nothing is implied: the admission (ordinary archive, quarantine only, or both) is always chosen and
 * always shown, a selection of emails is sent whole (up to the Worker's 1,000) or not at all, and a form that cannot be
 * sent says why instead of sending something smaller.
 */
class ExportBuilderViewModel(private val selected: List<String>, private val exports: ExportRepository, private val zone: ZoneId = ZoneId.systemDefault()) : ViewModel() {
    private val form = MutableStateFlow(
        ExportBuilderState(
            kind = if (selected.isEmpty()) ExportKind.ARCHIVE else ExportKind.SELECTED,
            admission = ExportAdmission.NORMAL,
            from = "", to = "", sender = "", recipient = "",
            selectedCount = selected.size,
            problem = null, busy = false, note = null, noteCode = null, created = null,
        ),
    )

    val state: StateFlow<ExportBuilderState> = form.map { f -> f.copy(problem = problemOf(f)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), form.value)

    fun kind(kind: ExportKind) = form.update { it.copy(kind = kind, note = null) }

    fun admission(admission: ExportAdmission) = form.update { it.copy(admission = admission, note = null) }

    fun from(text: String) = form.update { it.copy(from = text, note = null) }

    fun to(text: String) = form.update { it.copy(to = text, note = null) }

    fun sender(text: String) = form.update { it.copy(sender = text, note = null) }

    fun recipient(text: String) = form.update { it.copy(recipient = text, note = null) }

    private fun MutableStateFlow<ExportBuilderState>.update(change: (ExportBuilderState) -> ExportBuilderState) {
        value = change(value)
    }

    private fun day(text: String): Long? = try {
        LocalDate.parse(text.trim()).atStartOfDay(zone).toInstant().toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }

    /** The scope the form describes, or null when it describes none. */
    fun scopeOf(f: ExportBuilderState = form.value): ExportScope? = when (f.kind) {
        ExportKind.ARCHIVE -> ExportScope.Archive(f.admission)
        ExportKind.SELECTED -> ExportScope.Sources(f.admission, selected)
        ExportKind.FILTER -> {
            val fromMs = f.from.trim().takeIf { it.isNotEmpty() }?.let { day(it) ?: return null }
            val toMs = f.to.trim().takeIf { it.isNotEmpty() }?.let { day(it) ?: return null }
            ExportScope.Filtered(f.admission, ExportFilter(fromMs, toMs, f.sender.trim().lowercase().takeIf { it.isNotEmpty() }, f.recipient.trim().lowercase().takeIf { it.isNotEmpty() }))
        }
    }

    private fun problemOf(f: ExportBuilderState): BuilderProblem? {
        if (f.kind == ExportKind.FILTER) {
            if (f.from.isNotBlank() && day(f.from) == null) return BuilderProblem.BAD_FROM_DATE
            if (f.to.isNotBlank() && day(f.to) == null) return BuilderProblem.BAD_TO_DATE
        }
        val scope = scopeOf(f) ?: return BuilderProblem.EMPTY_FILTER
        return when (scope.problem()) {
            null -> null
            ScopeProblem.NO_SOURCES -> BuilderProblem.NO_SOURCES
            ScopeProblem.TOO_MANY_SOURCES -> BuilderProblem.TOO_MANY_SOURCES
            ScopeProblem.EMPTY_FILTER -> BuilderProblem.EMPTY_FILTER
            ScopeProblem.BAD_ADDRESS -> BuilderProblem.BAD_ADDRESS
            ScopeProblem.BAD_RANGE -> BuilderProblem.BAD_RANGE
            ScopeProblem.DUPLICATE_SOURCES, ScopeProblem.BAD_SOURCE_ID -> BuilderProblem.NO_SOURCES
        }
    }

    /** Stores the request and sends it. The owner sees the new export either way, because the request is stored first. */
    fun create() {
        val f = form.value
        if (f.busy || problemOf(f) != null) return
        val scope = scopeOf(f) ?: return
        form.update { it.copy(busy = true, note = null) }
        viewModelScope.launch {
            val out = exports.create(scope)
            form.update { current ->
                when (out) {
                    is CreateOutcome.Created -> current.copy(busy = false, created = out.intent.localId)
                    is CreateOutcome.Waiting -> current.copy(busy = false, created = out.intent.localId, note = CreateNote.WAITING, noteCode = out.code)
                    is CreateOutcome.Refused -> current.copy(busy = false, created = out.intent.localId, note = CreateNote.REFUSED, noteCode = out.code)
                    is CreateOutcome.Invalid -> current.copy(busy = false, note = CreateNote.INVALID)
                    CreateOutcome.SignIn -> current.copy(busy = false, note = CreateNote.SIGN_IN)
                    CreateOutcome.Superseded -> current.copy(busy = false)
                }
            }
        }
    }

    companion object {
        const val SOURCE_LIMIT = MAX_EXPORT_SOURCES
    }
}

enum class ExportNote { SIGN_IN, WAITING, UNREACHABLE }

data class ExportUiState(val intent: ExportIntent?, val loaded: Boolean, val busy: Boolean, val note: ExportNote?, val noteCode: String?)

/** One export: reads its state from Room, refreshes it from the Worker while on screen, and starts the download the owner chose. */
class ExportViewModel(
    private val localId: String,
    private val exports: ExportRepository,
    private val runInBackground: (suspend () -> Unit) -> Unit,
) : ViewModel() {
    private val busy = MutableStateFlow(false)
    private val note = MutableStateFlow<Pair<ExportNote, String?>?>(null)
    private var polling: Job? = null

    val state: StateFlow<ExportUiState> = combine(exports.intent(localId), busy, note) { intent, b, n -> ExportUiState(intent, true, b, n?.first, n?.second) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ExportUiState(null, false, false, null, null))

    /** Starts the bounded refresh loop; the screen calls it when it comes to the foreground. */
    fun onForeground() {
        if (polling?.isActive == true) return
        polling = viewModelScope.launch {
            for (wait in EXPORT_POLL_DELAYS_MS) {
                val settled = refreshOnce()
                if (settled) return@launch
                delay(wait)
            }
        }
    }

    fun onBackground() {
        polling?.cancel()
        polling = null
    }

    private suspend fun refreshOnce(): Boolean {
        val intent = state.value.intent
        if (intent != null && intent.phase != com.digifender.adminifender.core.data.export.ExportPhase.CREATED) return intent.settled
        return when (val out = exports.refresh(localId)) {
            is RefreshOutcome.Updated -> out.intent.settled.also { note.value = null }
            is RefreshOutcome.Unchanged -> out.intent.settled.also { note.value = ExportNote.UNREACHABLE to out.code }
            RefreshOutcome.SignIn -> true.also { note.value = ExportNote.SIGN_IN to null }
            RefreshOutcome.Superseded -> true
        }
    }

    fun refresh() {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                refreshOnce()
            } finally {
                busy.value = false
            }
        }
    }

    /** Sends the same request again (same key, same scope). It can only replay the export that may already exist. */
    fun resend() {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                note.value = when (val out = exports.resend(localId)) {
                    is CreateOutcome.Waiting -> ExportNote.WAITING to out.code
                    CreateOutcome.SignIn -> ExportNote.SIGN_IN to null
                    else -> null
                }
            } finally {
                busy.value = false
            }
        }
    }

    /** Streams the export into the file the owner chose. It runs in the app's own scope so leaving the screen does not stop it. */
    fun download(destination: Destination) {
        runInBackground { exports.download(localId, destination) }
    }

    fun stopTracking() {
        viewModelScope.launch { exports.stopTracking(localId) }
    }

    fun dismiss(onGone: () -> Unit) {
        viewModelScope.launch { if (exports.dismiss(localId)) onGone() }
    }

    fun noteShown() {
        note.value = null
    }
}

data class StorageUiState(
    val files: List<LocalFile>,
    val usedBytes: Long,
    val exports: List<ExportIntent>,
    val deletions: List<DeletionReview>,
)

/** What this phone holds privately: downloaded files, exports in progress or finished, and deletions that are open or unknown. */
class StorageViewModel(
    private val attachments: AttachmentRepository,
    private val exports: ExportRepository,
    private val deletions: DeletionRepository,
) : ViewModel() {
    val state: StateFlow<StorageUiState> = combine(attachments.allFiles(), exports.intents(), deletions.reviews()) { files, intents, reviews ->
        StorageUiState(
            files.sortedByDescending { it.lastAccessAt },
            files.filter { it.state == com.digifender.adminifender.core.data.files.FileState.READY }.sumOf { it.sizeBytes },
            intents.sortedByDescending { it.createdAtMs },
            reviews.sortedByDescending { it.createdAtMs },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), StorageUiState(emptyList(), 0, emptyList(), emptyList()))

    fun removeFile(attachmentId: String) {
        viewModelScope.launch { attachments.remove(attachmentId) }
    }

    /** Removes every downloaded file from this phone. Emails and their originals on the server are untouched. */
    fun clearFiles() {
        viewModelScope.launch { attachments.purgeAll() }
    }

    fun dismissExport(localId: String) {
        viewModelScope.launch { exports.dismiss(localId) }
    }

    fun dismissDeletion(localId: String) {
        viewModelScope.launch { deletions.dismiss(localId) }
    }
}
