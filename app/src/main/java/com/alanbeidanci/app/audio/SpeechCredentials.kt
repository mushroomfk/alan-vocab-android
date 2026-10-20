package com.alanbeidanci.app.audio

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The user's speech key is device-local, encrypted, and excluded from every backup. */
object SpeechCredentials {
    private const val PREFERENCES = "alan_speech_credentials"
    private const val PAYLOAD = "encrypted_key"
    private const val KEY_ALIAS = "alan_speech_aes_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun isConfigured(context: Context): Boolean = preferences(context).contains(PAYLOAD)

    @Synchronized
    fun read(context: Context): String {
        val payload = preferences(context).getString(PAYLOAD, null) ?: return ""
        // An unavailable keystore must not erase the saved credential.
        return runCatching {
            val parts = payload.split('.', limit = 2)
            require(parts.size == 2)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrDefault("")
    }

    @Synchronized
    fun save(context: Context, value: String) {
        val key = value.trim()
        require(key.isNotBlank() && '\n' !in key && '\r' !in key) { "请输入有效的 API Key" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val payload = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." +
            Base64.encodeToString(cipher.doFinal(key.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(preferences(context).edit().putString(PAYLOAD, payload).commit()) { "Key 未保存" }
    }

    @Synchronized
    fun clear(context: Context) {
        check(preferences(context).edit().remove(PAYLOAD).commit()) { "Key 未删除" }
    }

    private fun preferences(context: Context) = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
            generateKey()
        }
    }
}
