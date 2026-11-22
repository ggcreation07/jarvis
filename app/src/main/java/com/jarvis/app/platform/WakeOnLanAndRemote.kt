package com.jarvis.app.platform

import android.content.Context
import com.jarvis.app.data.security.DeviceIdentityManager
import com.jarvis.app.data.security.RemoteIdentitySigner
import com.jarvis.app.data.security.SecretStorage
import com.jarvis.app.data.security.SecureSecretStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Dns
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.TimeUnit

object WakeOnLan {
    private val MAC = Regex(
        "^([0-9a-fA-F]{2})[:-]?([0-9a-fA-F]{2})[:-]?([0-9a-fA-F]{2})[:-]?" +
            "([0-9a-fA-F]{2})[:-]?([0-9a-fA-F]{2})[:-]?([0-9a-fA-F]{2})$"
    )
    private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

    fun normMac(raw: String): String {
        val m = MAC.matchEntire(raw.trim()) ?: return ""
        val mac = m.groupValues.drop(1).joinToString(":") { it.lowercase() }
        return if (mac == "00:00:00:00:00:00" || mac == "ff:ff:ff:ff:ff:ff") "" else mac
    }

    private fun packet(mac: String): ByteArray? {
        val clean = normMac(mac)
        if (clean.isEmpty()) return null
        val body = clean.split(":").map { it.toInt(16).toByte() }.toByteArray()
        return ByteArray(6) { 0xFF.toByte() } + ByteArray(16 * 6) { body[it % 6] }
    }

    private fun targets(near: String): List<String> {
        val out = mutableListOf("255.255.255.255")
        val m = IPV4.matchEntire(near.substringBefore(":").trim()) ?: return out
        val o = m.groupValues.drop(1).map { it.toInt() }
        if (o.any { it > 255 }) return out
        val priv = o[0] == 10 || (o[0] == 172 && o[1] in 16..31) || (o[0] == 192 && o[1] == 168)
        if (priv) out.add("${o[0]}.${o[1]}.${o[2]}.255")
        return out
    }

    suspend fun send(mac: String, nearHost: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val data = packet(mac) ?: return@withContext false to "That doesn't look like a valid MAC address."
        var sent = 0
        var lastErr = ""
        repeat(3) {
            for (addr in targets(nearHost)) {
                for (port in intArrayOf(9, 7)) {
                    try {
                        DatagramSocket().use { s ->
                            s.broadcast = true
                            s.send(DatagramPacket(data, data.size, InetAddress.getByName(addr), port))
                        }
                        sent++
                    } catch (e: Exception) {
                        lastErr = e.message ?: e.javaClass.simpleName
                    }
                }
            }
        }
        if (sent == 0) false to "Couldn't send Wake-on-LAN packet ($lastErr)."
        else true to "Sent Wake-on-LAN packet to ${normMac(mac)}."
    }
}

object LanAddressPolicy {
    private val IPV4_PATTERN = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
    private const val ALIAS_SUFFIX = ".jarvis.local"
    private const val IPV4_ALIAS_PREFIX = "v4-"
    private const val IPV6_ALIAS_PREFIX = "v6-"

    data class WebSocketEndpoint(
        val url: String,
        val hostHeader: String,
        val usesLanCleartext: Boolean
    )

    fun isLanHostname(host: String): Boolean {
        val clean = host.trim().lowercase().removeSurrounding("[", "]")
        if (clean.isEmpty()) return false
        if (clean == "localhost" || clean == "127.0.0.1" || clean == "10.0.2.2") return true
        return clean.endsWith(".local") ||
            clean.endsWith(".lan") ||
            clean.endsWith(".home") ||
            clean.endsWith(".internal")
    }

    fun isPrivateOrLanIpLiteral(host: String): Boolean {
        val clean = host.trim().lowercase().removeSurrounding("[", "]")
        val ipv4Match = IPV4_PATTERN.matchEntire(clean)
        if (ipv4Match != null) {
            val o = ipv4Match.groupValues.drop(1).map { it.toIntOrNull() ?: return false }
            if (o.any { it !in 0..255 }) return false
            return o[0] == 10 ||
                (o[0] == 172 && o[1] in 16..31) ||
                (o[0] == 192 && o[1] == 168) ||
                (o[0] == 100 && o[1] in 64..127) || // Tailscale CGNAT
                (o[0] == 169 && o[1] == 254) || // Link-local
                o[0] == 127 // Loopback
        }
        if (clean.contains(':')) {
            return try {
                val addr = InetAddress.getByName(clean)
                addr is Inet6Address && (addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress || isUniqueLocalIpv6(addr))
            } catch (_: Exception) {
                false
            }
        }
        return false
    }

    private fun isUniqueLocalIpv6(addr: Inet6Address): Boolean {
        val firstByte = addr.address.firstOrNull()?.toInt()?.and(0xFF) ?: return false
        return (firstByte and 0xFE) == 0xFC // fc00::/7
    }

    fun toLanLocalAlias(ipLiteral: String): String {
        val clean = ipLiteral.trim().removeSurrounding("[", "]")
        return if (IPV4_PATTERN.matches(clean)) {
            "$IPV4_ALIAS_PREFIX${clean.replace('.', '-')}$ALIAS_SUFFIX"
        } else {
            val bytes = InetAddress.getByName(clean).address
            val hex = bytes.joinToString("") { "%02x".format(it) }
            "$IPV6_ALIAS_PREFIX$hex$ALIAS_SUFFIX"
        }
    }

    fun fromLanLocalAlias(hostname: String): String? {
        val clean = hostname.trim().lowercase()
        if (!clean.endsWith(ALIAS_SUFFIX)) return null
        val label = clean.removeSuffix(ALIAS_SUFFIX)
        val candidate = when {
            label.startsWith(IPV4_ALIAS_PREFIX) -> {
                val raw = label.removePrefix(IPV4_ALIAS_PREFIX).replace('-', '.')
                if (IPV4_PATTERN.matches(raw)) raw else return null
            }
            label.startsWith(IPV6_ALIAS_PREFIX) -> {
                val hex = label.removePrefix(IPV6_ALIAS_PREFIX)
                if (hex.length != 32 || !hex.all { it in '0'..'9' || it in 'a'..'f' }) return null
                val bytes = ByteArray(16) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
                InetAddress.getByAddress(bytes).hostAddress ?: return null
            }
            else -> return null
        }
        return candidate.takeIf { isPrivateOrLanIpLiteral(it) }
    }

    fun resolveWebSocketEndpoint(host: String, port: Int): WebSocketEndpoint {
        val cleanHost = host.trim().removeSurrounding("[", "]")
        val isLocalName = isLanHostname(cleanHost)
        val isLanIp = isPrivateOrLanIpLiteral(cleanHost)
        val usesLanCleartext = isLocalName || isLanIp
        val scheme = if (usesLanCleartext) "ws" else "wss"
        val connectHost = when {
            isLocalName -> if (cleanHost.contains(':') && !cleanHost.startsWith("[")) "[$cleanHost]" else cleanHost
            isLanIp -> toLanLocalAlias(cleanHost)
            else -> cleanHost
        }
        val headerHost = if (cleanHost.contains(':')) "[$cleanHost]:$port" else "$cleanHost:$port"
        return WebSocketEndpoint(
            url = "$scheme://$connectHost:$port/ws",
            hostHeader = headerHost,
            usesLanCleartext = usesLanCleartext
        )
    }

    val lanOnlyDns: Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val decodedIp = fromLanLocalAlias(hostname)
            if (decodedIp != null) {
                val addr = InetAddress.getByName(decodedIp)
                if (addr is Inet4Address || addr is Inet6Address) {
                    return listOf(addr)
                }
            }
            return Dns.SYSTEM.lookup(hostname)
        }
    }
}

class RemotePcClient internal constructor(
    private val identity: RemoteIdentitySigner,
    private val secretStore: SecretStorage
) {
    constructor(context: Context) : this(
        identity = DeviceIdentityManager(context.applicationContext),
        secretStore = SecureSecretStore(context.applicationContext)
    )

    private val client = HttpClient(OkHttp) {
        install(WebSockets)
        engine {
            config {
                dns(LanAddressPolicy.lanOnlyDns)
                connectTimeout(5, TimeUnit.SECONDS)
                readTimeout(15, TimeUnit.SECONDS)
            }
        }
    }

    private data class AuthenticatedSessionInfo(
        val hostId: String,
        val nonce: String,
        val hostPublicKey: String,
        val hostFingerprint: String,
        var lastHostCounter: Long
    )

    private data class PersistentRemoteSession(
        val endpointKey: String,
        val socket: DefaultClientWebSocketSession,
        val auth: AuthenticatedSessionInfo,
        var clientInputSeq: Long = 0L
    )

    private val remoteSessionMutex = Mutex()
    private var activeRemoteSession: PersistentRemoteSession? = null

    fun deviceInfo(): DeviceIdentityManager.Info = identity.info()

    suspend fun testConnection(host: String, port: Int): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val cleanHost = host.trim()
        if (cleanHost.isEmpty()) return@withContext false to "Enter the PC IP or Tailscale hostname."
        if (port !in 1..65535) return@withContext false to "Invalid PC port ($port)."

        val endpoint = LanAddressPolicy.resolveWebSocketEndpoint(cleanHost, port)
        val endpointKey = "$cleanHost:$port"
        try {
            var verifiedFingerprint = ""
            withTimeout(8_000L) {
                client.webSocket(
                    urlString = endpoint.url,
                    request = { header(HttpHeaders.Host, endpoint.hostHeader) }
                ) {
                    val auth = authenticateSession(this, endpointKey)
                    verifiedFingerprint = auth.hostFingerprint
                    close(CloseReason(CloseReason.Codes.NORMAL, "Verified"))
                }
            }
            true to "Authenticated PC $cleanHost:$port (${verifiedFingerprint.take(22)}…)."
        } catch (e: SecurityException) {
            false to (e.message ?: "PC identity verification failed.")
        } catch (e: Exception) {
            false to "Couldn't authenticate $cleanHost:$port (${e.message ?: "connection failed"})."
        }
    }

    suspend fun sendTask(
        host: String,
        port: Int,
        goal: String,
        kind: String = "browser"
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val cleanHost = host.trim()
        if (cleanHost.isEmpty()) return@withContext false to "No paired PC host configured."
        if (port !in 1..65535) return@withContext false to "Invalid PC port ($port)."

        val endpoint = LanAddressPolicy.resolveWebSocketEndpoint(cleanHost, port)
        val endpointKey = "$cleanHost:$port"
        val taskId = "task_${UUID.randomUUID()}"
        val canonicalPayload = DeviceIdentityManager.canonicalTaskPayload(taskId, kind, goal)
        val payloadDigest = DeviceIdentityManager.sha256Hex(canonicalPayload)

        try {
            var replyText = "Sent task to PC ($cleanHost:$port)."
            var replyOk = true
            withTimeout(12_000L) {
                client.webSocket(
                    urlString = endpoint.url,
                    request = { header(HttpHeaders.Host, endpoint.hostHeader) }
                ) {
                    val auth = authenticateSession(this, endpointKey)
                    val signed = identity.signEnvelope(
                        connectionNonce = auth.nonce,
                        messageType = "task.submit",
                        taskId = taskId,
                        payloadDigest = payloadDigest
                    )
                    val payload = JSONObject()
                        .put("type", "task.submit")
                        .put("task_id", taskId)
                        .put("device_id", signed.deviceId)
                        .put("counter", signed.counter)
                        .put("signature", signed.signature)
                        .put("payload_digest", payloadDigest)
                        .put("public_key", identity.info().publicKey)
                        .put("goal", goal)
                        .put("kind", kind)
                    send(Frame.Text(payload.toString()))

                    val incomingFrame = receiveTextFrame(this)
                        ?: throw SecurityException("PC closed connection without a signed response envelope.")
                    val resp = JSONObject(incomingFrame.readText())
                    val verifiedSummary = verifyHostResponseEnvelope(
                        auth = auth,
                        resp = resp,
                        expectedTaskId = taskId
                    )
                    replyText = verifiedSummary.ifBlank { replyText }
                    replyOk = resp.optString("status", "ok") != "error"
                }
            }
            replyOk to replyText
        } catch (e: SecurityException) {
            false to (e.message ?: "PC response failed cryptographic verification.")
        } catch (e: Exception) {
            false to "Remote PC unreachable at $cleanHost:$port (${e.message ?: "check PC companion"})."
        }
    }

    suspend fun sendRemoteInput(
        host: String,
        port: Int,
        action: String,
        x: Int? = null,
        y: Int? = null,
        text: String? = null
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val cleanHost = host.trim()
        if (cleanHost.isEmpty() || port !in 1..65535) {
            return@withContext false to "No paired PC host configured."
        }
        val endpointKey = "$cleanHost:$port"
        remoteSessionMutex.withLock {
            try {
                val session = getOrOpenRemoteSessionLocked(cleanHost, port, endpointKey)
                val inputId = "in_${++session.clientInputSeq}"
                val canonical = DeviceIdentityManager.canonicalInputPayload(inputId, action, x, y, text)
                val digest = DeviceIdentityManager.sha256Hex(canonical)
                val signed = identity.signEnvelope(
                    connectionNonce = session.auth.nonce,
                    messageType = "remote.input",
                    taskId = inputId,
                    payloadDigest = digest
                )
                val payload = JSONObject()
                    .put("type", "remote.input")
                    .put("task_id", inputId)
                    .put("device_id", signed.deviceId)
                    .put("counter", signed.counter)
                    .put("signature", signed.signature)
                    .put("payload_digest", digest)
                    .put("action", action)
                if (x != null) payload.put("x", x)
                if (y != null) payload.put("y", y)
                if (text != null) payload.put("text", text)

                withTimeout(3_000L) {
                    session.socket.send(Frame.Text(payload.toString()))
                }
                true to "Sent authenticated input ($action)."
            } catch (e: SecurityException) {
                closeActiveRemoteSessionLocked()
                false to (e.message ?: "Remote PC input rejected: authentication failed.")
            } catch (e: Exception) {
                closeActiveRemoteSessionLocked()
                false to "Remote input failed (${e.message ?: "disconnected"})."
            }
        }
    }

    suspend fun closeRemoteSession() {
        remoteSessionMutex.withLock {
            closeActiveRemoteSessionLocked()
        }
    }

    private suspend fun getOrOpenRemoteSessionLocked(
        cleanHost: String,
        port: Int,
        endpointKey: String
    ): PersistentRemoteSession {
        val existing = activeRemoteSession
        if (existing != null && existing.endpointKey == endpointKey && existing.socket.isActive) {
            return existing
        }
        closeActiveRemoteSessionLocked()
        val endpoint = LanAddressPolicy.resolveWebSocketEndpoint(cleanHost, port)
        val socket = withTimeout(5_000L) {
            client.webSocketSession(urlString = endpoint.url) {
                header(HttpHeaders.Host, endpoint.hostHeader)
            }
        }
        return try {
            val auth = withTimeout(5_000L) {
                authenticateSession(socket, endpointKey)
            }
            PersistentRemoteSession(endpointKey, socket, auth).also {
                activeRemoteSession = it
            }
        } catch (e: Exception) {
            try {
                socket.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Auth failed"))
            } catch (_: Exception) {
            }
            throw e
        }
    }

    private suspend fun closeActiveRemoteSessionLocked() {
        val cur = activeRemoteSession ?: return
        activeRemoteSession = null
        try {
            cur.socket.close(CloseReason(CloseReason.Codes.NORMAL, "Closed"))
        } catch (_: Exception) {
        }
    }

    private suspend fun authenticateSession(
        session: DefaultClientWebSocketSession,
        endpointKey: String
    ): AuthenticatedSessionInfo {
        val myInfo = identity.info()
        val hello = JSONObject()
            .put("type", "auth.hello")
            .put("device_id", myInfo.deviceId)
            .put("public_key", myInfo.publicKey)
            .put("fingerprint", myInfo.fingerprint)
        session.send(Frame.Text(hello.toString()))

        val challengeFrame = receiveTextFrame(session)
            ?: throw SecurityException("PC host did not send an authentication challenge.")
        val challenge = JSONObject(challengeFrame.readText())
        if (challenge.optString("type") != "auth.challenge") {
            throw SecurityException("Unexpected handshake frame from PC host.")
        }

        val hostId = challenge.optString("host_id").trim()
        val nonce = challenge.optString("nonce").trim()
        val hostPublicKey = challenge.optString("public_key").trim()
        val claimedFingerprint = challenge.optString("fingerprint").trim()
        if (hostId.isEmpty() || nonce.length < 16 || hostPublicKey.isEmpty() || claimedFingerprint.isEmpty()) {
            throw SecurityException("PC host sent an incomplete authentication challenge.")
        }

        val computedFingerprint = DeviceIdentityManager.computeFingerprint(hostPublicKey)
            ?: throw SecurityException("PC host public key is invalid.")
        if (computedFingerprint != claimedFingerprint) {
            throw SecurityException("PC host fingerprint does not match its public key.")
        }

        val pinned = loadPinnedHosts()[endpointKey]
        if (pinned != null && (pinned.fingerprint != computedFingerprint || pinned.hostId != hostId)) {
            throw SecurityException(
                "Security warning: PC identity changed for $endpointKey. Clear or re-pair device if intentional."
            )
        }

        val signedAuth = identity.signAuth(hostId, nonce)
        val authResp = JSONObject()
            .put("type", "auth.response")
            .put("device_id", signedAuth.deviceId)
            .put("counter", signedAuth.counter)
            .put("signature", signedAuth.signature)
            .put("public_key", signedAuth.publicKey)
            .put("fingerprint", signedAuth.fingerprint)
        session.send(Frame.Text(authResp.toString()))

        val okFrame = receiveTextFrame(session)
            ?: throw SecurityException("PC host rejected or dropped authentication.")
        val okJson = JSONObject(okFrame.readText())
        if (okJson.optString("type") != "auth.ok") {
            val reason = okJson.optString("summary", "Authentication rejected by PC host.")
            throw SecurityException(reason)
        }

        val hostCounter = okJson.optLong("counter", -1L)
        val okTaskId = okJson.optString("task_id", "auth")
        val okStatus = okJson.optString("status", "ok")
        val okDigest = okJson.optString("payload_digest").trim()
        val okSig = okJson.optString("signature").trim()
        val expectedDigest = DeviceIdentityManager.sha256Hex(
            DeviceIdentityManager.canonicalResponsePayload(okStatus, "")
        )
        if (hostCounter <= 0L || okDigest != expectedDigest) {
            throw SecurityException("PC auth.ok envelope failed digest or counter validation.")
        }
        val verified = identity.verifyHostEnvelope(
            publicKey = hostPublicKey,
            expectedFingerprint = computedFingerprint,
            hostId = hostId,
            connectionNonce = nonce,
            counter = hostCounter,
            messageType = "auth.ok",
            taskId = okTaskId,
            payloadDigest = okDigest,
            signature = okSig
        )
        if (!verified) {
            throw SecurityException("PC auth.ok failed ECDSA host envelope signature verification.")
        }

        if (pinned == null) {
            savePinnedHost(endpointKey, PinnedHost(hostId, computedFingerprint, hostPublicKey))
        }

        return AuthenticatedSessionInfo(
            hostId = hostId,
            nonce = nonce,
            hostPublicKey = hostPublicKey,
            hostFingerprint = computedFingerprint,
            lastHostCounter = hostCounter
        )
    }

    private fun verifyHostResponseEnvelope(
        auth: AuthenticatedSessionInfo,
        resp: JSONObject,
        expectedTaskId: String
    ): String {
        val msgType = resp.optString("type").trim()
        val taskId = resp.optString("task_id").trim()
        val hostCounter = resp.optLong("counter", -1L)
        val status = resp.optString("status", "ok").trim()
        val summary = resp.optString("summary", "")
        val payloadDigest = resp.optString("payload_digest").trim()
        val signature = resp.optString("signature").trim()

        if (msgType.isEmpty() || taskId != expectedTaskId) {
            throw SecurityException("PC response task_id mismatch or missing message type.")
        }
        if (hostCounter <= auth.lastHostCounter) {
            throw SecurityException("PC response failed replay counter check.")
        }
        val expectedDigest = DeviceIdentityManager.sha256Hex(
            DeviceIdentityManager.canonicalResponsePayload(status, summary)
        )
        if (payloadDigest != expectedDigest) {
            throw SecurityException("PC response failed SHA-256 payload digest verification.")
        }
        val valid = identity.verifyHostEnvelope(
            publicKey = auth.hostPublicKey,
            expectedFingerprint = auth.hostFingerprint,
            hostId = auth.hostId,
            connectionNonce = auth.nonce,
            counter = hostCounter,
            messageType = msgType,
            taskId = taskId,
            payloadDigest = payloadDigest,
            signature = signature
        )
        if (!valid) {
            throw SecurityException("PC response failed ECDSA host envelope verification.")
        }
        auth.lastHostCounter = hostCounter
        return summary
    }

    private suspend fun receiveTextFrame(session: DefaultClientWebSocketSession): Frame.Text? {
        while (true) {
            when (val frame = session.incoming.receiveCatching().getOrNull()) {
                is Frame.Text -> return frame
                is Frame.Close, null -> return null
                else -> continue
            }
        }
    }

    private data class PinnedHost(
        val hostId: String,
        val fingerprint: String,
        val publicKey: String
    )

    private suspend fun loadPinnedHosts(): Map<String, PinnedHost> {
        val raw = secretStore.getSecret(SecureSecretStore.KEY_TRUSTED_PC_HOSTS)
        if (raw.isBlank()) return emptyMap()
        return try {
            val obj = JSONObject(raw)
            val map = mutableMapOf<String, PinnedHost>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val entry = obj.optJSONObject(k) ?: continue
                map[k] = PinnedHost(
                    hostId = entry.optString("host_id"),
                    fingerprint = entry.optString("fingerprint"),
                    publicKey = entry.optString("public_key")
                )
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private suspend fun savePinnedHost(endpointKey: String, host: PinnedHost) {
        val existingRaw = secretStore.getSecret(SecureSecretStore.KEY_TRUSTED_PC_HOSTS)
        val root = try {
            if (existingRaw.isBlank()) JSONObject() else JSONObject(existingRaw)
        } catch (_: Exception) {
            JSONObject()
        }
        root.put(
            endpointKey,
            JSONObject()
                .put("host_id", host.hostId)
                .put("fingerprint", host.fingerprint)
                .put("public_key", host.publicKey)
        )
        secretStore.setSecret(SecureSecretStore.KEY_TRUSTED_PC_HOSTS, root.toString())
    }
}

class TvControlClient {
    suspend fun checkTvReachable(host: String, port: Int = 5555): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (host.isBlank()) return@withContext false to "Enter the TV IP address first."
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host.trim(), port), 3500)
            }
            true to "Android TV ADB port $host:$port is reachable."
        } catch (e: Exception) {
            false to "TV at $host:$port didn't answer (${e.message ?: "check Wi-Fi & USB debugging"})."
        }
    }
}
