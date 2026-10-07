package com.digifender.adminifender

import com.digifender.adminifender.core.data.network.AccessTokens
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CleanupAccessTokensTest {
    @Test fun pendingCleanupCannotReadOrRestoreCredentialsInAWorker() = runTest {
        val tokens = CleanupAccessTokens(object : AccessTokens {
            override suspend fun current(): String = error("must not restore old vault")
            override suspend fun rejected(token: String) = error("must not mutate old vault")
        }) { true }
        assertNull(tokens.current())
        tokens.rejected("synthetic")
    }

    @Test fun cleanupStartingDuringRefreshWithholdsTheLateCredential() = runTest {
        var blocked = false
        val entered = CompletableDeferred<Unit>()
        val reply = CompletableDeferred<String>()
        val tokens = CleanupAccessTokens(object : AccessTokens {
            override suspend fun current(): String { entered.complete(Unit); return reply.await() }
            override suspend fun rejected(token: String) = Unit
        }) { blocked }
        val request = async { tokens.current() }
        entered.await()
        blocked = true
        reply.complete("synthetic")
        assertNull(request.await())
        blocked = false
        assertEquals("synthetic", tokens.current())
    }
}
