package com.digifender.adminifender.core.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.digifender.adminifender.core.data.files.FileCipher
import com.digifender.adminifender.core.data.files.SealedFileException
import com.digifender.adminifender.core.data.platform.KeystoreFileKeys
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/** The attachment key is a separate, non-exportable Android Keystore key. Compiled by CI; not run (no device here). */
@RunWith(AndroidJUnit4::class)
class KeystoreFileKeysTest {
    private val keys = KeystoreFileKeys("adminifender.files.test")
    private val cipher = FileCipher(keys)

    @Test
    fun sealsAndOpensWithAKeyThatCannotBeExported() {
        val plain = ByteArray(200_000) { (it % 251).toByte() }
        val sealed = ByteArrayOutputStream().also { out -> cipher.seal(out, "att", "sha").use { it.write(plain) } }.toByteArray()
        assertArrayEquals(plain, cipher.open(ByteArrayInputStream(sealed), "att", "sha", 1_000_000))
        assertNull(keys.key().encoded)
    }

    @Test
    fun aFileSealedForAnotherAttachmentDoesNotOpen() {
        val sealed = ByteArrayOutputStream().also { out -> cipher.seal(out, "att", "sha").use { it.write(ByteArray(10)) } }.toByteArray()
        try {
            cipher.open(ByteArrayInputStream(sealed), "other", "sha", 1_000)
            fail("expected a refusal")
        } catch (_: SealedFileException) {
            // as it must
        }
    }
}
