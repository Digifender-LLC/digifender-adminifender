package com.digifender.adminifender.core.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Server projection of a concern. Only sync writes this table; queued edits live in `pending_mutations`. */
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

@Entity(tableName = "chat_threads", indices = [Index("scopeKind", "scopeId")])
data class ChatThreadEntity(
    @PrimaryKey val id: String,
    val scopeKind: String,
    val scopeId: String?,
    val createdAt: Long,
)

/**
 * A question and its durable answer. [sent] is false until the server confirms it holds the turn; the cited source
 * IDs let a deletion or quarantine withdraw the cached answer (lld.md#sync).
 */
@Entity(tableName = "chat_turns", indices = [Index("threadId", "createdAt")])
data class ChatTurnEntity(
    @PrimaryKey val turnId: String,
    val threadId: String,
    val content: String,
    val state: String,
    val sent: Boolean,
    val reason: String?,
    val errorCode: String?,
    val answer: String?,
    val claimsJson: String,
    val gapsJson: String,
    val citedSourceIdsJson: String,
    val invalidated: Boolean,
    val withheld: Boolean,
    val createdAt: Long,
    val completedAt: Long?,
)
