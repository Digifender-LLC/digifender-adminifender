package com.digifender.adminifender.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.digifender.adminifender.core.data.network.ArchiveView
import com.digifender.adminifender.core.data.network.ChatScope
import com.digifender.adminifender.core.data.network.ScopeKind
import com.digifender.adminifender.core.data.network.SourceSummary
import com.digifender.adminifender.core.data.network.isRecordId
import com.digifender.adminifender.core.data.repository.ArchiveRepository
import com.digifender.adminifender.core.data.repository.CHAT_POLL_DELAYS_MS
import com.digifender.adminifender.core.data.repository.CancelOutcome
import com.digifender.adminifender.core.data.repository.ChatRepository
import com.digifender.adminifender.core.data.repository.ChatThread
import com.digifender.adminifender.core.data.repository.ChatTurn
import com.digifender.adminifender.core.data.repository.ConcernRepository
import com.digifender.adminifender.core.data.repository.DeleteThread
import com.digifender.adminifender.core.data.repository.EditAgain
import com.digifender.adminifender.core.data.repository.ReadOutcome
import com.digifender.adminifender.core.data.repository.SendOutcome
import com.digifender.adminifender.core.model.Concern
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val STOP_TIMEOUT_MS = 5_000L
private const val PICKER_SOURCES = 200
private const val PICKER_LIMIT = 100

/**
 * What a conversation is about, in words the owner recognises. [name] is the cached title or subject; [missing] means
 * the concern or email is not on this phone (deleted, or not synced yet). A service has no name on the phone: the
 * Worker offers no way to list services, so only a short form of its ID is shown, with the titles of the cached
 * concerns that point at it ([linked]) so the owner can tell services apart.
 */
data class ScopeLabel(val kind: ScopeKind, val name: String?, val missing: Boolean, val linked: List<String> = emptyList())

/** An item the owner can start a conversation about, built only from what the synced cache holds. */
data class ScopeOption(val scope: ChatScope, val label: ScopeLabel, val detail: Int)

data class ChatListItem(val thread: ChatThread, val label: ScopeLabel)

data class ChatListState(
    val items: List<ChatListItem>?,
    val concerns: List<ScopeOption>,
    val sources: List<ScopeOption>,
    /** [ScopeOption.detail] is the number of cached concerns linked to the service. */
    val services: List<ScopeOption>,
)

private fun shortId(id: String) = id.take(8)

private const val MAX_LINKED_TITLES = 3

/** Titles of the concerns that point at [serviceId], without repeats, at most [MAX_LINKED_TITLES]. */
private fun linkedTitles(serviceId: String, concerns: List<Concern>): List<String> =
    concerns.filter { it.serviceId == serviceId }.map { it.title }.filter { it.isNotBlank() }.distinct().take(MAX_LINKED_TITLES)

fun labelFor(scope: ChatScope, concerns: Map<String, Concern>, sources: Map<String, SourceSummary>): ScopeLabel = when (scope.kind) {
    ScopeKind.ALL -> ScopeLabel(ScopeKind.ALL, null, false)
    ScopeKind.CONCERN -> concerns[scope.id]?.let { ScopeLabel(ScopeKind.CONCERN, it.title, false) } ?: ScopeLabel(ScopeKind.CONCERN, null, true)
    ScopeKind.SOURCE -> sources[scope.id]?.let { ScopeLabel(ScopeKind.SOURCE, it.subject?.ifBlank { null }, false) } ?: ScopeLabel(ScopeKind.SOURCE, null, true)
    ScopeKind.SERVICE -> ScopeLabel(ScopeKind.SERVICE, scope.id?.let(::shortId), false, scope.id?.let { linkedTitles(it, concerns.values.toList()) }.orEmpty())
}

/** Services the cached concerns point at, most-linked first, each with its linked concerns' titles. This is all the phone can know about services. */
fun serviceOptions(concerns: List<Concern>): List<ScopeOption> = concerns
    .mapNotNull { c -> c.serviceId?.takeIf { isRecordId(it) } }
    .groupingBy { it }
    .eachCount()
    .entries
    .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
    .map { (id, count) -> ScopeOption(ChatScope(ScopeKind.SERVICE, id), ScopeLabel(ScopeKind.SERVICE, shortId(id), false, linkedTitles(id, concerns)), count) }

/** Emails that may be asked about: synced, ready and not quarantined. */
fun askable(source: SourceSummary) = !source.quarantined && source.state == "ready" && (source.privacy == "hybrid" || source.privacy == "local_only")

@OptIn(ExperimentalCoroutinesApi::class)
class ChatListViewModel(private val chat: ChatRepository, private val concerns: ConcernRepository, private val archive: ArchiveRepository) : ViewModel() {
    private val threadSources = chat.threads().flatMapLatest { threads ->
        val ids = threads.filter { it.scope.kind == ScopeKind.SOURCE }.mapNotNull { it.scope.id }.distinct()
        if (ids.isEmpty()) flowOf(emptyList<SourceSummary>()) else archive.sources(ids)
    }

    val state: StateFlow<ChatListState> = combine(chat.threads(), concerns.views(), threadSources, archive.archive(ArchiveView.ARCHIVE, PICKER_SOURCES)) { threads, views, known, recent ->
        val byConcern = views.associate { it.server.id to it.shown }
        val bySource = known.associateBy { it.id }
        ChatListState(
            items = threads.map { ChatListItem(it, labelFor(it.scope, byConcern, bySource)) },
            concerns = views.map { it.shown }.sortedWith(compareByDescending<Concern> { it.updatedAtMs }.thenBy { it.id }).take(PICKER_LIMIT)
                .map { ScopeOption(ChatScope(ScopeKind.CONCERN, it.id), ScopeLabel(ScopeKind.CONCERN, it.title, false), 0) },
            sources = recent.filter(::askable).take(PICKER_LIMIT).map { ScopeOption(ChatScope(ScopeKind.SOURCE, it.id), ScopeLabel(ScopeKind.SOURCE, it.subject?.ifBlank { null }, false), 0) },
            services = serviceOptions(views.map { it.shown }),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ChatListState(null, emptyList(), emptyList(), emptyList()))

    /** Opens the latest conversation for [scope] or starts a local one; no request is made until a question is sent. */
    fun open(scope: ChatScope, onReady: (String) -> Unit) {
        viewModelScope.launch { chat.openThread(scope)?.let(onReady) }
    }

    /** Removes a conversation from this phone; [onInFlight] runs when a question is unconfirmed or still being answered. */
    fun delete(localId: String, onInFlight: () -> Unit) {
        viewModelScope.launch { if (chat.deleteThread(localId) == DeleteThread.InFlight) onInFlight() }
    }

    /** The list came to the foreground: send what is stored and read back what is unconfirmed. */
    fun onForeground() {
        viewModelScope.launch { chat.recover() }
    }
}

enum class ChatProblem { SIGN_IN, UNREACHABLE }

enum class ChatMessage {
    EMPTY,
    TOO_LONG,
    SCOPE_REFUSED,
    GONE,
    SIGN_IN,
    NOT_CONFIRMED,
    CANCELLED,
    WITHDRAWN,
    ALREADY_DISPATCHED,
    NOT_CANCELLABLE,
    CANCEL_REFUSED,
    EARLIER_UNAVAILABLE,

    /** "Edit as a new question" found other text in the draft, so the question stays where it is. */
    DRAFT_IN_USE,
}

data class ChatThreadState(
    val loaded: Boolean,
    val thread: ChatThread?,
    val label: ScopeLabel?,
    val turns: List<ChatTurn>,
    /** Cached emails the turns cite or propose, by ID; a missing one is not on this phone. */
    val sources: Map<String, SourceSummary>,
    val problem: ChatProblem?,
    val message: ChatMessage?,
)

/**
 * The two sources of a problem banner are kept apart so one cannot overwrite the other: a failed history read and a
 * failed poll each set and clear only their own, and a sign-in problem outranks an unreachable one.
 */
private data class ChatTransient(val historyProblem: ChatProblem? = null, val pollProblem: ChatProblem? = null, val message: ChatMessage? = null) {
    val problem: ChatProblem?
        get() = if (historyProblem == ChatProblem.SIGN_IN || pollProblem == ChatProblem.SIGN_IN) ChatProblem.SIGN_IN else historyProblem ?: pollProblem
}

private fun ChatTurn.mentionedSourceIds(): List<String> {
    val cited = answer?.claims.orEmpty().flatMap { claim -> claim.citations.mapNotNull { it.sourceId } }
    val proposed = answer?.proposals.orEmpty().map { it.targetId }.filter { isRecordId(it) }
    return cited + proposed
}

/**
 * One conversation. The draft is written to Room as it is typed; a question is stored before it is sent; and polling
 * follows the schedule in [CHAT_POLL_DELAYS_MS] (1, 2, 5 and 10 seconds), after which only a foreground or a finished
 * sync reads again. Leaving the screen stops the timers and nothing else: no cancel request is ever sent for it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatThreadViewModel(
    private val localId: String,
    private val chat: ChatRepository,
    private val concerns: ConcernRepository,
    private val archive: ArchiveRepository,
    syncTicks: Flow<Long?>,
) : ViewModel() {
    private val transient = MutableStateFlow(ChatTransient())

    private val draft = MutableStateFlow("")

    /** The text field reads this directly, so typing is never delayed behind the Room-backed [state]. */
    val draftText: StateFlow<String> = draft

    /** Set by the first keystroke or the first read of the stored draft, so a late read can never overwrite typing. */
    private var draftLoaded = false
    private val draftLock = Mutex()
    private var submitting = false
    private var foreground = false
    private var pollJob: Job? = null

    private fun labelFlow(scope: ChatScope): Flow<ScopeLabel> = when (scope.kind) {
        ScopeKind.ALL -> flowOf(ScopeLabel(ScopeKind.ALL, null, false))
        ScopeKind.CONCERN -> concerns.concern(scope.id.orEmpty()).map { view -> ScopeLabel(ScopeKind.CONCERN, view?.shown?.title, view == null) }
        ScopeKind.SOURCE -> archive.source(scope.id.orEmpty()).map { source -> ScopeLabel(ScopeKind.SOURCE, source?.subject?.ifBlank { null }, source == null) }
        ScopeKind.SERVICE -> concerns.views().map { views -> ScopeLabel(ScopeKind.SERVICE, scope.id?.let(::shortId), false, scope.id?.let { linkedTitles(it, views.map { v -> v.shown }) }.orEmpty()) }
    }

    private val header = chat.thread(localId).flatMapLatest { thread ->
        if (thread == null) flowOf<Pair<ChatThread?, ScopeLabel?>>(null to null) else labelFlow(thread.scope).map { label -> Pair<ChatThread?, ScopeLabel?>(thread, label) }
    }

    private val citedSources = chat.turns(localId).flatMapLatest { turns ->
        val ids = turns.flatMap { it.mentionedSourceIds() }.distinct()
        if (ids.isEmpty()) flowOf(emptyMap<String, SourceSummary>()) else archive.sources(ids).map { list -> list.associateBy { it.id } }
    }

    val state: StateFlow<ChatThreadState> = combine(header, chat.turns(localId), citedSources, transient) { (thread, label), turns, sources, t ->
        ChatThreadState(thread != null, thread, label, turns, sources, t.problem, t.message)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ChatThreadState(false, null, null, emptyList(), emptyMap(), null, null))

    init {
        viewModelScope.launch {
            val stored = chat.thread(localId).filterNotNull().first().draft
            if (!draftLoaded) {
                draftLoaded = true
                draft.value = stored
            }
        }
        // A finished sync is a reason to read again, once, while the screen is showing.
        viewModelScope.launch { syncTicks.collect { if (foreground) restartPolling() } }
    }

    /** Typing: shown at once and written to Room, in order, skipping texts that were already replaced. */
    fun onDraft(text: String) {
        draftLoaded = true
        draft.value = text
        // Typing again dismisses the last result line.
        if (transient.value.message != null) transient.update { it.copy(message = null) }
        viewModelScope.launch { draftLock.withLock { if (draft.value == text) chat.setDraft(localId, text) } }
    }

    fun send() {
        if (submitting) return
        val text = draft.value
        if (text.isBlank()) {
            transient.update { it.copy(message = ChatMessage.EMPTY) }
            return
        }
        submitting = true
        transient.update { it.copy(message = null) }
        viewModelScope.launch {
            // Stored with its ID, text and scope, and the draft cleared, before any request exists.
            val outcome = draftLock.withLock {
                chat.submit(localId, text).also { if (it is SendOutcome.Submitted && draft.value == text) draft.value = "" }
            }
            submitting = false
            when (outcome) {
                is SendOutcome.Submitted -> restartPolling()
                SendOutcome.Empty -> transient.update { it.copy(message = ChatMessage.EMPTY) }
                SendOutcome.TooLong -> transient.update { it.copy(message = ChatMessage.TOO_LONG) }
                SendOutcome.Gone -> transient.update { it.copy(message = ChatMessage.GONE) }
                SendOutcome.ScopeRefused -> transient.update { it.copy(message = ChatMessage.SCOPE_REFUSED) }
            }
        }
    }

    /** The screen is in the foreground again: read the saved history and the turns still moving, from the start of the schedule. */
    fun onForeground() {
        foreground = true
        viewModelScope.launch { noteHistory(chat.refreshHistory(localId)) }
        restartPolling()
    }

    /** The screen left the foreground. Only the timers stop; the question and its cancel state are untouched. */
    fun onBackground() {
        foreground = false
        pollJob?.cancel()
        pollJob = null
    }

    fun refresh() {
        viewModelScope.launch { noteHistory(chat.refreshHistory(localId)) }
        restartPolling()
    }

    fun loadEarlier() {
        viewModelScope.launch {
            val outcome = chat.loadEarlier(localId)
            noteHistory(outcome)
            if (outcome is ReadOutcome.Unavailable || outcome == ReadOutcome.Offline) transient.update { it.copy(message = ChatMessage.EARLIER_UNAVAILABLE) }
        }
    }

    /** A history read sets or clears only its own banner; the poll's is untouched. */
    private fun noteHistory(outcome: ReadOutcome) {
        val problem = when (outcome) {
            ReadOutcome.SignIn -> ChatProblem.SIGN_IN
            ReadOutcome.Offline, is ReadOutcome.Unavailable -> ChatProblem.UNREACHABLE
            ReadOutcome.Updated, ReadOutcome.Stale, ReadOutcome.NotFound, ReadOutcome.NothingYet -> null
        }
        transient.update { it.copy(historyProblem = problem) }
    }

    private fun restartPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch { poll() }
    }

    private suspend fun poll() {
        var step = 0
        while (true) {
            val report = chat.recover()
            transient.update { it.copy(pollProblem = if (report.signIn) ChatProblem.SIGN_IN else if (report.unreachable) ChatProblem.UNREACHABLE else null) }
            if (!report.waiting || report.signIn || step >= CHAT_POLL_DELAYS_MS.size) return
            delay(CHAT_POLL_DELAYS_MS[step++])
        }
    }

    /** Explicit cancel of one question. The Worker decides, and its answer is shown as it gave it. */
    fun cancel(turnId: String) {
        viewModelScope.launch {
            val message = when (chat.cancel(turnId)) {
                CancelOutcome.Cancelled -> ChatMessage.CANCELLED
                CancelOutcome.Withdrawn -> ChatMessage.WITHDRAWN
                CancelOutcome.AlreadyDispatched -> ChatMessage.ALREADY_DISPATCHED
                CancelOutcome.NotCancellable -> ChatMessage.NOT_CANCELLABLE
                CancelOutcome.NotConfirmed -> ChatMessage.NOT_CONFIRMED
                CancelOutcome.SignIn -> ChatMessage.SIGN_IN
                CancelOutcome.Gone -> ChatMessage.GONE
                is CancelOutcome.Refused -> ChatMessage.CANCEL_REFUSED
            }
            transient.update { it.copy(message = message) }
        }
    }

    /**
     * Puts a refused or withdrawn question's text back into the draft so it can be edited and sent as a new question.
     * Only an empty draft is replaced: with other text there the question stays and says so, so neither is lost. The
     * draft lock is held so a keystroke still being written cannot land between the check and the change.
     */
    fun editAgain(turnId: String) {
        viewModelScope.launch {
            val message = draftLock.withLock {
                if (draft.value.isNotBlank()) {
                    ChatMessage.DRAFT_IN_USE
                } else {
                    when (val outcome = chat.editAgain(turnId)) {
                        is EditAgain.Restored -> {
                            draftLoaded = true
                            draft.value = outcome.text
                            null
                        }
                        EditAgain.DraftInUse -> ChatMessage.DRAFT_IN_USE
                        EditAgain.Unavailable -> null
                    }
                }
            }
            if (message != null) transient.update { it.copy(message = message) }
        }
    }

    fun messageShown() {
        transient.update { it.copy(message = null) }
    }
}
