package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.network.ArchiveApi
import com.digifender.adminifender.core.data.network.ArchiveView
import com.digifender.adminifender.core.data.network.AttachmentDto
import com.digifender.adminifender.core.data.network.ChunkDto
import com.digifender.adminifender.core.data.network.ChunksPageDto
import com.digifender.adminifender.core.data.network.ParsedQuery
import com.digifender.adminifender.core.data.network.ReadResult
import com.digifender.adminifender.core.data.network.SearchHitDto
import com.digifender.adminifender.core.data.network.SearchPageDto
import com.digifender.adminifender.core.data.network.SearchRequest
import com.digifender.adminifender.core.data.network.SourceDetailDto
import com.digifender.adminifender.core.data.network.SourceDto
import com.digifender.adminifender.core.data.network.SourceSummary
import com.digifender.adminifender.core.data.network.parseQuery
import com.digifender.adminifender.core.data.repository.ArchiveCache
import com.digifender.adminifender.core.data.repository.ArchiveRepository
import com.digifender.adminifender.core.data.repository.CacheSource
import com.digifender.adminifender.core.data.repository.CachedReason
import com.digifender.adminifender.core.data.repository.CachedSearchHit
import com.digifender.adminifender.core.data.repository.DownloadOutcome
import com.digifender.adminifender.core.data.repository.InvalidQuery
import com.digifender.adminifender.core.data.repository.MAX_CACHED_TEXT_CHARS
import com.digifender.adminifender.core.data.repository.SearchOutcome
import com.digifender.adminifender.core.data.repository.SourceText
import com.digifender.adminifender.core.data.repository.cachedSnippet
import com.digifender.adminifender.core.data.sync.QueuedMutation
import com.digifender.adminifender.core.model.Concern
import com.digifender.adminifender.core.model.Importance
import com.digifender.adminifender.core.model.Status
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val ID = "0b4e6f8a-2c3d-4e5f-8a9b-1c2d3e4f5a6b"

private class ScriptedArchiveApi : ArchiveApi {
    val sources = ArrayDeque<ReadResult<SourceDetailDto>>()
    val chunkPages = ArrayDeque<ReadResult<ChunksPageDto>>()
    val searches = ArrayDeque<ReadResult<SearchPageDto>>()
    val log = mutableListOf<String>()
    var lastSearch: SearchRequest? = null

    override suspend fun source(id: String): ReadResult<SourceDetailDto> {
        log += "source:$id"
        return sources.removeFirst()
    }

    override suspend fun chunks(id: String, cursor: String?, limit: Int): ReadResult<ChunksPageDto> {
        log += "chunks:${cursor ?: "-"}"
        return chunkPages.removeFirst()
    }

    override suspend fun search(request: SearchRequest, cursor: String?, limit: Int): ReadResult<SearchPageDto> {
        log += "search:${cursor ?: "-"}"
        lastSearch = request
        return searches.removeFirst()
    }
}

/** In-memory cache with Room's rule: text is kept only for a cached source at the same revision. */
private class MemoryArchive : ArchiveCache {
    val rows = MutableStateFlow(emptyMap<String, SourceSummary>())
    val texts = MutableStateFlow(emptyMap<String, SourceText>())
    var lastCachedSearch: List<String>? = null

    override fun sources(view: ArchiveView, limit: Int) = rows.map { m -> m.values.filter { it.quarantined == (view == ArchiveView.QUARANTINE) }.take(limit) }
    override fun count(view: ArchiveView) = rows.map { m -> m.values.count { it.quarantined == (view == ArchiveView.QUARANTINE) } }
    override fun source(id: String) = rows.map { it[id] }
    override fun sources(ids: List<String>) = rows.map { m -> ids.mapNotNull { m[it] } }
    override fun text(id: String) = texts.map { it[id] }

    override suspend fun search(terms: List<String>, view: ArchiveView, sender: String?, fromMs: Long?, toMs: Long?, limit: Int): List<CachedSearchHit> {
        lastCachedSearch = terms
        return rows.value.values.filter { s -> s.quarantined == (view == ArchiveView.QUARANTINE) && terms.all { t -> s.subject.orEmpty().contains(t, true) || texts.value[s.id]?.text.orEmpty().contains(t, true) } }
            .map { CachedSearchHit(it, cachedSnippet(texts.value[it.id]?.text, terms)) }
    }

    override suspend fun putText(id: String, text: SourceText, nowMs: Long): Boolean {
        if (rows.value[id]?.revision != text.revision) return false
        texts.value = texts.value + (id to text)
        return true
    }
}

private fun summary(id: String = ID, revision: Int = 3, subject: String = "Water bill", quarantined: Boolean = false) =
    SourceSummary(id, revision, "ready", quarantined, "hybrid", 1_790_000_000_000, subject, listOf("billing@water.example"))

private fun detail(revision: Int = 3, text: String? = "Your bill is due", inChunks: Boolean = false) = SourceDetailDto(
    SourceDto(ID, "email", "ready", "normal", "hybrid", revision, "2026-09-21T10:00:00Z", "Water bill", listOf("billing@water.example")),
    text,
    inChunks,
    listOf(AttachmentDto("a1", "bill.pdf", "application/pdf", 2048, "ab".repeat(32), "attachment", "ready")),
)

class ArchiveRepositoryTest {
    private val api = ScriptedArchiveApi()
    private val cache = MemoryArchive()
    private val concerns = MutableStateFlow(emptyList<Concern>())
    private val cacheSource = object : CacheSource {
        override fun concerns(): Flow<List<Concern>> = concerns
        override fun mutations(): Flow<List<QueuedMutation>> = flowOf(emptyList())
        override fun syncState(): Flow<Pair<Long?, Boolean>> = flowOf(null to true)
    }
    private val repo = ArchiveRepository(api, cache, cacheSource) { 1_800_000_000_000 }

    private fun page(vararg hits: SearchHitDto, remaining: Int = 0, next: String? = null) = ReadResult.Ok(SearchPageDto(hits.toList(), remaining, next, 77))

    private fun hit(source: String = ID, attachment: String? = null) = SearchHitDto(source, "ch1", attachment, 1, "Water bill", "your [bill] is due", 2, 0, "2026-09-21T10:00:00Z")

    @Test
    fun queriesFollowTheServerLimits() {
        assertEquals(ParsedQuery.Empty, parseQuery("   "))
        assertEquals(ParsedQuery.TooLong, parseQuery("x".repeat(201)))
        assertEquals(ParsedQuery.TooManyTerms, parseQuery((1..21).joinToString(" ")))
        assertEquals(ParsedQuery.Ok("water  bill", listOf("water", "bill")), parseQuery("  water  bill "))
    }

    @Test
    fun invalidQueriesNeverReachTheServerOrCache() = runTest {
        assertEquals(SearchOutcome.Invalid(InvalidQuery.EMPTY), repo.search(" ", ArchiveView.ARCHIVE, null, null, null))
        assertEquals(SearchOutcome.Invalid(InvalidQuery.TOO_MANY_TERMS), repo.search((1..21).joinToString(" "), ArchiveView.ARCHIVE, null, null, null))
        assertTrue(api.log.isEmpty())
        assertNull(cache.lastCachedSearch)
    }

    @Test
    fun onlineSearchPassesFiltersAndReportsPartialCoverage() = runTest {
        api.searches += page(hit(), hit(attachment = "a1"), remaining = 2, next = "c2")
        val outcome = repo.search("bill", ArchiveView.QUARANTINE, "  Billing@Water.example ", 1_000, null) as SearchOutcome.Online
        assertEquals(SearchRequest("bill", ArchiveView.QUARANTINE, "billing@water.example", 1_000, null), api.lastSearch)
        assertEquals(2, outcome.partitionsRemaining)
        assertEquals("c2", outcome.nextCursor)
        assertEquals(listOf(false, true), outcome.hits.map { it.inAttachment })
        assertEquals(Instant.parse("2026-09-21T10:00:00Z").toEpochMilli(), outcome.hits[0].receivedAtMs)
        assertNull(cache.lastCachedSearch)
    }

    @Test
    fun offlineSearchFallsBackToCachedContentAndSaysSo() = runTest {
        cache.rows.value = mapOf(ID to summary(), "other" to summary("other", subject = "Holiday photos"))
        cache.texts.value = mapOf(ID to SourceText(3, "Please pay your water bill by Friday.", true, emptyList()))
        api.searches += ReadResult.Unavailable("network")
        val outcome = repo.search("pay friday", ArchiveView.ARCHIVE, null, null, null) as SearchOutcome.CachedOnly
        assertEquals(CachedReason.OFFLINE, outcome.reason)
        assertEquals(listOf(ID), outcome.hits.map { it.sourceId })
        assertEquals("Please [pay] your water bill by Friday.", outcome.hits.single().snippet)
    }

    @Test
    fun expiredSessionAndServerFailuresAreLabelledDifferently() = runTest {
        api.searches += ReadResult.SessionExpired
        api.searches += ReadResult.Unavailable("unavailable")
        assertEquals(CachedReason.SIGN_IN, (repo.search("bill", ArchiveView.ARCHIVE, null, null, null) as SearchOutcome.CachedOnly).reason)
        assertEquals(CachedReason.UNAVAILABLE, (repo.search("bill", ArchiveView.ARCHIVE, null, null, null) as SearchOutcome.CachedOnly).reason)
    }

    @Test
    fun aFailedFurtherPageDoesNotSwitchToCachedResults() = runTest {
        api.searches += ReadResult.CursorExpired
        assertEquals(SearchOutcome.MoreUnavailable("cursor_expired"), repo.search("bill", ArchiveView.ARCHIVE, null, null, null, cursor = "c2"))
        assertNull(cache.lastCachedSearch)
    }

    @Test
    fun downloadSavesInlineTextForTheCachedRevision() = runTest {
        cache.rows.value = mapOf(ID to summary())
        api.sources += ReadResult.Ok(detail())
        val outcome = repo.download(ID) as DownloadOutcome.Saved
        assertEquals("Your bill is due", outcome.text.text)
        assertEquals("bill.pdf", repo.cachedText(ID).first()?.attachments?.single()?.name)
    }

    @Test
    fun aResponseForAnotherRevisionIsShownButNotSaved() = runTest {
        // The phone already synced revision 4 (for example a privacy change); a revision 3 body must not be cached.
        cache.rows.value = mapOf(ID to summary(revision = 4))
        api.sources += ReadResult.Ok(detail(revision = 3))
        assertTrue(repo.download(ID) is DownloadOutcome.NotSaved)
        assertNull(repo.cachedText(ID).first())
    }

    @Test
    fun aSourceDeletedBeforeTheResponseArrivesIsNotResurrected() = runTest {
        api.sources += ReadResult.Ok(detail())
        assertTrue(repo.download(ID) is DownloadOutcome.NotSaved)
        assertTrue(cache.texts.value.isEmpty())
    }

    @Test
    fun chunkedBodiesAreJoinedInOrderAndStopAtAttachmentText() = runTest {
        cache.rows.value = mapOf(ID to summary())
        api.sources += ReadResult.Ok(detail(text = null, inChunks = true))
        api.chunkPages += ReadResult.Ok(ChunksPageDto(listOf(ChunkDto("c0", null, 0, null, 0, "Hello "), ChunkDto("c1", null, 1, null, 6, "world")), "p2"))
        api.chunkPages += ReadResult.Ok(ChunksPageDto(listOf(ChunkDto("c2", null, 2, null, 11, "!"), ChunkDto("c3", "a1", 0, 1, 0, "PDF text")), "p3"))
        val outcome = repo.download(ID) as DownloadOutcome.Saved
        assertEquals("Hello world!", outcome.text.text)
        assertTrue(outcome.text.complete)
        assertEquals(listOf("source:$ID", "chunks:-", "chunks:p2"), api.log)
    }

    @Test
    fun veryLongBodiesAreKeptOnlyInPartAndSaySo() = runTest {
        cache.rows.value = mapOf(ID to summary())
        api.sources += ReadResult.Ok(detail(text = "x".repeat(MAX_CACHED_TEXT_CHARS + 10)))
        val outcome = repo.download(ID) as DownloadOutcome.Saved
        assertEquals(MAX_CACHED_TEXT_CHARS, outcome.text.text?.length)
        assertFalse(outcome.text.complete)
    }

    @Test
    fun downloadFailuresMapToWhatTheOwnerCanDo() = runTest {
        api.sources += ReadResult.Unavailable("not_found")
        api.sources += ReadResult.SessionExpired
        api.sources += ReadResult.Unavailable("network")
        assertEquals(DownloadOutcome.Gone, repo.download(ID))
        assertEquals(DownloadOutcome.SignIn, repo.download(ID))
        assertEquals(DownloadOutcome.Unavailable("network"), repo.download(ID))
        assertEquals(DownloadOutcome.Gone, repo.download("../sync/changes"))
        assertEquals(3, api.log.size)
    }

    @Test
    fun linkedConcernsComeFromTheCache() = runTest {
        fun concern(id: String, sources: List<String>, updated: Long) = Concern(id, 1, id, Status.OPEN, Importance.ROUTINE, null, null, null, null, null, null, null, sources, updated)
        concerns.value = listOf(concern("old", listOf(ID), 1), concern("unrelated", listOf("x"), 5), concern("new", listOf("y", ID), 9))
        assertEquals(listOf("new", "old"), repo.linkedConcerns(ID).first().map { it.id })
    }

    @Test
    fun cachedSnippetsMarkTheEarliestMatch() {
        assertEquals("…bc [def] gh", cachedSnippet("abc def gh", listOf("gh", "DEF"), radius = 3))
        assertEquals("one [two] three", cachedSnippet("one\n\ntwo   three", listOf("two")))
        assertNull(cachedSnippet("abc", listOf("zzz")))
        assertNull(cachedSnippet(null, listOf("abc")))
    }
}
