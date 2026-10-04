package com.digifender.adminifender.core.data.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.digifender.adminifender.core.data.session.StoredSession
import com.digifender.adminifender.core.data.session.TokenVault
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Session storage sealed with a non-exportable AES-GCM key in the Android Keystore. The file lives in
 * `noBackupFilesDir` (credential-encrypted storage, never backed up). The key does not require user authentication so
 * background sync can refresh tokens; the passkey prompt is what proves the owner is present.
 */
class KeystoreTokenVault(context: Context, private val alias: String = DEFAULT_ALIAS) : TokenVault {
    private val file = AtomicFile(File(context.noBackupFilesDir, FILE_NAME))

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
    }

    override suspend fun load(): StoredSession? = withContext(Dispatchers.IO) {
        if (!file.baseFile.exists()) return@withContext null
        try {
            val bytes = file.readFully()
            if (bytes.size <= IV_BYTES) throw IOException("truncated")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            cipher.updateAAD(AAD)
            Json.decodeFromString(StoredSession.serializer(), cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES).decodeToString())
        } catch (_: GeneralSecurityException) {
            // A lost or invalidated key cannot be recovered; the owner signs in again.
            file.delete()
            null
        } catch (_: IOException) {
            file.delete()
            null
        } catch (_: SerializationException) {
            file.delete()
            null
        }
    }

    override suspend fun save(session: StoredSession) = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(AAD)
        val sealed = cipher.doFinal(Json.encodeToString(StoredSession.serializer(), session).encodeToByteArray())
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "unexpected_iv" }
        val out = file.startWrite()
        try {
            out.write(iv)
            out.write(sealed)
            file.finishWrite(out)
        } catch (e: IOException) {
            file.failWrite(out)
            throw e
        }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "adminifender.session.v1"
        const val FILE_NAME = "session.v1.bin"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        val AAD = "adminifender-session-v1".encodeToByteArray()
    }
}
