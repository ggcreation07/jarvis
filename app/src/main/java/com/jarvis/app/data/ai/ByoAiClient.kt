package com.jarvis.app.data.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

enum class KeyCheckStatus {
    EMPTY,
    CHECKING,
    WORKING,
    REJECTED,
    UNVERIFIED
}

data class QuotaUsageSnapshot(
    val providerId: String,
    val requestsToday: Int,
    val lastStatus: String,
    val cooldownUntilMs: Long = 0L
)

class ByoAiClient {

    private val client = HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
                readTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
    }

    private val quotaUsage = ConcurrentHashMap<String, QuotaUsageSnapshot>()

    fun recordUsage(providerId: String, status: String, isRateLimited: Boolean = false) {
        val current = quotaUsage[providerId] ?: QuotaUsageSnapshot(providerId, 0, "Ready")
        quotaUsage[providerId] = current.copy(
            requestsToday = current.requestsToday + 1,
            lastStatus = status,
            cooldownUntilMs = if (isRateLimited) System.currentTimeMillis() + 60_000L else 0L
        )
    }

    fun allQuotaSnapshots(): List<QuotaUsageSnapshot> = quotaUsage.values.toList()

    suspend fun verifyKey(providerId: String, apiKey: String): KeyCheckStatus = withContext(Dispatchers.IO) {
        val clean = apiKey.trim()
        if (clean.isEmpty()) return@withContext KeyCheckStatus.EMPTY
        try {
            val resp = when (providerId) {
                "groq" -> client.get("https://api.groq.com/openai/v1/models") {
                    header("Authorization", "Bearer $clean")
                }
                "openrouter" -> client.get("https://openrouter.ai/api/v1/models") {
                    header("Authorization", "Bearer $clean")
                }
                "nvidia" -> client.get("https://integrate.api.nvidia.com/v1/models") {
                    header("Authorization", "Bearer $clean")
                }
                "mistral" -> client.get("https://api.mistral.ai/v1/models") {
                    header("Authorization", "Bearer $clean")
                }
                "gemini" -> client.get("https://generativelanguage.googleapis.com/v1beta/models?key=$clean")
                "tavily" -> client.post("https://api.tavily.com/search") {
                    contentType(ContentType.Application.Json)
                    setBody(JSONObject().put("api_key", clean).put("query", "ping").put("max_results", 1).toString())
                }
                else -> return@withContext KeyCheckStatus.UNVERIFIED
            }
            when {
                resp.status.isSuccess() -> KeyCheckStatus.WORKING
                resp.status.value in setOf(401, 403) -> KeyCheckStatus.REJECTED
                else -> KeyCheckStatus.UNVERIFIED
            }
        } catch (_: Exception) {
            KeyCheckStatus.UNVERIFIED
        }
    }

    suspend fun chatOpenAiCompatible(
        providerId: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        history: List<Pair<String, String>>,
        userMessage: String
    ): String = withContext(Dispatchers.IO) {
        val endpoint = when (providerId) {
            "groq" -> "https://api.groq.com/openai/v1/chat/completions"
            "openrouter" -> "https://openrouter.ai/api/v1/chat/completions"
            "nvidia" -> "https://integrate.api.nvidia.com/v1/chat/completions"
            "mistral" -> "https://api.mistral.ai/v1/chat/completions"
            else -> throw IllegalArgumentException("Unsupported provider: $providerId")
        }
        val messagesArr = JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", systemPrompt))
            history.takeLast(10).forEach { (role, content) ->
                put(JSONObject().put("role", if (role == "assistant") "assistant" else "user").put("content", content))
            }
            put(JSONObject().put("role", "user").put("content", userMessage))
        }
        val body = JSONObject()
            .put("model", model)
            .put("messages", messagesArr)
            .put("temperature", 0.5)

        val resp = client.post(endpoint) {
            header("Authorization", "Bearer ${apiKey.trim()}")
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        if (resp.status.value == 429) {
            recordUsage(providerId, "Rate limited (429)", isRateLimited = true)
            throw IllegalStateException("$providerId rate limit reached (HTTP 429).")
        }
        if (!resp.status.isSuccess()) {
            recordUsage(providerId, "HTTP ${resp.status.value}")
            throw IllegalStateException("$providerId error HTTP ${resp.status.value}: ${resp.bodyAsText().take(180)}")
        }
        val json = JSONObject(resp.bodyAsText())
        val text = json.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.trim()
            .orEmpty()
        recordUsage(providerId, "OK")
        text.ifBlank { "No text returned." }
    }

    suspend fun chatDirectGeminiRest(
        apiKey: String,
        model: String,
        systemPrompt: String,
        history: List<Pair<String, String>>,
        userMessage: String
    ): String = withContext(Dispatchers.IO) {
        val targetModel = model.ifBlank { "gemini-2.5-flash" }
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$targetModel:generateContent?key=${apiKey.trim()}"
        val contentsArr = JSONArray().apply {
            history.takeLast(10).forEach { (role, text) ->
                put(
                    JSONObject()
                        .put("role", if (role == "assistant") "model" else "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", text)))
                )
            }
            put(
                JSONObject()
                    .put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", userMessage)))
            )
        }
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
            .put("contents", contentsArr)

        val resp = client.post(url) {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        if (resp.status.value == 429) {
            recordUsage("gemini", "Rate limited (429)", isRateLimited = true)
            throw IllegalStateException("Gemini API key rate limit reached (429).")
        }
        if (!resp.status.isSuccess()) {
            recordUsage("gemini", "HTTP ${resp.status.value}")
            throw IllegalStateException("Gemini API error HTTP ${resp.status.value}")
        }
        val json = JSONObject(resp.bodyAsText())
        val text = json.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?.optJSONObject(0)
            ?.optString("text")
            ?.trim()
            .orEmpty()
        recordUsage("gemini", "OK")
        text.ifBlank { "No text returned." }
    }
}
