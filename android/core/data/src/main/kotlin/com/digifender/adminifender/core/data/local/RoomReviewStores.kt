package com.digifender.adminifender.core.data.local

import com.digifender.adminifender.core.data.export.ExportDownload
import com.digifender.adminifender.core.data.export.ExportIntent
import com.digifender.adminifender.core.data.export.ExportPhase
import com.digifender.adminifender.core.data.export.ExportStore
import com.digifender.adminifender.core.data.export.ExportVerification
import com.digifender.adminifender.core.data.network.Cleanup
import com.digifender.adminifender.core.data.network.CleanupState
import com.digifender.adminifender.core.data.network.OperationReceipt
import com.digifender.adminifender.core.data.network.OperationState
import com.digifender.adminifender.core.data.network.ReviewState
import com.digifender.adminifender.core.data.network.parseExportScope
import com.digifender.adminifender.core.data.network.parseExportStatus
import com.digifender.adminifender.core.data.network.toJson
import com.digifender.adminifender.core.data.network.wireJson
import com.digifender.adminifender.core.data.review.DeletedSourcePurge
import com.digifender.adminifender.core.data.review.DeletionPhase
import com.digifender.adminifender.core.data.review.DeletionReview
import com.digifender.adminifender.core.data.review.DeletionStore
import com.digifender.adminifender.core.data.review.StoredPreview
import com.digifender.adminifender.core.data.review.StoredReadback
import androidx.room.withTransaction
import com.digifender.adminifender.core.data.files.BlobDirectory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

private val strings = ListSerializer(String.serializer())

private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull
private fun JsonObject.int(key: String) = this[key]?.jsonPrimitive?.intOrNull ?: 0
private fun JsonObject.longOrNull(key: String) = this[key]?.jsonPrimitive?.longOrNull
private fun JsonElement.stringList(): List<String> = jsonArray.map { it.jsonPrimitive.content }

private fun revisionsJson(map: Map<String, Int>) = buildJsonObject { map.toSortedMap().forEach { (k, v) -> put(k, v) } }

private fun JsonObject.revisions(): Map<String, Int> = entries.associate { (k, v) -> k to (v.jsonPrimitive.intOrNull ?: 0) }

internal fun DeletionReview.toEntity(): DeletionReviewEntity = DeletionReviewEntity(
    localId = localId,
    sourceIdsJson = wireJson.encodeToString(strings, sourceIds),
    prepareKey = prepareKey,
    phase = phase.name,
    errorCode = errorCode,
    reviewId = reviewId,
    paramsDigest = paramsDigest,
    expiresAt = expiresAtMs,
    preconditionsJson = buildJsonObject {
        put("sources", revisionsJson(sourceRevisions))
        put("concerns", revisionsJson(concernRevisions))
    }.toString(),
    previewJson = preview?.let {
        buildJsonObject {
            put("attachments", it.attachments)
            put("expectedCount", it.expectedCount)
            put("concernsErased", JsonArray(it.concernsErased.map(::JsonPrimitive)))
            put("concernsDetached", JsonArray(it.concernsDetached.map(::JsonPrimitive)))
            put("derived", JsonArray(it.derived.map(::JsonPrimitive)))
        }.toString()
    },
    confirmKey = confirmKey,
    operationId = operationId,
    receiptJson = receipt?.let {
        buildJsonObject {
            put("ledgerSeq", it.ledgerSeq)
            put("ledgerRecordHash", it.ledgerRecordHash)
            put("sourcesDeleted", it.sourcesDeleted)
        }.toString()
    },
    cleanupJson = cleanup?.let {
        buildJsonObject {
            put("state", it.state.wire)
            put("total", it.total)
            put("completed", it.completed)
            put("failed", it.failed)
            put("unrecorded", it.unrecorded)
        }.toString()
    },
    cleanupCheckedAt = cleanupCheckedAtMs,
    readbackJson = readback?.let {
        buildJsonObject {
            put("state", it.state.wire)
            it.operationState?.let { op -> put("operationState", op.wire) }
            put("checkedAt", it.checkedAtMs)
        }.toString()
    },
    createdAt = createdAtMs,
    updatedAt = updatedAtMs,
)

internal fun DeletionReviewEntity.toReview(): DeletionReview {
    val pre = wireJson.parseToJsonElement(preconditionsJson).jsonObject
    val receipt = receiptJson?.let { wireJson.parseToJsonElement(it).jsonObject }
    val cleanup = cleanupJson?.let { wireJson.parseToJsonElement(it).jsonObject }
    val readback = readbackJson?.let { wireJson.parseToJsonElement(it).jsonObject }
    val preview = previewJson?.let { wireJson.parseToJsonElement(it).jsonObject }
    return DeletionReview(
        localId = localId,
        sourceIds = wireJson.decodeFromString(strings, sourceIdsJson),
        prepareKey = prepareKey,
        phase = DeletionPhase.valueOf(phase),
        errorCode = errorCode,
        reviewId = reviewId,
        paramsDigest = paramsDigest,
        expiresAtMs = expiresAt,
        sourceRevisions = pre["sources"]?.jsonObject?.revisions().orEmpty(),
        concernRevisions = pre["concerns"]?.jsonObject?.revisions().orEmpty(),
        preview = preview?.let { StoredPreview(it.int("attachments"), it["concernsErased"]!!.stringList(), it["concernsDetached"]!!.stringList(), it["derived"]!!.stringList(), it.int("expectedCount")) },
        confirmKey = confirmKey,
        operationId = operationId,
        receipt = receipt?.let { OperationReceipt.Deletion(it.longOrNull("ledgerSeq") ?: 0, it.str("ledgerRecordHash").orEmpty(), it.int("sourcesDeleted")) },
        cleanup = cleanup?.let { Cleanup(CleanupState.byWire.getValue(it.str("state")!!), it.int("total"), it.int("completed"), it.int("failed"), it.int("unrecorded")) },
        cleanupCheckedAtMs = cleanupCheckedAt,
        readback = readback?.let { StoredReadback(ReviewState.byWire.getValue(it.str("state")!!), it.str("operationState")?.let(OperationState.byWire::get), it.longOrNull("checkedAt") ?: 0) },
        createdAtMs = createdAt,
        updatedAtMs = updatedAt,
    )
}

class RoomDeletionStore(private val db: AppDatabase) : DeletionStore {
    override fun observeAll(): Flow<List<DeletionReview>> = db.deletionReviews().observeAll().map { rows -> rows.map { it.toReview() } }

    override fun observe(localId: String): Flow<DeletionReview?> = db.deletionReviews().observe(localId).map { it?.toReview() }

    override suspend fun get(localId: String) = db.deletionReviews().get(localId)?.toReview()

    override suspend fun all() = db.deletionReviews().all().map { it.toReview() }

    override suspend fun put(review: DeletionReview) = db.deletionReviews().put(review.toEntity())

    override suspend fun delete(localId: String) = db.deletionReviews().delete(localId)
}

private fun ExportVerification.toJson(): String = buildJsonObject {
    put("entries", entries)
    put("zipBytes", zipBytes)
    put("zipSha256", zipSha256)
    put("listedFiles", listedFiles)
    put("attachmentFiles", attachmentFiles)
    put("partialConcerns", partialConcerns)
    put("rulesIncluded", rulesIncluded)
    put("servicesIncluded", servicesIncluded)
}.toString()

private fun parseVerification(text: String): ExportVerification {
    val o = wireJson.parseToJsonElement(text).jsonObject
    return ExportVerification(
        o.int("entries"),
        o.longOrNull("zipBytes") ?: 0,
        o.str("zipSha256").orEmpty(),
        o.int("listedFiles"),
        o.int("attachmentFiles"),
        o.int("partialConcerns"),
        o["rulesIncluded"]?.jsonPrimitive?.contentOrNull == "true",
        o["servicesIncluded"]?.jsonPrimitive?.contentOrNull == "true",
    )
}

internal fun ExportIntent.toEntity() = ExportIntentEntity(
    localId = localId,
    createKey = createKey,
    scopeJson = scope.toJson().toString(),
    phase = phase.name,
    errorCode = errorCode,
    exportId = exportId,
    statusJson = status?.toJson()?.toString(),
    checkedAt = checkedAtMs,
    download = download.name,
    downloadError = downloadError,
    destinationLabel = destinationLabel,
    destinationDiscarded = destinationDiscarded,
    verificationJson = verification?.toJson(),
    verifiedAt = verifiedAtMs,
    createdAt = createdAtMs,
    updatedAt = updatedAtMs,
)

internal fun ExportIntentEntity.toIntent() = ExportIntent(
    localId = localId,
    createKey = createKey,
    scope = parseExportScope(wireJson.parseToJsonElement(scopeJson)),
    phase = ExportPhase.valueOf(phase),
    errorCode = errorCode,
    exportId = exportId,
    // A stored status that no longer parses is shown as "not read yet" rather than guessed at.
    status = statusJson?.let { runCatching { parseExportStatus(wireJson.parseToJsonElement(it)) }.getOrNull() },
    checkedAtMs = checkedAt,
    download = ExportDownload.valueOf(download),
    downloadError = downloadError,
    destinationLabel = destinationLabel,
    destinationDiscarded = destinationDiscarded,
    verification = verificationJson?.let { runCatching { parseVerification(it) }.getOrNull() },
    verifiedAtMs = verifiedAt,
    createdAtMs = createdAt,
    updatedAtMs = updatedAt,
)

class RoomExportStore(private val db: AppDatabase) : ExportStore {
    override fun observeAll(): Flow<List<ExportIntent>> = db.exportIntents().observeAll().map { rows -> rows.map { it.toIntent() } }

    override fun observe(localId: String): Flow<ExportIntent?> = db.exportIntents().observe(localId).map { it?.toIntent() }

    override suspend fun get(localId: String) = db.exportIntents().get(localId)?.toIntent()

    override suspend fun all() = db.exportIntents().all().map { it.toIntent() }

    override suspend fun put(intent: ExportIntent) = db.exportIntents().put(intent.toEntity())

    override suspend fun delete(localId: String) = db.exportIntents().delete(localId)
}

/** Removes the phone's private copies of deleted emails in one transaction, then their sealed files from disk. */
class RoomDeletedSourcePurge(private val db: AppDatabase, private val chat: RoomChatCache, private val blobs: BlobDirectory) : DeletedSourcePurge {
    override suspend fun purge(sourceIds: Collection<String>, nowMs: Long) {
        val ids = sourceIds.distinct()
        if (ids.isEmpty()) return
        val names = db.withTransaction {
            val collected = mutableListOf<String>()
            for (chunk in ids.chunked(500)) {
                db.tombstones().put(chunk.map { SourceTombstoneEntity(it, nowMs) })
                db.sources().deleteMany(chunk)
                collected += db.localFiles().forSources(chunk).mapNotNull { it.blobName }
                db.localFiles().deleteForSources(chunk)
            }
            db.sourceTexts().pruneStale()
            // The sources are gone from the table, so every answer that rests on one of them no longer holds.
            chat.dropRestrictedAnswers(ids.toSet())
            collected
        }
        blobs.delete(names)
    }
}
