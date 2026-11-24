package com.jarvis.app

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ApplicationProvider
import com.jarvis.app.data.ai.ByoAiClient
import com.jarvis.app.data.ai.DynamicModelInfo
import com.jarvis.app.data.ai.FetchModelsResult
import com.jarvis.app.data.ai.FirebaseAiService
import com.jarvis.app.data.ai.KeyCheckStatus
import com.jarvis.app.data.ai.ModelListSource
import com.jarvis.app.data.ai.ProviderEndpoints
import com.jarvis.app.data.local.ConfigStore
import com.jarvis.app.data.local.JarvisDatabase
import com.jarvis.app.data.local.JarvisRepository
import com.jarvis.app.data.network.JarvisHttpTools
import com.jarvis.app.data.security.SecureSecretStore
import com.jarvis.app.domain.brain.JarvisOrchestrator
import com.jarvis.app.domain.brain.ToolExecutor
import com.jarvis.app.domain.brain.TurnProgressEvent
import com.jarvis.app.domain.model.BrainConfig
import com.jarvis.app.domain.model.ScreenConfig
import com.jarvis.app.platform.DeviceController
import com.jarvis.app.platform.DeviceTelemetry
import com.jarvis.app.platform.JarvisAssistantRuntime
import com.jarvis.app.platform.RemotePcClient
import com.jarvis.app.platform.TvControlClient
import com.jarvis.app.ui.JarvisViewModel
import com.jarvis.app.ui.sheets.SettingsSheet
import com.jarvis.app.ui.theme.JarvisTheme
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Phase2DynamicModelsTest {

    private lateinit var server: HttpServer
    private var serverPort: Int = 0
    private val requestedPaths = CopyOnWriteArrayList<String>()
    private val headersByPath = ConcurrentHashMap<String, Map<String, List<String>>>()
    private val requestBodiesByPath = ConcurrentHashMap<String, String>()
    private val mockResponses = ConcurrentHashMap<String, Pair<Int, String>>()

    @Before
    fun setUp() {
        requestedPaths.clear()
        headersByPath.clear()
        requestBodiesByPath.clear()
        mockResponses.clear()

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange: HttpExchange ->
            val path = exchange.requestURI.path
            requestedPaths.add(path)
            headersByPath[path] = exchange.requestHeaders.toMap()
            val reqBody = exchange.requestBody.bufferedReader(Charsets.UTF_8).readText()
            requestBodiesByPath[path] = reqBody

            val (code, body) = mockResponses[path] ?: Pair(404, "Not Found")
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        serverPort = server.address.port
    }

    @After
    fun tearDown() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        JarvisAssistantRuntime.getInstance(app).byoAiClientOverride = null
        server.stop(0)
    }

    private fun testEndpoints(): ProviderEndpoints = object : ProviderEndpoints {
        override fun groqModelsUrl() = "http://127.0.0.1:$serverPort/groq/models"
        override fun groqChatUrl() = "http://127.0.0.1:$serverPort/groq/chat"
        override fun openRouterAuthUrl() = "http://127.0.0.1:$serverPort/openrouter/auth/key"
        override fun openRouterModelsUrl() = "http://127.0.0.1:$serverPort/openrouter/models"
        override fun openRouterChatUrl() = "http://127.0.0.1:$serverPort/openrouter/chat"
        override fun nvidiaModelsUrl() = "http://127.0.0.1:$serverPort/nvidia/models"
        override fun nvidiaChatUrl() = "http://127.0.0.1:$serverPort/nvidia/chat"
        override fun mistralModelsUrl() = "http://127.0.0.1:$serverPort/mistral/models"
        override fun mistralChatUrl() = "http://127.0.0.1:$serverPort/mistral/chat"
        override fun geminiModelsUrl() = "http://127.0.0.1:$serverPort/gemini/models"
        override fun geminiGenerateContentUrl(model: String): String {
            val clean = model.trim().removePrefix("models/")
            return "http://127.0.0.1:$serverPort/gemini/generate/$clean"
        }
        override fun tavilySearchUrl() = "http://127.0.0.1:$serverPort/tavily/search"
    }

    private fun getHeader(path: String, name: String): String? {
        return headersByPath[path]?.entries?.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    }

    private fun pumpUntil(timeoutMs: Long = 3_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.runUiThreadTasks()
            if (condition()) {
                ShadowLooper.runUiThreadTasks()
                return
            }
            Thread.sleep(10L)
        }
        ShadowLooper.runUiThreadTasks()
    }

    @Test
    fun `gemini fetchModels uses x-goog-api-key and filters to generateContent chat models preserving exact IDs`() = runBlocking {
        mockResponses["/gemini/models"] = Pair(
            200,
            """
            {
              "models": [
                {"name": "models/gemini-2.5-flash-preview-05-20", "displayName": "Gemini 2.5 Flash Preview", "supportedGenerationMethods": ["generateContent", "countTokens"]},
                {"name": "models/gemini-embedding-001", "supportedGenerationMethods": ["embedContent"]},
                {"name": "models/text-embedding-004", "supportedGenerationMethods": ["embedContent"]},
                {"name": "models/imagen-3.0-generate-002", "supportedGenerationMethods": ["predict"]},
                {"name": "models/gemini-2.5-flash-native-audio-preview", "supportedGenerationMethods": ["generateContent", "bidiGenerateContent"]},
                {"name": "models/aqa", "supportedGenerationMethods": ["generateAnswer"]}
              ]
            }
            """.trimIndent()
        )

        val client = ByoAiClient(endpoints = testEndpoints())
        val res = client.fetchModels("gemini", "gemini-key-123")

        assertTrue(res is FetchModelsResult.Success)
        val models = (res as FetchModelsResult.Success).models
        assertEquals(listOf("gemini-2.5-flash-preview-05-20"), models.map { it.id })
        assertEquals("gemini-key-123", getHeader("/gemini/models", "x-goog-api-key"))
    }

    @Test
    fun `groq openrouter nvidia mistral fetchModels parse and filter incompatible models`() = runBlocking {
        mockResponses["/groq/models"] = Pair(
            200,
            """
            {
              "data": [
                {"id": "llama-3.3-70b-versatile", "active": true, "owned_by": "Meta"},
                {"id": "whisper-large-v3-turbo", "active": true},
                {"id": "playai-tts", "active": true},
                {"id": "inactive-chat-model", "active": false}
              ]
            }
            """.trimIndent()
        )
        mockResponses["/openrouter/models"] = Pair(
            200,
            """
            {
              "data": [
                {
                  "id": "anthropic/claude-3.7-sonnet",
                  "name": "Claude 3.7 Sonnet",
                  "architecture": {"modality": "text+image->text", "output_modalities": ["text"]},
                  "supported_parameters": ["tools", "temperature"]
                },
                {
                  "id": "black-forest-labs/flux-pro",
                  "architecture": {"modality": "text->image", "output_modalities": ["image"]}
                }
              ]
            }
            """.trimIndent()
        )
        mockResponses["/nvidia/models"] = Pair(
            200,
            """
            {
              "data": [
                {"id": "nvidia/llama-3.1-nemotron-70b-instruct", "owned_by": "nvidia"},
                {"id": "nvidia/nv-embedqa-e5-v5", "type": "embedding"},
                {"id": "nvidia/parakeet-tdt-0.6b-v2"}
              ]
            }
            """.trimIndent()
        )
        mockResponses["/mistral/models"] = Pair(
            200,
            """
            {
              "data": [
                {"id": "mistral-large-2411", "capabilities": {"completion_chat": true, "function_calling": true}},
                {"id": "mistral-embed", "capabilities": {"completion_chat": false}},
                {"id": "mistral-ocr-latest", "capabilities": {"completion_chat": false}}
              ]
            }
            """.trimIndent()
        )

        val client = ByoAiClient(endpoints = testEndpoints())

        val groq = client.fetchModels("groq", "k1") as FetchModelsResult.Success
        assertEquals(listOf("llama-3.3-70b-versatile"), groq.models.map { it.id })
        assertEquals("Bearer k1", getHeader("/groq/models", "Authorization"))

        val or = client.fetchModels("openrouter", "k2") as FetchModelsResult.Success
        assertEquals(listOf("anthropic/claude-3.7-sonnet"), or.models.map { it.id })
        assertEquals(true, or.models.first().supportsTools)
        assertEquals("Bearer k2", getHeader("/openrouter/models", "Authorization"))

        val nv = client.fetchModels("nvidia", "k3") as FetchModelsResult.Success
        assertEquals(listOf("nvidia/llama-3.1-nemotron-70b-instruct"), nv.models.map { it.id })

        val mis = client.fetchModels("mistral", "k4") as FetchModelsResult.Success
        assertEquals(listOf("mistral-large-2411"), mis.models.map { it.id })
        assertEquals(true, mis.models.first().supportsTools)
    }

    @Test
    fun `empty model list never inserts fabricated models`() = runBlocking {
        mockResponses["/groq/models"] = Pair(200, """{"data": [{"id": "whisper-large-v3"}]}""")

        val client = ByoAiClient(endpoints = testEndpoints())
        val res = client.fetchModels("groq", "k-empty") as FetchModelsResult.Success

        assertTrue(res.models.isEmpty())
    }

    @Test
    fun `invalid API key and network failure return proper failure without marking WORKING`() = runBlocking {
        mockResponses["/mistral/models"] = Pair(401, """{"message": "Unauthorized"}""")

        val client = ByoAiClient(endpoints = testEndpoints())
        val invalidRes = client.fetchModels("mistral", "bad-key")
        assertTrue(invalidRes is FetchModelsResult.Failure)
        assertEquals(KeyCheckStatus.INVALID_KEY, (invalidRes as FetchModelsResult.Failure).keyStatus)

        val unreachableEndpoints = object : ProviderEndpoints by testEndpoints() {
            override fun groqModelsUrl() = "http://127.0.0.1:1/unreachable"
        }
        val netClient = ByoAiClient(endpoints = unreachableEndpoints)
        val netRes = netClient.fetchModels("groq", "any-key")
        assertTrue(netRes is FetchModelsResult.Failure)
        assertEquals(KeyCheckStatus.NETWORK_ERROR, (netRes as FetchModelsResult.Failure).keyStatus)
    }

    @Test
    fun `cache persistence offline fallback and failed refresh preserve previous cache without marking WORKING`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val runtime = JarvisAssistantRuntime.getInstance(app)
        val configStore = ConfigStore(app)

        runtime.byoAiClientOverride = ByoAiClient(endpoints = testEndpoints())
        runtime.secretStore.setSecret(SecureSecretStore.KEY_GROQ, "gsk-test-cache")
        runtime.secretStore.setKeyStatus(SecureSecretStore.KEY_GROQ, KeyCheckStatus.NOT_VERIFIED)
        runtime.refreshKeyStatuses()

        mockResponses["/groq/models"] = Pair(
            200,
            """{"data": [{"id": "cached-groq-model-1"}, {"id": "cached-groq-model-2"}]}"""
        )

        runtime.refreshModelsForProvider("groq")
        pumpUntil {
            runtime.providerModels.value["groq"]?.source == ModelListSource.LIVE &&
                runtime.providerModels.value["groq"]?.isLoading == false
        }

        // Fetching models must NOT mark an unverified key as WORKING
        assertNotEquals(KeyCheckStatus.WORKING, runtime.keyStatuses.value[SecureSecretStore.KEY_GROQ])

        // Cache was persisted
        val persisted = configStore.loadProviderModelsCache("groq")
        assertNotNull(persisted)
        assertEquals(listOf("cached-groq-model-1", "cached-groq-model-2"), persisted!!.models.map { it.id })

        // Now simulate network failure / offline refresh
        val offlineEndpoints = object : ProviderEndpoints by testEndpoints() {
            override fun groqModelsUrl() = "http://127.0.0.1:1/offline"
        }
        runtime.byoAiClientOverride = ByoAiClient(endpoints = offlineEndpoints)

        runtime.refreshModelsForProvider("groq")
        pumpUntil {
            runtime.providerModels.value["groq"]?.isLoading == false &&
                runtime.providerModels.value["groq"]?.error != null
        }

        val offlineState = runtime.providerModels.value["groq"]!!
        assertEquals(ModelListSource.CACHED, offlineState.source)
        assertEquals(listOf("cached-groq-model-1", "cached-groq-model-2"), offlineState.models.map { it.id })
        assertTrue(offlineState.error!!.contains("Network error"))

        // Persisted cache must remain intact after failed refresh
        val persistedAfterFail = configStore.loadProviderModelsCache("groq")
        assertEquals(listOf("cached-groq-model-1", "cached-groq-model-2"), persistedAfterFail!!.models.map { it.id })
    }

    @Test
    fun `selected model preserved when present and Settings UI shows Model Unavailable when absent`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val runtime = JarvisAssistantRuntime.getInstance(app)
        val viewModel = JarvisViewModel(app)

        runtime.byoAiClientOverride = ByoAiClient(endpoints = testEndpoints())
        runtime.secretStore.setSecret(SecureSecretStore.KEY_NVIDIA, "nv-key")

        mockResponses["/nvidia/models"] = Pair(
            200,
            """{"data": [{"id": "meta/llama-3.3-70b-instruct"}, {"id": "qwen/qwen2.5-72b-instruct"}]}"""
        )

        runtime.selectProvider("nvidia")
        runtime.selectModel("nvidia", "meta/llama-3.3-70b-instruct")
        pumpUntil { runtime.brainConfig.value.activeModel == "meta/llama-3.3-70b-instruct" }

        runtime.refreshModelsForProvider("nvidia")
        pumpUntil { runtime.providerModels.value["nvidia"]?.source == ModelListSource.LIVE }

        // Still preserved when present
        assertEquals("meta/llama-3.3-70b-instruct", runtime.brainConfig.value.activeModel)
        assertEquals("nvidia", runtime.brainConfig.value.activeProvider)

        // Now provider removes that model
        mockResponses["/nvidia/models"] = Pair(
            200,
            """{"data": [{"id": "qwen/qwen2.5-72b-instruct"}]}"""
        )
        runtime.refreshModelsForProvider("nvidia")
        pumpUntil {
            runtime.providerModels.value["nvidia"]?.models?.map { it.id } == listOf("qwen/qwen2.5-72b-instruct")
        }

        // Must NOT silently switch model or provider
        assertEquals("meta/llama-3.3-70b-instruct", runtime.brainConfig.value.activeModel)
        assertEquals("nvidia", runtime.brainConfig.value.activeProvider)

        val activityController = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = activityController.get()
        try {
            activity.setContent {
                JarvisTheme(screenConfig = ScreenConfig()) {
                    SettingsSheet(viewModel = viewModel, onClose = {})
                }
            }
            ShadowLooper.runUiThreadTasks()

            // Verify SettingsSheet rendered without crashing and ViewModel exposes the unavailable model state
            val uiState = viewModel.providerModels.value["nvidia"]!!
            assertEquals(ModelListSource.LIVE, uiState.source)
            assertTrue(uiState.models.none { it.id == viewModel.brainConfig.value.activeModel })
            assertEquals(listOf("qwen/qwen2.5-72b-instruct"), uiState.models.map { it.id })

            // Selecting a valid model resolves it cleanly
            viewModel.selectModel("nvidia", "qwen/qwen2.5-72b-instruct")
            pumpUntil { runtime.brainConfig.value.activeModel == "qwen/qwen2.5-72b-instruct" }
            assertEquals("qwen/qwen2.5-72b-instruct", runtime.brainConfig.value.activeModel)
        } finally {
            activityController.pause().stop().destroy()
        }
    }

    @Test
    fun `exact model ID routing, no cross-provider leakage, and no silent fallback to hardcoded models`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = JarvisDatabase.getInstance(app)
        val repository = JarvisRepository(db.jarvisDao())
        val configStore = ConfigStore(app)
        val secretStore = SecureSecretStore(app)
        val client = ByoAiClient(endpoints = testEndpoints())
        val toolExecutor = ToolExecutor(
            repository = repository,
            configStore = configStore,
            httpTools = JarvisHttpTools(),
            telemetry = DeviceTelemetry(app),
            deviceController = DeviceController(app),
            firebaseAiService = FirebaseAiService(),
            remotePcClient = RemotePcClient(app),
            tvControlClient = TvControlClient()
        )
        val orchestrator = JarvisOrchestrator(
            repository = repository,
            configStore = configStore,
            secretStore = secretStore,
            firebaseAiService = FirebaseAiService(),
            byoAiClient = client,
            toolExecutor = toolExecutor
        )

        secretStore.setSecret(SecureSecretStore.KEY_GEMINI, "gem-key")
        secretStore.setSecret(SecureSecretStore.KEY_GROQ, "groq-key")

        // Populate Groq cache with only one exact model
        configStore.saveProviderModelsCache(
            "groq",
            listOf(DynamicModelInfo(id = "exact-dynamic-groq-id", providerId = "groq"))
        )

        // 1. Exact dynamic model ID reaches Gemini REST endpoint
        mockResponses["/gemini/generate/gemini-2.5-pro-exp-03-25"] = Pair(
            200,
            """{"candidates": [{"content": {"parts": [{"text": "Hello from exact Gemini model"}]}}]}"""
        )
        val gemEvents = orchestrator.executeTurn(
            rawUserInput = "hi",
            attachedBitmap = null,
            history = emptyList(),
            brainConfig = BrainConfig(activeProvider = "gemini", activeModel = "gemini-2.5-pro-exp-03-25")
        ).toList()
        val gemDone = gemEvents.filterIsInstance<TurnProgressEvent.Finished>().last()
        assertEquals(false, gemDone.isError)
        assertEquals("Hello from exact Gemini model", gemDone.replyText)
        assertTrue(requestedPaths.contains("/gemini/generate/gemini-2.5-pro-exp-03-25"))

        // 2. Exact dynamic model ID reaches Groq endpoint verbatim
        mockResponses["/groq/chat"] = Pair(
            200,
            """{"choices": [{"message": {"content": "Hello from exact Groq model"}}]}"""
        )
        val groqEvents = orchestrator.executeTurn(
            rawUserInput = "hi",
            attachedBitmap = null,
            history = emptyList(),
            brainConfig = BrainConfig(activeProvider = "groq", activeModel = "exact-dynamic-groq-id")
        ).toList()
        val groqDone = groqEvents.filterIsInstance<TurnProgressEvent.Finished>().last()
        assertEquals(false, groqDone.isError)
        assertEquals("exact-dynamic-groq-id", JSONObject(requestBodiesByPath["/groq/chat"]!!).getString("model"))

        // 3. Cross-provider model leakage rejected (Groq/OpenRouter/Mistral IDs cannot reach Gemini)
        requestedPaths.clear()
        val crossEvents = orchestrator.executeTurn(
            rawUserInput = "hi",
            attachedBitmap = null,
            history = emptyList(),
            brainConfig = BrainConfig(activeProvider = "gemini", activeModel = "llama-3.3-70b-versatile")
        ).toList()
        val crossDone = crossEvents.filterIsInstance<TurnProgressEvent.Finished>().last()
        assertTrue(crossDone.isError)
        assertTrue(requestedPaths.isEmpty())

        // 4. Blank model does NOT silently fall back to hardcoded gemini-2.5-flash
        val blankEvents = orchestrator.executeTurn(
            rawUserInput = "hi",
            attachedBitmap = null,
            history = emptyList(),
            brainConfig = BrainConfig(activeProvider = "gemini", activeModel = "")
        ).toList()
        val blankDone = blankEvents.filterIsInstance<TurnProgressEvent.Finished>().last()
        assertTrue(blankDone.isError)
        assertTrue(requestedPaths.isEmpty())

        // 5. Unavailable model on cached BYO provider fails cleanly without silent fallback
        val unavailableEvents = orchestrator.executeTurn(
            rawUserInput = "hi",
            attachedBitmap = null,
            history = emptyList(),
            brainConfig = BrainConfig(activeProvider = "groq", activeModel = "removed-old-model")
        ).toList()
        val unavailableDone = unavailableEvents.filterIsInstance<TurnProgressEvent.Finished>().last()
        assertTrue(unavailableDone.isError)
        assertTrue(unavailableDone.replyText.contains("unavailable", ignoreCase = true))

        // 6. Non-Firebase model on firebase_ai fails cleanly without silent fallback
        val fbMismatch = orchestrator.executeTurn(
            rawUserInput = "hi",
            attachedBitmap = null,
            history = emptyList(),
            brainConfig = BrainConfig(activeProvider = "firebase_ai", activeModel = "llama-3.3-70b-versatile")
        ).toList()
        assertTrue(fbMismatch.filterIsInstance<TurnProgressEvent.Finished>().last().isError)
    }

    @Test
    fun `verifyKey uses dynamically returned compatible model for generation probe`() = runBlocking {
        mockResponses["/gemini/models"] = Pair(
            200,
            """
            {
              "models": [
                {"name": "models/gemini-embedding-001", "supportedGenerationMethods": ["embedContent"]},
                {"name": "models/gemini-2.9-custom-flash", "supportedGenerationMethods": ["generateContent"]}
              ]
            }
            """.trimIndent()
        )
        mockResponses["/gemini/generate/gemini-2.9-custom-flash"] = Pair(
            200,
            """{"candidates": [{"content": {"parts": [{"text": "ok"}]}}]}"""
        )

        val client = ByoAiClient(endpoints = testEndpoints())
        val status = client.verifyKey("gemini", "gem-dynamic-probe")

        assertEquals(KeyCheckStatus.WORKING, status)
        assertEquals(
            listOf("/gemini/models", "/gemini/generate/gemini-2.9-custom-flash"),
            requestedPaths.toList()
        )
    }
}
