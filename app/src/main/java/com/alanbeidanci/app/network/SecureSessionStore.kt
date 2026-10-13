package com.alanbeidanci.app.network

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureSessionStore(context: Context) {
    private val sessionPreferences = context.getSharedPreferences("alan_secure_session", Context.MODE_PRIVATE)
    private val devicePreferences = context.getSharedPreferences("alan_device", Context.MODE_PRIVATE)
    private val _session = MutableStateFlow(loadSession())

    val session: StateFlow<UserSession?> = _session

    val deviceId: String by lazy {
        devicePreferences.getString(DEVICE_ID, null) ?: "android-${UUID.randomUUID()}".also {
            devicePreferences.edit().putString(DEVICE_ID, it).apply()
        }
    }

    @Synchronized
    fun nextClientSeq(): Long {
        val next = devicePreferences.getLong(CLIENT_SEQ, 0L) + 1L
        devicePreferences.edit().putLong(CLIENT_SEQ, next).apply()
        return next
    }

    @Synchronized
    fun ensureLocalSession() {
        val current = _session.value
        if (current?.userId == LOCAL_USER_ID && current.mainlineEnabled) return
        save(
            UserSession(
                userId = LOCAL_USER_ID,
                username = "本机",
                accessToken = "local",
                accessTokenExpiresAt = "9999-12-31T00:00:00Z",
                refreshToken = "local",
                refreshTokenExpiresAt = "9999-12-31T00:00:00Z",
                mainlineEnabled = true,
            ),
        )
    }

    @Synchronized
    fun save(value: UserSession) {
        val json = JSONObject()
            .put("userId", value.userId)
            .put("username", value.username)
            .put("accessToken", value.accessToken)
            .put("accessTokenExpiresAt", value.accessTokenExpiresAt)
            .put("refreshToken", value.refreshToken)
            .put("refreshTokenExpiresAt", value.refreshTokenExpiresAt)
            .put("mainlineEnabled", value.mainlineEnabled)
            .toString()
        sessionPreferences.edit().putString(SESSION_PAYLOAD, encrypt(json)).apply()
        _session.value = value
    }

    @Synchronized
    fun clear() {
        sessionPreferences.edit().clear().apply()
        _session.value = null
    }

    @Synchronized
    fun updateMainlineEnabled(enabled: Boolean) {
        val current = _session.value ?: return
        if (current.mainlineEnabled == enabled) return
        save(current.copy(mainlineEnabled = enabled))
    }

    private fun loadSession(): UserSession? {
        val payload = sessionPreferences.getString(SESSION_PAYLOAD, null) ?: return null
        return runCatching {
            val json = JSONObject(decrypt(payload))
            UserSession(
                userId = json.getLong("userId"),
                username = json.getString("username"),
                accessToken = json.getString("accessToken"),
                accessTokenExpiresAt = json.getString("accessTokenExpiresAt"),
                refreshToken = json.getString("refreshToken"),
                refreshTokenExpiresAt = json.getString("refreshTokenExpiresAt"),
                mainlineEnabled = json.optBoolean("mainlineEnabled", true),
            )
        }.getOrElse {
            sessionPreferences.edit().clear().apply()
            null
        }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val parts = value.split('.', limit = 2)
        require(parts.size == 2)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)),
        )
        return String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val LOCAL_USER_ID = 1L
        const val KEY_ALIAS = "alan_session_aes_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val SESSION_PAYLOAD = "encrypted_session"
        const val DEVICE_ID = "device_id"
        const val CLIENT_SEQ = "client_seq"
    }
}
