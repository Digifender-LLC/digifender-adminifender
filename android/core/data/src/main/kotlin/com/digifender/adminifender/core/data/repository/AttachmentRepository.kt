package com.digifender.adminifender.core.data.repository

import com.digifender.adminifender.core.data.network.ArchiveApi
import com.digifender.adminifender.core.data.network.ArchiveBytesApi
import com.digifender.adminifender.core.data.network.ReadResult
import com.digifender.adminifender.core.data.network.TransferResult
import com.digifender.adminifender.core.data.network.isSourceId
import java.io.IOException

/** Downloads only a currently authorized attachment of the source the owner opened, with its current hash. */
class AttachmentRepository(private val archive: ArchiveApi, private val bytes: ArchiveBytesApi, private val downloads: PrivateDownloadStore) {
    val changes = downloads.changes
    suspend fun saved(sourceId: String) = downloads.savedAttachments(sourceId)
    suspend fun download(sourceId: String, attachmentId: String): TransferResult<SavedDownload> {
        val fence = downloads.fence
        if (!isSourceId(sourceId) || !isSourceId(attachmentId)) return TransferResult.Rejected("invalid_request")
        val detail = when (val result = archive.source(sourceId)) {
            is ReadResult.Ok -> result.value
            else -> return readFailure(result)
        }
        if (detail.source.id != sourceId || detail.source.state != "ready") return TransferResult.Rejected("source_unavailable")
        val attachment = detail.attachments.singleOrNull { it.id == attachmentId } ?: return TransferResult.Rejected("not_found")
        return downloads.attachment(sourceId, attachmentId, fence) { output ->
            when (val result = bytes.attachment(attachmentId, attachment.sha256, attachment.sizeBytes, output)) {
                is TransferResult.Ok -> {
                    // Do not publish bytes from a source deleted or changed while the stream was arriving.
                    when (val latest = archive.source(sourceId)) {
                        is ReadResult.Ok -> if (latest.value.source.id == sourceId && latest.value.source.state == "ready" && latest.value.source.revision == detail.source.revision && latest.value.attachments.any { it.id == attachmentId && it.sha256 == attachment.sha256 && it.sizeBytes == attachment.sizeBytes }) result else TransferResult.Rejected("download_invalidated")
                        else -> readFailure(latest)
                    }
                }
                else -> result
            }
        }
    }

    suspend fun remove(sourceId: String, attachmentId: String): TransferResult<Unit> = try {
        downloads.removeAttachment(sourceId, attachmentId)
        TransferResult.Ok(Unit)
    } catch (_: IOException) { TransferResult.Unavailable("local_cleanup_pending") }

    private fun readFailure(result: ReadResult<*>): TransferResult<Nothing> = when (result) {
        ReadResult.SessionExpired -> TransferResult.SignIn
        is ReadResult.Unavailable -> if (result.code == "not_found") TransferResult.Rejected("not_found") else TransferResult.Unavailable(result.code)
        else -> TransferResult.Unavailable("source_unavailable")
    }
}
