package com.jarvis.app

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ApplicationProvider
import com.jarvis.app.data.ai.ByoAiClient
import com.jarvis.app.data.ai.ByoStreamEvent
import com.jarvis.app.data.ai.DefaultProviderEndpoints
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
import com.jarvis.app.domain.model.BYO_LLM_PROVIDER_IDS
import com.jarvis.app.domain.model.BrainConfig
import com.jarvis.app.domain.model.PROVIDER_CATALOG
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
class Phase3DirectProvidersTest {

    private data class MockHttpReply(
        val statusCode: Int,
        val body: String,
        val contentType: String = "application/json"
    )

    private lateinit var server: HttpServer
    private var serverPort: Int = 0
    private val requestedPaths = CopyOnWriteArrayList<String>()
    private val headersByPath = ConcurrentHashMap<String, Map<String, List<String>>>()
    private val requestBodiesByPath = ConcurrentHashMap<String, String>()
    private val mockResponses = ConcurrentHashMap<String, MockHttpReply>()

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

            val reply = mockResponses[path] ?: MockHttpReply(404, "Not Found")
            val bytes = reply.body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", reply.contentType)
            exchange.sendResponseHeaders(reply.statusCode, bytes.size.toLong())
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
        override fun geminiGenerateContentUrl(model: String) = "http://127.0.0.1:$serverPort/gemini/generate/$model"
        override fun openAiModelsUrl() = "http://127.0.0.1:$serverPort/openai/v1/models"
        override fun openAiChatUrl() = "http://127.0.0.1:$serverPort/openai/v1/chat/completions"
        override fun anthropicModelsUrl() = "http://127.0.0.1:$serverPort/anthropic/v1/models"
        override fun anthropicMessagesUrl() = "http://127.0.0.1:$serverPort/anthropic/v1/messages"
        override fun xaiModelsUrl() = "http://127.0.0.1:$serverPort/xai/v1/models"
        override fun xaiChatUrl() = "http://127.0.0.1:$serverPort/xai/v1/chat/completions"
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

    private fun createOrchestrator(app: Application, client: ByoAiClient): Triple<JarvisOrchestrator, SecureSecretStore, ConfigStore> {
        val db = JarvisDatabase.getInstance(app)
        val repository = JarvisRepository(db.jarvisDao())
        val configStore = ConfigStore(app)
        val secretStore = SecureSecretStore(app)
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
        return Triple(orchestrator, secretStore, configStore)
    }

    @Test
    fun `default endpoints and catalog include openai anthropic and xai accurately`() {
        assertEquals("https://api.openai.com/v1/models", DefaultProviderEndpoints.openAiModelsUrl())
        assertEquals("https://api.openai.com/v1/chat/completions", DefaultProviderEndpoints.openAiChatUrl())
        assertEquals("https://api.anthropic.com/v1/models", DefaultProviderEndpoints.anthropicModelsUrl())
        assertEquals("https://api.anthropic.com/v1/messages", DefaultProviderEndpoints.anthropicMessagesUrl())
        assertEquals("https://api.x.ai/v1/models", DefaultProviderEndpoints.xaiModelsUrl())
        assertEquals("https://api.x.ai/v1/chat/completions", DefaultProviderEndpoints.xaiChatUrl())

        assertTrue(setOf("openai", "anthropic", "xai").all { it in BYO_LLM_PROVIDER_IDS })
        val catalogIds = PROVIDER_CATALOG.map { it.id }.toSet()
        assertTrue(setOf("openai", "anthropic", "xai").all { it in catalogIds })
        PROVIDER_CATALOG.filter { it.id in setOf("openai", "anthropic", "xai") }.forEach {
            assertTrue(it.requiresKey)
            assertTrue(it.models.isEmpty())
            assertEquals("", it.defaultModel)
        }
    }

    @Test
    fun `fetchModels uses provider-specific auth headers and filters incompatible models for openai anthropic and xai`() = runBlocking {
        mockResponses["/openai/v1/models"] = MockHttpReply(
            200,
            """
            {
              "data": [
                {"id": "gpt-4o", "owned_by": "openai"},
                {"id": "gpt-4o-mini", "owned_by": "openai"},
                {"id": "o3-mini", "owned_by": "openai"},
                {"id": "text-embedding-3-large"},
                {"id": "whisper-1"},
                {"id": "tts-1"},
                {"id": "dall-e-3"},
                {"id": "gpt-image-1"},
                {"id": "gpt-4o-realtime-preview"},
                {"id": "omni-moderation-latest"},
                {"id": "babbage-002"}
              ]
            }
            """.trimIndent()
        )
        mockResponses["/anthropic/v1/models"] = MockHttpReply(
            200,
            """
            {
              "data": [
                {"id": "claude-sonnet-4-20250514", "display_name": "Claude Sonnet 4", "type": "model"},
                {"id": "claude-3-5-haiku-20241022", "display_name": "Claude 3.5 Haiku", "type": "model"}
              ]
            }
            """.trimIndent()
        )
        mockResponses["/xai/v1/models"] = MockHttpReply(
            200,
            """
            {
              "data": [
                {"id": "grok-3", "owned_by": "xai"},
                {"id": "grok-3-mini", "owned_by": "xai"},
                {"id": "grok-2-image-1212", "owned_by": "xai"},
                {"id": "grok-imagine-video", "owned_by": "xai"}
              ]
            }
            """.trimIndent()
        )

        val client = ByoAiClient(endpoints = testEndpoints())

        val openAiRes = client.fetchModels("openai", "sk-openai-secret") as FetchModelsResult.Success
        assertEquals(listOf("gpt-4o", "gpt-4o-mini", "o3-mini"), openAiRes.models.map { it.id })
        assertEquals("Bearer sk-openai-secret", getHeader("/openai/v1/models", "Authorization"))

        val anthropicRes = client.fetchModels("anthropic", "sk-ant-secret") as FetchModelsResult.Success
        assertEquals(listOf("claude-sonnet-4-20250514", "claude-3-5-haiku-20241022"), anthropicRes.models.map { it.id })
        assertEquals("Claude Sonnet 4", anthropicRes.models.first().displayName)
        assertEquals("sk-ant-secret", getHeader("/anthropic/v1/models", "x-api-key"))
        assertEquals(ByoAiClient.ANTHROPIC_API_VERSION, getHeader("/anthropic/v1/models", "anthropic-version"))
        assertEquals(null, getHeader("/anthropic/v1/models", "Authorization"))

        val xaiRes = client.fetchModels("xai", "xai-secret-token") as FetchModelsResult.Success
        assertEquals(listOf("grok-3", "grok-3-mini"), xaiRes.models.map { it.id })
        assertEquals("Bearer xai-secret-token", getHeader("/xai/v1/models", "Authorization"))
    }

    @Test
    fun `verifyKey performs authenticated model check plus native generation probe for openai anthropic and xai`() = runBlocking {
        // OpenAI
        mockResponses["/openai/v1/models"] = MockHttpReply(
            200,
            """{"data": [{"id": "o3-mini"}, {"id": "gpt-4o-mini"}]}"""
        )
        mockResponses["/openai/v1/chat/completions"] = MockHttpReply(
            200,
            """{"choices": [{"message": {"content": "ok"}}]}"""
        )
        // Anthropic
        mockResponses["/anthropic/v1/models"] = MockHttpReply(
            200,
            """{"data": [{"id": "claude-3-5-haiku-20241022", "display_name": "Claude 3.5 Haiku"}]}"""
        )
        mockResponses["/anthropic/v1/messages"] = MockHttpReply(
            200,
            """{"id": "msg_1", "type": "message", "role": "assistant", "content": [{"type": "text", "text": "ok"}]}"""
        )
        // xAI
        mockResponses["/xai/v1/models"] = MockHttpReply(
            200,
            """{"data": [{"id": "grok-3-mini"}]}"""
        )
        mockResponses["/xai/v1/chat/completions"] = MockHttpReply(
            200,
            """{"choices": [{"message": {"content": "ok"}}]}"""
        )

        val client = ByoAiClient(endpoints = testEndpoints())

        assertEquals(KeyCheckStatus.WORKING, client.verifyKey("openai", "sk-openai-valid"))
        assertEquals("gpt-4o-mini", JSONObject(requestBodiesByPath["/openai/v1/chat/completions"]!!).getString("model"))
        assertEquals("Bearer sk-openai-valid", getHeader("/openai/v1/chat/completions", "Authorization"))

        assertEquals(KeyCheckStatus.WORKING, client.verifyKey("anthropic", "sk-ant-valid"))
        val anthropicProbe = JSONObject(requestBodiesByPath["/anthropic/v1/messages"]!!)
        assertEquals("claude-3-5-haiku-20241022", anthropicProbe.getString("model"))
        assertEquals(1, anthropicProbe.getInt("max_tokens"))
        assertEquals("sk-ant-valid", getHeader("/anthropic/v1/messages", "x-api-key"))
        assertEquals(ByoAiClient.ANTHROPIC_API_VERSION, getHeader("/anthropic/v1/messages", "anthropic-version"))

        assertEquals(KeyCheckStatus.WORKING, client.verifyKey("xai", "xai-valid"))
        assertEquals("grok-3-mini", JSONObject(requestBodiesByPath["/xai/v1/chat/completions"]!!).getString("model"))
        assertEquals("Bearer xai-valid", getHeader("/xai/v1/chat/completions", "Authorization"))
    }

    @Test
    fun `error classification handles 401 403 429 quota credit-balance 529 and network errors cleanly`() = runBlocking {
        val client = ByoAiClient(endpoints = testEndpoints())

        mockResponses["/openai/v1/models"] = MockHttpReply(401, """{"error": {"message": "Incorrect API key provided"}}""")
        assertEquals(KeyCheckStatus.INVALID_KEY, client.verifyKey("openai", "bad-openai"))

        mockResponses["/anthropic/v1/models"] = MockHttpReply(403, """{"error": {"type": "permission_error", "message": "Forbidden"}}""")
        assertEquals(KeyCheckStatus.PERMISSION_DENIED, client.verifyKey("anthropic", "restricted-ant"))

        mockResponses["/xai/v1/models"] = MockHttpReply(429, """{"error": "Rate limit exceeded"}""")
        assertEquals(KeyCheckStatus.RATE_LIMITED, client.verifyKey("xai", "busy-xai"))

        mockResponses["/openai/v1/models"] = MockHttpReply(200, """{"data": [{"id": "gpt-4o-mini"}]}""")
        mockResponses["/openai/v1/chat/completions"] = MockHttpReply(
            429,
            """{"error": {"code": "insufficient_quota", "message": "You exceeded your current quota"}}"""
        )
        assertEquals(KeyCheckStatus.QUOTA_EXCEEDED, client.verifyKey("openai", "empty-wallet-openai"))

        mockResponses["/anthropic/v1/models"] = MockHttpReply(200, """{"data": [{"id": "claude-3-5-haiku-20241022"}]}""")
        mockResponses["/anthropic/v1/messages"] = MockHttpReply(
            400,
            """{"error": {"type": "invalid_request_error", "message": "Your credit balance is too low to access the Anthropic API."}}"""
        )
        assertEquals(KeyCheckStatus.QUOTA_EXCEEDED, client.verifyKey("anthropic", "no-credit-ant"))

        mockResponses["/anthropic/v1/messages"] = MockHttpReply(529, """{"error": {"type": "overloaded_error"}}""")
        assertEquals(KeyCheckStatus.SERVER_ERROR, client.verifyKey("anthropic", "overloaded-ant"))

        val unreachable = ByoAiClient(
            endpoints = object : ProviderEndpoints by testEndpoints() {
                override fun xaiModelsUrl() = "http://127.0.0.1:1/unreachable"
            }
        )
        assertEquals(KeyCheckStatus.NETWORK_ERROR, unreachable.verifyKey("xai", "any-key"))
    }

    @Test
    fun `anthropic native request schema and SSE streaming emit incremental events without OpenAI payload format`() = runBlocking {
        val ssePayload = listOf(
            "event: message_start",
            """data: {"type":"message_start","message":{"id":"msg_123","role":"assistant"}}""",
            "",
            "event: content_block_delta",
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello "}}""",
            "",
            "event: content_block_delta",
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"from Claude"}}""",
            "",
            "event: message_stop",
            """data: {"type":"message_stop"}"""
        ).joinToString("\n") + "\n"

        mockResponses["/anthropic/v1/messages"] = MockHttpReply(
            statusCode = 200,
            body = ssePayload,
            contentType = "text/event-stream"
        )

        val app = ApplicationProvider.getApplicationContext<Application>()
        val client = ByoAiClient(endpoints = testEndpoints())
        val (orchestrator, secretStore, _) = createOrchestrator(app, client)

        secretStore.setSecret(SecureSecretStore.KEY_ANTHROPIC, "sk-ant-live-test")

        val events = orchestrator.executeTurn(
            rawUserInput = "Say hi",
            attachedBitmap = null,
            history = emptyList(),
            brainConfig = BrainConfig(activeProvider = "anthropic", activeModel = "claude-sonnet-4-20250514")
        ).toList()

        val partials = events.filterIsInstance<TurnProgressEvent.StreamingText>().map { it.text }
        assertEquals(listOf("Hello ", "Hello from Claude"), partials)
        val finished = events.filterIsInstance<TurnProgressEvent.Finished>().last()
        assertFalse(finished.isError)
        assertEquals("Hello from Claude", finished.replyText)

        // Verify Anthropic native request payload structure (top-level system, max_tokens, no system role in messages)
        val sentJson = JSONObject(requestBodiesByPath["/anthropic/v1/messages"]!!)
        assertEquals("claude-sonnet-4-20250514", sentJson.getString("model"))
        assertTrue(sentJson.getInt("max_tokens") > 0)
        assertTrue(sentJson.getString("system").isNotBlank())
        assertTrue(sentJson.getBoolean("stream"))
        val sentMessages = sentJson.getJSONArray("messages")
        assertEquals(1, sentMessages.length())
        assertEquals("user", sentMessages.getJSONObject(0).getString("role"))
        assertEquals("Say hi", sentMessages.getJSONObject(0).getString("content"))
    }

    @Test
    fun `openai and xai streaming responses emit partial chunks and exact model routing`() = runBlocking {
        val openAiSse = listOf(
            """data: {"choices":[{"delta":{"content":"OpenAI "}}]}""",
            "",
            """data: {"choices":[{"delta":{"content":"works"}}]}""",
            "",
            "data: [DONE]"
        ).joinToString("\n") + "\n"

        val xaiSse = listOf(
            """data: {"choices":[{"delta":{"content":"Grok "}}]}""",
            "",
            """data: {"choices":[{"delta":{"content":"ready"}}]}""",
            "",
            "data: [DONE]"
        ).joinToString("\n") + "\n"

        mockResponses["/openai/v1/chat/completions"] = MockHttpReply(200, openAiSse, "text/event-stream")
        mockResponses["/xai/v1/chat/completions"] = MockHttpReply(200, xaiSse, "text/event-stream")

        val app = ApplicationProvider.getApplicationContext<Application>()
        val client = ByoAiClient(endpoints = testEndpoints())
        val (orchestrator, secretStore, _) = createOrchestrator(app, client)

        secretStore.setSecret(SecureSecretStore.KEY_OPENAI, "sk-openai-only")
        secretStore.setSecret(SecureSecretStore.KEY_XAI, "xai-grok-only")

        val openAiEvents = orchestrator.executeTurn(
            rawUserInput = "Test OpenAI",
            attachedBitmap = null,
            history = emptyList(),
            brainConfig = BrainConfig(activeProvider = "openai", activeModel = "gpt-4o")
        ).toList()
        assertEquals(
            listOf("OpenAI ", "OpenAI works"),
            openAiEvents.filterIsInstance<TurnProgressEvent.StreamingText>().map { it.text }
        )
        assertEquals("OpenAI works", openAiEvents.filterIsInstance<TurnProgressEvent.Finished>().last().replyText)
        assertEquals("gpt-4o", JSONObject(requestBodiesByPath["/openai/v1/chat/completions"]!!).getString("model"))
        assertEquals("Bearer sk-openai-only", getHeader("/openai/v1/chat/completions", "Authorization"))

        val xaiEvents = orchestrator.executeTurn(
            rawUserInput = "Test xAI",
            attachedBitmap = null,
            history = emptyList(),
            brainConfig = BrainConfig(activeProvider = "xai", activeModel = "grok-3")
        ).toList()
        assertEquals(
            listOf("Grok ", "Grok ready"),
            xaiEvents.filterIsInstance<TurnProgressEvent.StreamingText>().map { it.text }
        )
        assertEquals("Grok ready", xaiEvents.filterIsInstance<TurnProgressEvent.Finished>().last().replyText)
        assertEquals("grok-3", JSONObject(requestBodiesByPath["/xai/v1/chat/completions"]!!).getString("model"))
        assertEquals("Bearer xai-grok-only", getHeader("/xai/v1/chat/completions", "Authorization"))
    }

    @Test
    fun `no key or model leaks across providers and error messages redact secrets`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val client = ByoAiClient(endpoints = testEndpoints())
        val (orchestrator, secretStore, _) = createOrchestrator(app, client)

        val secretOpenAi = "sk-proj-supersecret-openai-9999"
        val secretXai = "xai-supersecret-grok-8888"
        val secretAnthropic = "sk-ant-supersecret-claude-7777"

        // Save distinct keys per provider and confirm isolation
        secretStore.setSecret(SecureSecretStore.KEY_OPENAI, secretOpenAi)
        secretStore.setSecret(SecureSecretStore.KEY_ANTHROPIC, secretAnthropic)
        secretStore.setSecret(SecureSecretStore.KEY_XAI, secretXai)

        assertEquals(secretOpenAi, secretStore.getSecret(SecureSecretStore.KEY_OPENAI))
        assertEquals(secretAnthropic, secretStore.getSecret(SecureSecretStore.KEY_ANTHROPIC))
        assertEquals(secretXai, secretStore.getSecret(SecureSecretStore.KEY_XAI))

        // Clearing OpenAI must leave Anthropic and xAI intact
        secretStore.setSecret(SecureSecretStore.KEY_OPENAI, "")
        assertEquals("", secretStore.getSecret(SecureSecretStore.KEY_OPENAI))
        assertEquals(secretAnthropic, secretStore.getSecret(SecureSecretStore.KEY_ANTHROPIC))
        assertEquals(secretXai, secretStore.getSecret(SecureSecretStore.KEY_XAI))

        // Cross-provider model leakage blocked before any network call
        requestedPaths.clear()
        val leakToAnthropic = orchestrator.executeTurn(
            rawUserInput = "hello",
            attachedBitmap = null,
            history = emptyList(),
            brainConfig = BrainConfig(activeProvider = "anthropic", activeModel = "gpt-4o")
        ).toList().filterIsInstance<TurnProgressEvent.Finished>().last()
        assertTrue(leakToAnthropic.isError)
        assertTrue(requestedPaths.isEmpty())

        val leakToXai = orchestrator.executeTurn(
            rawUserInput = "hello",
            attachedBitmap = null,
            history = emptyList(),
            brainConfig = BrainConfig(activeProvider = "xai", activeModel = "claude-sonnet-4-20250514")
        ).toList().filterIsInstance<TurnProgressEvent.Finished>().last()
        assertTrue(leakToXai.isError)
        assertTrue(requestedPaths.isEmpty())

        val leakOpenRouterIdToOpenAi = orchestrator.executeTurn(
            rawUserInput = "hello",
            attachedBitmap = null,
            history = emptyList(),
            brainConfig = BrainConfig(activeProvider = "openai", activeModel = "openai/gpt-4o")
        ).toList().filterIsInstance<TurnProgressEvent.Finished>().last()
        assertTrue(leakOpenRouterIdToOpenAi.isError)
        assertTrue(requestedPaths.isEmpty())

        // API key echoed back in HTTP error response is sanitized and NEVER exposed
        mockResponses["/xai/v1/chat/completions"] = MockHttpReply(
            statusCode = 401,
            body = """{"error": {"message": "Invalid API key provided: $secretXai"}}"""
        )
        val errFinished = orchestrator.executeTurn(
            rawUserInput = "hello",
            attachedBitmap = null,
            history = emptyList(),
            brainConfig = BrainConfig(activeProvider = "xai", activeModel = "grok-3")
        ).toList().filterIsInstance<TurnProgressEvent.Finished>().last()

        assertTrue(errFinished.isError)
        assertFalse(errFinished.replyText.contains(secretXai))
        assertTrue(errFinished.replyText.contains("[REDACTED]"))
    }

    @Test
    fun `runtime saveAndVerifyApiKey and SettingsSheet integrate openai anthropic and xai cleanly`() = runBlocking {
        mockResponses["/openai/v1/models"] = MockHttpReply(200, """{"data": [{"id": "gpt-4o-mini"}, {"id": "gpt-4o"}]}""")
        mockResponses["/openai/v1/chat/completions"] = MockHttpReply(200, """{"choices": [{"message": {"content": "ok"}}]}""")
        mockResponses["/anthropic/v1/models"] = MockHttpReply(200, """{"data": [{"id": "claude-sonnet-4-20250514", "display_name": "Claude Sonnet 4"}]}""")
        mockResponses["/anthropic/v1/messages"] = MockHttpReply(200, """{"content": [{"type": "text", "text": "ok"}]}""")
        mockResponses["/xai/v1/models"] = MockHttpReply(200, """{"data": [{"id": "grok-3"}]}""")
        mockResponses["/xai/v1/chat/completions"] = MockHttpReply(200, """{"choices": [{"message": {"content": "ok"}}]}""")

        val app = ApplicationProvider.getApplicationContext<Application>()
        val runtime = JarvisAssistantRuntime.getInstance(app)
        val viewModel = JarvisViewModel(app)
        runtime.byoAiClientOverride = ByoAiClient(endpoints = testEndpoints())

        runtime.saveAndVerifyApiKey(SecureSecretStore.KEY_ANTHROPIC, "anthropic", "sk-ant-ui-key")
        pumpUntil {
            runtime.keyStatuses.value[SecureSecretStore.KEY_ANTHROPIC] == KeyCheckStatus.WORKING &&
                runtime.providerModels.value["anthropic"]?.source == ModelListSource.LIVE
        }
        assertEquals(KeyCheckStatus.WORKING, runtime.keyStatuses.value[SecureSecretStore.KEY_ANTHROPIC])
        assertEquals(
            listOf("claude-sonnet-4-20250514"),
            runtime.providerModels.value["anthropic"]?.models?.map { it.id }
        )

        // Ensure OpenAI and xAI remain untouched / NOT_VERIFIED until configured themselves
        assertNotEquals(KeyCheckStatus.WORKING, runtime.keyStatuses.value[SecureSecretStore.KEY_OPENAI])
        assertNotEquals(KeyCheckStatus.WORKING, runtime.keyStatuses.value[SecureSecretStore.KEY_XAI])

        runtime.selectProvider("anthropic")
        runtime.selectModel("anthropic", "claude-sonnet-4-20250514")
        pumpUntil { runtime.brainConfig.value.activeModel == "claude-sonnet-4-20250514" }

        val activityController = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = activityController.get()
        try {
            activity.setContent {
                JarvisTheme(screenConfig = ScreenConfig()) {
                    SettingsSheet(viewModel = viewModel, onClose = {})
                }
            }
            ShadowLooper.runUiThreadTasks()
            assertEquals("anthropic", viewModel.brainConfig.value.activeProvider)
            assertEquals("claude-sonnet-4-20250514", viewModel.brainConfig.value.activeModel)
        } finally {
            activityController.pause().stop().destroy()
        }
    }

    @Test
    fun `openai reasoning models omit unsupported temperature and use max_completion_tokens in verify streaming and non-streaming`() = runBlocking {
        val client = ByoAiClient(endpoints = testEndpoints())
        val reasoningModels = listOf("o1", "o1-mini", "o3", "o3-mini", "o4-mini", "gpt-5", "gpt-5-mini")

        reasoningModels.forEach { reasoningModel ->
            assertTrue("Expected $reasoningModel to be detected as reasoning", client.isOpenAiReasoningModel(reasoningModel))
        }
        assertFalse(client.isOpenAiReasoningModel("gpt-4o"))
        assertFalse(client.isOpenAiReasoningModel("gpt-4o-mini"))

        // Non-streaming reasoning payload omits temperature & selects max_completion_tokens when bounded
        mockResponses["/openai/v1/chat/completions"] = MockHttpReply(
            200,
            """{"choices": [{"message": {"content": "Reasoning answer"}}]}"""
        )
        val nonStreamReply = client.chatOpenAiCompatible(
            providerId = "openai",
            apiKey = "sk-openai-test",
            model = "o3-mini",
            systemPrompt = "Be concise.",
            history = emptyList(),
            userMessage = "Solve 2+2"
        )
        assertEquals("Reasoning answer", nonStreamReply)
        val nonStreamBody = JSONObject(requestBodiesByPath["/openai/v1/chat/completions"]!!)
        assertEquals("o3-mini", nonStreamBody.getString("model"))
        assertFalse(nonStreamBody.getBoolean("stream"))
        assertFalse("Reasoning model must omit temperature", nonStreamBody.has("temperature"))
        assertFalse("Reasoning model must never receive max_tokens", nonStreamBody.has("max_tokens"))

        // Streaming reasoning payload omits temperature & uses max_completion_tokens when maxTokens is set
        val streamSse = listOf(
            """data: {"choices":[{"delta":{"content":"Streamed "}}]}""",
            "",
            """data: {"choices":[{"delta":{"content":"reasoning"}}]}""",
            "",
            "data: [DONE]"
        ).joinToString("\n") + "\n"
        mockResponses["/openai/v1/chat/completions"] = MockHttpReply(200, streamSse, "text/event-stream")

        val streamEvents = client.streamOpenAiCompatible(
            providerId = "openai",
            apiKey = "sk-openai-test",
            model = "gpt-5",
            systemPrompt = "Be concise.",
            history = emptyList(),
            userMessage = "Hello",
            maxTokens = 256
        ).toList()
        assertEquals("Streamed reasoning", streamEvents.filterIsInstance<ByoStreamEvent.Completed>().last().finalText)
        val streamBody = JSONObject(requestBodiesByPath["/openai/v1/chat/completions"]!!)
        assertEquals("gpt-5", streamBody.getString("model"))
        assertTrue(streamBody.getBoolean("stream"))
        assertFalse("gpt-5 must omit temperature", streamBody.has("temperature"))
        assertFalse("gpt-5 must not send max_tokens", streamBody.has("max_tokens"))
        assertEquals(256, streamBody.getInt("max_completion_tokens"))

        // Search preview omits temperature as well (non-streaming & streaming)
        client.chatOpenAiCompatible(
            providerId = "openai",
            apiKey = "sk-openai-test",
            model = "gpt-4o-search-preview",
            systemPrompt = "Be concise.",
            history = emptyList(),
            userMessage = "News?",
            maxTokens = 128
        )
        val searchBody = JSONObject(requestBodiesByPath["/openai/v1/chat/completions"]!!)
        assertFalse(searchBody.has("temperature"))
        assertEquals(128, searchBody.getInt("max_tokens"))

        // Ordinary OpenAI chat model preserves temperature: 0.5 and uses max_tokens when bounded
        val normalEvents = client.streamOpenAiCompatible(
            providerId = "openai",
            apiKey = "sk-openai-test",
            model = "gpt-4o",
            systemPrompt = "Be concise.",
            history = emptyList(),
            userMessage = "Hello",
            maxTokens = 256
        ).toList()
        assertEquals("Streamed reasoning", normalEvents.filterIsInstance<ByoStreamEvent.Completed>().last().finalText)
        val normalBody = JSONObject(requestBodiesByPath["/openai/v1/chat/completions"]!!)
        assertEquals(0.5, normalBody.getDouble("temperature"), 0.001)
        assertEquals(256, normalBody.getInt("max_tokens"))
        assertFalse(normalBody.has("max_completion_tokens"))

        // Verification probe when account only lists reasoning model uses max_completion_tokens and omits temperature
        mockResponses["/openai/v1/models"] = MockHttpReply(200, """{"data": [{"id": "o4-mini"}]}""")
        mockResponses["/openai/v1/chat/completions"] = MockHttpReply(200, """{"choices": [{"message": {"content": "ok"}}]}""")
        assertEquals(KeyCheckStatus.WORKING, client.verifyKey("openai", "sk-openai-test"))
        val probeBody = JSONObject(requestBodiesByPath["/openai/v1/chat/completions"]!!)
        assertEquals("o4-mini", probeBody.getString("model"))
        assertEquals(1, probeBody.getInt("max_completion_tokens"))
        assertFalse(probeBody.has("max_tokens"))
        assertFalse(probeBody.has("temperature"))
    }

    @Test
    fun `openai model filter excludes Responses-API-only models while keeping supported reasoning and chat models`() = runBlocking {
        mockResponses["/openai/v1/models"] = MockHttpReply(
            200,
            """
            {
              "data": [
                {"id": "o1-pro"},
                {"id": "o1-pro-2025-03-19"},
                {"id": "o3-pro"},
                {"id": "gpt-5-pro"},
                {"id": "codex-mini-latest"},
                {"id": "gpt-5-codex"},
                {"id": "o3-deep-research"},
                {"id": "o1"},
                {"id": "o3-mini"},
                {"id": "o4-mini"},
                {"id": "gpt-5"},
                {"id": "gpt-5-mini"},
                {"id": "gpt-4o"},
                {"id": "gpt-4o-mini"}
              ]
            }
            """.trimIndent()
        )

        val client = ByoAiClient(endpoints = testEndpoints())
        val result = client.fetchModels("openai", "sk-openai-catalog") as FetchModelsResult.Success
        assertEquals(
            listOf("o1", "o3-mini", "o4-mini", "gpt-5", "gpt-5-mini", "gpt-4o", "gpt-4o-mini"),
            result.models.map { it.id }
        )
    }
}
