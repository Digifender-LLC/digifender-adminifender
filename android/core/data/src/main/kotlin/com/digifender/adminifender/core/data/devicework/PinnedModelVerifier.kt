package com.digifender.adminifender.core.data.devicework

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The release must supply an independently pinned manifest digest; no artifact or hash is fabricated here. */
@Serializable
data class LocalModelManifest(
    val modelId: String,
    val sha256: String,
    val byteLength: Long,
    val runtimeVersion: String,
    val contextTokens: Int,
    val inputLimit: Int,
    val outputLimit: Int,
    val tokenizerDigest: String,
)

sealed interface ModelVerification {
    /** Verification alone does not mean loaded: the CPU adapter must successfully initialize before readiness. */
    data class Verified(val manifest: LocalModelManifest, val manifestHash: String) : ModelVerification
    data class Unavailable(val reason: String) : ModelVerification
}

/** Checks both the manifest pin and artifact bytes off the UI thread. It never downloads or opts in for users. */
class PinnedModelVerifier(private val privateModelDirectory: File, private val runtimeVersion: String) {
    private val json = Json { ignoreUnknownKeys = true }
    suspend fun verify(manifestBytes: ByteArray, pinnedManifestHash: String, artifact: File): ModelVerification = withContext(Dispatchers.IO) {
        if (manifestBytes.size > 32 * 1024 || !DEVICE_HASH.matches(pinnedManifestHash) || digest(manifestBytes) != pinnedManifestHash) return@withContext ModelVerification.Unavailable("manifest_hash_mismatch")
        val manifest = try { json.decodeFromString(LocalModelManifest.serializer(), manifestBytes.decodeToString(throwOnInvalidSequence = true)) }
        catch (_: Exception) { return@withContext ModelVerification.Unavailable("manifest_invalid") }
        if (!manifest.modelId.matches(Regex("^[A-Za-z0-9._:/-]{1,100}$")) || !DEVICE_HASH.matches(manifest.sha256) || !DEVICE_HASH.matches(manifest.tokenizerDigest) ||
            manifest.byteLength !in 1..MAX_MODEL_BYTES || manifest.runtimeVersion != runtimeVersion ||
            manifest.contextTokens != 2048 || manifest.inputLimit !in 1..1536 || manifest.outputLimit !in 1..256) {
            return@withContext ModelVerification.Unavailable("manifest_unsupported")
        }
        try {
            val file = artifact.canonicalFile
            if (!file.toPath().startsWith(privateModelDirectory.canonicalFile.toPath()) || !file.isFile) return@withContext ModelVerification.Unavailable("model_missing")
            if (file.length() != manifest.byteLength) return@withContext ModelVerification.Unavailable("model_size_mismatch")
            val hash = MessageDigest.getInstance("SHA-256")
            var total = 0L
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > manifest.byteLength) return@withContext ModelVerification.Unavailable("model_size_mismatch")
                    hash.update(buffer, 0, read)
                }
            }
            if (total != manifest.byteLength || hex(hash.digest()) != manifest.sha256) return@withContext ModelVerification.Unavailable("model_hash_mismatch")
            ModelVerification.Verified(manifest, pinnedManifestHash)
        } catch (_: IOException) { ModelVerification.Unavailable("model_unavailable") }
    }

    private companion object {
        const val MAX_MODEL_BYTES = 2L * 1024 * 1024 * 1024
        fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        fun digest(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
