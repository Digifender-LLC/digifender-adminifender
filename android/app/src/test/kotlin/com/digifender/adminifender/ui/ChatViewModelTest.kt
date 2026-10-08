package com.digifender.adminifender.ui

import com.digifender.adminifender.core.data.network.ArchiveApi
import com.digifender.adminifender.core.data.network.ArchiveView
import com.digifender.adminifender.core.data.network.CancelResult
import com.digifender.adminifender.core.data.network.ChatScope
import com.digifender.adminifender.core.data.network.ChunksPageDto
import com.digifender.adminifender.core.data.network.HistoryDto
import com.digifender.adminifender.core.data.network.PostResult
import com.digifender.adminifender.core.data.network.ReadResult
import com.digifender.adminifender.core.data.network.ScopeDto
import com.digifender.adminifender.core.data.network.ScopeKind
import com.digifender.adminifender.core.data.network.SearchPageDto
import com.digifender.adminifender.core.data.network.SearchRequest
import com.digifender.adminifender.core.data.network.SourceDetailDto
import com.digifender.adminifender.core.data.network.SourceSummary
import com.digifender.adminifender.core.data.network.ThreadDto
import com.digifender.adminifender.core.data.repository.ArchiveCache
import com.digifender.adminifender.core.data.repository.ArchiveRepository
import com.digifender.adminifender.core.data.repository.CachedSearchHit
import com.digifender.adminifender.core.data.repository.CancelNote
import com.digifender.adminifender.core.data.repository.ChatRepository
import com.digifender.adminifender.core.data.repository.ConcernRepository
import com.digifender.adminifender.core.data.repository.Dispatch
import com.digifender.adminifender.core.data.repository.ServerState
import com.digifender.adminifender.core.data.repository.SourceText
import com.digifender.adminifender.core.model.Concern
import com.digifender.adminifender.core.model.Importance
import com.digifender.adminifender.core.model.Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val TURN_A = "11111111-1111-4111-8111-111111111111"
private const val CONCERN_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
private const val SERVICE_ID = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"

private class StubArchiveApi : ArchiveApi {
    override suspend fun source(id: String): ReadResult<SourceDetailDto> = ReadResult.Unavailable("network")
    override suspend fun chunks(id: String, cursor: String?, limit: Int): ReadResult<ChunksPageDto> = ReadResult.Unavailable("network")
    override suspend fun search(request: SearchRequest, cursor: String?, limit: Int): ReadResult<SearchPageDto> = ReadResult.Unavailable("network")
}

private class SourceCache : ArchiveCache {
    val rows = MutableStateFlow(emptyMap<String, SourceSummary>())
    override fun sources(view: ArchiveView, limit: Int) = rows.map { it.values.toList() }
    override fun count(view: ArchiveView) = rows.map { it.size }
    override fun source(id: String) = rows.map { it[id] }
    override fun sources(ids: List<String>) = rows.map { m -> ids.mapNotNull { m[it] } }
    override fun text(id: String): Flow<SourceText?> = flowOf(null)
    override suspend fun search(terms: List<String>, view: ArchiveView, sender: String?, fromMs: Long?, toMs: Long?, limit: Int) = emptyList<CachedSearchHit>()
    override suspend fun putText(id: String, text: SourceText, nowMs: Long) = false
}

private fun summary(id: String, subject: String? = "Water bill", quarantined: Boolean = false, state: String = "ready", privacy: String = "hybrid") =
    SourceSummary(id, 1, state, quarantined, privacy, 100, subject, listOf("billing@water.example"))

private fun concern(id: String, serviceId: String?, title: String = "Invoice", updated: Long = 1) =
    Concern(id, 1, title, Status.OPEN, Importance.ROUTINE, null, null, null, null, null, null, null, emptyList(), updated, serviceId)

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private val api = FakeChatApi()
    private val cache = InMemoryChatCache()
    private var ids = 0
    private val chat = ChatRepository(api, cache, { NOW }) { "00000000-0000-4000-8000-%012d".format(++ids) }
    private val fake = Fake()
    private val concerns = ConcernRepository(fake.cache, fake, NoSync, { NOW })
    private val sources = SourceCache()
    private val archive = ArchiveRepository(StubArchiveApi(), sources, fake.cache) { NOW }
    private val ticks = MutableSharedFlow<Long?>(extraBufferCapacity = 8)

    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun reset() = Dispatchers.resetMain()

    private suspend fun createdThread(): String {
        val localId = chat.openThread(ChatScope(ScopeKind.ALL))!!
        cache.updateCreate(localId, com.digifender.adminifender.core.data.repository.CreateState.CREATED, SERVER_THREAD, null)
        return localId
    }

    private fun model(localId: String) = ChatThreadViewModel(localId, chat, concerns, archive, ticks)

    private fun emptyHistory() = ReadResult.Ok(HistoryDto(ThreadDto(SERVER_THREAD, ScopeDto("all"), "2026-10-08T10:00:00.000Z"), emptyList(), false))

    @Test
    fun typingIsWrittenToRoomInOrderAndAStoredDraftFillsTheField() = runTest {
        val localId = createdThread()
        chat.setDraft(localId, "half a question")
        val vm = model(localId)
        assertEquals("half a question", vm.draftText.value)
        vm.onDraft("half a question, now more")
        vm.onDraft("half a question, now more and more")
        assertEquals("half a question, now more and more", cache.threadOnce(localId)!!.draft)
    }

    @Test
    fun sendStoresTheQuestionClearsTheDraftAndSendsIt() = runTest {
        val localId = createdThread()
        api.postResults += PostResult.Accepted("pending")
        api.defaultTurn = { ReadResult.Ok(turnDto(TURN_A, state = "pending")) }
        val vm = model(localId)
        vm.onDraft("Did I pay?")
        vm.send()
        runCurrent()
        assertEquals("", vm.draftText.value)
        assertEquals("", cache.threadOnce(localId)!!.draft)
        val turn = cache.turnsOnce(localId).single()
        assertEquals("Did I pay?", turn.content)
        assertEquals(Dispatch.ACCEPTED, turn.dispatch)
        assertEquals(listOf(Triple(SERVER_THREAD, turn.turnId, "Did I pay?")), api.posted)
    }

    @Test
    fun aBlankQuestionIsNotSentAndSaysSo() = runTest {
        val localId = createdThread()
        val vm = model(localId)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.onDraft("   ")
        vm.send()
        assertEquals(ChatMessage.EMPTY, vm.state.value.message)
        assertTrue(cache.turnsOnce(localId).isEmpty())
        job.cancel()
    }

    @Test
    fun pollingFollowsOneTwoFiveAndTenSecondsThenWaitsForAForeground() = runTest {
        val localId = createdThread()
        api.postResults += PostResult.Accepted("pending")
        api.defaultTurn = { ReadResult.Ok(turnDto(TURN_A, state = "pending")) }
        val vm = model(localId)
        vm.onDraft("Did I pay?")
        vm.send()
        runCurrent()
        // The turn ID is assigned by the repository; the scripted Worker answers any read as this turn.
        val id = cache.turnsOnce(localId).single().turnId
        api.defaultTurn = { ReadResult.Ok(turnDto(id, state = "pending")) }
        api.log.clear()
        vm.onBackground()
        api.historyReads += { emptyHistory() }
        vm.onForeground()
        runCurrent()
        fun reads() = api.log.count { it == "turn" }
        assertEquals(1, reads())
        advanceTimeBy(999)
        assertEquals(1, reads())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, reads())
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(3, reads())
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(4, reads())
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(5, reads())
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(5, reads())

        api.historyReads += { emptyHistory() }
        vm.onForeground()
        runCurrent()
        assertEquals(6, reads())
    }

    @Test
    fun leavingTheForegroundStopsTheTimersButNeverCancelsTheQuestion() = runTest {
        val localId = createdThread()
        api.postResults += PostResult.Accepted("pending")
        val vm = model(localId)
        vm.onDraft("Did I pay?")
        api.defaultTurn = { ReadResult.Ok(turnDto(TURN_A, state = "pending")) }
        vm.send()
        runCurrent()
        val id = cache.turnsOnce(localId).single().turnId
        api.defaultTurn = { ReadResult.Ok(turnDto(id, state = "pending")) }
        vm.onBackground()
        api.log.clear()
        advanceTimeBy(120_000)
        runCurrent()
        assertTrue(api.log.isEmpty())
        assertFalse(api.log.contains("cancel"))
        assertEquals(ServerState.PENDING, cache.turnOnce(id)!!.state)
        assertNull(cache.turnOnce(id)!!.cancelNote)
    }

    @Test
    fun aFinishedSyncReadsAgainOnlyWhileTheScreenIsShowing() = runTest {
        val localId = createdThread()
        api.postResults += PostResult.Accepted("pending")
        val vm = model(localId)
        vm.onDraft("Did I pay?")
        api.defaultTurn = { ReadResult.Ok(turnDto(TURN_A, state = "pending")) }
        vm.send()
        runCurrent()
        val id = cache.turnsOnce(localId).single().turnId
        api.defaultTurn = { ReadResult.Ok(turnDto(id, state = "pending")) }
        vm.onBackground()
        api.log.clear()
        ticks.emit(1L)
        runCurrent()
        assertTrue(api.log.isEmpty())
        api.historyReads += { emptyHistory() }
        vm.onForeground()
        runCurrent()
        api.log.clear()
        ticks.emit(2L)
        runCurrent()
        assertEquals(1, api.log.count { it == "turn" })
    }

    @Test
    fun theWorkersRefusalToCancelIsShownAndKept() = runTest {
        val localId = createdThread()
        api.postResults += PostResult.Accepted("running")
        val vm = model(localId)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.onDraft("Did I pay?")
        api.defaultTurn = { ReadResult.Ok(turnDto(TURN_A, state = "running")) }
        vm.send()
        runCurrent()
        val id = cache.turnsOnce(localId).single().turnId
        api.defaultTurn = { ReadResult.Ok(turnDto(id, state = "running")) }
        vm.onBackground()
        api.cancelResults += CancelResult.AlreadyDispatched
        vm.cancel(id)
        runCurrent()
        assertEquals(ChatMessage.ALREADY_DISPATCHED, vm.state.value.message)
        assertEquals(CancelNote.ALREADY_DISPATCHED, vm.state.value.turns.single().cancelNote)
        job.cancel()
    }

    @Test
    fun citedEmailsComeFromTheSyncedCacheAndAnAnswerCitingAMissingOneIsHeldBack() = runTest {
        val localId = createdThread()
        val vm = model(localId)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        api.defaultTurn = { ReadResult.Unavailable("network") }
        cache.sources[SOURCE_1] = fact(SOURCE_1)
        sources.rows.value = mapOf(SOURCE_1 to summary(SOURCE_1))
        val second = "22222222-2222-4222-8222-222222222222"
        api.historyReads += {
            ReadResult.Ok(
                HistoryDto(
                    ThreadDto(SERVER_THREAD, ScopeDto("all"), "2026-10-08T10:00:00.000Z"),
                    listOf(
                        turnDto(TURN_A, state = "complete", answer = "Yes.", claims = listOf(claim(citation(SOURCE_1))), createdAt = "2026-10-08T10:00:00.000Z", completedAt = "2026-10-08T10:01:00.000Z"),
                        turnDto(second, state = "complete", answer = "Maybe.", claims = listOf(claim(citation(SOURCE_2))), createdAt = "2026-10-08T10:02:00.000Z", completedAt = "2026-10-08T10:03:00.000Z"),
                    ),
                    false,
                ),
            )
        }
        vm.refresh()
        runCurrent()
        val state = vm.state.value
        assertEquals(setOf(SOURCE_1), state.sources.keys)
        assertEquals(listOf("Yes.", null), state.turns.map { it.answer?.text })
        job.cancel()
    }

    @Test
    fun scopeLabelsNameTheCachedItemOrSayItIsNotOnThePhone() {
        val byConcern = mapOf(CONCERN_ID to concern(CONCERN_ID, null, "Water invoice"))
        val bySource = mapOf(SOURCE_1 to summary(SOURCE_1, "Water bill"))
        assertEquals(ScopeLabel(ScopeKind.ALL, null, false), labelFor(ChatScope(ScopeKind.ALL), byConcern, bySource))
        assertEquals(ScopeLabel(ScopeKind.CONCERN, "Water invoice", false), labelFor(ChatScope(ScopeKind.CONCERN, CONCERN_ID), byConcern, bySource))
        assertEquals(ScopeLabel(ScopeKind.CONCERN, null, true), labelFor(ChatScope(ScopeKind.CONCERN, SERVICE_ID), byConcern, bySource))
        assertEquals(ScopeLabel(ScopeKind.SOURCE, "Water bill", false), labelFor(ChatScope(ScopeKind.SOURCE, SOURCE_1), byConcern, bySource))
        assertEquals(ScopeLabel(ScopeKind.SOURCE, null, true), labelFor(ChatScope(ScopeKind.SOURCE, SOURCE_2), byConcern, bySource))
        // No service name exists on the phone, so only a short form of the ID is shown, never an invented name.
        assertEquals(ScopeLabel(ScopeKind.SERVICE, SERVICE_ID.take(8), false), labelFor(ChatScope(ScopeKind.SERVICE, SERVICE_ID), byConcern, bySource))
    }

    @Test
    fun servicesAreOfferedOnlyWhenACachedConcernPointsAtOne() {
        assertTrue(serviceOptions(listOf(concern("c1", null), concern("c2", "not-an-id"))).isEmpty())
        val options = serviceOptions(listOf(concern("c1", SERVICE_ID), concern("c2", SERVICE_ID), concern("c3", "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee")))
        assertEquals(listOf(SERVICE_ID, "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"), options.map { it.scope.id })
        assertEquals(listOf(2, 1), options.map { it.detail })
        assertTrue(options.all { it.scope.kind == ScopeKind.SERVICE })
    }

    @Test
    fun aServiceIsToldApartByTheTitlesOfTheConcernsLinkedToIt() {
        val all = listOf(
            concern("c1", SERVICE_ID, "Water invoice"),
            concern("c2", SERVICE_ID, "Water invoice"),
            concern("c3", SERVICE_ID, "Meter reading overdue"),
            concern("c4", "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee", "Gas outage"),
            concern("c5", null, "Unrelated"),
        )
        val options = serviceOptions(all)
        // Repeats collapse and only concerns of that service are listed.
        assertEquals(listOf("Water invoice", "Meter reading overdue"), options[0].label.linked)
        assertEquals(listOf("Gas outage"), options[1].label.linked)
        // The same names label a conversation that already exists.
        val label = labelFor(ChatScope(ScopeKind.SERVICE, SERVICE_ID), all.associateBy { it.id }, emptyMap())
        assertEquals(SERVICE_ID.take(8), label.name)
        assertEquals(setOf("Water invoice", "Meter reading overdue"), label.linked.toSet())
        val many = serviceOptions(List(6) { concern("m$it", SERVICE_ID, "Concern $it") })
        assertEquals(3, many.single().label.linked.size)
    }

    @Test
    fun aSignInProblemFromTheHistoryReadIsNotOverwrittenByTheNextPoll() = runTest {
        val localId = createdThread()
        api.postResults += PostResult.Accepted("pending")
        val vm = model(localId)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.onDraft("Did I pay?")
        api.defaultTurn = { ReadResult.Ok(turnDto(TURN_A, state = "pending")) }
        vm.send()
        runCurrent()
        val id = cache.turnsOnce(localId).single().turnId
        api.defaultTurn = { ReadResult.Ok(turnDto(id, state = "pending")) }
        vm.onBackground()
        api.historyReads += { ReadResult.SessionExpired }
        vm.onForeground()
        runCurrent()
        // The poll that followed read fine, which clears only its own problem.
        assertEquals(ChatProblem.SIGN_IN, vm.state.value.problem)
        api.historyReads += { emptyHistory() }
        vm.refresh()
        runCurrent()
        assertNull(vm.state.value.problem)
        job.cancel()
    }

    @Test
    fun editingARefusedQuestionAgainFillsTheDraftAndANonEmptyDraftIsNeverReplaced() = runTest {
        val localId = createdThread()
        api.postResults += PostResult.Refused("invalid_request")
        val vm = model(localId)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        vm.onDraft("Original question")
        vm.send()
        runCurrent()
        val first = cache.turnsOnce(localId).single().turnId

        vm.onDraft("Something typed meanwhile")
        vm.editAgain(first)
        runCurrent()
        assertEquals(ChatMessage.DRAFT_IN_USE, vm.state.value.message)
        assertEquals("Something typed meanwhile", vm.draftText.value)
        assertEquals("Original question", cache.turnOnce(first)?.content)

        vm.onDraft("")
        runCurrent()
        vm.editAgain(first)
        runCurrent()
        assertEquals("Original question", vm.draftText.value)
        assertEquals("Original question", cache.threadOnce(localId)!!.draft)
        assertNull(cache.turnOnce(first))
        job.cancel()
    }

    @Test
    fun onlyReadyUnquarantinedAndModelPermittedEmailsCanBeAskedAbout() {
        assertTrue(askable(summary(SOURCE_1)))
        assertTrue(askable(summary(SOURCE_1, privacy = "local_only")))
        assertFalse(askable(summary(SOURCE_1, quarantined = true)))
        assertFalse(askable(summary(SOURCE_1, state = "staged")))
        assertFalse(askable(summary(SOURCE_1, privacy = "no_ai")))
        assertFalse(askable(summary(SOURCE_1, privacy = "restricted_metadata")))
    }

    @Test
    fun theListOffersRealChoicesAndOpensTheLatestConversationForASubject() = runTest {
        val vm = ChatListViewModel(chat, concerns, archive)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
        sources.rows.value = mapOf(SOURCE_1 to summary(SOURCE_1), SOURCE_2 to summary(SOURCE_2, quarantined = true))
        fake.concern.value = listOf(concern("c1", SERVICE_ID))
        val state = vm.state.value
        assertEquals(listOf(SOURCE_1), state.sources.map { it.scope.id })
        assertEquals(listOf("c1"), state.concerns.map { it.scope.id })
        assertEquals(listOf(SERVICE_ID), state.services.map { it.scope.id })
        var opened: String? = null
        vm.open(ChatScope(ScopeKind.ALL)) { opened = it }
        runCurrent()
        var again: String? = null
        vm.open(ChatScope(ScopeKind.ALL)) { again = it }
        runCurrent()
        assertEquals(opened, again)
        assertEquals(1, vm.state.value.items?.size)
        job.cancel()
    }
}
