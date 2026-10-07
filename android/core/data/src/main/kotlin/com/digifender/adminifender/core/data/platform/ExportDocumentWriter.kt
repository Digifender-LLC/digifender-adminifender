package com.digifender.adminifender.core.data.platform

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.digifender.adminifender.core.data.repository.ExportCopyWriter
import com.digifender.adminifender.core.data.repository.ExportDestination
import com.digifender.adminifender.core.data.repository.ExportRepository
import com.digifender.adminifender.core.data.repository.ExportSaveOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Only accepts a just-created URI from ActivityResultContracts.CreateDocument after the owner's explicit Save.
 * The caller never passes an existing document. Streams are closed; failed/cancelled writes attempt to delete
 * only this new partial destination, never its directory or any previously chosen file.
 */
class ExportDocumentWriter(private val resolver: ContentResolver, exports: ExportRepository) {
    private val writer = ExportCopyWriter(exports)

    suspend fun write(exportId: String, createdDocument: Uri?): ExportSaveOutcome {
        if (createdDocument == null) return ExportSaveOutcome.Cancelled
        if (createdDocument.scheme != "content") return ExportSaveOutcome.Failed("invalid_destination", partialRemoved = false)
        return writer.write(exportId, object : ExportDestination {
            override fun open() = resolver.openOutputStream(createdDocument, "wt")
            override fun remove() = DocumentsContract.deleteDocument(resolver, createdDocument)
        })
    }

    /** A picker result arriving after logout or a changed selection is cleaned up without copying anything. */
    suspend fun discardCreated(createdDocument: Uri?): ExportSaveOutcome {
        if (createdDocument == null) return ExportSaveOutcome.Cancelled
        val removed = withContext(Dispatchers.IO) {
            try { createdDocument.scheme == "content" && DocumentsContract.deleteDocument(resolver, createdDocument) } catch (_: Exception) { false }
        }
        return ExportSaveOutcome.Failed("selection_changed", partialRemoved = removed)
    }
}
