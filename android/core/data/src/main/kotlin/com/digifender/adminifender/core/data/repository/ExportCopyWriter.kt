package com.digifender.adminifender.core.data.repository

import com.digifender.adminifender.core.data.network.TransferResult
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

sealed interface ExportSaveOutcome {
    data object Saved : ExportSaveOutcome
    data object Cancelled : ExportSaveOutcome
    data class Failed(val code: String, val partialRemoved: Boolean) : ExportSaveOutcome
}

/** Represents only the newly created destination selected for this one Save action. */
interface ExportDestination {
    fun open(): OutputStream?
    fun remove(): Boolean
}

class ExportCopyWriter(private val exports: ExportRepository) {
    suspend fun write(exportId: String, destination: ExportDestination?): ExportSaveOutcome {
        if (destination == null) return ExportSaveOutcome.Cancelled
        // A document provider may suspend/block while opening. Capture before dispatching to IO so a
        // sign-out and fresh sign-in during that interval cannot authorize this older copy operation.
        val fence = exports.copyFence()
        return try {
            val result = withContext(Dispatchers.IO) {
                destination.open()?.use { exports.saveCopy(exportId, it, fence) } ?: TransferResult.Unavailable("destination_unavailable")
            }
            when (result) {
                is TransferResult.Ok -> ExportSaveOutcome.Saved
                else -> ExportSaveOutcome.Failed(when (result) {
                    is TransferResult.Rejected -> result.code
                    is TransferResult.Unavailable -> result.code
                    else -> "sign_in"
                }, removePartial(destination))
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { removePartial(destination) }
            throw cancelled
        } catch (_: Exception) {
            ExportSaveOutcome.Failed("copy_failed", removePartial(destination))
        }
    }

    private suspend fun removePartial(destination: ExportDestination): Boolean = withContext(Dispatchers.IO) {
        try { destination.remove() } catch (_: Exception) { false }
    }
}
