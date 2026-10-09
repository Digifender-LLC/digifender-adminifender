package com.digifender.adminifender.core.data.files

import android.annotation.SuppressLint
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * The app-private directory that holds sealed files under opaque names. Names carry no attachment ID, file name or
 * type, and a name is never reused, so a late writer can never land bytes under a name a newer row owns. Files here are
 * not trusted just because they exist: a row names the ones that count and [sweep] removes the rest.
 */
class BlobDirectory(private val dir: File) {
    init {
        dir.mkdirs()
    }

    private val name = Regex("^[0-9a-f]{32}\\.(tmp|enc)$")

    /** A fresh, empty file for a download in progress. */
    fun newTemp(): File = File(dir, UUID.randomUUID().toString().replace("-", "") + ".tmp").also { it.createNewFile() }

    /** Makes a finished temp file a sealed blob and returns its name. */
    fun finish(temp: File): String {
        require(temp.parentFile?.canonicalFile == dir.canonicalFile && temp.name.endsWith(".tmp")) { "not_a_temp_file" }
        val finalName = temp.name.removeSuffix(".tmp") + ".enc"
        val target = File(dir, finalName)
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath())
        }
        return finalName
    }

    /** The file for a stored name, or null for a name this directory could not have made (no path separators, no traversal). */
    fun file(blob: String): File? = blob.takeIf { name.matches(it) }?.let { File(dir, it) }

    fun exists(blob: String) = file(blob)?.isFile == true

    fun delete(blob: String): Boolean = file(blob)?.let { !it.exists() || it.delete() } ?: true

    fun delete(blobs: Collection<String>) {
        blobs.forEach { delete(it) }
    }

    fun names(): Set<String> = dir.listFiles().orEmpty().filter { it.isFile }.map { it.name }.toSet()

    /**
     * Removes every file the database does not name and that was last written before [olderThanMs], and returns how many.
     * The cut-off keeps a download that is writing right now (or has just finished and is about to be committed) safe.
     */
    fun sweep(keep: Set<String>, olderThanMs: Long): Int {
        var removed = 0
        for (file in dir.listFiles().orEmpty()) {
            if (file.name !in keep && file.lastModified() < olderThanMs && file.delete()) removed += 1
        }
        return removed
    }

    fun deleteAll(): Boolean {
        var all = true
        for (file in dir.listFiles().orEmpty()) if (!file.delete()) all = false
        return all
    }

    /**
     * Free space for the next download, so a full phone is reported before bytes are written, not after. The app never asks
     * Android to clear other apps' caches for room, so the plain free-space figure is the right one (hence the suppression).
     */
    @SuppressLint("UsableSpace")
    fun usableBytes(): Long = dir.usableSpace

    @Throws(IOException::class)
    fun length(blob: String): Long = file(blob)?.length() ?: throw IOException("missing")
}
