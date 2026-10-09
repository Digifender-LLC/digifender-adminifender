package com.digifender.adminifender.ui

import com.digifender.adminifender.core.data.network.ArchiveApi
import com.digifender.adminifender.core.data.network.ArchiveView
import com.digifender.adminifender.core.data.network.ChunksPageDto
import com.digifender.adminifender.core.data.network.ReadResult
import com.digifender.adminifender.core.data.network.SearchPageDto
import com.digifender.adminifender.core.data.network.SearchRequest
import com.digifender.adminifender.core.data.network.SourceDetailDto
import com.digifender.adminifender.core.data.network.SourceDto
import com.digifender.adminifender.core.data.network.SourceSummary
import com.digifender.adminifender.core.data.repository.ArchiveCache
import com.digifender.adminifender.core.data.repository.ArchiveRepository
import com.digifender.adminifender.core.data.repository.CacheSource
import com.digifender.adminifender.core.data.repository.CachedReason
import com.digifender.adminifender.core.data.repository.CachedSearchHit
import com.digifender.adminifender.core.data.repository.SourceText
import com.digifender.adminifender.core.data.sync.QueuedMutation
import com.digifender.adminifender.core.model.Concern
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private const val SID = "0b4e6f8a-2c3d-4e5f-8a9b-1c2d3e4f5a6b"

private class Api : ArchiveApi {
    var source: ReadResult<SourceDetailDto> = ReadResult.Unavailable("network")
    val requests = mutableListOf<SearchRequest>()

    override suspend fun source(id: String) = source
    override suspend fun chunks(id: String, cursor: String?, limit: Int): ReadResult<ChunksPageDto> = ReadResult.Unavailable("network")
    override suspend fun search(request: SearchRequest, cursor: String?, limit: Int): ReadResult<SearchPageDto> {
        requests += request
        return ReadResult.Unavailable("network")
    }
}

private class Cache : ArchiveCache {
    val rows = MutableStateFlow(emptyMap<String, SourceSummary>())
    override fun sources(view: ArchiveView, limit: Int) = rows.map { it.values.toList() }
    override fun count(view: ArchiveView) = rows.map { it.size }
    override fun source(id: String) = rows.map { it[id] }
    override fun sources(ids: List<String>) = rows.map { m -> ids.mapNotNull { m[it] } }
    override fun text(id: String): Flow<SourceText?> = flowOf(null)
    override suspend fun search(terms: List<String>, view: ArchiveView, sender: String?, fromMs: Long?, toMs: Long?, limit: Int) =
        rows.value.values.map { CachedSearchHit(it, null) }
    // Never stores, so the screen relies on its in-memory copy.
    override suspend fun putText(id: String, text: SourceText, nowMs: Long) = false
}

private val noConcerns = object : CacheSource {
    override fun concerns(): Flow<List<Concern>> = flowOf(emptyList())
    override fun mutations(): Flow<List<QueuedMutation>> = flowOf(emptyList())
    override fun syncState(): Flow<Pair<Long?, Boolean>> = flowOf(null to true)
}

@OptIn(ExperimentalCoroutinesApi::class)
class ArchiveViewModelTest {
    private val api = Api()
    private val cache = Cache()
    private val repo = ArchiveRepository(api, cache, noConcerns) { 10 * 86_400_000L }

    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun reset() = Dispatchers.resetMain()

    @Test
    fun offlineResultsAreLabelledCachedAndSwitchingViewSearchesAgain() = runTest {
        cache.rows.value = mapOf(SID to SourceSummary(SID, 1, "ready", false, "hybrid", 5, "Bill", emptyList()))
        val model = ArchiveViewModel(repo) { 10 * 86_400_000L }
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }
        model.query.value = "bill"
        model.range.value = DateRange.WEEK
        model.submit()
        assertEquals(Coverage.CachedOnly(CachedReason.OFFLINE), model.state.value.search?.coverage)
        assertEquals(3 * 86_400_000L, api.requests.single().fromMs)
        model.selectView(ArchiveView.QUARANTINE)
        assertEquals(ArchiveView.QUARANTINE, api.requests.last().view)
        model.clearSearch()
        assertNull(model.state.value.search)
        job.cancel()
    }

    @Test
    fun textHeldInMemoryDisappearsWhenSyncDeletesTheSource() = runTest {
        val summary = SourceSummary(SID, 2, "ready", false, "hybrid", 5, "Bill", emptyList())
        cache.rows.value = mapOf(SID to summary)
        api.source = ReadResult.Ok(SourceDetailDto(SourceDto(SID, "email", "ready", "normal", "hybrid", 3, "2026-09-21T10:00:00Z"), "secret text"))
        val model = SourceViewModel(SID, repo)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }
        assertEquals(TextStatus.NOT_SAVED, model.state.value.status)
        assertEquals("secret text", model.state.value.text?.text)
        cache.rows.value = emptyMap()
        assertEquals(TextStatus.GONE, model.state.value.status)
        assertNull(model.state.value.text)
        job.cancel()
    }

    @Test
    fun selectionKeepsTheOwnersChoiceAndRefusesTheOneOverTheLimitOutLoud() = runTest {
        val model = ArchiveViewModel(repo) { 10 * 86_400_000L }
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }
        assertFalse(model.state.value.selecting)
        model.startSelecting()
        val ids = (1..1001).map { "%08x-0000-4000-8000-%012x".format(it, it) }
        ids.take(1000).forEach { model.toggle(it) }
        assertEquals(1000, model.state.value.selection.size)
        assertFalse(model.state.value.selectionAtLimit)
        // The 1,001st is not added, and the screen is told why; nothing is dropped quietly.
        model.toggle(ids[1000])
        assertEquals(1000, model.state.value.selection.size)
        assertTrue(model.state.value.selectionAtLimit)
        assertFalse(ids[1000] in model.state.value.selection)
        // Choosing again removes one and clears the notice.
        model.toggle(ids[0])
        assertEquals(999, model.state.value.selection.size)
        assertFalse(model.state.value.selectionAtLimit)
        // The order the owner chose is kept.
        assertEquals(ids.subList(1, 1000), model.state.value.selection.toList())
        model.stopSelecting()
        assertFalse(model.state.value.selecting)
        assertTrue(model.state.value.selection.isEmpty())
        job.cancel()
    }
}
