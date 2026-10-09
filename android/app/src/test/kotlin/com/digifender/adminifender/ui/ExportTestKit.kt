package com.digifender.adminifender.ui

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

fun contentOf(bytes: ByteArray, failAfter: Int? = null, gateAfter: Int? = null, length: Long? = bytes.size.toLong()): Pair<ScriptedStream, OpenResult> {
    val stream = ScriptedStream(bytes, failAfter, gateAfter)
    return stream to OpenResult.Open(ContentStream(length ?: -1, null, stream, stream::abort))
}

