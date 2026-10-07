package com.digifender.adminifender.core.data.chat

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey val scopeKey: String,
    val scopeKind: String,
    val scopeId: String?,
    val threadKey: String,
    val threadId: String?,
    val draft: String,
    val requestId: String?,
    val requestContent: String?,
    val requestKnown: Boolean,
)

@Dao
interface ChatSessionDao {
    @Query("SELECT * FROM chat_sessions WHERE scopeKey = :key")
    suspend fun get(key: String): ChatSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(session: ChatSessionEntity)

    @Query("DELETE FROM chat_sessions")
    suspend fun clear()
}

/** Private credential-encrypted app storage, covered by the manifest's backup/transfer exclusions. */
@Database(entities = [ChatSessionEntity::class], version = 1, exportSchema = true)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun sessions(): ChatSessionDao

    companion object {
        const val NAME = "adminifender-chat.db"
        fun open(context: Context): ChatDatabase = Room.databaseBuilder(context, ChatDatabase::class.java, NAME).build()
    }
}

/** Keep Room implementation types inside the data module. */
fun openPrivateChatStore(context: Context): ChatStore = RoomChatStore(ChatDatabase.open(context).sessions())

class RoomChatStore(private val dao: ChatSessionDao) : ChatStore {
    override suspend fun get(scope: ChatScope): ChatSession? = dao.get(scope.key)?.let {
        ChatSession(ChatScope(it.scopeKind, it.scopeId), it.threadKey, it.threadId, it.draft, it.requestId, it.requestContent, it.requestKnown)
    }

    override suspend fun put(session: ChatSession) = dao.put(
        ChatSessionEntity(session.scope.key, session.scope.kind, session.scope.id, session.threadKey, session.threadId, session.draft, session.requestId, session.requestContent, session.requestKnown),
    )

    override suspend fun clear() = dao.clear()
}
