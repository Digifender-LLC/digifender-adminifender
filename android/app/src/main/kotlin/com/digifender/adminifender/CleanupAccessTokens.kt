package com.digifender.adminifender

import com.digifender.adminifender.core.data.network.AccessTokens

/** A persisted cleanup obligation blocks background workers before the activity restores its session. */
internal class CleanupAccessTokens(private val delegate: AccessTokens, private val blocked: () -> Boolean) : AccessTokens {
    override suspend fun current(): String? {
        if (blocked()) return null
        val token = delegate.current()
        return if (blocked()) null else token
    }

    override suspend fun rejected(token: String) {
        if (!blocked()) delegate.rejected(token)
    }
}
