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

interface RemoteIdentitySigner {
    fun info(): DeviceIdentityManager.Info
    fun signAuth(hostId: String, nonce: String): DeviceIdentityManager.Signed
    fun signEnvelope(
        connectionNonce: String,
        messageType: String,
        taskId: String,
        payloadDigest: String
    ): DeviceIdentityManager.Signed
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
    ): Boolean
}

class DeviceIdentityManager(private val context: Context) : RemoteIdentitySigner {
    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "jarvis.remote.device.identity.v1"
        private const val PREFS = "jarvis_device_identity_meta_v1"
        private const val COUNTER = "outbound_counter"

        fun sha256(bytes: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(bytes)

        fun sha256Hex(bytes: ByteArray): String =
            sha256(bytes).joinToString("") { "%02x".format(it) }

        fun sha256Hex(text: String): String =
            sha256Hex(text.toByteArray(StandardCharsets.UTF_8))

        fun b64Url(bytes: ByteArray): String =
            Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

        fun decodeB64(text: String): ByteArray = try {
            Base64.decode(text.trim(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        } catch (_: IllegalArgumentException) {
            Base64.decode(text.trim(), Base64.DEFAULT)
        }

        fun computeFingerprint(publicKeyB64: String): String? = try {
            val encoded = decodeB64(publicKeyB64)
            if (encoded.isEmpty()) null
            else {
                KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))
                "sha256:${b64Url(sha256(encoded))}"
            }
        } catch (_: Exception) {
            null
        }

        fun canonicalTaskPayload(taskId: String, kind: String, goal: String): String =
            "task_id=${taskId.trim()}\nkind=${kind.trim()}\ngoal=$goal"

        fun canonicalInputPayload(
            inputId: String,
            action: String,
            x: Int?,
            y: Int?,
            text: String?
        ): String = "input_id=${inputId.trim()}\naction=${action.trim()}\nx=${x ?: ""}\ny=${y ?: ""}\ntext=${text ?: ""}"

        fun canonicalResponsePayload(status: String, summary: String): String =
            "status=${status.trim()}\nsummary=$summary"

        fun verifyHostEnvelopeStatic(
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
            if (counter <= 0L || hostId.isBlank() || connectionNonce.isBlank() ||
                messageType.isBlank() || taskId.isBlank() || payloadDigest.isBlank() || signature.isBlank()
            ) {
                false
            } else {
                val encoded = decodeB64(publicKey)
                val actual = "sha256:${b64Url(sha256(encoded))}"
                if (actual != expectedFingerprint.trim()) {
                    false
                } else {
                    val key: PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))
                    val material =
                        "aura-envelope-v1\nhost\n${hostId.trim()}\n${connectionNonce.trim()}\n$counter\n${messageType.trim()}\n${taskId.trim()}\n${payloadDigest.trim()}"
                    val verifier = Signature.getInstance("SHA256withECDSA")
                    verifier.initVerify(key)
                    verifier.update(material.toByteArray(StandardCharsets.UTF_8))
                    verifier.verify(decodeB64(signature))
                }
            }
        } catch (_: Exception) {
            false
        }
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

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    override fun info(): Info {
        val public = publicDer()
        val hash = sha256(public)
        return Info(
            deviceId = "phone-${hex(hash).take(32)}",
            publicKey = b64Url(public),
            fingerprint = "sha256:${b64Url(hash)}"
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
        return b64Url(signer.sign())
    }

    override fun signAuth(hostId: String, nonce: String): Signed {
        val identity = info()
        val counter = nextCounter()
        val material = "aura-auth-v1\n${hostId.trim()}\n${identity.deviceId}\n${nonce.trim()}\n$counter"
        return Signed(identity.deviceId, counter, sign(material), identity.publicKey, identity.fingerprint)
    }

    override fun signEnvelope(
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

    override fun verifyHostEnvelope(
        publicKey: String,
        expectedFingerprint: String,
        hostId: String,
        connectionNonce: String,
        counter: Long,
        messageType: String,
        taskId: String,
        payloadDigest: String,
        signature: String
    ): Boolean = verifyHostEnvelopeStatic(
        publicKey = publicKey,
        expectedFingerprint = expectedFingerprint,
        hostId = hostId,
        connectionNonce = connectionNonce,
        counter = counter,
        messageType = messageType,
        taskId = taskId,
        payloadDigest = payloadDigest,
        signature = signature
    )
}
