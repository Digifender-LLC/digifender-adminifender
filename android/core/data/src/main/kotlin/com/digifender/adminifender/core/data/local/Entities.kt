package com.digifender.adminifender.core.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
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
    val serviceId: String? = null,
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

/**
 * The last briefing snapshot read from the server, by reference only: concern IDs with the revision the snapshot
 * showed, and new source IDs. Titles and text are never copied here, so a deleted item disappears from the briefing
 * with the sync tombstone and nothing private outlives sign-out (which clears every table). Holds at most one row.
 * The review columns record the owner's "mark reviewed" intent before it is sent, so an unanswered send is retried
 * as the same request after process death.
 */
@Entity(tableName = "briefing")
data class BriefingEntity(
    @PrimaryKey val briefingId: String,
    val snapshotSeq: Long,
    val throughTime: Long,
    val lastReviewedAt: Long?,
    val fetchedAt: Long,
    val itemsJson: String,
    val reviewPhase: String,
    val reviewKey: String?,
    val reviewedAt: Long?,
)

/**
 * A chat conversation. [localId] exists before the Worker has a thread; [serverId] is filled once creation is
 * confirmed. [createKey] is the fixed Idempotency-Key for creating it. [draft] is the unsent text, kept so it survives
 * process death. [olderCursor] is the `nextBefore` the Worker gave with the oldest page read so far (null when no earlier
 * turns are known), and [historyLoaded] says a history page has been read at all. Cleared with every other table at sign-out.
 */
@Entity(tableName = "chat_threads", indices = [Index("updatedAt")])
data class ChatThreadEntity(
    @PrimaryKey val localId: String,
    val createKey: String,
    val serverId: String?,
    val scopeKind: String,
    val scopeId: String?,
    val createState: String,
    val createError: String?,
    val draft: String,
    val olderCursor: String?,
    val historyLoaded: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * One question and the Worker's record of its answer. `turnId` is the UUID the Worker uses as request identity and is
 * written, with the exact `content` and scope, before anything is sent. `dispatch` names the last step that was
 * started, so a crash leaves an honest trace. `answerJson` holds the answer and the cited excerpts; it is the only
 * private model-derived text and is nulled (never merely hidden) when an email it may rest on changes.
 */
@Entity(
    tableName = "chat_turns",
    foreignKeys = [ForeignKey(entity = ChatThreadEntity::class, parentColumns = ["localId"], childColumns = ["threadLocalId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("threadLocalId", "createdAt")],
)
data class ChatTurnEntity(
    @PrimaryKey val turnId: String,
    val threadLocalId: String,
    val content: String,
    val scopeKind: String,
    val scopeId: String?,
    val dispatch: String,
    val dispatchError: String?,
    val state: String?,
    val reason: String?,
    val errorCode: String?,
    val answerJson: String?,
    val serverInvalidated: Boolean,
    val serverWithheld: Boolean,
    val localInvalid: String?,
    val cancelNote: String?,
    val createdAt: Long,
    val completedAt: Long?,
    val checkedAt: Long?,
)
