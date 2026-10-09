package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.export.CreateOutcome
import com.digifender.adminifender.core.data.export.ExportDownload
import com.digifender.adminifender.core.data.export.ExportDownloadOutcome
import com.digifender.adminifender.core.data.export.ExportIntent
import com.digifender.adminifender.core.data.export.ExportPhase
import com.digifender.adminifender.core.data.export.ExportRepository
import com.digifender.adminifender.core.data.export.ExportScheduler
import com.digifender.adminifender.core.data.export.ExportStore
import com.digifender.adminifender.core.data.export.RefreshOutcome
import com.digifender.adminifender.core.data.network.ContentStream
import com.digifender.adminifender.core.data.network.CreateExportResult
import com.digifender.adminifender.core.data.network.ExportAdmission
import com.digifender.adminifender.core.data.network.ExportApi
import com.digifender.adminifender.core.data.network.ExportFilter
import com.digifender.adminifender.core.data.network.ExportRead
import com.digifender.adminifender.core.data.network.ExportScope
import com.digifender.adminifender.core.data.network.ExportState
import com.digifender.adminifender.core.data.network.ExportStatus
import com.digifender.adminifender.core.data.network.InvalidatedReason
import com.digifender.adminifender.core.data.network.OpenResult
import com.digifender.adminifender.core.data.network.ScopeProblem
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryExportStore : ExportStore {
    val rows = MutableStateFlow<Map<String, ExportIntent>>(emptyMap())
    override fun observeAll(): Flow<List<ExportIntent>> = rows.map { it.values.toList() }
    override fun observe(localId: String): Flow<ExportIntent?> = rows.map { it[localId] }
    override suspend fun get(localId: String) = rows.value[localId]
    override suspend fun all() = rows.value.values.toList()
    override suspend fun put(intent: ExportIntent) {
        rows.value = rows.value + (intent.localId to intent)
    }
    override suspend fun delete(localId: String) {
        rows.value = rows.value - localId
    }
}

class RecordingExportScheduler : ExportScheduler {
    val polls = mutableListOf<String>()
    var cancelled = 0
    override fun schedulePoll(localId: String) {
        polls += localId
    }
    override fun cancelAll() {
        cancelled += 1
    }
}

class FakeExportApi : ExportApi {
    var onCreate: suspend (String, ExportScope) -> CreateExportResult = { _, _ -> CreateExportResult.NoAnswer("network") }
    var onStatus: suspend (String) -> ExportRead = { ExportRead.Unavailable("network") }
    var onOpen: suspend (String) -> OpenResult = { OpenResult.Unavailable("network") }
    val creates = mutableListOf<Pair<String, ExportScope>>()
    val opens = mutableListOf<String>()

    override suspend fun create(idempotencyKey: String, scope: ExportScope): CreateExportResult {
        creates += idempotencyKey to scope
        return onCreate(idempotencyKey, scope)
    }

    override suspend fun status(exportId: String) = onStatus(exportId)

    override suspend fun openContent(exportId: String): OpenResult {
        opens += exportId
        return onOpen(exportId)
    }
}

private fun contentOf(bytes: ByteArray, failAfter: Int? = null, gateAfter: Int? = null, length: Long? = bytes.size.toLong()): Pair<ScriptedStream, OpenResult> {
    val stream = ScriptedStream(bytes, failAfter, gateAfter)
    return stream to OpenResult.Open(ContentStream(length ?: -1, null, stream, stream::abort))
}

class ExportRepositoryTest {
    private val api = FakeExportApi()
    private val store = InMemoryExportStore()
    private val scheduler = RecordingExportScheduler()
    private var ids = 0
    private var now = 5_000L
    private val repo = ExportRepository(api, store, scheduler, { now }, { "x-${ids++}" })
    private val dir = tempDirectory()
    private val built = buildExport()

    private fun pending(scope: ExportScope = defaultScope()) = ExportStatus(EXPORT_ID, ExportState.PENDING, scope, 41, EXPORT_CREATED, EXPORT_CREATED + 1000, null, null, null, null)

    /** A created, ready export in the store, as a screen would show it. */
    private suspend fun readyIntent(export: BuiltExport = built): String {
        api.onCreate = { _, _ -> CreateExportResult.Created(pending(export.status.scope!!)) }
        val created = repo.create(export.status.scope!!) as CreateOutcome.Created
        api.onStatus = { ExportRead.Ok(export.status) }
        repo.refresh(created.intent.localId)
        assertEquals(ExportState.READY, store.get(created.intent.localId)!!.state)
        return created.intent.localId
    }

    @Test
    fun invalidScopesAreRefusedBeforeAnythingIsStoredOrSent() = runBlocking {
        val cases = mapOf(
            ExportScope.Sources(ExportAdmission.NORMAL, emptyList()) to ScopeProblem.NO_SOURCES,
            ExportScope.Sources(ExportAdmission.NORMAL, (1..1001).map { uuid(it) }) to ScopeProblem.TOO_MANY_SOURCES,
            ExportScope.Sources(ExportAdmission.NORMAL, listOf(uuid(1), uuid(1))) to ScopeProblem.DUPLICATE_SOURCES,
            ExportScope.Sources(ExportAdmission.NORMAL, listOf("not-an-id")) to ScopeProblem.BAD_SOURCE_ID,
            ExportScope.Filtered(ExportAdmission.NORMAL, ExportFilter()) to ScopeProblem.EMPTY_FILTER,
            ExportScope.Filtered(ExportAdmission.NORMAL, ExportFilter(sender = "has space@example.com")) to ScopeProblem.BAD_ADDRESS,
            ExportScope.Filtered(ExportAdmission.NORMAL, ExportFilter(fromMs = 10, toMs = 10)) to ScopeProblem.BAD_RANGE,
        )
        for ((scope, problem) in cases) assertEquals(CreateOutcome.Invalid(problem), repo.create(scope))
        assertTrue(store.all().isEmpty())
        assertTrue(api.creates.isEmpty())
        // Exactly 1,000 is allowed, and nothing is silently cut to a first page.
        api.onCreate = { _, scope -> CreateExportResult.Created(pending(scope)) }
        assertTrue(repo.create(ExportScope.Sources(ExportAdmission.NORMAL, (1..1000).map { uuid(it) })) is CreateOutcome.Created)
        assertEquals(1000, (api.creates.single().second as ExportScope.Sources).sourceIds.size)
    }

    @Test
    fun theNormalizedScopeAndKeyAreStoredBeforeTheRequestIsMade() = runBlocking {
        var stored: ExportIntent? = null
        api.onCreate = { key, scope ->
            stored = store.all().single()
            assertEquals(key, stored!!.createKey)
            assertEquals(scope, stored!!.scope)
            CreateExportResult.Created(pending(scope))
        }
        val scope = ExportScope.Sources(ExportAdmission.QUARANTINE, listOf(uuid(9), uuid(3)))
        val out = repo.create(scope) as CreateOutcome.Created
        assertEquals(ExportPhase.CREATING, stored!!.phase)
        assertEquals(listOf(uuid(3), uuid(9)), (stored!!.scope as ExportScope.Sources).sourceIds)
        assertEquals(ExportPhase.CREATED, out.intent.phase)
        assertEquals(EXPORT_ID, out.intent.exportId)
        assertEquals(listOf(out.intent.localId), scheduler.polls)
    }

    @Test
    fun quarantineIsNeverImpliedAndAddressesAreLowerCased() = runBlocking {
        api.onCreate = { _, scope -> CreateExportResult.Created(pending(scope)) }
        repo.create(ExportScope.Archive(ExportAdmission.NORMAL))
        repo.create(ExportScope.Filtered(ExportAdmission.ALL, ExportFilter(sender = " Boss@Example.COM ")))
        assertEquals(ExportAdmission.NORMAL, api.creates[0].second.admission)
        assertEquals(ExportAdmission.ALL, api.creates[1].second.admission)
        assertEquals("boss@example.com", (api.creates[1].second as ExportScope.Filtered).filter.sender)
    }

    @Test
    fun anUnansweredRequestIsOnlyEverRepeatedAsTheSameRequest() = runBlocking {
        val scope = ExportScope.Filtered(ExportAdmission.NORMAL, ExportFilter(fromMs = 0, toMs = 1000, recipient = "me@example.com"))
        val waiting = repo.create(scope) as CreateOutcome.Waiting
        assertEquals(ExportPhase.CREATING, store.get(waiting.intent.localId)!!.phase)
        api.onCreate = { _, requested -> CreateExportResult.Created(pending(requested)) }
        assertTrue(repo.resend(waiting.intent.localId) is CreateOutcome.Created)
        assertEquals(2, api.creates.size)
        assertEquals(api.creates[0], api.creates[1])
        assertEquals(1, store.all().size)
    }

    @Test
    fun anExportOfAnotherScopeThanAskedForIsNeverOffered() = runBlocking {
        api.onCreate = { _, _ -> CreateExportResult.Created(pending(ExportScope.Archive(ExportAdmission.ALL))) }
        val out = repo.create(ExportScope.Archive(ExportAdmission.NORMAL)) as CreateOutcome.Refused
        assertEquals("scope_mismatch", out.code)
        assertEquals(ExportPhase.REFUSED, store.get(out.intent.localId)!!.phase)
        val file = File(dir, "never.zip")
        assertEquals(ExportDownloadOutcome.NotReady(null), repo.download(out.intent.localId, FileDestination(file)))
        assertTrue(api.opens.isEmpty())
    }

    @Test
    fun aRefusedRequestIsFinalAndSessionLossKeepsTheIntent() = runBlocking {
        api.onCreate = { _, _ -> CreateExportResult.Refused("invalid_request") }
        assertEquals(ExportPhase.REFUSED, (repo.create(defaultScope()) as CreateOutcome.Refused).intent.phase)
        api.onCreate = { _, _ -> CreateExportResult.SessionExpired }
        assertEquals(CreateOutcome.SignIn, repo.create(defaultScope()))
        assertTrue(store.all().any { it.phase == ExportPhase.CREATING })
    }

    @Test
    fun aLateAnswerAfterSignOutDoesNotRecreateAnything() = runBlocking {
        val gate = kotlinx.coroutines.CompletableDeferred<CreateExportResult>()
        api.onCreate = { _, _ -> gate.await() }
        val creating = async { repo.create(defaultScope()) }
        while (store.all().isEmpty()) kotlinx.coroutines.yield()
        repo.invalidate()
        store.rows.value = emptyMap()
        gate.complete(CreateExportResult.Created(pending()))
        assertEquals(CreateOutcome.Superseded, creating.await())
        assertTrue(store.all().isEmpty())
        assertEquals(1, scheduler.cancelled)
    }

    @Test
    fun readbacksAreStoredAndASettledStateIsNeverTakenBackByAnOlderAnswer() = runBlocking {
        val id = readyIntent()
        api.onStatus = { ExportRead.Ok(built.status.copy(state = ExportState.INVALIDATED, counts = null, omissions = null, invalidatedReason = InvalidatedReason.DELETED)) }
        assertTrue(repo.refresh(id) is RefreshOutcome.Updated)
        assertEquals(ExportState.INVALIDATED, store.get(id)!!.state)
        // An older answer that still says ready arrives later: ignored.
        api.onStatus = { ExportRead.Ok(built.status) }
        repo.refresh(id)
        assertEquals(ExportState.INVALIDATED, store.get(id)!!.state)
        assertEquals(InvalidatedReason.DELETED, store.get(id)!!.status?.invalidatedReason)
    }

    @Test
    fun aStatusForAnotherExportOrAnotherScopeIsNotStored() = runBlocking {
        val id = readyIntent()
        api.onStatus = { ExportRead.Ok(built.status.copy(id = uuid(7777))) }
        assertTrue(repo.refresh(id) is RefreshOutcome.Unchanged)
        api.onStatus = { ExportRead.Ok(built.status.copy(scope = ExportScope.Archive(ExportAdmission.ALL))) }
        assertTrue(repo.refresh(id) is RefreshOutcome.Unchanged)
        assertEquals(built.status, store.get(id)!!.status)
    }

    @Test
    fun aVerifiedDownloadIsClaimedOnlyAfterEveryCheckPassed() = runBlocking {
        val id = readyIntent()
        val target = File(dir, "export.zip")
        api.onOpen = { contentOf(built.bytes).second }
        val out = repo.download(id, FileDestination(target)) as ExportDownloadOutcome.Verified
        assertArrayEquals(built.bytes, target.readBytes())
        assertEquals(ExportDownload.VERIFIED, out.intent.download)
        assertEquals(sha256Of(built.bytes), out.intent.verification?.zipSha256)
        assertEquals(1, out.intent.verification?.attachmentFiles)
        assertNotNull(out.intent.verifiedAtMs)
        assertEquals("export.zip", out.intent.destinationLabel)
    }

    @Test
    fun aFailedCheckDiscardsTheFileAndRetryingIsADownloadNotANewExport() = runBlocking {
        val id = readyIntent()
        val tampered = buildExport(tamperEntry = { name, data -> if (name.endsWith("invoice.pdf")) data.copyOf().also { it[0] = 1 } else data })
        // Same export, but the ZIP the Worker served disagrees with its own manifest.
        val bad = tampered.bytes
        val target = File(dir, "bad.zip")
        api.onOpen = { contentOf(bad).second }
        val destination = FileDestination(target)
        // The tampered bytes carry a valid CRC, so it is the manifest hash that fails.
        val failed = repo.download(id, destination) as ExportDownloadOutcome.Failed
        assertEquals(ExportDownload.FAILED, failed.intent.download)
        assertEquals("verification_file_hash_mismatch", failed.intent.downloadError)
        assertEquals(true, failed.intent.destinationDiscarded)
        assertFalse(target.exists())
        assertNull(failed.intent.verification)
        // The export itself is untouched, and retrying downloads again into a new destination.
        api.onOpen = { contentOf(built.bytes).second }
        val again = File(dir, "good.zip")
        assertTrue(repo.download(id, FileDestination(again)) is ExportDownloadOutcome.Verified)
        assertEquals(1, api.creates.size)
        assertEquals(2, api.opens.size)
    }

    @Test
    fun aDroppedConnectionIsAnInterruptionNotAPartialSuccess() = runBlocking {
        val id = readyIntent()
        val target = File(dir, "cut.zip")
        api.onOpen = { contentOf(built.bytes, failAfter = built.bytes.size / 2).second }
        val out = repo.download(id, FileDestination(target)) as ExportDownloadOutcome.Interrupted
        assertEquals(ExportDownload.INTERRUPTED, out.intent.download)
        assertEquals("network", out.intent.downloadError)
        assertEquals(true, out.intent.destinationDiscarded)
        assertFalse(target.exists())
        assertNull(out.intent.verification)
    }

    @Test
    fun aBodyShorterThanItsLengthOrCutBeforeItsEndRecordIsNeverVerified() = runBlocking {
        val id = readyIntent()
        val cut = built.bytes.copyOf(built.bytes.size - 30)
        api.onOpen = { contentOf(cut, length = cut.size.toLong()).second }
        val out = repo.download(id, FileDestination(File(dir, "short.zip"))) as ExportDownloadOutcome.Failed
        assertEquals("verification_truncated", out.intent.downloadError)
        api.onOpen = { contentOf(built.bytes, length = built.bytes.size + 9L).second }
        val lying = repo.download(id, FileDestination(File(dir, "lying.zip"))) as ExportDownloadOutcome.Failed
        assertEquals("verification_length_mismatch", lying.intent.downloadError)
    }

    @Test
    fun aDestinationThatCannotBeWrittenFailsHonestlyAndSaysWhetherTheLeftoverWasRemoved() = runBlocking {
        val id = readyIntent()
        api.onOpen = { contentOf(built.bytes).second }
        val full = repo.download(id, FileDestination(File(dir, "full.zip"), failWritesAfter = 500)) as ExportDownloadOutcome.Failed
        assertEquals("destination_write", full.intent.downloadError)
        assertEquals(true, full.intent.destinationDiscarded)
        api.onOpen = { contentOf(built.bytes).second }
        val stuck = repo.download(id, FileDestination(File(dir, "stuck.zip"), failWritesAfter = 500, refuseDiscard = true)) as ExportDownloadOutcome.Failed
        assertEquals(false, stuck.intent.destinationDiscarded)
    }

    @Test
    fun anExportThatIsNotReadyOrWasInvalidatedMeanwhileIsNotDownloaded() = runBlocking {
        api.onCreate = { _, scope -> CreateExportResult.Created(pending(scope)) }
        val created = repo.create(defaultScope()) as CreateOutcome.Created
        val file = File(dir, "early.zip")
        api.onStatus = { ExportRead.Ok(pending()) }
        assertEquals(ExportDownloadOutcome.NotReady(ExportState.PENDING), repo.download(created.intent.localId, FileDestination(file)))
        assertTrue(api.opens.isEmpty())
        val id = readyIntent()
        api.onStatus = { ExportRead.Ok(built.status.copy(state = ExportState.INVALIDATED, counts = null, omissions = null, invalidatedReason = InvalidatedReason.QUARANTINED)) }
        assertEquals(ExportDownloadOutcome.NotReady(ExportState.INVALIDATED), repo.download(id, FileDestination(file)))
        assertTrue(api.opens.isEmpty())
    }

    @Test
    fun answersToTheDownloadRequestAreRecordedForWhatTheyAre() = runBlocking {
        val id = readyIntent()
        api.onOpen = { OpenResult.Gone("expired", 410) }
        val gone = repo.download(id, FileDestination(File(dir, "g.zip"))) as ExportDownloadOutcome.Failed
        assertEquals("expired", gone.intent.downloadError)
        api.onOpen = { OpenResult.Unavailable("not_ready") }
        val busy = repo.download(id, FileDestination(File(dir, "b.zip"))) as ExportDownloadOutcome.Interrupted
        assertEquals("not_ready", busy.intent.downloadError)
        api.onOpen = { OpenResult.SessionExpired }
        assertEquals(ExportDownloadOutcome.SignIn, repo.download(id, FileDestination(File(dir, "s.zip"))))
    }

    @Test
    fun stoppingTheCoroutineClosesTheConnectionAndDiscardsThePartialFile() = runBlocking {
        val id = readyIntent()
        val (stream, open) = contentOf(built.bytes, gateAfter = built.bytes.size / 2)
        api.onOpen = { open }
        val target = File(dir, "cancelled.zip")
        val running = async(Dispatchers.Default) { repo.download(id, FileDestination(target)) }
        stream.reachedGate.await()
        running.cancel()
        try {
            withTimeout(10_000) { running.await() }
            throw AssertionError("expected cancellation")
        } catch (_: CancellationException) {
            // as it must
        }
        assertTrue(stream.aborted)
        assertFalse(target.exists())
        val row = store.get(id)!!
        assertEquals(ExportDownload.INTERRUPTED, row.download)
        assertEquals("cancelled", row.downloadError)
    }

    @Test
    fun signOutDuringADownloadStopsItAndWritesNothingBack() = runBlocking {
        val id = readyIntent()
        val (stream, open) = contentOf(built.bytes, gateAfter = built.bytes.size / 2)
        api.onOpen = { open }
        val target = File(dir, "signed-out.zip")
        val running = async(Dispatchers.Default) { repo.download(id, FileDestination(target)) }
        stream.reachedGate.await()
        repo.invalidate()
        store.rows.value = emptyMap()
        stream.release()
        withTimeout(10_000) { running.await() }
        assertTrue(stream.aborted)
        assertFalse(target.exists())
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun aStopBetweenTheLastReadAndTheRecordRemovesTheFinishedFileAndSettlesTheRow() = runBlocking {
        val id = readyIntent()
        val (_, open) = contentOf(built.bytes)
        api.onOpen = { open }
        val target = File(dir, "stopped-late.zip")
        val inner = FileDestination(target)
        // The session ends after the whole ZIP was written and verified, and before the outcome is recorded.
        val destination = object : com.digifender.adminifender.core.data.files.Destination by inner {
            override fun sizeBytes(): Long? {
                runBlocking { repo.invalidate() }
                return inner.sizeBytes()
            }
        }
        val outcome = withTimeout(10_000) { repo.download(id, destination) }
        assertTrue(outcome is ExportDownloadOutcome.Superseded)
        assertFalse("a finished file is not kept for a request that no longer exists", target.exists())
    }

    @Test
    fun noEntryIsEverWrittenOutsideTheChosenDestination() = runBlocking {
        val hostile = buildExport(extraEntries = listOf("files/../../escape.txt" to "x".encodeToByteArray()))
        val id = readyIntent(hostile)
        val before = dir.walkTopDown().map { it.name }.toSet()
        api.onOpen = { contentOf(hostile.bytes).second }
        val out = repo.download(id, FileDestination(File(dir, "hostile.zip"))) as ExportDownloadOutcome.Failed
        assertEquals("verification_unsafe_name", out.intent.downloadError)
        val after = dir.walkTopDown().map { it.name }.toSet()
        assertEquals(before, after)
        assertFalse(File(dir, "escape.txt").exists())
        assertFalse(File(dir.parentFile, "escape.txt").exists())
    }

    @Test
    fun startingTheAppMarksARunningDownloadInterruptedAndRestartsPolling() = runBlocking {
        val id = readyIntent()
        store.put(store.get(id)!!.copy(download = ExportDownload.DOWNLOADING, destinationLabel = "export.zip"))
        api.onCreate = { _, scope -> CreateExportResult.Created(pending(scope)) }
        val waiting = repo.create(defaultScope()) as CreateOutcome.Created
        scheduler.polls.clear()
        repo.recover()
        val row = store.get(id)!!
        assertEquals(ExportDownload.INTERRUPTED, row.download)
        assertEquals("process_ended", row.downloadError)
        // Whether the partial file is still there is not known, and is not guessed.
        assertNull(row.destinationDiscarded)
        assertEquals(listOf(waiting.intent.localId), scheduler.polls)
    }

    @Test
    fun stoppedAndFinishedIntentsAreDismissedButNeverARunningOrUnansweredOne() = runBlocking {
        val waiting = repo.create(defaultScope()) as CreateOutcome.Waiting
        assertFalse(repo.dismiss(waiting.intent.localId))
        repo.stopTracking(waiting.intent.localId)
        assertEquals(ExportPhase.STOPPED, store.get(waiting.intent.localId)!!.phase)
        assertTrue(repo.dismiss(waiting.intent.localId))
    }
}
