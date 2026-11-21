package com.jarvis.app.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec

class DeviceIdentityManager(private val context: Context) {
    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "jarvis.remote.device.identity.v1"
        private const val PREFS = "jarvis_device_identity_meta_v1"
        private const val COUNTER = "outbound_counter"
    }

    data class Info(
        val deviceId: String,
        val publicKey: String,
        val fingerprint: String
    )

    data class Signed(
        val deviceId: String,
        val counter: Long,
        val signature: String,
        val publicKey: String = "",
        val fingerprint: String = "",
        val deviceName: String = ""
    )

    private val lock = Any()
    private val preferences by lazy {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun ensureKey() = synchronized(lock) {
        val store = keyStore()
        if (store.containsAlias(KEY_ALIAS)) return@synchronized
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        generator.generateKeyPair()
    }

    private fun publicDer(): ByteArray {
        ensureKey()
        return keyStore().getCertificate(KEY_ALIAS).publicKey.encoded
    }

    private fun digest(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun b64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun fromB64(text: String): ByteArray =
        Base64.decode(text, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun info(): Info {
        val public = publicDer()
        val hash = digest(public)
        return Info(
            deviceId = "phone-${hex(hash).take(32)}",
            publicKey = b64(public),
            fingerprint = "sha256:${b64(hash)}"
        )
    }

    private fun nextCounter(): Long = synchronized(lock) {
        val current = preferences.getLong(COUNTER, 0L)
        if (current == Long.MAX_VALUE) throw IllegalStateException("Identity counter exhausted")
        val next = current + 1L
        if (!preferences.edit().putLong(COUNTER, next).commit()) {
            throw IllegalStateException("Could not persist identity counter")
        }
        next
    }

    private fun sign(material: String): String {
        ensureKey()
        val privateKey = keyStore().getKey(KEY_ALIAS, null) as PrivateKey
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(privateKey)
        signer.update(material.toByteArray(StandardCharsets.UTF_8))
        return b64(signer.sign())
    }

    fun signAuth(hostId: String, nonce: String): Signed {
        val identity = info()
        val counter = nextCounter()
        val material = "aura-auth-v1\n${hostId.trim()}\n${identity.deviceId}\n${nonce.trim()}\n$counter"
        return Signed(identity.deviceId, counter, sign(material), identity.publicKey, identity.fingerprint)
    }

    fun signEnvelope(
        connectionNonce: String,
        messageType: String,
        taskId: String,
        payloadDigest: String
    ): Signed {
        val identity = info()
        val counter = nextCounter()
        val material =
            "aura-envelope-v1\ndevice\n${identity.deviceId}\n${connectionNonce.trim()}\n$counter\n${messageType.trim()}\n${taskId.trim()}\n${payloadDigest.trim()}"
        return Signed(identity.deviceId, counter, sign(material))
    }

    fun verifyHostEnvelope(
        publicKey: String,
        expectedFingerprint: String,
        hostId: String,
        connectionNonce: String,
        counter: Long,
        messageType: String,
        taskId: String,
        payloadDigest: String,
        signature: String
    ): Boolean = try {
        val encoded = fromB64(publicKey.trim())
        val actual = "sha256:${b64(digest(encoded))}"
        if (actual != expectedFingerprint.trim()) return false
        val key: PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))
        val material =
            "aura-envelope-v1\nhost\n$hostId\n$connectionNonce\n$counter\n$messageType\n$taskId\n$payloadDigest"
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(key)
        verifier.update(material.toByteArray(StandardCharsets.UTF_8))
        verifier.verify(fromB64(signature))
    } catch (_: Exception) {
        false
    }
}
