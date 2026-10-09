package com.digifender.adminifender.core.data.platform

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.digifender.adminifender.core.data.files.FileKeys
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The AES-256-GCM key that seals downloaded attachments: non-exportable and held by the Android Keystore, under an
 * alias of its own so that losing or rotating it never touches the session. The key does not require user
 * authentication, because downloads run in the background; the files it protects are app-private and are removed at
 * sign-out. A key that is gone makes every sealed file unreadable, which the app treats as "download it again".
 */
class KeystoreFileKeys(private val alias: String = DEFAULT_ALIAS) : FileKeys {
    @Synchronized
    override fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "adminifender.files.v1"
    }
}
