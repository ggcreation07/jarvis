package com.jarvis.app.platform

import android.content.Context
import com.jarvis.app.data.security.DeviceIdentityManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

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

class RemotePcClient(context: Context) {
    private val identity = DeviceIdentityManager(context)
    private val client = HttpClient(OkHttp) {
        install(WebSockets)
    }

    fun deviceInfo(): DeviceIdentityManager.Info = identity.info()

    suspend fun testConnection(host: String, port: Int): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (host.isBlank()) return@withContext false to "Enter the PC IP or Tailscale hostname."
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host.trim(), port), 3500)
            }
            true to "PC endpoint $host:$port is reachable."
        } catch (e: Exception) {
            false to "Couldn't reach $host:$port (${e.message ?: "connection failed"})."
        }
    }

    suspend fun sendTask(
        host: String,
        port: Int,
        goal: String,
        kind: String = "browser"
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (host.isBlank()) return@withContext false to "No paired PC host configured."
        try {
            val signed = identity.signEnvelope("direct", "task.submit", "task_${System.currentTimeMillis()}", goal.hashCode().toString())
            var replyText = "Sent task to PC ($host:$port)."
            withTimeout(12_000L) {
                client.webSocket(host = host.trim(), port = port, path = "/ws") {
                    val payload = JSONObject()
                        .put("type", "task.submit")
                        .put("device_id", signed.deviceId)
                        .put("counter", signed.counter)
                        .put("signature", signed.signature)
                        .put("public_key", identity.info().publicKey)
                        .put("goal", goal)
                        .put("kind", kind)
                    send(Frame.Text(payload.toString()))
                    val incoming = incoming.receiveCatching().getOrNull()
                    if (incoming is Frame.Text) {
                        val resp = incoming.readText()
                        replyText = JSONObject(resp).optString("summary", replyText)
                    }
                }
            }
            true to replyText
        } catch (e: Exception) {
            false to "Remote PC unreachable at $host:$port (${e.message ?: "check PC companion"})."
        }
    }

    suspend fun sendRemoteInput(host: String, port: Int, action: String, x: Int? = null, y: Int? = null, text: String? = null) {
        if (host.isBlank()) return
        withContext(Dispatchers.IO) {
            try {
                withTimeout(3_000L) {
                    client.webSocket(host = host.trim(), port = port, path = "/ws") {
                        val payload = JSONObject().put("type", "remote.input").put("action", action)
                        if (x != null) payload.put("x", x)
                        if (y != null) payload.put("y", y)
                        if (text != null) payload.put("text", text)
                        send(Frame.Text(payload.toString()))
                    }
                }
            } catch (_: Exception) {
            }
        }
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
