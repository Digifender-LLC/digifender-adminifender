package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.files.FileCipher
import com.digifender.adminifender.core.data.files.SealedFileException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.fail
import org.junit.Test

class FileCipherTest {
    private val keys = SoftKeys()
    private val cipher = FileCipher(keys)
    private val id = "0b4e6f8a-2c3d-4e5f-8a9b-1c2d3e4f5a6b"
    private val plain = ByteArray(100_000) { (it * 31).toByte() }
    private val sha = sha256Of(plain)

    private fun seal(bytes: ByteArray = plain, attachmentId: String = id, hash: String = sha): ByteArray {
        val out = ByteArrayOutputStream()
        cipher.seal(out, attachmentId, hash).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun open(sealed: ByteArray, attachmentId: String = id, hash: String = sha, max: Long = 1_000_000, using: FileCipher = cipher) =
        using.open(ByteArrayInputStream(sealed), attachmentId, hash, max)

    private fun assertRefused(block: () -> Unit) {
        try {
            block()
            fail("expected the file to be refused")
        } catch (_: SealedFileException) {
            // refused
        }
    }

    @Test
    fun aSealedFileOpensToTheSameBytesAndIsOnlyAsLargeAsPromised() {
        val sealed = seal()
        assertArrayEquals(plain, open(sealed))
        assertEquals(cipher.sealedSize(plain.size.toLong()), sealed.size.toLong())
        // The plaintext does not appear in what is stored.
        assertEquals(-1, String(sealed, Charsets.ISO_8859_1).indexOf(String(plain.copyOfRange(0, 64), Charsets.ISO_8859_1)))
        assertArrayEquals(ByteArray(0), open(seal(ByteArray(0), hash = sha256Of(ByteArray(0))), hash = sha256Of(ByteArray(0))))
    }

    @Test
    fun everySealingUsesAFreshNonce() {
        assertNotEquals(seal().toList().subList(5, 17), seal().toList().subList(5, 17))
    }

    @Test
    fun aChangedByteAnywhereIsRefused() {
        val sealed = seal()
        for (position in listOf(0, 4, 10, 17, 5_000, sealed.size - 1)) {
            val damaged = sealed.copyOf().also { it[position] = (it[position].toInt() xor 1).toByte() }
            assertRefused { open(damaged) }
        }
    }

    @Test
    fun aFileMovedUnderAnotherAttachmentOrChecksumIsRefused() {
        val sealed = seal()
        assertRefused { open(sealed, attachmentId = "1b4e6f8a-2c3d-4e5f-8a9b-1c2d3e4f5a6b") }
        assertRefused { open(sealed, hash = sha256Of(ByteArray(3))) }
    }

    @Test
    fun aReplacedOrLostKeyMakesTheFileUnreadable() {
        val sealed = seal()
        assertRefused { open(sealed, using = FileCipher(SoftKeys())) }
    }

    @Test
    fun truncatedOversizedAndForeignFilesAreRefused() {
        val sealed = seal()
        assertRefused { open(sealed.copyOf(8)) }
        assertRefused { open(sealed.copyOf(sealed.size - 1)) }
        assertRefused { open(sealed, max = 99_999) }
        assertRefused { open(ByteArray(64) { 7 }) }
        assertRefused { open(ByteArray(0)) }
    }
}
