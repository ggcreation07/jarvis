package com.jarvis.app.data.ai

import androidx.compose.runtime.Immutable
import com.jarvis.app.domain.model.FIREBASE_AI_MODELS
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

enum class KeyCheckStatus {
    EMPTY,
    NOT_VERIFIED,
    CHECKING,
    WORKING,
    INVALID_KEY,
    RATE_LIMITED,
    NETWORK_ERROR,
    PERMISSION_DENIED,
    QUOTA_EXCEEDED,
    GENERATION_UNAVAILABLE,
    SERVER_ERROR,
    @Deprecated("Use INVALID_KEY")
    REJECTED,
    @Deprecated("Use NOT_VERIFIED")
    UNVERIFIED
}

@Immutable
@Serializable
data class DynamicModelInfo(
    val id: String,
    val displayName: String = "",
    val providerId: String,
    val ownedBy: String = "",
    val supportsTools: Boolean? = null
)

enum class ModelListSource {
    NONE,
    LIVE,
    CACHED
}

@Immutable
@Serializable
data class CachedProviderModels(
    val providerId: String,
    val models: List<DynamicModelInfo>,
    val fetchedAtMs: Long
)

@Immutable
data class ProviderModelsUiState(
    val providerId: String,
    val models: List<DynamicModelInfo> = emptyList(),
    val source: ModelListSource = ModelListSource.NONE,
    val isLoading: Boolean = false,
    val error: String? = null,
    val lastFetchedAtMs: Long = 0L
)

sealed interface FetchModelsResult {
    data class Success(
        val providerId: String,
        val models: List<DynamicModelInfo>
    ) : FetchModelsResult

    data class Failure(
        val providerId: String,
        val errorMessage: String,
        val keyStatus: KeyCheckStatus
    ) : FetchModelsResult
}

data class QuotaUsageSnapshot(
    val providerId: String,
    val requestsToday: Int,
    val lastStatus: String,
    val cooldownUntilMs: Long = 0L
)

sealed interface ByoStreamEvent {
    data class PartialText(val accumulated: String) : ByoStreamEvent
    data class Completed(val finalText: String) : ByoStreamEvent
}

interface ProviderEndpoints {
    fun groqModelsUrl(): String
    fun groqChatUrl(): String
    fun openRouterAuthUrl(): String
    fun openRouterModelsUrl(): String = "https://openrouter.ai/api/v1/models"
    fun openRouterChatUrl(): String
    fun nvidiaModelsUrl(): String
    fun nvidiaChatUrl(): String
    fun mistralModelsUrl(): String
    fun mistralChatUrl(): String
    fun geminiModelsUrl(): String
    fun geminiGenerateContentUrl(model: String): String
    fun openAiModelsUrl(): String = "https://api.openai.com/v1/models"
    fun openAiChatUrl(): String = "https://api.openai.com/v1/chat/completions"
    fun anthropicModelsUrl(): String = "https://api.anthropic.com/v1/models"
    fun anthropicMessagesUrl(): String = "https://api.anthropic.com/v1/messages"
    fun xaiModelsUrl(): String = "https://api.x.ai/v1/models"
    fun xaiChatUrl(): String = "https://api.x.ai/v1/chat/completions"
    fun tavilySearchUrl(): String
}

object DefaultProviderEndpoints : ProviderEndpoints {
    override fun groqModelsUrl() = "https://api.groq.com/openai/v1/models"
    override fun groqChatUrl() = "https://api.groq.com/openai/v1/chat/completions"
    override fun openRouterAuthUrl() = "https://openrouter.ai/api/v1/auth/key"
    override fun openRouterModelsUrl() = "https://openrouter.ai/api/v1/models"
    override fun openRouterChatUrl() = "https://openrouter.ai/api/v1/chat/completions"
    override fun nvidiaModelsUrl() = "https://integrate.api.nvidia.com/v1/models"
    override fun nvidiaChatUrl() = "https://integrate.api.nvidia.com/v1/chat/completions"
    override fun mistralModelsUrl() = "https://api.mistral.ai/v1/models"
    override fun mistralChatUrl() = "https://api.mistral.ai/v1/chat/completions"
    override fun geminiModelsUrl() = "https://generativelanguage.googleapis.com/v1beta/models"
    override fun geminiGenerateContentUrl(model: String): String {
        val clean = model.trim().removePrefix("models/")
        return "https://generativelanguage.googleapis.com/v1beta/models/$clean:generateContent"
    }
    override fun openAiModelsUrl() = "https://api.openai.com/v1/models"
    override fun openAiChatUrl() = "https://api.openai.com/v1/chat/completions"
    override fun anthropicModelsUrl() = "https://api.anthropic.com/v1/models"
    override fun anthropicMessagesUrl() = "https://api.anthropic.com/v1/messages"
    override fun xaiModelsUrl() = "https://api.x.ai/v1/models"
    override fun xaiChatUrl() = "https://api.x.ai/v1/chat/completions"
    override fun tavilySearchUrl() = "https://api.tavily.com/search"
}

class ByoAiClient(
    private val client: HttpClient = createDefaultHttpClient(),
    private val endpoints: ProviderEndpoints = DefaultProviderEndpoints
) {

    companion object {
        const val ANTHROPIC_API_VERSION = "2023-06-01"
        private const val ANTHROPIC_DEFAULT_MAX_TOKENS = 4096

        fun createDefaultHttpClient(): HttpClient = HttpClient(OkHttp) {
            engine {
                config {
                    connectTimeout(12, java.util.concurrent.TimeUnit.SECONDS)
                    readTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
                }
            }
        }

        private val NON_CHAT_ID_MARKERS = listOf(
            "embed",
            "rerank",
            "whisper",
            "-tts",
            "tts-",
            "/tts",
            "playai",
            "orpheus",
            "kokoro",
            "-asr",
            "parakeet",
            "canary-",
            "fastpitch",
            "hifigan",
            "transcribe",
            "native-audio",
            "imagen",
            "veo-",
            "veo/",
            "lyria",
            "dall-e",
            "stable-diffusion",
            "sdxl",
            "/flux",
            "flux-",
            "midjourney",
            "edify",
            "trellis",
            "cosmos-",
            "-ocr",
            "ocr-",
            "paddleocr",
            "nv-ingest",
            "moderation",
            "llama-guard",
            "prompt-guard",
            "nemotron-safety",
            "shieldgemma",
            "alphafold",
            "esmfold",
            "fourcastnet",
            "diffdock",
            "genmol",
            "corrdiff"
        )
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

    suspend fun fetchModels(providerId: String, apiKey: String): FetchModelsResult = withContext(Dispatchers.IO) {
        val clean = apiKey.trim()
        if (clean.isEmpty()) {
            return@withContext FetchModelsResult.Failure(
                providerId = providerId,
                errorMessage = "No API key saved for $providerId.",
                keyStatus = KeyCheckStatus.EMPTY
            )
        }

        try {
            val resp = when (providerId) {
                "gemini" -> client.get(endpoints.geminiModelsUrl()) {
                    header("x-goog-api-key", clean)
                }
                "groq" -> client.get(endpoints.groqModelsUrl()) {
                    header("Authorization", "Bearer $clean")
                }
                "openrouter" -> client.get(endpoints.openRouterModelsUrl()) {
                    header("Authorization", "Bearer $clean")
                }
                "nvidia" -> client.get(endpoints.nvidiaModelsUrl()) {
                    header("Authorization", "Bearer $clean")
                }
                "mistral" -> client.get(endpoints.mistralModelsUrl()) {
                    header("Authorization", "Bearer $clean")
                }
                "openai" -> client.get(endpoints.openAiModelsUrl()) {
                    header("Authorization", "Bearer $clean")
                }
                "anthropic" -> client.get(endpoints.anthropicModelsUrl()) {
                    header("x-api-key", clean)
                    header("anthropic-version", ANTHROPIC_API_VERSION)
                }
                "xai" -> client.get(endpoints.xaiModelsUrl()) {
                    header("Authorization", "Bearer $clean")
                }
                else -> return@withContext FetchModelsResult.Failure(
                    providerId = providerId,
                    errorMessage = "Provider '$providerId' does not support dynamic model fetching.",
                    keyStatus = KeyCheckStatus.NOT_VERIFIED
                )
            }

            if (!resp.status.isSuccess()) {
                val status = classifyResponse(resp, isGenerationProbe = false)
                return@withContext FetchModelsResult.Failure(
                    providerId = providerId,
                    errorMessage = formatFetchError(providerId, resp.status.value, status),
                    keyStatus = status
                )
            }

            val body = runCatching { resp.bodyAsText() }.getOrDefault("")
            val parsed = parseAndFilterModels(providerId, body)
                ?: return@withContext FetchModelsResult.Failure(
                    providerId = providerId,
                    errorMessage = "Invalid model list response from $providerId.",
                    keyStatus = KeyCheckStatus.SERVER_ERROR
                )

            FetchModelsResult.Success(providerId = providerId, models = parsed)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            FetchModelsResult.Failure(
                providerId = providerId,
                errorMessage = "Network error while fetching models for $providerId.",
                keyStatus = KeyCheckStatus.NETWORK_ERROR
            )
        }
    }

    internal fun parseAndFilterModels(providerId: String, rawJson: String): List<DynamicModelInfo>? {
        val root = runCatching { JSONObject(rawJson) }.getOrNull() ?: return null
        val results = mutableListOf<DynamicModelInfo>()
        val seenIds = HashSet<String>()

        when (providerId) {
            "gemini" -> {
                val arr = root.optJSONArray("models") ?: return emptyList()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val rawName = obj.optString("name").trim()
                    if (rawName.isEmpty()) continue

                    val methodsArr = obj.optJSONArray("supportedGenerationMethods") ?: continue
                    var supportsGenerateContent = false
                    for (m in 0 until methodsArr.length()) {
                        if (methodsArr.optString(m) == "generateContent") {
                            supportsGenerateContent = true
                            break
                        }
                    }
                    if (!supportsGenerateContent) continue

                    val exactId = rawName.removePrefix("models/")
                    if (exactId.isEmpty() || !isCompatibleChatModelId(providerId, exactId)) continue
                    if (seenIds.add(exactId)) {
                        val displayName = obj.optString("displayName").trim().ifBlank { exactId }
                        results.add(
                            DynamicModelInfo(
                                id = exactId,
                                displayName = displayName,
                                providerId = providerId,
                                ownedBy = "Google"
                            )
                        )
                    }
                }
            }
            "groq", "openrouter", "nvidia", "mistral", "openai", "anthropic", "xai" -> {
                val arr = root.optJSONArray("data") ?: return emptyList()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val exactId = obj.optString("id").trim()
                    if (exactId.isEmpty()) continue

                    if (providerId == "groq" && obj.has("active") && !obj.optBoolean("active", true)) {
                        continue
                    }

                    var supportsTools: Boolean? = null
                    if (providerId == "mistral") {
                        val caps = obj.optJSONObject("capabilities")
                        if (caps != null) {
                            if (caps.has("completion_chat") && !caps.optBoolean("completion_chat", true)) {
                                continue
                            }
                            if (caps.has("function_calling")) {
                                supportsTools = caps.optBoolean("function_calling")
                            }
                        }
                        val type = obj.optString("type").lowercase()
                        if (type == "embedding" || type == "ocr" || type == "moderation") continue
                    }

                    if (providerId == "openrouter") {
                        val arch = obj.optJSONObject("architecture")
                        if (arch != null) {
                            val outMods = arch.optJSONArray("output_modalities")
                            if (outMods != null && outMods.length() > 0) {
                                var hasTextOut = false
                                for (k in 0 until outMods.length()) {
                                    if (outMods.optString(k).equals("text", ignoreCase = true)) {
                                        hasTextOut = true
                                        break
                                    }
                                }
                                if (!hasTextOut) continue
                            } else {
                                val modality = arch.optString("modality").lowercase()
                                if (modality.contains("->")) {
                                    val outputSide = modality.substringAfter("->")
                                    if (!outputSide.contains("text")) continue
                                }
                            }
                        }
                        val params = obj.optJSONArray("supported_parameters")
                        if (params != null) {
                            var hasTools = false
                            for (k in 0 until params.length()) {
                                if (params.optString(k) == "tools") {
                                    hasTools = true
                                    break
                                }
                            }
                            supportsTools = hasTools
                        }
                    }

                    if (providerId == "xai" || providerId == "openai") {
                        val outMods = obj.optJSONArray("output_modalities")
                        if (outMods != null && outMods.length() > 0) {
                            var hasTextOut = false
                            for (k in 0 until outMods.length()) {
                                if (outMods.optString(k).equals("text", ignoreCase = true)) {
                                    hasTextOut = true
                                    break
                                }
                            }
                            if (!hasTextOut) continue
                        }
                    }

                    if (providerId == "nvidia") {
                        val modelType = obj.optString("type").lowercase()
                        val mod = obj.optString("model_type").lowercase()
                        if (modelType.contains("embed") || modelType.contains("rerank") ||
                            modelType.contains("audio") || modelType.contains("image") ||
                            mod.contains("embed") || mod.contains("rerank") ||
                            mod.contains("audio") || mod.contains("image")
                        ) {
                            continue
                        }
                    }

                    if (!isCompatibleChatModelId(providerId, exactId)) continue

                    if (seenIds.add(exactId)) {
                        val displayName = obj.optString("name").trim()
                            .ifBlank { obj.optString("display_name").trim() }
                            .ifBlank { exactId }
                        val ownedBy = obj.optString("owned_by").trim()
                            .ifBlank { if (providerId == "anthropic") "Anthropic" else "" }
                        results.add(
                            DynamicModelInfo(
                                id = exactId,
                                displayName = displayName,
                                providerId = providerId,
                                ownedBy = ownedBy,
                                supportsTools = supportsTools
                            )
                        )
                    }
                }
            }
            else -> return emptyList()
        }
        return results
    }

    private fun isCompatibleChatModelId(providerId: String, modelId: String): Boolean {
        val lower = modelId.lowercase()
        if (NON_CHAT_ID_MARKERS.any { lower.contains(it) }) return false
        if (providerId == "gemini") {
            if (lower == "aqa" || lower.endsWith("/aqa")) return false
            if (lower.contains("robotics") || lower.contains("computer-use")) return false
            if (lower.contains("bison") || lower.contains("gecko")) return false
        }
        if (providerId == "nvidia") {
            if (lower.startsWith("baai/bge") || lower.startsWith("intfloat/e5")) return false
        }
        if (providerId == "openai") {
            if (lower.contains("realtime") ||
                lower.contains("-audio") ||
                lower.startsWith("gpt-image") ||
                lower.startsWith("chatgpt-image") ||
                lower.startsWith("sora-") ||
                lower.contains("computer-use") ||
                lower.contains("deep-research") ||
                lower == "o1-pro" ||
                lower.startsWith("o1-pro-") ||
                lower == "o3-pro" ||
                lower.startsWith("o3-pro-") ||
                lower == "gpt-5-pro" ||
                lower.startsWith("gpt-5-pro-") ||
                lower == "codex-mini-latest" ||
                lower.startsWith("codex-") ||
                lower.contains("-codex") ||
                lower.startsWith("babbage-") ||
                lower.startsWith("davinci-") ||
                lower.startsWith("text-davinci") ||
                lower.startsWith("text-curie") ||
                lower.startsWith("text-babbage") ||
                lower.startsWith("text-ada") ||
                lower.startsWith("omni-moderation") ||
                lower.startsWith("ft:babbage") ||
                lower.startsWith("ft:davinci") ||
                lower.startsWith("ft:curie") ||
                lower.startsWith("ft:ada")
            ) {
                return false
            }
        }
        if (providerId == "xai") {
            if (lower.contains("-image") || lower.contains("imagine") || lower.contains("-video")) {
                return false
            }
        }
        return true
    }

    private fun formatFetchError(providerId: String, statusCode: Int, status: KeyCheckStatus): String {
        return when (status) {
            KeyCheckStatus.INVALID_KEY -> "Invalid API key for $providerId (HTTP $statusCode)."
            KeyCheckStatus.PERMISSION_DENIED -> "API key lacks permission to list $providerId models (HTTP $statusCode)."
            KeyCheckStatus.QUOTA_EXCEEDED -> "Quota or billing limit reached on $providerId (HTTP $statusCode)."
            KeyCheckStatus.RATE_LIMITED -> "Rate limited while fetching $providerId models (HTTP 429)."
            KeyCheckStatus.SERVER_ERROR -> "$providerId server error (HTTP $statusCode)."
            else -> "Failed to fetch $providerId models (HTTP $statusCode)."
        }
    }

    suspend fun verifyKey(providerId: String, apiKey: String): KeyCheckStatus = withContext(Dispatchers.IO) {
        val clean = apiKey.trim()
        if (clean.isEmpty()) return@withContext KeyCheckStatus.EMPTY

        try {
            when (providerId) {
                "gemini" -> verifyGemini(clean)
                "groq" -> verifyOpenAiCompatible(
                    providerId = "groq",
                    apiKey = clean,
                    modelsUrl = endpoints.groqModelsUrl(),
                    chatUrl = endpoints.groqChatUrl(),
                    fallbackProbeModel = "llama-3.3-70b-versatile"
                )
                "openrouter" -> verifyOpenRouter(clean)
                "nvidia" -> verifyOpenAiCompatible(
                    providerId = "nvidia",
                    apiKey = clean,
                    modelsUrl = endpoints.nvidiaModelsUrl(),
                    chatUrl = endpoints.nvidiaChatUrl(),
                    fallbackProbeModel = "meta/llama-3.3-70b-instruct"
                )
                "mistral" -> verifyOpenAiCompatible(
                    providerId = "mistral",
                    apiKey = clean,
                    modelsUrl = endpoints.mistralModelsUrl(),
                    chatUrl = endpoints.mistralChatUrl(),
                    fallbackProbeModel = "mistral-small-latest"
                )
                "openai" -> verifyOpenAiCompatible(
                    providerId = "openai",
                    apiKey = clean,
                    modelsUrl = endpoints.openAiModelsUrl(),
                    chatUrl = endpoints.openAiChatUrl(),
                    fallbackProbeModel = "gpt-4o-mini"
                )
                "anthropic" -> verifyAnthropic(clean)
                "xai" -> verifyOpenAiCompatible(
                    providerId = "xai",
                    apiKey = clean,
                    modelsUrl = endpoints.xaiModelsUrl(),
                    chatUrl = endpoints.xaiChatUrl(),
                    fallbackProbeModel = "grok-3-mini"
                )
                "tavily" -> verifyTavily(clean)
                else -> KeyCheckStatus.NOT_VERIFIED
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            KeyCheckStatus.NETWORK_ERROR
        }
    }

    internal fun isOpenAiReasoningModel(modelId: String): Boolean {
        val lower = modelId.trim().lowercase()
        return lower == "o1" || lower.startsWith("o1-") ||
            lower == "o3" || lower.startsWith("o3-") ||
            lower == "o4" || lower.startsWith("o4-") ||
            lower == "gpt-5" || lower.startsWith("gpt-5-")
    }

    private fun supportsCustomTemperature(providerId: String, modelId: String): Boolean {
        if (providerId != "openai") return true
        val lower = modelId.trim().lowercase()
        return !isOpenAiReasoningModel(lower) && !lower.contains("-search-preview")
    }

    private fun usesMaxCompletionTokens(providerId: String, modelId: String): Boolean {
        return providerId == "openai" && isOpenAiReasoningModel(modelId)
    }

    private fun applyOpenAiTokenParameter(
        payload: JSONObject,
        providerId: String,
        modelId: String,
        maxTokens: Int
    ) {
        if (usesMaxCompletionTokens(providerId, modelId)) {
            payload.put("max_completion_tokens", maxTokens)
        } else {
            payload.put("max_tokens", maxTokens)
        }
    }

    private fun selectVerificationProbeModel(
        providerId: String,
        fetchedModels: List<DynamicModelInfo>,
        fallbackProbeModel: String
    ): String {
        if (fetchedModels.isEmpty()) return fallbackProbeModel
        val candidates = if (providerId == "openai") {
            fetchedModels.filterNot { isOpenAiReasoningModel(it.id) }.ifEmpty { fetchedModels }
        } else {
            fetchedModels
        }
        val preferred = candidates.firstOrNull { m ->
            val id = m.id.lowercase()
            id.contains("flash") || id.contains("mini") || id.contains("nano") ||
                id.contains("small") || id.contains("haiku") ||
                id.contains("8b") || id.contains(":free") || id.contains("instant")
        }
        return (preferred ?: candidates.first()).id
    }

    private suspend fun verifyGemini(apiKey: String): KeyCheckStatus {
        // Step 1: Verify key against official models list using secure header authentication
        val modelsResp = client.get(endpoints.geminiModelsUrl()) {
            header("x-goog-api-key", apiKey)
        }
        val authStatus = classifyResponse(modelsResp, isGenerationProbe = false)
        if (authStatus != KeyCheckStatus.WORKING) {
            return authStatus
        }

        val modelsBody = runCatching { modelsResp.bodyAsText() }.getOrDefault("")
        val parsedModels = parseAndFilterModels("gemini", modelsBody).orEmpty()
        val probeModel = selectVerificationProbeModel("gemini", parsedModels, "gemini-2.5-flash")

        // Step 2: Verify actual generation capability with a minimal probe token
        val probeBody = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "ping")))))
            .put("generationConfig", JSONObject().put("maxOutputTokens", 1))
        val probeResp = client.post(endpoints.geminiGenerateContentUrl(probeModel)) {
            header("x-goog-api-key", apiKey)
            contentType(ContentType.Application.Json)
            setBody(probeBody.toString())
        }
        return classifyResponse(probeResp, isGenerationProbe = true)
    }

    private suspend fun verifyAnthropic(apiKey: String): KeyCheckStatus {
        // Step 1: Authenticated models list using x-api-key + anthropic-version headers
        val modelsResp = client.get(endpoints.anthropicModelsUrl()) {
            header("x-api-key", apiKey)
            header("anthropic-version", ANTHROPIC_API_VERSION)
        }
        val authStatus = classifyResponse(modelsResp, isGenerationProbe = false)
        if (authStatus != KeyCheckStatus.WORKING) {
            return authStatus
        }

        val modelsBody = runCatching { modelsResp.bodyAsText() }.getOrDefault("")
        val parsedModels = parseAndFilterModels("anthropic", modelsBody).orEmpty()
        val probeModel = selectVerificationProbeModel("anthropic", parsedModels, "claude-3-5-haiku-latest")

        // Step 2: Minimal native Messages API probe
        val probeBody = JSONObject()
            .put("model", probeModel)
            .put("max_tokens", 1)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "ping")))
        val probeResp = client.post(endpoints.anthropicMessagesUrl()) {
            header("x-api-key", apiKey)
            header("anthropic-version", ANTHROPIC_API_VERSION)
            contentType(ContentType.Application.Json)
            setBody(probeBody.toString())
        }
        return classifyResponse(probeResp, isGenerationProbe = true)
    }

    private suspend fun verifyOpenRouter(apiKey: String): KeyCheckStatus {
        // Step 1: OpenRouter requires GET /api/v1/auth/key. The public /models endpoint
        // returns 200 without authentication and must NEVER be used to verify API keys.
        val authResp = client.get(endpoints.openRouterAuthUrl()) {
            header("Authorization", "Bearer $apiKey")
        }
        val authStatus = classifyResponse(authResp, isGenerationProbe = false)
        if (authStatus != KeyCheckStatus.WORKING) {
            return authStatus
        }

        // Check OpenRouter credit / limit constraints in auth payload if present
        val authBodyText = runCatching { authResp.bodyAsText() }.getOrDefault("")
        val dataObj = runCatching { JSONObject(authBodyText).optJSONObject("data") }.getOrNull()
        if (dataObj != null) {
            val limit = dataObj.optDouble("limit", -1.0)
            val usage = dataObj.optDouble("usage", 0.0)
            if (limit in 0.0..usage && limit > 0.0) {
                return KeyCheckStatus.QUOTA_EXCEEDED
            }
        }

        val fetchedResult = runCatching {
            val modelsResp = client.get(endpoints.openRouterModelsUrl()) {
                header("Authorization", "Bearer $apiKey")
            }
            if (modelsResp.status.isSuccess()) {
                parseAndFilterModels("openrouter", modelsResp.bodyAsText()).orEmpty()
            } else {
                emptyList()
            }
        }.getOrDefault(emptyList())

        val probeModel = selectVerificationProbeModel("openrouter", fetchedResult, "google/gemini-2.5-flash")

        // Step 2: Minimal generation probe
        val probeBody = JSONObject()
            .put("model", probeModel)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "ping")))
            .put("max_tokens", 1)
        val probeResp = client.post(endpoints.openRouterChatUrl()) {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(probeBody.toString())
        }
        return classifyResponse(probeResp, isGenerationProbe = true)
    }

    private suspend fun verifyOpenAiCompatible(
        providerId: String,
        apiKey: String,
        modelsUrl: String,
        chatUrl: String,
        fallbackProbeModel: String
    ): KeyCheckStatus {
        // Step 1: Authenticated models list
        val modelsResp = client.get(modelsUrl) {
            header("Authorization", "Bearer $apiKey")
        }
        val authStatus = classifyResponse(modelsResp, isGenerationProbe = false)
        if (authStatus != KeyCheckStatus.WORKING) {
            return authStatus
        }

        val modelsBody = runCatching { modelsResp.bodyAsText() }.getOrDefault("")
        val parsedModels = parseAndFilterModels(providerId, modelsBody).orEmpty()
        val probeModel = selectVerificationProbeModel(providerId, parsedModels, fallbackProbeModel)

        // Step 2: Generation probe
        val probeBody = JSONObject()
            .put("model", probeModel)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "ping")))
        applyOpenAiTokenParameter(probeBody, providerId, probeModel, 1)
        val probeResp = client.post(chatUrl) {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(probeBody.toString())
        }
        return classifyResponse(probeResp, isGenerationProbe = true)
    }

    private suspend fun verifyTavily(apiKey: String): KeyCheckStatus {
        // Tavily is a search utility, not an LLM provider. Validate search ping separately.
        val resp = client.post(endpoints.tavilySearchUrl()) {
            contentType(ContentType.Application.Json)
            setBody(JSONObject().put("api_key", apiKey).put("query", "ping").put("max_results", 1).toString())
        }
        return classifyResponse(resp, isGenerationProbe = false)
    }

    private suspend fun classifyResponse(resp: HttpResponse, isGenerationProbe: Boolean): KeyCheckStatus {
        if (resp.status.isSuccess()) return KeyCheckStatus.WORKING
        val statusCode = resp.status.value
        val bodyText = runCatching { resp.bodyAsText() }.getOrDefault("")
        return classifyStatusCodeAndBody(statusCode, bodyText, isGenerationProbe)
    }

    private fun classifyStatusCodeAndBody(
        statusCode: Int,
        rawBodyText: String,
        isGenerationProbe: Boolean
    ): KeyCheckStatus {
        val bodyText = rawBodyText.lowercase()
        return when {
            statusCode == 401 -> KeyCheckStatus.INVALID_KEY
            statusCode == 402 -> KeyCheckStatus.QUOTA_EXCEEDED
            statusCode == 429 -> {
                if (bodyText.contains("insufficient_quota") ||
                    bodyText.contains("exceeded your current quota") ||
                    bodyText.contains("credit balance") ||
                    bodyText.contains("billing")
                ) {
                    KeyCheckStatus.QUOTA_EXCEEDED
                } else {
                    KeyCheckStatus.RATE_LIMITED
                }
            }
            statusCode == 403 -> {
                when {
                    bodyText.contains("quota") ||
                        bodyText.contains("credit") ||
                        bodyText.contains("billing") ||
                        bodyText.contains("balance") ||
                        bodyText.contains("exhausted") ||
                        bodyText.contains("insufficient") ||
                        bodyText.contains("payment") -> KeyCheckStatus.QUOTA_EXCEEDED
                    bodyText.contains("invalid") || bodyText.contains("api key") || bodyText.contains("unauthorized") ->
                        KeyCheckStatus.INVALID_KEY
                    else -> KeyCheckStatus.PERMISSION_DENIED
                }
            }
            statusCode == 400 -> {
                when {
                    bodyText.contains("api_key_invalid") ||
                        bodyText.contains("api key not valid") ||
                        bodyText.contains("invalid api key") ||
                        bodyText.contains("invalid x-api-key") ||
                        bodyText.contains("invalid_api_key") -> KeyCheckStatus.INVALID_KEY
                    bodyText.contains("quota") ||
                        bodyText.contains("billing") ||
                        bodyText.contains("credit balance") ||
                        bodyText.contains("too low") -> KeyCheckStatus.QUOTA_EXCEEDED
                    isGenerationProbe -> KeyCheckStatus.GENERATION_UNAVAILABLE
                    else -> KeyCheckStatus.INVALID_KEY
                }
            }
            statusCode == 529 || statusCode in 500..599 -> KeyCheckStatus.SERVER_ERROR
            isGenerationProbe -> KeyCheckStatus.GENERATION_UNAVAILABLE
            else -> KeyCheckStatus.INVALID_KEY
        }
    }

    internal fun sanitizeSecret(text: String, apiKey: String = ""): String {
        var sanitized = text
        val trimmedKey = apiKey.trim()
        if (trimmedKey.length >= 4) {
            sanitized = sanitized.replace(trimmedKey, "[REDACTED]")
        }
        sanitized = sanitized
            .replace(Regex("(Bearer\\s+)[A-Za-z0-9._\\-]{8,}", RegexOption.IGNORE_CASE), "$1[REDACTED]")
            .replace(Regex("(x-api-key[:\\s]+)[A-Za-z0-9._\\-]{8,}", RegexOption.IGNORE_CASE), "$1[REDACTED]")
            .replace(Regex("(x-goog-api-key[:\\s]+)[A-Za-z0-9._\\-]{8,}", RegexOption.IGNORE_CASE), "$1[REDACTED]")
            .replace(Regex("(sk-[A-Za-z0-9._\\-]{8,})"), "[REDACTED]")
            .replace(Regex("(xai-[A-Za-z0-9._\\-]{8,})"), "[REDACTED]")
        return sanitized
    }

    private fun buildChatHttpErrorMessage(
        providerId: String,
        statusCode: Int,
        rawBodyText: String,
        apiKey: String
    ): Pair<KeyCheckStatus, String> {
        val status = classifyStatusCodeAndBody(statusCode, rawBodyText, isGenerationProbe = true)
        val detail = extractProviderErrorDetail(rawBodyText)
        val sanitizedDetail = sanitizeSecret(detail, apiKey).take(160)
        val suffix = if (sanitizedDetail.isNotBlank()) ": $sanitizedDetail" else "."
        val message = when (status) {
            KeyCheckStatus.INVALID_KEY -> "Invalid API key for $providerId (HTTP $statusCode)$suffix"
            KeyCheckStatus.PERMISSION_DENIED -> "Permission denied for $providerId (HTTP $statusCode)$suffix"
            KeyCheckStatus.QUOTA_EXCEEDED -> "Quota or billing limit reached on $providerId (HTTP $statusCode)$suffix"
            KeyCheckStatus.RATE_LIMITED -> "$providerId rate limit reached (HTTP 429)$suffix"
            KeyCheckStatus.SERVER_ERROR -> "$providerId server error (HTTP $statusCode)$suffix"
            else -> "$providerId error HTTP $statusCode$suffix"
        }
        return status to message
    }

    private fun extractProviderErrorDetail(rawBody: String): String {
        val trimmed = rawBody.trim()
        if (trimmed.isEmpty()) return ""
        val parsed = runCatching {
            val json = JSONObject(trimmed)
            json.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
                ?: json.optString("message").takeIf { it.isNotBlank() }
                ?: json.optString("detail").takeIf { it.isNotBlank() }
        }.getOrNull()
        return (parsed ?: trimmed).replace('\n', ' ').trim()
    }

    internal fun validateModelForProvider(providerId: String, model: String): String {
        val clean = model.trim()
        if (clean.isEmpty()) {
            throw IllegalArgumentException("No model selected for $providerId. Open Settings and select an available model.")
        }
        val lower = clean.lowercase()
        val firebaseOnly = FIREBASE_AI_MODELS.any { it.equals(clean, ignoreCase = true) }

        if (providerId == "gemini") {
            if (firebaseOnly) {
                throw IllegalArgumentException(
                    "Model '$clean' is a built-in Firebase AI model and cannot be routed to Google AI Studio REST."
                )
            }
            val normalized = clean.removePrefix("models/")
            val normLower = normalized.lowercase()
            if (normalized.contains("/") ||
                !(normLower.startsWith("gemini-") || normLower.startsWith("gemma-") || normLower.startsWith("learnlm-"))
            ) {
                throw IllegalArgumentException(
                    "Model '$clean' is not a valid Google AI Studio Gemini model. Select a Gemini model in Settings."
                )
            }
            return normalized
        }

        if (firebaseOnly) {
            throw IllegalArgumentException(
                "Model '$clean' belongs to Firebase AI Logic and cannot be routed to $providerId."
            )
        }
        if (lower.startsWith("models/") || lower.startsWith("gemini-")) {
            throw IllegalArgumentException(
                "Gemini model '$clean' cannot be routed to $providerId. Select a $providerId model in Settings."
            )
        }
        if (providerId == "openai") {
            if (clean.contains("/") ||
                lower.startsWith("claude-") ||
                lower.startsWith("grok-") ||
                lower.startsWith("mistral-") ||
                lower.startsWith("llama-")
            ) {
                throw IllegalArgumentException("Model '$clean' does not belong to OpenAI.")
            }
        }
        if (providerId == "anthropic") {
            if (clean.contains("/") ||
                lower.startsWith("gpt-") ||
                lower.startsWith("o1-") ||
                lower.startsWith("o3-") ||
                lower.startsWith("o4-") ||
                lower.startsWith("chatgpt-") ||
                lower.startsWith("grok-") ||
                lower.startsWith("mistral-") ||
                lower.startsWith("llama-")
            ) {
                throw IllegalArgumentException("Model '$clean' does not belong to Anthropic Claude.")
            }
        }
        if (providerId == "xai") {
            if (clean.contains("/") ||
                lower.startsWith("claude-") ||
                lower.startsWith("gpt-") ||
                lower.startsWith("o1-") ||
                lower.startsWith("o3-") ||
                lower.startsWith("o4-") ||
                lower.startsWith("chatgpt-") ||
                lower.startsWith("mistral-") ||
                lower.startsWith("llama-")
            ) {
                throw IllegalArgumentException("Model '$clean' does not belong to xAI Grok.")
            }
        }
        if (providerId == "mistral" && (
                lower.startsWith("openai/") ||
                    lower.startsWith("anthropic/") ||
                    lower.startsWith("google/") ||
                    lower.startsWith("meta/") ||
                    lower.startsWith("llama-") ||
                    lower.startsWith("gpt-") ||
                    lower.startsWith("claude-") ||
                    lower.startsWith("grok-")
                )
        ) {
            throw IllegalArgumentException("Model '$clean' does not belong to Mistral.")
        }
        if (providerId == "groq" && (
                lower.startsWith("anthropic/") ||
                    lower.startsWith("mistral-") ||
                    lower.startsWith("claude-") ||
                    lower.startsWith("grok-") ||
                    lower.startsWith("gpt-4") ||
                    lower.startsWith("gpt-3.5") ||
                    lower.startsWith("gpt-5")
                )
        ) {
            throw IllegalArgumentException("Model '$clean' does not belong to Groq.")
        }
        if (providerId == "nvidia" && (
                lower.startsWith("anthropic/") ||
                    lower.startsWith("openai/") ||
                    lower.startsWith("claude-") ||
                    lower.startsWith("grok-") ||
                    lower.startsWith("gpt-4") ||
                    lower.startsWith("gpt-3.5") ||
                    lower.startsWith("gpt-5")
                )
        ) {
            throw IllegalArgumentException("Model '$clean' does not belong to NVIDIA NIM.")
        }
        return clean
    }

    private fun openAiChatEndpointFor(providerId: String): String = when (providerId) {
        "groq" -> endpoints.groqChatUrl()
        "openrouter" -> endpoints.openRouterChatUrl()
        "nvidia" -> endpoints.nvidiaChatUrl()
        "mistral" -> endpoints.mistralChatUrl()
        "openai" -> endpoints.openAiChatUrl()
        "xai" -> endpoints.xaiChatUrl()
        else -> throw IllegalArgumentException("Unsupported provider: $providerId")
    }

    internal fun buildOpenAiMessagesPayload(
        providerId: String,
        model: String,
        systemPrompt: String,
        history: List<Pair<String, String>>,
        userMessage: String,
        stream: Boolean,
        maxTokens: Int? = null
    ): JSONObject {
        val messagesArr = JSONArray().apply {
            if (systemPrompt.isNotBlank()) {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
            }
            history.takeLast(10).forEach { (role, content) ->
                if (content.isNotBlank()) {
                    put(
                        JSONObject()
                            .put("role", if (role == "assistant") "assistant" else "user")
                            .put("content", content)
                    )
                }
            }
            put(JSONObject().put("role", "user").put("content", userMessage))
        }
        return JSONObject().apply {
            put("model", model)
            put("messages", messagesArr)
            if (supportsCustomTemperature(providerId, model)) {
                put("temperature", 0.5)
            }
            if (maxTokens != null) {
                applyOpenAiTokenParameter(this, providerId, model, maxTokens)
            }
            put("stream", stream)
        }
    }

    suspend fun chatOpenAiCompatible(
        providerId: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        history: List<Pair<String, String>>,
        userMessage: String,
        maxTokens: Int? = null
    ): String = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        val validatedModel = validateModelForProvider(providerId, model)
        val endpoint = openAiChatEndpointFor(providerId)
        val body = buildOpenAiMessagesPayload(
            providerId = providerId,
            model = validatedModel,
            systemPrompt = systemPrompt,
            history = history,
            userMessage = userMessage,
            stream = false,
            maxTokens = maxTokens
        )

        val resp = try {
            client.post(endpoint) {
                header("Authorization", "Bearer $cleanKey")
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            recordUsage(providerId, "Network error")
            throw IllegalStateException("Network error while contacting $providerId.")
        }

        val rawBody = runCatching { resp.bodyAsText() }.getOrDefault("")
        if (!resp.status.isSuccess()) {
            val (status, message) = buildChatHttpErrorMessage(providerId, resp.status.value, rawBody, cleanKey)
            recordUsage(providerId, "HTTP ${resp.status.value}", isRateLimited = status == KeyCheckStatus.RATE_LIMITED)
            throw IllegalStateException(message)
        }
        val text = extractOpenAiChoiceText(rawBody)
        recordUsage(providerId, "OK")
        text.ifBlank { "No text returned." }
    }

    fun streamOpenAiCompatible(
        providerId: String,
        apiKey: String,
        model: String,
        systemPrompt: String,
        history: List<Pair<String, String>>,
        userMessage: String,
        maxTokens: Int? = null
    ): Flow<ByoStreamEvent> = flow {
        val cleanKey = apiKey.trim()
        val validatedModel = validateModelForProvider(providerId, model)
        val endpoint = openAiChatEndpointFor(providerId)
        val body = buildOpenAiMessagesPayload(
            providerId = providerId,
            model = validatedModel,
            systemPrompt = systemPrompt,
            history = history,
            userMessage = userMessage,
            stream = true,
            maxTokens = maxTokens
        )

        try {
            client.preparePost(endpoint) {
                header("Authorization", "Bearer $cleanKey")
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }.execute { resp ->
                if (!resp.status.isSuccess()) {
                    val rawErr = runCatching { resp.bodyAsText() }.getOrDefault("")
                    val (status, msg) = buildChatHttpErrorMessage(providerId, resp.status.value, rawErr, cleanKey)
                    recordUsage(providerId, "HTTP ${resp.status.value}", isRateLimited = status == KeyCheckStatus.RATE_LIMITED)
                    throw IllegalStateException(msg)
                }

                val isEventStream = resp.contentType()?.match(ContentType.Text.EventStream) == true
                val channel = resp.bodyAsChannel()
                val rawBuffer = StringBuilder()
                val accumulated = StringBuilder()
                var detectedPlainJson = false

                while (true) {
                    val line = channel.readUTF8Line() ?: break
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue

                    if (!isEventStream && !detectedPlainJson && accumulated.isEmpty() && trimmed.startsWith("{")) {
                        detectedPlainJson = true
                    }
                    if (detectedPlainJson) {
                        rawBuffer.append(line).append('\n')
                        continue
                    }

                    val dataPayload = when {
                        trimmed.startsWith("data:") -> trimmed.removePrefix("data:").trim()
                        trimmed.startsWith("{") -> trimmed
                        else -> continue
                    }
                    if (dataPayload == "[DONE]") break
                    if (!dataPayload.startsWith("{")) continue

                    val chunkJson = runCatching { JSONObject(dataPayload) }.getOrNull() ?: continue
                    val errObj = chunkJson.optJSONObject("error")
                    if (errObj != null) {
                        val errMsg = sanitizeSecret(errObj.optString("message").ifBlank { "$providerId stream error." }, cleanKey)
                        throw IllegalStateException(errMsg)
                    }
                    val choices = chunkJson.optJSONArray("choices") ?: continue
                    val choice0 = choices.optJSONObject(0) ?: continue
                    val deltaText = choice0.optJSONObject("delta")?.optString("content").orEmpty()
                    val fullMsgText = choice0.optJSONObject("message")?.optString("content").orEmpty()
                    val addition = deltaText.ifEmpty { if (accumulated.isEmpty()) fullMsgText else "" }
                    if (addition.isNotEmpty()) {
                        accumulated.append(addition)
                        emit(ByoStreamEvent.PartialText(accumulated.toString()))
                    }
                }

                val finalText = if (detectedPlainJson) {
                    extractOpenAiChoiceText(rawBuffer.toString())
                } else {
                    accumulated.toString().trim()
                }
                recordUsage(providerId, "OK")
                emit(ByoStreamEvent.Completed(finalText.ifBlank { "No text returned." }))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (_: Exception) {
            recordUsage(providerId, "Network error")
            throw IllegalStateException("Network error while contacting $providerId.")
        }
    }.flowOn(Dispatchers.IO)

    private fun extractOpenAiChoiceText(rawJson: String): String {
        val json = runCatching { JSONObject(rawJson) }.getOrNull() ?: return ""
        return json.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.trim()
            .orEmpty()
    }

    private fun buildAnthropicMessagesPayload(
        model: String,
        systemPrompt: String,
        history: List<Pair<String, String>>,
        userMessage: String,
        maxTokens: Int = ANTHROPIC_DEFAULT_MAX_TOKENS,
        stream: Boolean = false
    ): JSONObject {
        val normalizedTurns = mutableListOf<Pair<String, String>>()
        history.takeLast(10).forEach { (rawRole, content) ->
            val cleanContent = content.trim()
            if (cleanContent.isEmpty()) return@forEach
            val role = if (rawRole == "assistant") "assistant" else "user"
            if (normalizedTurns.isEmpty() && role == "assistant") {
                return@forEach
            }
            val last = normalizedTurns.lastOrNull()
            if (last != null && last.first == role) {
                normalizedTurns[normalizedTurns.lastIndex] = role to "${last.second}\n\n$cleanContent"
            } else {
                normalizedTurns.add(role to cleanContent)
            }
        }
        val cleanUser = userMessage.trim()
        val last = normalizedTurns.lastOrNull()
        if (last != null && last.first == "user") {
            normalizedTurns[normalizedTurns.lastIndex] = "user" to "${last.second}\n\n$cleanUser"
        } else {
            normalizedTurns.add("user" to cleanUser)
        }

        val messagesArr = JSONArray().apply {
            normalizedTurns.forEach { (role, content) ->
                put(JSONObject().put("role", role).put("content", content))
            }
        }

        return JSONObject().apply {
            put("model", model)
            put("max_tokens", maxTokens)
            if (systemPrompt.isNotBlank()) {
                put("system", systemPrompt)
            }
            put("messages", messagesArr)
            if (stream) {
                put("stream", true)
            }
        }
    }

    private fun extractAnthropicMessageText(rawJson: String): String {
        val json = runCatching { JSONObject(rawJson) }.getOrNull() ?: return ""
        val contentArr = json.optJSONArray("content") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until contentArr.length()) {
            val block = contentArr.optJSONObject(i) ?: continue
            val blockType = block.optString("type")
            if (blockType == "text" || (blockType.isEmpty() && block.has("text"))) {
                val txt = block.optString("text")
                if (txt.isNotEmpty()) sb.append(txt)
            }
        }
        return sb.toString().trim()
    }

    suspend fun chatAnthropicMessages(
        apiKey: String,
        model: String,
        systemPrompt: String,
        history: List<Pair<String, String>>,
        userMessage: String
    ): String = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        val validatedModel = validateModelForProvider("anthropic", model)
        val body = buildAnthropicMessagesPayload(
            model = validatedModel,
            systemPrompt = systemPrompt,
            history = history,
            userMessage = userMessage,
            stream = false
        )

        val resp = try {
            client.post(endpoints.anthropicMessagesUrl()) {
                header("x-api-key", cleanKey)
                header("anthropic-version", ANTHROPIC_API_VERSION)
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            recordUsage("anthropic", "Network error")
            throw IllegalStateException("Network error while contacting anthropic.")
        }

        val rawBody = runCatching { resp.bodyAsText() }.getOrDefault("")
        if (!resp.status.isSuccess()) {
            val (status, message) = buildChatHttpErrorMessage("anthropic", resp.status.value, rawBody, cleanKey)
            recordUsage("anthropic", "HTTP ${resp.status.value}", isRateLimited = status == KeyCheckStatus.RATE_LIMITED)
            throw IllegalStateException(message)
        }
        val text = extractAnthropicMessageText(rawBody)
        recordUsage("anthropic", "OK")
        text.ifBlank { "No text returned." }
    }

    fun streamAnthropicMessages(
        apiKey: String,
        model: String,
        systemPrompt: String,
        history: List<Pair<String, String>>,
        userMessage: String
    ): Flow<ByoStreamEvent> = flow {
        val cleanKey = apiKey.trim()
        val validatedModel = validateModelForProvider("anthropic", model)
        val body = buildAnthropicMessagesPayload(
            model = validatedModel,
            systemPrompt = systemPrompt,
            history = history,
            userMessage = userMessage,
            stream = true
        )

        try {
            client.preparePost(endpoints.anthropicMessagesUrl()) {
                header("x-api-key", cleanKey)
                header("anthropic-version", ANTHROPIC_API_VERSION)
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }.execute { resp ->
                if (!resp.status.isSuccess()) {
                    val rawErr = runCatching { resp.bodyAsText() }.getOrDefault("")
                    val (status, msg) = buildChatHttpErrorMessage("anthropic", resp.status.value, rawErr, cleanKey)
                    recordUsage("anthropic", "HTTP ${resp.status.value}", isRateLimited = status == KeyCheckStatus.RATE_LIMITED)
                    throw IllegalStateException(msg)
                }

                val isEventStream = resp.contentType()?.match(ContentType.Text.EventStream) == true
                val channel = resp.bodyAsChannel()
                val rawBuffer = StringBuilder()
                val accumulated = StringBuilder()
                var detectedPlainJson = false

                while (true) {
                    val line = channel.readUTF8Line() ?: break
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue

                    if (!isEventStream && !detectedPlainJson && accumulated.isEmpty() && trimmed.startsWith("{")) {
                        detectedPlainJson = true
                    }
                    if (detectedPlainJson) {
                        rawBuffer.append(line).append('\n')
                        continue
                    }

                    if (trimmed.startsWith("event:")) continue
                    val dataPayload = if (trimmed.startsWith("data:")) {
                        trimmed.removePrefix("data:").trim()
                    } else {
                        continue
                    }
                    if (dataPayload == "[DONE]") break
                    if (!dataPayload.startsWith("{")) continue

                    val evtJson = runCatching { JSONObject(dataPayload) }.getOrNull() ?: continue
                    when (evtJson.optString("type")) {
                        "content_block_start" -> {
                            val block = evtJson.optJSONObject("content_block")
                            if (block != null && block.optString("type") == "text") {
                                val text = block.optString("text")
                                if (text.isNotEmpty()) {
                                    accumulated.append(text)
                                    emit(ByoStreamEvent.PartialText(accumulated.toString()))
                                }
                            }
                        }
                        "content_block_delta" -> {
                            val delta = evtJson.optJSONObject("delta")
                            if (delta != null && delta.optString("type") == "text_delta") {
                                val text = delta.optString("text")
                                if (text.isNotEmpty()) {
                                    accumulated.append(text)
                                    emit(ByoStreamEvent.PartialText(accumulated.toString()))
                                }
                            }
                        }
                        "error" -> {
                            val errMsg = evtJson.optJSONObject("error")?.optString("message")
                                ?.takeIf { it.isNotBlank() }
                                ?: "Anthropic stream error."
                            throw IllegalStateException(sanitizeSecret(errMsg, cleanKey))
                        }
                        "message_stop" -> break
                    }
                }

                val finalText = if (detectedPlainJson) {
                    extractAnthropicMessageText(rawBuffer.toString())
                } else {
                    accumulated.toString().trim()
                }
                recordUsage("anthropic", "OK")
                emit(ByoStreamEvent.Completed(finalText.ifBlank { "No text returned." }))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (_: Exception) {
            recordUsage("anthropic", "Network error")
            throw IllegalStateException("Network error while contacting anthropic.")
        }
    }.flowOn(Dispatchers.IO)

    suspend fun chatDirectGeminiRest(
        apiKey: String,
        model: String,
        systemPrompt: String,
        history: List<Pair<String, String>>,
        userMessage: String
    ): String = withContext(Dispatchers.IO) {
        val cleanKey = apiKey.trim()
        val targetModel = validateModelForProvider("gemini", model)
        val url = endpoints.geminiGenerateContentUrl(targetModel)
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

        val resp = try {
            client.post(url) {
                header("x-goog-api-key", cleanKey)
                contentType(ContentType.Application.Json)
                setBody(body.toString())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            recordUsage("gemini", "Network error")
            throw IllegalStateException("Network error while contacting gemini.")
        }

        val rawBody = runCatching { resp.bodyAsText() }.getOrDefault("")
        if (!resp.status.isSuccess()) {
            val (status, message) = buildChatHttpErrorMessage("gemini", resp.status.value, rawBody, cleanKey)
            recordUsage("gemini", "HTTP ${resp.status.value}", isRateLimited = status == KeyCheckStatus.RATE_LIMITED)
            throw IllegalStateException(message)
        }
        val json = JSONObject(rawBody)
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
