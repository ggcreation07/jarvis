package com.jarvis.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.jarvis.app.data.ai.ByoAiClient
import com.jarvis.app.data.ai.KeyCheckStatus
import com.jarvis.app.data.ai.ProviderEndpoints
import com.jarvis.app.data.security.SecureSecretStore
import com.jarvis.app.platform.JarvisAssistantRuntime
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Phase1ApiKeyValidationTest {

    private lateinit var server: HttpServer
    private var serverPort: Int = 0
    private val requestedPaths = CopyOnWriteArrayList<String>()
    private val headersByPath = ConcurrentHashMap<String, Map<String, List<String>>>()
    private val mockResponses = ConcurrentHashMap<String, Pair<Int, String>>()

    @Before
    fun setUp() {
        requestedPaths.clear()
        headersByPath.clear()
        mockResponses.clear()

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange: HttpExchange ->
            val path = exchange.requestURI.path
            requestedPaths.add(path)
            headersByPath[path] = exchange.requestHeaders.toMap()

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
        override fun tavilySearchUrl() = "http://127.0.0.1:$serverPort/tavily/search"
    }

    private fun getHeader(path: String, name: String): String? {
        return headersByPath[path]?.entries?.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
    }

    @Test
    fun `valid key and successful generation produces WORKING for Gemini`() = runBlocking {
        mockResponses["/gemini/models"] = Pair(200, """{"models": [{"name": "models/gemini-2.5-flash"}]}""")
        mockResponses["/gemini/generate/gemini-2.5-flash"] = Pair(200, """{"candidates": [{"content": {"parts": [{"text": "pong"}]}}]}""")

        val client = ByoAiClient(endpoints = testEndpoints())
        val status = client.verifyKey("gemini", "valid-gemini-key")

        assertEquals(KeyCheckStatus.WORKING, status)
        assertEquals(listOf("/gemini/models", "/gemini/generate/gemini-2.5-flash"), requestedPaths.toList())
        assertEquals("valid-gemini-key", getHeader("/gemini/models", "x-goog-api-key"))
        assertEquals("valid-gemini-key", getHeader("/gemini/generate/gemini-2.5-flash", "x-goog-api-key"))
    }

    @Test
    fun `valid key and successful generation produces WORKING for Groq`() = runBlocking {
        mockResponses["/groq/models"] = Pair(200, """{"data": [{"id": "llama-3.3-70b-versatile"}]}""")
        mockResponses["/groq/chat"] = Pair(200, """{"choices": [{"message": {"content": "pong"}}]}""")

        val client = ByoAiClient(endpoints = testEndpoints())
        val status = client.verifyKey("groq", "gsk-valid-key")

        assertEquals(KeyCheckStatus.WORKING, status)
        assertEquals(listOf("/groq/models", "/groq/chat"), requestedPaths.toList())
        assertEquals("Bearer gsk-valid-key", getHeader("/groq/models", "Authorization"))
        assertEquals("Bearer gsk-valid-key", getHeader("/groq/chat", "Authorization"))
    }

    @Test
    fun `invalid key returns HTTP 401 producing INVALID_KEY`() = runBlocking {
        mockResponses["/groq/models"] = Pair(401, """{"error": {"message": "Invalid API Key"}}""")

        val client = ByoAiClient(endpoints = testEndpoints())
        val status = client.verifyKey("groq", "bad-key")

        assertEquals(KeyCheckStatus.INVALID_KEY, status)
        assertEquals(listOf("/groq/models"), requestedPaths.toList())
    }

    @Test
    fun `permission denied returns HTTP 403 producing PERMISSION_DENIED`() = runBlocking {
        mockResponses["/mistral/models"] = Pair(403, """{"message": "Access forbidden for this resource."}""")

        val client = ByoAiClient(endpoints = testEndpoints())
        val status = client.verifyKey("mistral", "restricted-key")

        assertEquals(KeyCheckStatus.PERMISSION_DENIED, status)
    }

    @Test
    fun `rate limited returns HTTP 429 producing RATE_LIMITED`() = runBlocking {
        mockResponses["/nvidia/models"] = Pair(429, """{"error": "Too Many Requests"}""")

        val client = ByoAiClient(endpoints = testEndpoints())
        val status = client.verifyKey("nvidia", "busy-key")

        assertEquals(KeyCheckStatus.RATE_LIMITED, status)
    }

    @Test
    fun `quota or billing error produces QUOTA_EXCEEDED`() = runBlocking {
        mockResponses["/openrouter/auth/key"] = Pair(200, """{"data": {"label": "test", "usage": 10.0, "limit": 5.0}}""")

        val client = ByoAiClient(endpoints = testEndpoints())
        val status = client.verifyKey("openrouter", "sk-or-exhausted")

        assertEquals(KeyCheckStatus.QUOTA_EXCEEDED, status)
        assertEquals(listOf("/openrouter/auth/key"), requestedPaths.toList())
    }

    @Test
    fun `payment required HTTP 402 on generation probe produces QUOTA_EXCEEDED`() = runBlocking {
        mockResponses["/openrouter/auth/key"] = Pair(200, """{"data": {"label": "test", "usage": 1.0, "limit": 100.0}}""")
        mockResponses["/openrouter/chat"] = Pair(402, """{"error": {"message": "Insufficient credits"}}""")

        val client = ByoAiClient(endpoints = testEndpoints())
        val status = client.verifyKey("openrouter", "sk-or-no-credits")

        assertEquals(KeyCheckStatus.QUOTA_EXCEEDED, status)
    }

    @Test
    fun `network timeout or unreachable host produces NETWORK_ERROR`() = runBlocking {
        val unreachableEndpoints = object : ProviderEndpoints by testEndpoints() {
            override fun groqModelsUrl() = "http://127.0.0.1:1/unreachable"
            override fun groqChatUrl() = "http://127.0.0.1:1/unreachable"
        }
        val client = ByoAiClient(endpoints = unreachableEndpoints)
        val status = client.verifyKey("groq", "any-key")

        assertEquals(KeyCheckStatus.NETWORK_ERROR, status)
    }

    @Test
    fun `public OpenRouter models endpoint returning 200 with invalid key must NOT produce WORKING`() = runBlocking {
        // Even if OpenRouter /models returns 200 without auth, the key verification queries /auth/key
        mockResponses["/openrouter/auth/key"] = Pair(401, """{"error": {"message": "Invalid Bearer Token"}}""")
        mockResponses["/openrouter/models"] = Pair(200, """{"data": [{"id": "free-model"}]}""")

        val client = ByoAiClient(endpoints = testEndpoints())
        val status = client.verifyKey("openrouter", "invalid-key")

        assertNotEquals(KeyCheckStatus.WORKING, status)
        assertEquals(KeyCheckStatus.INVALID_KEY, status)
        assertTrue(requestedPaths.contains("/openrouter/auth/key"))
    }

    @Test
    fun `one provider key is never sent to another provider endpoint`() = runBlocking {
        mockResponses["/gemini/models"] = Pair(200, """{"models": []}""")
        mockResponses["/gemini/generate/gemini-2.5-flash"] = Pair(200, """{"candidates": [{"content": {"parts": [{"text": "ok"}]}}]}""")

        val client = ByoAiClient(endpoints = testEndpoints())
        client.verifyKey("gemini", "gemini-only-secret")

        assertTrue(requestedPaths.all { it.startsWith("/gemini/") })
        assertTrue(requestedPaths.none { it.startsWith("/groq/") || it.startsWith("/openrouter/") || it.startsWith("/nvidia/") })
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
    fun `saved but unverified key remains NOT_VERIFIED in runtime refreshKeyStatuses`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val runtime = JarvisAssistantRuntime.getInstance(app)
        val secretStore = runtime.secretStore

        secretStore.setSecret(SecureSecretStore.KEY_GROQ, "gsk-unverified-key")
        // Overwrite or clear any existing status to simulate unverified state
        secretStore.setKeyStatus(SecureSecretStore.KEY_GROQ, KeyCheckStatus.NOT_VERIFIED)

        runtime.refreshKeyStatuses()
        pumpUntil { runtime.keyStatuses.value[SecureSecretStore.KEY_GROQ] == KeyCheckStatus.NOT_VERIFIED }

        val status = runtime.keyStatuses.value[SecureSecretStore.KEY_GROQ]
        assertEquals(KeyCheckStatus.NOT_VERIFIED, status)
    }

    @Test
    fun `opening Settings does not overwrite a previously verified INVALID_KEY status`() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val runtime = JarvisAssistantRuntime.getInstance(app)
        val secretStore = runtime.secretStore

        secretStore.setSecret(SecureSecretStore.KEY_MISTRAL, "mistral-bad-key")
        secretStore.setKeyStatus(SecureSecretStore.KEY_MISTRAL, KeyCheckStatus.INVALID_KEY)

        // refreshKeyStatuses is called when opening SettingsSheet
        runtime.refreshKeyStatuses()
        pumpUntil { runtime.keyStatuses.value[SecureSecretStore.KEY_MISTRAL] == KeyCheckStatus.INVALID_KEY }

        val status = runtime.keyStatuses.value[SecureSecretStore.KEY_MISTRAL]
        assertEquals(KeyCheckStatus.INVALID_KEY, status)
    }
}
