package com.digifender.adminifender.core.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.digifender.adminifender.core.data.platform.KeystoreTokenVault
import com.digifender.adminifender.core.data.session.IssuedTokens
import com.digifender.adminifender.core.data.session.StoredSession
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeystoreTokenVaultTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val file = File(context.noBackupFilesDir, "session.v1.bin")
    private val session = StoredSession("device-1", IssuedTokens("afa_synthetic", "afr_synthetic", 1L, 2L))

    @Test
    fun roundTripsAndKeepsPlaintextOffDisk() = runTest {
        file.delete()
        val vault = KeystoreTokenVault(context, alias = "adminifender.session.test")
        assertNull(vault.load())
        vault.save(session)
        assertEquals(session, KeystoreTokenVault(context, alias = "adminifender.session.test").load())
        assertFalse(file.readBytes().decodeToString().contains("afr_synthetic"))
    }

    @Test
    fun aCorruptFileIsDiscarded() = runTest {
        val vault = KeystoreTokenVault(context, alias = "adminifender.session.test")
        vault.save(session)
        file.writeBytes(ByteArray(40) { 7 })
        assertNull(vault.load())
        assertFalse(file.exists())
    }
}
