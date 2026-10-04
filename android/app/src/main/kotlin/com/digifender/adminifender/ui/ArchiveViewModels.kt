package com.digifender.adminifender.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.digifender.adminifender.core.data.network.ArchiveView
import com.digifender.adminifender.core.data.network.SourceSummary
import com.digifender.adminifender.core.data.repository.ArchiveRepository
import com.digifender.adminifender.core.data.repository.CachedReason
import com.digifender.adminifender.core.data.repository.DownloadOutcome
import com.digifender.adminifender.core.data.repository.InvalidQuery
import com.digifender.adminifender.core.data.repository.SearchHit
import com.digifender.adminifender.core.data.repository.SearchOutcome
import com.digifender.adminifender.core.data.repository.SourceText
import com.digifender.adminifender.core.model.Concern
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val STOP_TIMEOUT_MS = 5_000L
private const val LIST_PAGE = 100
private const val DAY_MS = 86_400_000L

/** Received-date filter offered next to the query; dates are applied by the server or the cache, not the screen. */
enum class DateRange(val days: Int?) { ANY(null), WEEK(7), MONTH(31), YEAR(366) }

/** What a result list covers. Only [Complete] may be described as the whole archive. */
sealed interface Coverage {
    data object Complete : Coverage

    /** More partitions remain; the order shown is per partition, not a global ranking yet. */
    data class Partial(val partitionsRemaining: Int) : Coverage

    data class CachedOnly(val reason: CachedReason) : Coverage
}

data class SearchUiState(
    val running: Boolean,
    val hits: List<SearchHit>,
    val coverage: Coverage?,
    val invalid: InvalidQuery?,
    val nextCursor: String?,
    val moreFailed: Boolean,
)

data class ArchiveUiState(
    val view: ArchiveView,
    val list: List<SourceSummary>?,
    val total: Int,
    val search: SearchUiState?,
)

/** Search parameters fixed when the owner submits, so "load more" continues the same query. */
private data class Submitted(val query: String, val view: ArchiveView, val sender: String?, val fromMs: Long?)

@OptIn(ExperimentalCoroutinesApi::class)
class ArchiveViewModel(private val archive: ArchiveRepository, private val clock: () -> Long) : ViewModel() {
    private val view = MutableStateFlow(ArchiveView.ARCHIVE)
    private val limit = MutableStateFlow(LIST_PAGE)
    private val search = MutableStateFlow<SearchUiState?>(null)
    private var submitted: Submitted? = null
    private var job: Job? = null

    val query = MutableStateFlow("")
    val sender = MutableStateFlow("")
    val range = MutableStateFlow(DateRange.ANY)

    private val list = combine(view, limit) { v, l -> v to l }.flatMapLatest { (v, l) -> combine(archive.archive(v, l), archive.count(v)) { rows, n -> Triple(v, rows, n) } }

    val state: StateFlow<ArchiveUiState> = combine(list, search) { (v, rows, n), s -> ArchiveUiState(v, rows, n, s) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ArchiveUiState(ArchiveView.ARCHIVE, null, 0, null))

    fun selectView(next: ArchiveView) {
        if (view.value == next) return
        view.value = next
        limit.value = LIST_PAGE
        // Results from one view never stay on screen under the other's label.
        if (submitted != null) submit()
    }

    fun showMore() {
        limit.value += LIST_PAGE
    }

    fun submit() {
        val days = range.value.days
        val params = Submitted(query.value, view.value, sender.value.trim().ifEmpty { null }, days?.let { clock() - it * DAY_MS })
        submitted = params
        job?.cancel()
        search.value = SearchUiState(running = true, hits = emptyList(), coverage = null, invalid = null, nextCursor = null, moreFailed = false)
        job = viewModelScope.launch {
            search.value = when (val outcome = archive.search(params.query, params.view, params.sender, params.fromMs, null)) {
                is SearchOutcome.Online -> SearchUiState(false, outcome.hits, coverageOf(outcome), null, outcome.nextCursor, false)
                is SearchOutcome.CachedOnly -> SearchUiState(false, outcome.hits, Coverage.CachedOnly(outcome.reason), null, null, false)
                is SearchOutcome.Invalid -> SearchUiState(false, emptyList(), null, outcome.reason, null, false)
                is SearchOutcome.MoreUnavailable -> SearchUiState(false, emptyList(), null, null, null, true)
            }
        }
    }

    fun loadMore() {
        val params = submitted ?: return
        val current = search.value ?: return
        val cursor = current.nextCursor ?: return
        if (current.running) return
        search.value = current.copy(running = true, moreFailed = false)
        job = viewModelScope.launch {
            search.value = when (val outcome = archive.search(params.query, params.view, params.sender, params.fromMs, null, cursor)) {
                is SearchOutcome.Online -> current.copy(running = false, hits = current.hits + outcome.hits, coverage = coverageOf(outcome), nextCursor = outcome.nextCursor)
                else -> current.copy(running = false, moreFailed = true)
            }
        }
    }

    fun clearSearch() {
        job?.cancel()
        submitted = null
        query.value = ""
        search.value = null
    }

    private fun coverageOf(outcome: SearchOutcome.Online) = if (outcome.partitionsRemaining > 0) Coverage.Partial(outcome.partitionsRemaining) else Coverage.Complete
}

/** Download state of the source's text, shown next to whatever copy is on screen. */
enum class TextStatus { LOADING, SAVED, NOT_SAVED, OFFLINE, SIGN_IN, GONE, UNAVAILABLE }

data class SourceUiState(
    val loaded: Boolean,
    val summary: SourceSummary?,
    val text: SourceText?,
    val textFromCache: Boolean,
    val status: TextStatus,
    val concerns: List<Concern>,
)

class SourceViewModel(private val id: String, private val archive: ArchiveRepository) : ViewModel() {
    private val fetched = MutableStateFlow<SourceText?>(null)
    private val status = MutableStateFlow(TextStatus.LOADING)
    private var everCached = false

    val state: StateFlow<SourceUiState> = combine(archive.source(id), archive.cachedText(id), archive.linkedConcerns(id), fetched, status) { summary, cached, concerns, live, st ->
        if (summary != null) everCached = true
        // Once sync removes the source, no copy held in memory stays on screen (FR-011).
        val deleted = everCached && summary == null
        val text = when {
            deleted -> null
            cached != null -> cached
            else -> live
        }
        SourceUiState(true, summary, text, cached != null && !deleted, if (deleted) TextStatus.GONE else st, if (deleted) emptyList() else concerns)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SourceUiState(false, null, null, false, TextStatus.LOADING, emptyList()))

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    init {
        download()
    }

    fun download() {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            status.value = TextStatus.LOADING
            status.value = when (val outcome = archive.download(id)) {
                is DownloadOutcome.Saved -> TextStatus.SAVED.also { fetched.value = null }
                is DownloadOutcome.NotSaved -> TextStatus.NOT_SAVED.also { fetched.value = outcome.text }
                DownloadOutcome.Gone -> TextStatus.GONE.also { fetched.value = null }
                DownloadOutcome.SignIn -> TextStatus.SIGN_IN
                is DownloadOutcome.Unavailable -> if (outcome.code == "network") TextStatus.OFFLINE else TextStatus.UNAVAILABLE
            }
            _busy.value = false
        }
    }
}
