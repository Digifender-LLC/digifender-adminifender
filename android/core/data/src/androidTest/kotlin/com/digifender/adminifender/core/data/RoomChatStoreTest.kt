package com.digifender.adminifender.core.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.digifender.adminifender.core.data.chat.ChatDatabase
import com.digifender.adminifender.core.data.chat.ChatScope
import com.digifender.adminifender.core.data.chat.ChatSession
import com.digifender.adminifender.core.data.chat.RoomChatStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomChatStoreTest {
    @Test
    fun requestIdentityAndDraftSurviveDatabaseReopenAndArePurgedTogether() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "synthetic-chat-recovery-test.db"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, ChatDatabase::class.java, name).build()
        val scope = ChatScope()
        val session = ChatSession(scope, "create-key", "thread-id", "Synthetic draft", "turn-id", "Synthetic question", false)
        try {
            val first = open()
            try { RoomChatStore(first.sessions()).put(session) } finally { first.close() }
            val second = open()
            try {
                val store = RoomChatStore(second.sessions())
                assertEquals(session, store.get(scope))
                store.clear()
            } finally { second.close() }
            val third = open()
            try { assertNull(RoomChatStore(third.sessions()).get(scope)) } finally { third.close() }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
