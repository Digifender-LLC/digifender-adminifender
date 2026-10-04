package com.digifender.adminifender.core.data.local

import com.digifender.adminifender.core.data.network.ClaimDto
import com.digifender.adminifender.core.data.network.wireJson
import com.digifender.adminifender.core.data.repository.ChatCache
import com.digifender.adminifender.core.data.repository.ChatScope
import com.digifender.adminifender.core.data.repository.ChatTurn
import com.digifender.adminifender.core.data.repository.TurnState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

private val strings = ListSerializer(String.serializer())
private val claimList = ListSerializer(ClaimDto.serializer())

private fun ChatTurnEntity.toTurn() = ChatTurn(
    turnId, threadId, content, TurnState.fromWire(state) ?: TurnState.FAILED, sent, reason, errorCode, answer,
    wireJson.decodeFromString(claimList, claimsJson), wireJson.decodeFromString(strings, gapsJson), invalidated, withheld, createdAt, completedAt,
)

private fun ChatTurn.toEntity(): ChatTurnEntity {
    val cited = claims.flatMap { c -> c.citations.mapNotNull { it.sourceId } }.distinct()
    return ChatTurnEntity(
        turnId, threadId, content, state.wire, sent, reason, errorCode, answer, wireJson.encodeToString(claimList, claims),
        wireJson.encodeToString(strings, gaps), wireJson.encodeToString(strings, cited), invalidated, withheld, createdAtMs, completedAtMs,
    )
}

/** Room-backed [ChatCache]. */
class RoomChatCache(private val db: AppDatabase) : ChatCache {
    override suspend fun thread(scope: ChatScope): String? = db.chat().latestThread(scope.kind.wire, scope.id)?.id

    override suspend fun putThread(id: String, scope: ChatScope, createdAtMs: Long) = db.chat().putThread(ChatThreadEntity(id, scope.kind.wire, scope.id, createdAtMs))

    override fun turns(threadId: String): Flow<List<ChatTurn>> = db.chat().observeTurns(threadId).map { rows -> rows.map { it.toTurn() } }

    override suspend fun turn(id: String): ChatTurn? = db.chat().turn(id)?.toTurn()

    override suspend fun insert(turn: ChatTurn) = db.chat().insertTurn(turn.toEntity())

    // Deletion and quarantine reach cached answers through sync (RoomLocalStore); the server rechecks citations on every read.
    override suspend fun put(turn: ChatTurn) = db.chat().putTurn(turn.toEntity())

    override suspend fun unfinished(): List<ChatTurn> = db.chat().unfinished().map { it.toTurn() }
}

