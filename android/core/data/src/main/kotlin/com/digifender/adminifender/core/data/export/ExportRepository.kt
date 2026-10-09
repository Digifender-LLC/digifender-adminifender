package com.digifender.adminifender.core.data.export

import com.digifender.adminifender.core.data.files.Destination
import com.digifender.adminifender.core.data.files.blockingWithAbort
import com.digifender.adminifender.core.data.network.CreateExportResult
import com.digifender.adminifender.core.data.network.ExportApi
import com.digifender.adminifender.core.data.network.ExportRead
import com.digifender.adminifender.core.data.network.ExportScope
import com.digifender.adminifender.core.data.network.ExportState
import com.digifender.adminifender.core.data.network.ExportStatus
import com.digifender.adminifender.core.data.network.OpenResult
import com.digifender.adminifender.core.data.network.ScopeProblem
import com.digifender.adminifender.core.data.network.normalized
import com.digifender.adminifender.core.data.network.problem
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface CreateOutcome {
    data class Created(val intent: ExportIntent) : CreateOutcome

    /** The request is stored; no answer is known. [ExportRepository.resend] sends the same key and body again. */
    data class Waiting(val intent: ExportIntent, val code: String) : CreateOutcome

    data class Refused(val intent: ExportIntent, val code: String) : CreateOutcome

    /** The scope cannot be sent; nothing was stored. */
    data class Invalid(val problem: ScopeProblem) : CreateOutcome

    data object SignIn : CreateOutcome

    data object Superseded : CreateOutcome
}

sealed interface RefreshOutcome {
    data class Updated(val intent: ExportIntent) : RefreshOutcome

    /** The Worker could not be read; what is stored stands. */
    data class Unchanged(val intent: ExportIntent, val code: String) : RefreshOutcome

    data object SignIn : RefreshOutcome

    data object Superseded : RefreshOutcome
}

sealed interface ExportDownloadOutcome {
    /** Every check passed: the saved file is the complete ZIP the export described. */
    data class Verified(val intent: ExportIntent) : ExportDownloadOutcome

    /** The transfer ended early (connection lost, app stopped). The partial file was discarded if the destination allowed it. */
    data class Interrupted(val intent: ExportIntent) : ExportDownloadOutcome

    /** The download or its checks failed. Nothing is claimed about the saved file, and it was discarded if the destination allowed it. */
    data class Failed(val intent: ExportIntent) : ExportDownloadOutcome

    /** The export is not ready (or no longer is); [state] is what the Worker said. */
    data class NotReady(val state: ExportState?) : ExportDownloadOutcome

    data object SignIn : ExportDownloadOutcome

    data object Superseded : ExportDownloadOutcome
}

private const val CHUNK = 64 * 1024

/**
 * Exports as owner-chosen files (FR-012, lld.md#exports). The scope and the Idempotency-Key are stored before the
 * request; an unanswered request is only ever repeated as the *same* request. The ZIP is streamed from the
 * authenticated endpoint to the owner's destination while it is verified, and only a verified file is called complete.
 * A failed or interrupted download is retried as a download, never as a new export request. As in the deletion
 * repository, every write is fenced against the row as it is now and the sign-in epoch.
 */
class ExportRepository(
    private val api: ExportApi,
    private val store: ExportStore,
    private val scheduler: ExportScheduler,
    private val clock: () -> Long,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val rows = Mutex()
    private var epoch = 0
    private val running = mutableMapOf<String, Job>()

    fun intents(): Flow<List<ExportIntent>> = store.observeAll()

    fun intent(localId: String): Flow<ExportIntent?> = store.observe(localId)

    /** Sign-out: running downloads stop, and answers to requests that began earlier are discarded. */
    suspend fun invalidate() {
        val jobs = rows.withLock {
            epoch += 1
            running.values.toList().also { running.clear() }
        }
        jobs.forEach { it.cancel() }
        scheduler.cancelAll()
    }

    /**
     * The session ended (signed out, refused or revoked): running downloads stop now, their connections close and their
     * partial files are discarded. Unlike [invalidate] this keeps the epoch, so the durable intents stay current and
     * keep recording what happened; nothing private survives except the owner's own requests.
     */
    suspend fun stopTransfers() {
        val jobs = rows.withLock { running.values.toList().also { running.clear() } }
        jobs.forEach { it.cancel() }
        scheduler.cancelAll()
    }

    private suspend fun epochNow() = rows.withLock { epoch }

    private suspend fun mutate(localId: String, startedIn: Int, change: suspend (ExportIntent) -> ExportIntent?): ExportIntent? = rows.withLock {
        if (epoch != startedIn) return@withLock null
        val current = store.get(localId) ?: return@withLock null
        val next = change(current) ?: return@withLock null
        store.put(next)
        next
    }

    /** Stores the intent (scope and key) and then asks the Worker. An invalid scope is refused before anything is stored. */
    suspend fun create(requested: ExportScope): CreateOutcome {
        val scope = requested.normalized()
        scope.problem()?.let { return CreateOutcome.Invalid(it) }
        val now = clock()
        val intent = ExportIntent(
            localId = newId(),
            createKey = newId(),
            scope = scope,
            phase = ExportPhase.CREATING,
            errorCode = null,
            exportId = null,
            status = null,
            checkedAtMs = null,
            download = ExportDownload.NONE,
            downloadError = null,
            destinationLabel = null,
            destinationDiscarded = null,
            verification = null,
            verifiedAtMs = null,
            createdAtMs = now,
            updatedAtMs = now,
        )
        val startedIn = rows.withLock { epoch.also { store.put(intent) } }
        return send(intent, startedIn)
    }

    /** The owner asks again for a request whose answer never came: the same key, the same scope, so it can only ever replay. */
    suspend fun resend(localId: String): CreateOutcome {
        val startedIn = epochNow()
        val intent = store.get(localId)?.takeIf { it.phase == ExportPhase.CREATING } ?: return CreateOutcome.Superseded
        return send(intent, startedIn)
    }

    private suspend fun send(intent: ExportIntent, startedIn: Int): CreateOutcome {
        val id = intent.localId
        return when (val result = api.create(intent.createKey, intent.scope)) {
            CreateExportResult.SessionExpired -> CreateOutcome.SignIn
            is CreateExportResult.NoAnswer -> mutate(id, startedIn) { if (it.phase == ExportPhase.CREATING) it.copy(errorCode = result.code, updatedAtMs = clock()) else null }
                ?.let { CreateOutcome.Waiting(it, result.code) } ?: CreateOutcome.Superseded
            is CreateExportResult.Refused -> mutate(id, startedIn) { if (it.phase == ExportPhase.CREATING) it.copy(phase = ExportPhase.REFUSED, errorCode = result.code, updatedAtMs = clock()) else null }
                ?.let { CreateOutcome.Refused(it, result.code) } ?: CreateOutcome.Superseded
            is CreateExportResult.Created -> {
                val status = result.status
                // The scope that was asked for is the scope the owner was shown; an export of anything else is never offered.
                val matches = status.scope == intent.scope
                val next = mutate(id, startedIn) {
                    if (it.phase != ExportPhase.CREATING) {
                        null
                    } else if (!matches) {
                        it.copy(phase = ExportPhase.REFUSED, errorCode = "scope_mismatch", exportId = status.id, updatedAtMs = clock())
                    } else {
                        it.copy(phase = ExportPhase.CREATED, errorCode = null, exportId = status.id, status = status, checkedAtMs = clock(), updatedAtMs = clock())
                    }
                } ?: return CreateOutcome.Superseded
                if (!matches) {
                    CreateOutcome.Refused(next, "scope_mismatch")
                } else {
                    if (!status.state.settled) scheduler.schedulePoll(id)
                    CreateOutcome.Created(next)
                }
            }
        }
    }

    /** Reads the export's state from the Worker and stores it before anyone sees it. A settled state is never taken back by an older answer. */
    suspend fun refresh(localId: String): RefreshOutcome {
        val startedIn = epochNow()
        val intent = store.get(localId) ?: return RefreshOutcome.Superseded
        val exportId = intent.exportId
        if (intent.phase != ExportPhase.CREATED || exportId == null) return RefreshOutcome.Unchanged(intent, "not_created")
        return when (val read = api.status(exportId)) {
            ExportRead.SessionExpired -> RefreshOutcome.SignIn
            ExportRead.NotKnown -> record(localId, startedIn, "not_known")
            is ExportRead.Unavailable -> record(localId, startedIn, read.code)
            is ExportRead.Ok -> {
                val status = read.status
                val next = mutate(localId, startedIn) { current ->
                    when {
                        status.id != current.exportId -> current.copy(errorCode = "status_mismatch", updatedAtMs = clock())
                        status.scope != null && status.scope != current.scope -> current.copy(errorCode = "scope_mismatch", updatedAtMs = clock())
                        current.status?.state?.let { it == ExportState.FAILED || it == ExportState.EXPIRED || it == ExportState.INVALIDATED } == true && current.status.state != status.state ->
                            current.copy(errorCode = null, checkedAtMs = clock(), updatedAtMs = clock())
                        else -> current.copy(errorCode = null, status = status, checkedAtMs = clock(), updatedAtMs = clock())
                    }
                } ?: return RefreshOutcome.Superseded
                if (next.errorCode != null) RefreshOutcome.Unchanged(next, next.errorCode) else RefreshOutcome.Updated(next)
            }
        }
    }

    /** One background read for WorkManager. True when nothing more can change (settled, dismissed or signed out). */
    suspend fun refreshForWorker(localId: String): Boolean = when (val r = refresh(localId)) {
        is RefreshOutcome.Updated -> r.intent.settled
        is RefreshOutcome.Unchanged -> r.intent.settled
        RefreshOutcome.SignIn, RefreshOutcome.Superseded -> true
    }

    private suspend fun record(localId: String, startedIn: Int, code: String): RefreshOutcome =
        mutate(localId, startedIn) { it.copy(errorCode = code, updatedAtMs = clock()) }?.let { RefreshOutcome.Unchanged(it, code) } ?: RefreshOutcome.Superseded

    /**
     * Streams the ZIP into [destination] and verifies it as it passes. The state is stored as downloading before the
     * request, and settled afterwards whatever happened: verified, failed or interrupted. Anything but verified discards
     * the destination's file if it can, and says honestly whether it could.
     */
    suspend fun download(localId: String, destination: Destination): ExportDownloadOutcome {
        val startedIn = epochNow()
        // Read the export's state again first: a ready export can have been invalidated since the screen was drawn.
        when (val r = refresh(localId)) {
            RefreshOutcome.SignIn -> {
                destination.discard()
                return ExportDownloadOutcome.SignIn
            }
            RefreshOutcome.Superseded -> {
                destination.discard()
                return ExportDownloadOutcome.Superseded
            }
            else -> Unit
        }
        var refusal: ExportDownloadOutcome? = null
        val started = mutate(localId, startedIn) { current ->
            val status = current.status
            when {
                current.phase != ExportPhase.CREATED || current.exportId == null || status == null || status.state != ExportState.READY -> {
                    refusal = ExportDownloadOutcome.NotReady(status?.state)
                    null
                }
                current.download == ExportDownload.DOWNLOADING -> {
                    refusal = ExportDownloadOutcome.Superseded
                    null
                }
                else -> current.copy(
                    download = ExportDownload.DOWNLOADING,
                    downloadError = null,
                    destinationLabel = destination.label,
                    destinationDiscarded = null,
                    verification = null,
                    verifiedAtMs = null,
                    updatedAtMs = clock(),
                )
            }
        }
        if (started == null) {
            destination.discard()
            return refusal ?: ExportDownloadOutcome.Superseded
        }
        val status = started.status!!
        return try {
            coroutineScope {
                val work = async { transfer(started, status, startedIn, destination) }
                rows.withLock { running[localId] = work }
                try {
                    work.await()
                } finally {
                    rows.withLock { if (running[localId] === work) running.remove(localId) }
                }
            }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            ExportDownloadOutcome.Superseded
        }
    }

    private class Failure(val state: ExportDownload, val code: String)

    private suspend fun transfer(intent: ExportIntent, status: ExportStatus, startedIn: Int, destination: Destination): ExportDownloadOutcome {
        val localId = intent.localId
        val counts = status.counts
        val scope = status.scope
        if (counts == null || scope == null || status.omissions == null) return settle(localId, startedIn, destination, Failure(ExportDownload.FAILED, "status_incomplete"), null)
        val expected = ExpectedExport(status.id, status.snapshotSeq, status.createdAtMs, scope, counts, status.omissions)
        val opened = try {
            api.openContent(intent.exportId!!)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { settle(localId, startedIn, destination, Failure(ExportDownload.INTERRUPTED, "cancelled"), null) }
            throw e
        }
        val stream = when (opened) {
            OpenResult.SessionExpired -> return settle(localId, startedIn, destination, Failure(ExportDownload.FAILED, "sign_in_required"), null, signIn = true)
            is OpenResult.Gone -> return settle(localId, startedIn, destination, Failure(ExportDownload.FAILED, opened.code), null, refreshAfter = true)
            is OpenResult.Refused -> return settle(localId, startedIn, destination, Failure(ExportDownload.FAILED, opened.code), null, refreshAfter = true)
            is OpenResult.Unavailable -> return settle(localId, startedIn, destination, Failure(ExportDownload.INTERRUPTED, opened.code), null)
            is OpenResult.Open -> opened.stream
        }
        var failure: Failure? = null
        var verification: ExportVerification? = null
        try {
            verification = stream.use { s ->
                blockingWithAbort(s::abort) {
                    val verifier = StreamingZipVerifier(expected)
                    val buffer = ByteArray(CHUNK)
                    var total = 0L
                    val out = try {
                        destination.open()
                    } catch (_: IOException) {
                        throw DestinationFailure()
                    }
                    try {
                        while (true) {
                            ensureActive()
                            val n = try {
                                s.body.read(buffer)
                            } catch (_: IOException) {
                                throw TransferInterrupted()
                            }
                            if (n < 0) break
                            total += n
                            verifier.accept(buffer, 0, n)
                            try {
                                out.write(buffer, 0, n)
                            } catch (_: IOException) {
                                throw DestinationFailure()
                            }
                        }
                        if (s.contentLength >= 0 && s.contentLength != total) throw ZipProblem("length_mismatch")
                        val result = verifier.finish()
                        try {
                            out.flush()
                        } catch (_: IOException) {
                            throw DestinationFailure()
                        }
                        result
                    } finally {
                        try {
                            out.close()
                        } catch (_: IOException) {
                            // A close that fails after a clean verify is caught by the size check below.
                        }
                    }
                }
            }
            val written = destination.sizeBytes()
            if (written != null && written != verification.zipBytes) failure = Failure(ExportDownload.FAILED, "destination_short")
        } catch (e: CancellationException) {
            withContext(NonCancellable) { settle(localId, startedIn, destination, Failure(ExportDownload.INTERRUPTED, "cancelled"), null) }
            throw e
        } catch (e: TransferInterrupted) {
            failure = interruption()
        } catch (e: DestinationFailure) {
            failure = Failure(ExportDownload.FAILED, "destination_write")
        } catch (e: ZipProblem) {
            failure = Failure(ExportDownload.FAILED, "verification_${e.code}")
        } catch (e: IOException) {
            failure = interruption()
        }
        if (failure?.code == "cancelled") {
            // The connection was closed because this download was stopped, not because it dropped: settle, then keep stopping.
            withContext(NonCancellable) { settle(localId, startedIn, destination, failure, null) }
            throw CancellationException("export download stopped")
        }
        return settle(localId, startedIn, destination, failure, if (failure == null) verification else null)
    }

    /** A failed read is a dropped connection, unless it failed because this download was being stopped. */
    private suspend fun interruption() = if (currentCoroutineContext().isActive) Failure(ExportDownload.INTERRUPTED, "network") else Failure(ExportDownload.INTERRUPTED, "cancelled")

    private class TransferInterrupted : IOException()

    private class DestinationFailure : IOException()

    /** Records how the download ended. Anything short of verified discards the destination's file first and records whether that worked. */
    private suspend fun settle(localId: String, startedIn: Int, destination: Destination, failure: Failure?, verification: ExportVerification?, signIn: Boolean = false, refreshAfter: Boolean = false): ExportDownloadOutcome {
        val discarded = if (failure != null || verification == null) destination.discard() else null
        val next = mutate(localId, startedIn) { current ->
            if (current.download != ExportDownload.DOWNLOADING) return@mutate null
            if (verification != null && failure == null) {
                current.copy(download = ExportDownload.VERIFIED, downloadError = null, destinationDiscarded = null, verification = verification, verifiedAtMs = clock(), updatedAtMs = clock())
            } else {
                val f = failure ?: Failure(ExportDownload.FAILED, "unverified")
                current.copy(download = f.state, downloadError = f.code, destinationDiscarded = discarded, verification = null, verifiedAtMs = null, updatedAtMs = clock())
            }
        } ?: return ExportDownloadOutcome.Superseded
        // A file that finished while the export was being invalidated (a mail in it deleted meanwhile) must not look clean: read the state once more.
        if (refreshAfter || next.download == ExportDownload.VERIFIED) refresh(localId)
        return when {
            signIn -> ExportDownloadOutcome.SignIn
            next.download == ExportDownload.VERIFIED -> ExportDownloadOutcome.Verified(next)
            next.download == ExportDownload.INTERRUPTED -> ExportDownloadOutcome.Interrupted(next)
            else -> ExportDownloadOutcome.Failed(next)
        }
    }

    /** The owner gives up tracking a request whose answer never came. An export may exist on the Worker; it expires on its own. */
    suspend fun stopTracking(localId: String) {
        mutate(localId, epochNow()) { if (it.phase == ExportPhase.CREATING) it.copy(phase = ExportPhase.STOPPED, updatedAtMs = clock()) else null }
    }

    suspend fun dismiss(localId: String): Boolean = rows.withLock {
        val row = store.get(localId) ?: return@withLock false
        if (row.download == ExportDownload.DOWNLOADING || row.phase == ExportPhase.CREATING) return@withLock false
        store.delete(localId)
        true
    }

    /** Start of the app. A download that was running when the process ended is interrupted, and its partial file may remain: that is said, not guessed. No request is made. */
    suspend fun recover() {
        val startedIn = epochNow()
        for (row in store.all()) {
            if (row.download == ExportDownload.DOWNLOADING) {
                mutate(row.localId, startedIn) { it.copy(download = ExportDownload.INTERRUPTED, downloadError = "process_ended", destinationDiscarded = null, updatedAtMs = clock()) }
            }
            if (row.phase == ExportPhase.CREATED && row.status?.state?.settled == false) scheduler.schedulePoll(row.localId)
        }
    }
}
