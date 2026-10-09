package com.digifender.adminifender.core.data.network

/** Outcome of a mutation: answered, refused with a fixed code, or unknown because no answer arrived. */
sealed interface CallResult<out T> {
    data class Ok<T>(val value: T) : CallResult<T>

    data class Refused(val code: String) : CallResult<Nothing>

    data object NoAnswer : CallResult<Nothing>

    data object SessionExpired : CallResult<Nothing>
}
