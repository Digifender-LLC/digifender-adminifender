package com.digifender.adminifender.core.data.files

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The key that seals downloaded files. In the app it is a non-exportable Android Keystore key, separate from the session key. */
fun interface FileKeys {
    fun key(): SecretKey
}

/** Why a sealed file could not be read back. All of them mean the phone's copy is unusable and must be fetched again. */
class SealedFileException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Standard AES-256-GCM, one message per file: `"ADMF"`, a version byte, the 12-byte nonce the cipher chose, then the
 * ciphertext and the 128-bit tag. The associated data names the attachment and its SHA-256, so a sealed file moved
 * under another attachment's row fails to open. Nothing here is segmented or home-made: a file is at most
 * [MAX_ATTACHMENT_BYTES], so it is opened whole, authenticated before any byte is used.
 */
class FileCipher(private val keys: FileKeys) {
    /** Sealing stream: write the plaintext, then [OutputStream.close] to finish the tag. Closing without writing all of it is the caller's to prevent. */
    fun seal(sink: OutputStream, attachmentId: String, sha256: String): OutputStream {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keys.key())
        cipher.updateAAD(aad(attachmentId, sha256))
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "unexpected_iv" }
        sink.write(MAGIC)
        sink.write(VERSION.toInt())
        sink.write(iv)
        return CipherOutputStream(sink, cipher)
    }

    /** The authenticated plaintext, at most [maxPlain] bytes. Throws [SealedFileException] for a file that is damaged, foreign, over the bound or sealed under another key. */
    fun open(input: InputStream, attachmentId: String, sha256: String, maxPlain: Long): ByteArray {
        val header = ByteArray(MAGIC.size + 1 + IV_BYTES)
        try {
            var read = 0
            while (read < header.size) {
                val n = input.read(header, read, header.size - read)
                if (n < 0) throw EOFException()
                read += n
            }
        } catch (e: EOFException) {
            throw SealedFileException("truncated", e)
        }
        if (!header.copyOfRange(0, MAGIC.size).contentEquals(MAGIC) || header[MAGIC.size] != VERSION) throw SealedFileException("not_a_sealed_file")
        val iv = header.copyOfRange(MAGIC.size + 1, header.size)
        val limit = maxPlain + TAG_BYTES
        val body = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (body.size() + n > limit) throw SealedFileException("too_large")
            body.write(buffer, 0, n)
        }
        if (body.size() < TAG_BYTES) throw SealedFileException("truncated")
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, keys.key(), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(aad(attachmentId, sha256))
            cipher.doFinal(body.toByteArray())
        } catch (e: GeneralSecurityException) {
            // A wrong key, a damaged byte or another attachment's AAD all end here; the tag does not say which.
            throw SealedFileException("authentication_failed", e)
        }
    }

    /** The on-disk size of a sealed file holding [plain] bytes. */
    fun sealedSize(plain: Long) = HEADER_BYTES + plain + TAG_BYTES

    private fun aad(attachmentId: String, sha256: String) = "adminifender-file-v1\n$attachmentId\n$sha256".encodeToByteArray()

    companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val TAG_BYTES = TAG_BITS / 8
        private val MAGIC = "ADMF".encodeToByteArray()
        private const val VERSION: Byte = 1
        private const val BUFFER = 64 * 1024
        const val HEADER_BYTES = 4 + 1 + IV_BYTES
    }
}

/** The largest raw attachment the Worker admits (backend admission `MAX_RAW_BYTES`, 25 MiB). It is the download bound; the 20 MiB extraction limit is not. */
const val MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024

/** Baseline: attachments are downloaded on demand into a 500 MiB least-recently-used cache. */
const val ATTACHMENT_CACHE_BYTES = 500L * 1024 * 1024
