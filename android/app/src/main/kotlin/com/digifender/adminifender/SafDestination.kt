package com.digifender.adminifender

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.digifender.adminifender.core.data.files.Destination
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream

/**
 * A file the owner created through the system document picker (Storage Access Framework). The app asks for no storage
 * permission and writes only where the owner pointed: the picker grants this one document. Bytes written here leave the
 * app's private storage; the app cannot take them back, and says so.
 */
class SafDestination(private val resolver: ContentResolver, private val uri: Uri) : Destination {
    override val label: String = try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    } catch (_: RuntimeException) {
        null
    }?.take(120) ?: "document"

    override fun open(): OutputStream = resolver.openOutputStream(uri, "wt") ?: throw IOException("no_output_stream")

    override fun sizeBytes(): Long? = try {
        resolver.openFileDescriptor(uri, "r")?.use { it.statSize }?.takeIf { it >= 0 }
    } catch (_: FileNotFoundException) {
        null
    } catch (_: RuntimeException) {
        null
    }

    override fun discard(): Boolean = try {
        DocumentsContract.deleteDocument(resolver, uri)
    } catch (_: FileNotFoundException) {
        // Nothing there to remove.
        true
    } catch (_: RuntimeException) {
        false
    }
}
