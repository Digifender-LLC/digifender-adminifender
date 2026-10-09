package com.digifender.adminifender.core.data.export

import com.digifender.adminifender.core.data.network.ExportScope
import com.digifender.adminifender.core.data.network.ExportState
import com.digifender.adminifender.core.data.network.ExportStatus
import kotlinx.coroutines.flow.Flow

/**
 * Where the *request* for an export stands.
 *
 * - [CREATING]: the scope and the key are stored; the Worker's answer may not have arrived. Sending again with the same key and body is safe.
 * - [CREATED]: the Worker named the export; [ExportIntent.status] is the last read-back.
 * - [REFUSED]: the Worker refused the request, or answered with a different scope than was asked; no export may be downloaded from it.
 * - [STOPPED]: the owner stopped tracking a request whose answer never came.
 */
enum class ExportPhase { CREATING, CREATED, REFUSED, STOPPED }

/**
 * Where the *download* stands, separately from the export's state on the Worker.
 * [VERIFIED] is the only state in which the saved file is claimed to be complete and correct.
 */
enum class ExportDownload { NONE, DOWNLOADING, INTERRUPTED, FAILED, VERIFIED }

/** An export the owner asked for, with the last thing the phone read about it. */
data class ExportIntent(
    val localId: String,
    val createKey: String,
    val scope: ExportScope,
    val phase: ExportPhase,
    val errorCode: String?,
    val exportId: String?,
    /** The last read-back; null until the Worker has answered once. */
    val status: ExportStatus?,
    val checkedAtMs: Long?,
    val download: ExportDownload,
    val downloadError: String?,
    /** The name of the file the owner chose, for messages. Never a path or URI. */
    val destinationLabel: String?,
    /** After an interrupted or failed download: true when the partial file was removed, false when it may remain, null when nothing was written or it is not known. */
    val destinationDiscarded: Boolean?,
    val verification: ExportVerification?,
    val verifiedAtMs: Long?,
    val createdAtMs: Long,
    val updatedAtMs: Long,
) {
    val state: ExportState? get() = status?.state

    /** Whether polling the Worker can still change anything. */
    val settled: Boolean get() = phase != ExportPhase.CREATED || status?.state?.settled == true
}

interface ExportStore {
    fun observeAll(): Flow<List<ExportIntent>>

    fun observe(localId: String): Flow<ExportIntent?>

    suspend fun get(localId: String): ExportIntent?

    suspend fun all(): List<ExportIntent>

    suspend fun put(intent: ExportIntent)

    suspend fun delete(localId: String)
}

/** Keeps asking the Worker how an export is doing while the app is not on screen. Bounded; never starts a download. */
interface ExportScheduler {
    fun schedulePoll(localId: String)

    fun cancelAll()
}
