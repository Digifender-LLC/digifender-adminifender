package com.digifender.adminifender.core.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Server projection of a concern. Sync publishes it; confirmed deletion may evict it. Edits live in the outbox. */
@Entity(tableName = "concerns", indices = [Index("status", "updatedAt")])
data class ConcernEntity(
    @PrimaryKey val id: String,
    val revision: Int,
    val title: String,
    val status: String,
    val importance: String,
    val criticalBasis: String?,
    val readAt: Long?,
    val acknowledgedAt: Long?,
    val resolvedAt: Long?,
    val snoozeUntil: Long?,
    val waitingNote: String?,
    val awaitedParty: String?,
    val followUpAt: Long?,
    val hasWaiting: Boolean,
    val dueAt: Long?,
    val dueZone: String?,
    val dueSourceText: String?,
    val dueRevision: Int?,
    val sourceIdsJson: String,
    val updatedAt: Long,
)

@Entity(tableName = "sources", indices = [Index("quarantined", "receivedAt")])
data class SourceEntity(
    @PrimaryKey val id: String,
    val revision: Int,
    val state: String,
    val quarantined: Boolean,
    val privacy: String,
    val receivedAt: Long,
    val subject: String?,
    val fromJson: String,
)

@Entity(tableName = "pending_mutations", indices = [Index("concernId"), Index("createdAt")])
data class PendingMutationEntity(
    @PrimaryKey val idempotencyKey: String,
    val concernId: String,
    val expectedRevision: Int,
    val kind: String,
    val argsJson: String,
    val createdAt: Long,
    val attempts: Int,
    val state: String,
    val nextAttemptAt: Long,
    val errorCode: String?,
    val currentRevision: Int?,
)

@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = 0,
    val throughSeq: Long,
    val needsBootstrap: Boolean,
    val lastSyncedAt: Long?,
)

/**
 * Text and attachment inventory of a source the owner opened, kept for offline reading and cached-only search.
 * A row is valid only while `sources` holds the same id at the same revision; sync prunes every other row in the
 * transaction that changed the source, so a deleted or changed source never keeps old text (FR-011, FR-033).
 */
@Entity(tableName = "source_texts")
data class SourceTextEntity(
    @PrimaryKey val sourceId: String,
    val revision: Int,
    val text: String?,
    val textComplete: Boolean,
    val attachmentsJson: String,
    val cachedAt: Long,
)

/** Explicit deletion is irreversible for this ID; stale in-flight sync pages cannot republish it. */
@Entity(tableName = "suppressed_sources")
data class SuppressedSourceEntity(@PrimaryKey val id: String)

/** A detached concern may return only after its reviewed pre-deletion revision has advanced. */
@Entity(tableName = "concern_deletion_fences")
data class ConcernDeletionFenceEntity(@PrimaryKey val id: String, val revision: Int)
