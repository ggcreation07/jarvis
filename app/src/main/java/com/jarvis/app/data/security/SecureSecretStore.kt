package com.jarvis.app.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

interface SecretStorage {
    suspend fun setSecret(key: String, value: String): Boolean
    suspend fun getSecret(key: String): String
}

class SecureSecretStore(context: Context) : SecretStorage {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()
    private var fallbackJvmKey: SecretKey? = null

    companion object {
        private const val PREFS_NAME = "jarvis_secure_secrets_v1"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "jarvis.byo.secrets.aes.v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128

        const val KEY_GEMINI = "gemini_api_key"
        const val KEY_GROQ = "groq_api_key"
        const val KEY_OPENROUTER = "openrouter_api_key"
        const val KEY_NVIDIA = "nvidia_api_key"
        const val KEY_MISTRAL = "mistral_api_key"
        const val KEY_OPENAI = "openai_api_key"
        const val KEY_ANTHROPIC = "anthropic_api_key"
        const val KEY_XAI = "xai_api_key"
        const val KEY_TAVILY = "tavily_api_key"
        const val KEY_PC_SECRET = "pc_shared_secret"
        const val KEY_TRUSTED_PC_HOSTS = "trusted_pc_hosts_json"
    }

    private fun secretKey(): SecretKey = synchronized(lock) {
        val keystoreKey = runCatching {
            val store = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return@runCatching it }

            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            generator.generateKey()
        }.getOrNull()

        if (keystoreKey != null) {
            return keystoreKey
        }

        fallbackJvmKey?.let { return it }
        val fallback = SecretKeySpec(ByteArray(32) { 0x5a.toByte() }, "AES")
        fallbackJvmKey = fallback
        fallback
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8))
        val combined = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        if (combined.size <= IV_BYTES) return ""
        val iv = combined.copyOfRange(0, IV_BYTES)
        val ciphertext = combined.copyOfRange(IV_BYTES, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        val plain = cipher.doFinal(ciphertext)
        return String(plain, StandardCharsets.UTF_8)
    }

    override suspend fun setSecret(key: String, value: String) = withContext(Dispatchers.IO) {
        val trimmed = value.trim()
        synchronized(lock) {
            if (trimmed.isEmpty()) {
                prefs.edit().remove(key).remove("status_$key").commit()
            } else {
                prefs.edit().putString(key, encrypt(trimmed)).commit()
            }
        }
    }

    override suspend fun getSecret(key: String): String = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val raw = prefs.getString(key, null) ?: return@withContext ""
            try {
                decrypt(raw)
            } catch (_: Exception) {
                ""
            }
        }
    }

    suspend fun setKeyStatus(key: String, status: com.jarvis.app.data.ai.KeyCheckStatus) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (status == com.jarvis.app.data.ai.KeyCheckStatus.EMPTY) {
                prefs.edit().remove("status_$key").commit()
            } else {
                prefs.edit().putString("status_$key", status.name).commit()
            }
        }
    }

    suspend fun getKeyStatus(key: String): com.jarvis.app.data.ai.KeyCheckStatus? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val name = prefs.getString("status_$key", null) ?: return@withContext null
            runCatching { com.jarvis.app.data.ai.KeyCheckStatus.valueOf(name) }.getOrNull()
        }
    }

    suspend fun hasSecret(key: String): Boolean = getSecret(key).isNotBlank()

    suspend fun allConfiguredKeys(): Map<String, Boolean> = withContext(Dispatchers.IO) {
        listOf(
            KEY_GEMINI,
            KEY_GROQ,
            KEY_OPENROUTER,
            KEY_NVIDIA,
            KEY_MISTRAL,
            KEY_OPENAI,
            KEY_ANTHROPIC,
            KEY_XAI,
            KEY_TAVILY
        ).associateWith { getSecret(it).isNotBlank() }
    }
}
