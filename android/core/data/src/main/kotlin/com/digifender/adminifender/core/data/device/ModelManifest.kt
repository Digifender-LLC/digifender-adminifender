package com.digifender.adminifender.core.data.device

import java.io.InputStream
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Signed/pinned description of the local model (lld.md#triage, tdd.md#version-freeze-and-model-manifest). The hash
 * and size identify the exact artifact; a changed hash is a new model that needs its own evaluation.
 */
@Serializable
data class ModelManifest(
    val modelId: String,
    val artifactUrl: String,
    val sha256: String,
    val byteLength: Long,
    val licenseUrl: String,
    val runtimeVersion: String,
    val contextTokens: Int,
    val inputLimit: Int,
    val outputLimit: Int,
    val tokenizerDigest: String,
) {
    /** Problems that make the manifest unusable; empty when it can be used. */
    fun problems(): List<String> = buildList {
        if (!Regex("^[A-Za-z0-9._:/-]{1,100}$").matches(modelId)) add("modelId")
        if (!HEX64.matches(sha256)) add("sha256")
        if (byteLength <= 0) add("byteLength")
        if (contextTokens <= 0 || inputLimit <= 0 || outputLimit <= 0 || inputLimit + outputLimit > contextTokens) add("limits")
    }

    /** Input and output caps actually applied: the manifest's, never above the v1 local limits. */
    val appliedInputLimit: Int get() = minOf(inputLimit, LOCAL_INPUT_TOKENS)
    val appliedOutputLimit: Int get() = minOf(outputLimit, LOCAL_OUTPUT_TOKENS)

    /** SHA-256 of the manifest's canonical JSON; the server records it with every local result. */
    fun hash(): String = sha256Hex(canonical.encodeToString(serializer(), this).toByteArray())

    companion object {
        const val LOCAL_INPUT_TOKENS = 1536
        const val LOCAL_OUTPUT_TOKENS = 256
        private val HEX64 = Regex("^[0-9a-f]{64}$")
        private val canonical = Json { encodeDefaults = true }
        private val lenient = Json { ignoreUnknownKeys = false }

        fun parse(text: String): ModelManifest? = runCatching { lenient.decodeFromString(serializer(), text) }.getOrNull()?.takeIf { it.problems().isEmpty() }
    }
}

enum class ArtifactCheck { OK, WRONG_SIZE, WRONG_HASH }

/** Streams [input] once, refusing anything beyond the declared size, and compares its SHA-256 to the manifest. */
fun verifyArtifact(input: InputStream, manifest: ModelManifest): ArtifactCheck {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(1 shl 16)
    var total = 0L
    while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        total += n
        if (total > manifest.byteLength) return ArtifactCheck.WRONG_SIZE
        digest.update(buffer, 0, n)
    }
    if (total != manifest.byteLength) return ArtifactCheck.WRONG_SIZE
    return if (digest.digest().toHex() == manifest.sha256) ArtifactCheck.OK else ArtifactCheck.WRONG_HASH
}

internal fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

internal fun sha256Hex(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()
