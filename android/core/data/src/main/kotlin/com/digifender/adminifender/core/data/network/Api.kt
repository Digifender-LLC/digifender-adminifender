package com.digifender.adminifender.core.data.network

import com.digifender.adminifender.core.model.ConcernAction
import com.digifender.adminifender.core.model.SendResult

/** Outcome of a read: a page, an expired cursor that needs a fresh bootstrap, or no usable answer. */
sealed interface ReadResult<out T> {
    data class Ok<T>(val value: T) : ReadResult<T>
    data object CursorExpired : ReadResult<Nothing>
    data object SessionExpired : ReadResult<Nothing>
    data class Unavailable(val code: String) : ReadResult<Nothing>
}

/** The backend calls the sync engine needs. Implementations never retry a mutation with a new key. */
interface AdminifenderApi {
    suspend fun changes(after: Long, limit: Int): ReadResult<ChangesPageDto>

    suspend fun bootstrap(cursor: String?, limit: Int): ReadResult<BootstrapPageDto>

    suspend fun concernAction(concernId: String, idempotencyKey: String, expectedRevision: Int, action: ConcernAction): SendResult
}
