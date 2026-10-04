package com.digifender.adminifender.core.data.network

import com.digifender.adminifender.core.model.Concern
import com.digifender.adminifender.core.model.Due
import com.digifender.adminifender.core.model.Importance
import com.digifender.adminifender.core.model.Status
import com.digifender.adminifender.core.model.Waiting
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

/** Wire DTOs for the sync API (lld.md#sync). Mapping to domain types is explicit and rejects unknown values. */
val wireJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

@Serializable
data class Envelope<T>(val data: T)

@Serializable
data class ErrorEnvelope(val error: ErrorBody) {
    @Serializable
    data class ErrorBody(val code: String, val details: JsonElement? = null)
}

@Serializable
data class ChangeDto(val seq: Long, val entityType: String, val entityId: String, val revision: Int, val deleted: Boolean, val projection: JsonElement? = null)

@Serializable
data class ChangesPageDto(val changes: List<ChangeDto>, val throughSeq: Long, val hasMore: Boolean)

@Serializable
data class SnapshotEntityDto(val entityType: String, val entityId: String, val revision: Int, val projection: JsonElement)

@Serializable
data class BootstrapPageDto(val snapshotSeq: Long, val entities: List<SnapshotEntityDto>, val nextCursor: String? = null)

@Serializable
data class WaitingDto(val note: String? = null, val awaitedParty: String? = null, val followUpAt: String? = null)

@Serializable
data class DueDto(val at: String? = null, val zone: String? = null, val sourceText: String? = null, val revision: Int)

@Serializable
data class ConcernDto(
    val id: String,
    val revision: Int,
    val title: String,
    val status: String,
    val importance: String,
    val criticalBasis: String? = null,
    val readAt: String? = null,
    val acknowledgedAt: String? = null,
    val resolvedAt: String? = null,
    val snoozeUntil: String? = null,
    val waiting: WaitingDto? = null,
    val due: DueDto? = null,
    val sourceIds: List<String> = emptyList(),
    val updatedAt: String,
)

@Serializable
data class SourceDto(
    val id: String,
    val kind: String,
    val state: String,
    val admission: String,
    val privacy: String,
    val revision: Int,
    val receivedAt: String,
    val subject: String? = null,
    val from: List<String> = emptyList(),
    val to: List<String> = emptyList(),
)

/** Source metadata the phone lists; text and attachments are fetched on demand through authenticated reads. */
data class SourceSummary(
    val id: String,
    val revision: Int,
    val state: String,
    val quarantined: Boolean,
    val privacy: String,
    val receivedAtMs: Long,
    val subject: String?,
    val from: List<String>,
)

private fun ms(value: String?): Long? = value?.let { Instant.parse(it).toEpochMilli() }

fun ConcernDto.toDomain(): Concern? {
    val status = Status.fromWire(status) ?: return null
    val importance = Importance.fromWire(importance) ?: return null
    return Concern(
        id = id,
        revision = revision,
        title = title,
        status = status,
        importance = importance,
        criticalBasis = criticalBasis,
        readAtMs = ms(readAt),
        acknowledgedAtMs = ms(acknowledgedAt),
        resolvedAtMs = ms(resolvedAt),
        snoozeUntilMs = ms(snoozeUntil),
        waiting = waiting?.let { Waiting(it.note, it.awaitedParty, ms(it.followUpAt)) },
        due = due?.let { Due(ms(it.at), it.zone, it.sourceText, it.revision) },
        sourceIds = sourceIds,
        updatedAtMs = ms(updatedAt) ?: 0,
    )
}

fun SourceDto.toDomain(): SourceSummary =
    SourceSummary(id, revision, state, admission == "quarantine", privacy, ms(receivedAt) ?: 0, subject, from)

/** One server change as the store applies it: a projection, a tombstone or an entity type this app ignores. */
sealed interface SyncChange {
    val seq: Long

    data class ConcernChanged(override val seq: Long, val concern: Concern) : SyncChange
    data class SourceChanged(override val seq: Long, val source: SourceSummary) : SyncChange
    data class Removed(override val seq: Long, val entityType: String, val entityId: String) : SyncChange
    data class Ignored(override val seq: Long) : SyncChange
}

/** Maps one feed entry. A projection that does not parse is an error, not something to skip silently. */
fun ChangeDto.toChange(): SyncChange {
    if (deleted) return SyncChange.Removed(seq, entityType, entityId)
    val body = projection ?: throw IllegalArgumentException("projection_missing")
    return when (entityType) {
        "concern" -> SyncChange.ConcernChanged(seq, wireJson.decodeFromJsonElement<ConcernDto>(body).toDomain() ?: throw IllegalArgumentException("concern_unknown_value"))
        "source" -> SyncChange.SourceChanged(seq, wireJson.decodeFromJsonElement<SourceDto>(body).toDomain())
        else -> SyncChange.Ignored(seq)
    }
}

fun SnapshotEntityDto.toChange(seq: Long): SyncChange = ChangeDto(seq, entityType, entityId, revision, false, projection).toChange()
