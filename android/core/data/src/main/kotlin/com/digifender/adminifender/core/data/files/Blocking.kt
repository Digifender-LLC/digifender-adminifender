package com.digifender.adminifender.core.data.files

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Runs blocking [block] on the IO dispatcher. If the caller is cancelled, [abort] runs first, which is what wakes a read
 * that is blocked on a socket (the connection is closed), and the cancellation then propagates once [block] has ended.
 */
internal suspend fun <T> blockingWithAbort(abort: () -> Unit, block: CoroutineScope.() -> T): T = coroutineScope {
    val work = async(Dispatchers.IO) { block() }
    try {
        work.await()
    } catch (e: CancellationException) {
        abort()
        throw e
    }
}
