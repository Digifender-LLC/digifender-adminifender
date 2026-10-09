package com.digifender.adminifender.inference

import android.content.Context
import android.net.Uri
import com.digifender.adminifender.core.data.device.ArtifactCheck
import com.digifender.adminifender.core.data.device.LocalModel
import com.digifender.adminifender.core.data.device.ModelManifest
import com.digifender.adminifender.core.data.device.verifyArtifact
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What the owner sees about the local model. */
sealed interface ModelState {
    /** This build has no pinned manifest, so no local model can be used. */
    data object NotConfigured : ModelState

    data class NotInstalled(val modelId: String) : ModelState

    data class Installing(val modelId: String) : ModelState

    data class Installed(val modelId: String) : ModelState

    data class Rejected(val modelId: String, val check: ArtifactCheck) : ModelState
}

/**
 * The local model file: chosen by the owner, copied into no-backup app storage while hashing, and activated only
 * when size and SHA-256 match the pinned manifest. No model bytes ship in the app or source control, and no
 * repository token is ever on the phone.
 */
class ModelStore(context: Context, manifestJson: String?) {
    private val app = context.applicationContext
    val manifest: ModelManifest? = manifestJson?.let(ModelManifest::parse)
    private val dir = File(app.noBackupFilesDir, "models")
    private val lock = Mutex()
    private var loaded: LiteRtLmModel? = null

    private fun file(m: ModelManifest) = File(dir, "${m.sha256}.litertlm")

    private val _state = MutableStateFlow(initial())
    val state: StateFlow<ModelState> = _state.asStateFlow()

    private fun initial(): ModelState {
        val m = manifest ?: return ModelState.NotConfigured
        val f = file(m)
        // Installation only renames a file into place after it verified; a size mismatch means it was altered.
        return if (f.isFile && f.length() == m.byteLength) ModelState.Installed(m.modelId) else ModelState.NotInstalled(m.modelId)
    }

    suspend fun install(uri: Uri): ModelState = lock.withLock {
        val m = manifest ?: return ModelState.NotConfigured
        _state.value = ModelState.Installing(m.modelId)
        val result = withContext(Dispatchers.IO) {
            dir.mkdirs()
            val temp = File(dir, "${m.sha256}.partial")
            val check = app.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { out -> verifyArtifact(Tee(input, out), m) }
            } ?: ArtifactCheck.WRONG_SIZE
            if (check == ArtifactCheck.OK && temp.renameTo(file(m))) {
                ModelState.Installed(m.modelId)
            } else {
                temp.delete()
                ModelState.Rejected(m.modelId, if (check == ArtifactCheck.OK) ArtifactCheck.WRONG_SIZE else check)
            }
        }
        _state.value = result
        result
    }

    /** The loaded model, or null when none is installed. */
    suspend fun model(): LocalModel? = lock.withLock {
        val m = manifest ?: return null
        if (_state.value !is ModelState.Installed) return null
        loaded ?: LiteRtLmModel(file(m), m, File(app.cacheDir, "litertlm")).also { loaded = it }
    }

    /** Removes the model file and unloads it. */
    suspend fun remove() = lock.withLock {
        loaded?.close()
        loaded = null
        withContext(Dispatchers.IO) { dir.listFiles()?.forEach { it.delete() } }
        _state.value = initial()
    }

    /** Copies everything read into [out], so the file is hashed in the same pass that stores it. */
    private class Tee(input: InputStream, private val out: OutputStream) : FilterInputStream(input) {
        override fun read(): Int = super.read().also { if (it >= 0) out.write(it) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) out.write(b, off, it) }
    }
}
