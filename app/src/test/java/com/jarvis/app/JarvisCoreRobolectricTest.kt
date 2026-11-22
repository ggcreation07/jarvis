package com.jarvis.app

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jarvis.app.data.local.JarvisDatabase
import com.jarvis.app.data.local.JarvisRepository
import com.jarvis.app.data.security.DeviceIdentityManager
import com.jarvis.app.data.security.RemoteIdentitySigner
import com.jarvis.app.data.security.SecretStorage
import com.jarvis.app.domain.brain.DirectActionMatch
import com.jarvis.app.domain.brain.DirectCommandMatcher
import com.jarvis.app.domain.model.ChatMessageItem
import com.jarvis.app.domain.model.HandPose
import com.jarvis.app.domain.model.HudStatus
import com.jarvis.app.domain.model.OverlayType
import com.jarvis.app.domain.model.RoutineItem
import com.jarvis.app.platform.AssistantLaunchIntentHelper
import com.jarvis.app.platform.GestureLatch
import com.jarvis.app.platform.LanAddressPolicy
import com.jarvis.app.platform.RemotePcClient
import com.jarvis.app.platform.VoiceController
import com.jarvis.app.platform.WakeOnLan
import com.jarvis.app.ui.JarvisViewModel
import com.jarvis.app.ui.components.OrbState
import com.jarvis.app.ui.components.resolveOrbState
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class JarvisCoreRobolectricTest {

    @Test
    fun `app name matches JARVIS`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("JARVIS", context.getString(R.string.app_name))
    }

    @Test
    fun `direct command matcher parses timers, looks, and device controls`() {
        val timer = DirectCommandMatcher.match("set a 5 minute timer") as? DirectActionMatch.ToolCall
        assertNotNull(timer)
        assertEquals("set_timer", timer?.toolName)
        assertEquals("300", timer?.args?.get("seconds"))

        val ironMan = DirectCommandMatcher.match("switch to iron man look") as? DirectActionMatch.ToolCall
        assertEquals("customize_screen", ironMan?.toolName)
        assertEquals("ironman", ironMan?.args?.get("value"))

        val torch = DirectCommandMatcher.match("turn on flashlight") as? DirectActionMatch.ToolCall
        assertEquals("device_control", torch?.toolName)
        assertEquals("flashlight_on", torch?.args?.get("action"))

        val app = DirectCommandMatcher.match("open Spotify") as? DirectActionMatch.ToolCall
        assertEquals("open_application", app?.toolName)
        assertEquals("Spotify", app?.args?.get("name"))

        assertNull(DirectCommandMatcher.match("Write a three paragraph essay about orbital mechanics."))
    }

    @Test
    fun `gesture latch requires neutral release and hold duration`() {
        val latch = GestureLatch(holdMs = 500L, releaseMs = 300L)
        assertNull(latch.update(HandPose.UNKNOWN, 100L))
        assertNull(latch.update(HandPose.UNKNOWN, 450L))
        assertNull(latch.update(HandPose.OPEN_PALM, 500L))
        assertNull(latch.update(HandPose.OPEN_PALM, 700L))
        assertNull(latch.update(HandPose.OPEN_PALM, 900L))
        val triggered = latch.update(HandPose.OPEN_PALM, 1050L)
        assertEquals(HandPose.OPEN_PALM, triggered)
        // Repeat hold does not double-fire before neutral release
        assertNull(latch.update(HandPose.OPEN_PALM, 1200L))
    }

    @Test
    fun `wake on lan normalizes valid macs and rejects invalid`() {
        assertEquals("aa:bb:cc:11:22:33", WakeOnLan.normMac("AA-BB-CC-11-22-33"))
        assertEquals("", WakeOnLan.normMac("00:00:00:00:00:00"))
        assertEquals("", WakeOnLan.normMac("invalid"))
    }

    @Test
    fun `room repository persists facts, routines, agenda, and archived chats`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, JarvisDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = JarvisRepository(db.jarvisDao())
            repo.addFact("Prefers dark HUD mode")
            repo.addFact("Prefers dark HUD mode") // deduplicated
            assertEquals(1, repo.getFactsOnce().size)

            repo.upsertRoutine(RoutineItem(name = "Morning", prompt = "Brief me", triggerTime = "08:00"))
            assertEquals("Morning", repo.getRoutinesOnce().first().name)

            repo.addAgendaItem("2026-11-20", "09:30", "Standup")
            assertEquals(1, repo.getAgendaOnce("2026-11-20").size)

            repo.addTurn(ChatMessageItem(role = "user", content = "Hello Jarvis"))
            repo.addTurn(ChatMessageItem(role = "assistant", content = "Good morning."))
            repo.archiveAndClearCurrentChat()
            assertTrue(repo.getCurrentTurnsOnce().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test
    fun `sha256 digest and host envelope verification enforce cryptographic integrity`() {
        // Known SHA-256 test vector ("abc")
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            DeviceIdentityManager.sha256Hex("abc")
        )
        val taskDigest1 = DeviceIdentityManager.sha256Hex(
            DeviceIdentityManager.canonicalTaskPayload("task_1", "browser", "Open docs")
        )
        val taskDigest2 = DeviceIdentityManager.sha256Hex(
            DeviceIdentityManager.canonicalTaskPayload("task_1", "browser", "Open docs")
        )
        assertEquals(64, taskDigest1.length)
        assertEquals(taskDigest1, taskDigest2)

        val hostKeys = generateEcKeyPair()
        val hostPubB64 = DeviceIdentityManager.b64Url(hostKeys.public.encoded)
        val hostFp = DeviceIdentityManager.computeFingerprint(hostPubB64)!!
        val summary = "Opened docs"
        val digest = DeviceIdentityManager.sha256Hex(
            DeviceIdentityManager.canonicalResponsePayload("ok", summary)
        )
        val nonce = "nonce-1234567890abcdef"
        val sig = signHostEnvelope(
            keyPair = hostKeys,
            hostId = "pc-workstation",
            nonce = nonce,
            counter = 1L,
            messageType = "task.result",
            taskId = "task_1",
            payloadDigest = digest
        )

        assertTrue(
            DeviceIdentityManager.verifyHostEnvelopeStatic(
                publicKey = hostPubB64,
                expectedFingerprint = hostFp,
                hostId = "pc-workstation",
                connectionNonce = nonce,
                counter = 1L,
                messageType = "task.result",
                taskId = "task_1",
                payloadDigest = digest,
                signature = sig
            )
        )
        // Reject tampered digest, counter <= 0, mismatched fingerprint, or wrong signature
        assertFalse(
            DeviceIdentityManager.verifyHostEnvelopeStatic(
                publicKey = hostPubB64,
                expectedFingerprint = hostFp,
                hostId = "pc-workstation",
                connectionNonce = nonce,
                counter = 1L,
                messageType = "task.result",
                taskId = "task_1",
                payloadDigest = DeviceIdentityManager.sha256Hex("tampered"),
                signature = sig
            )
        )
        assertFalse(
            DeviceIdentityManager.verifyHostEnvelopeStatic(
                publicKey = hostPubB64,
                expectedFingerprint = hostFp,
                hostId = "pc-workstation",
                connectionNonce = nonce,
                counter = 0L,
                messageType = "task.result",
                taskId = "task_1",
                payloadDigest = digest,
                signature = sig
            )
        )
        assertFalse(
            DeviceIdentityManager.verifyHostEnvelopeStatic(
                publicKey = hostPubB64,
                expectedFingerprint = "sha256:wrong",
                hostId = "pc-workstation",
                connectionNonce = nonce,
                counter = 1L,
                messageType = "task.result",
                taskId = "task_1",
                payloadDigest = digest,
                signature = sig
            )
        )
    }

    @Test
    fun `lan address policy isolates cleartext ws to private lan and enforces wss externally`() {
        val lan192 = LanAddressPolicy.resolveWebSocketEndpoint("192.168.1.50", 8765)
        assertTrue(lan192.usesLanCleartext)
        assertEquals("ws://v4-192-168-1-50.jarvis.local:8765/ws", lan192.url)
        assertEquals("192.168.1.50:8765", lan192.hostHeader)
        assertEquals("192.168.1.50", LanAddressPolicy.fromLanLocalAlias("v4-192-168-1-50.jarvis.local"))

        val tailscaleIp = LanAddressPolicy.resolveWebSocketEndpoint("100.64.12.9", 8765)
        assertTrue(tailscaleIp.usesLanCleartext)
        assertEquals("ws://v4-100-64-12-9.jarvis.local:8765/ws", tailscaleIp.url)

        val ipv6Ula = LanAddressPolicy.resolveWebSocketEndpoint("[fd12:3456::1]", 8765)
        assertTrue(ipv6Ula.usesLanCleartext)
        assertTrue(ipv6Ula.url.startsWith("ws://v6-") && ipv6Ula.url.endsWith(".jarvis.local:8765/ws"))

        val ipv6Loopback = LanAddressPolicy.resolveWebSocketEndpoint("::1", 8765)
        assertTrue(ipv6Loopback.usesLanCleartext)
        assertTrue(ipv6Loopback.url.endsWith(".jarvis.local:8765/ws"))

        val localMdns = LanAddressPolicy.resolveWebSocketEndpoint("workstation.local", 8765)
        assertTrue(localMdns.usesLanCleartext)
        assertEquals("ws://workstation.local:8765/ws", localMdns.url)

        // Public endpoints MUST use wss:// and never decode public IP aliases
        val publicDomain = LanAddressPolicy.resolveWebSocketEndpoint("remote.example.com", 8765)
        assertFalse(publicDomain.usesLanCleartext)
        assertEquals("wss://remote.example.com:8765/ws", publicDomain.url)

        val publicIp = LanAddressPolicy.resolveWebSocketEndpoint("8.8.8.8", 8765)
        assertFalse(publicIp.usesLanCleartext)
        assertEquals("wss://8.8.8.8:8765/ws", publicIp.url)
        assertNull(LanAddressPolicy.fromLanLocalAlias("v4-8-8-8-8.jarvis.local"))

        assertFalse(LanAddressPolicy.isPrivateOrLanIpLiteral("172.32.0.1"))
        assertFalse(LanAddressPolicy.isPrivateOrLanIpLiteral("100.128.0.1"))
        assertFalse(LanAddressPolicy.isPrivateOrLanIpLiteral("100.63.255.255"))

        val resolvedLoopback = LanAddressPolicy.lanOnlyDns.lookup("v4-127-0-0-1.jarvis.local")
        assertEquals(listOf(InetAddress.getByName("127.0.0.1")), resolvedLoopback)
    }

    @Test
    fun `remote pc client performs mutual ecdsa auth pins host key and verifies sha256 task responses`() = runBlocking {
        val phoneSigner = InMemoryRemoteIdentitySigner()
        val secretStore = InMemorySecretStorage()
        val pcClient = RemotePcClient(phoneSigner, secretStore)
        val hostKeys = generateEcKeyPair()
        val observedFrames = CopyOnWriteArrayList<JSONObject>()

        val server = LoopbackAuthWsServer(
            hostId = "jarvis-pc-main",
            hostKeys = hostKeys,
            observedFrames = observedFrames
        )
        server.start()
        try {
            val (pairOk, pairMsg) = pcClient.testConnection("127.0.0.1", server.port)
            assertTrue("Expected auth success, got: $pairMsg", pairOk)
            assertTrue(pairMsg.contains("Authenticated PC"))
            assertTrue(secretStore.getSecret("trusted_pc_hosts_json").contains("jarvis-pc-main"))

            val (taskOk, taskMsg) = pcClient.sendTask(
                host = "127.0.0.1",
                port = server.port,
                goal = "Open terminal and check status",
                kind = "shell"
            )
            assertTrue("Expected signed task success, got: $taskMsg", taskOk)
            assertEquals("Executed on jarvis-pc-main", taskMsg)

            val taskSubmit = observedFrames.firstOrNull { it.optString("type") == "task.submit" }
            assertNotNull(taskSubmit)
            val expectedTaskDigest = DeviceIdentityManager.sha256Hex(
                DeviceIdentityManager.canonicalTaskPayload(
                    taskSubmit!!.getString("task_id"),
                    "shell",
                    "Open terminal and check status"
                )
            )
            assertEquals(expectedTaskDigest, taskSubmit.getString("payload_digest"))

            val (inputOk, _) = pcClient.sendRemoteInput(
                host = "127.0.0.1",
                port = server.port,
                action = "click",
                x = 120,
                y = 240
            )
            assertTrue(inputOk)
            val deadline = System.currentTimeMillis() + 2_000L
            var inputFrame: JSONObject? = null
            while (System.currentTimeMillis() < deadline) {
                inputFrame = observedFrames.firstOrNull { it.optString("type") == "remote.input" }
                if (inputFrame != null) break
                kotlinx.coroutines.delay(15L)
            }
            assertNotNull("Expected server to receive remote.input frame", inputFrame)
            val expectedInputDigest = DeviceIdentityManager.sha256Hex(
                DeviceIdentityManager.canonicalInputPayload(
                    inputFrame!!.getString("task_id"),
                    "click",
                    120,
                    240,
                    null
                )
            )
            assertEquals(expectedInputDigest, inputFrame.getString("payload_digest"))
            assertTrue(
                "Expected server verification of all client ECDSA signatures, errors=${server.verificationErrors}",
                server.verificationErrors.isEmpty()
            )
            pcClient.closeRemoteSession()
        } finally {
            server.close()
        }
    }

    @Test
    fun `remote pc client rejects forged response envelopes and pinned host identity changes`() = runBlocking {
        val phoneSigner = InMemoryRemoteIdentitySigner()
        val secretStore = InMemorySecretStorage()
        val pcClient = RemotePcClient(phoneSigner, secretStore)
        val trustedHostKeys = generateEcKeyPair()

        // 1. Valid pairing pins trustedHostKeys
        val validServer = LoopbackAuthWsServer("jarvis-pc-main", trustedHostKeys)
        validServer.start()
        try {
            val (ok, msg) = pcClient.testConnection("127.0.0.1", validServer.port)
            assertTrue(msg, ok)

            // 2. Same endpoint port or pinned key must reject MITM key substitution
            validServer.hostKeys = generateEcKeyPair()
            val (mitmOk, mitmMsg) = pcClient.testConnection("127.0.0.1", validServer.port)
            assertFalse("MITM host key substitution should be rejected", mitmOk)
            assertTrue(mitmMsg.contains("identity changed"))
        } finally {
            validServer.close()
        }

        // 3. Forged / invalid signature on task.result must be rejected
        val forgedServer = LoopbackAuthWsServer(
            hostId = "jarvis-pc-other",
            hostKeys = trustedHostKeys,
            tamperTaskResultDigest = true
        )
        forgedServer.start()
        try {
            val (taskOk, taskMsg) = pcClient.sendTask("127.0.0.1", forgedServer.port, "Run command")
            assertFalse("Tampered response digest must be rejected", taskOk)
            assertTrue(taskMsg.contains("SHA-256") || taskMsg.contains("verification"))
        } finally {
            forgedServer.close()
        }

        // 4. Replayed host counter (<= auth.ok counter) must be rejected
        val replayServer = LoopbackAuthWsServer(
            hostId = "jarvis-pc-replay",
            hostKeys = trustedHostKeys,
            replayTaskResultCounter = true
        )
        replayServer.start()
        try {
            val (taskOk, taskMsg) = pcClient.sendTask("127.0.0.1", replayServer.port, "Run command")
            assertFalse("Replayed host counter must be rejected", taskOk)
            assertTrue(taskMsg.contains("replay"))
        } finally {
            replayServer.close()
        }
    }

    @Test
    fun `orb state priority and voice controller speaking metering stay safe when visualizer is unavailable`() {
        assertEquals(OrbState.IDLE, resolveOrbState(isListening = false, isSpeaking = false, isBusy = false, status = HudStatus.IDLE))
        assertEquals(OrbState.THINKING, resolveOrbState(isListening = false, isSpeaking = false, isBusy = true, status = HudStatus.IDLE))
        assertEquals(OrbState.THINKING, resolveOrbState(isListening = false, isSpeaking = false, isBusy = true, status = HudStatus.WORKING))
        // Speaking takes priority over thinking/busy
        assertEquals(OrbState.SPEAKING, resolveOrbState(isListening = false, isSpeaking = true, isBusy = true, status = HudStatus.THINKING))
        // Listening takes priority over everything else
        assertEquals(OrbState.LISTENING, resolveOrbState(isListening = true, isSpeaking = true, isBusy = true, status = HudStatus.SPEAKING))

        val context = ApplicationProvider.getApplicationContext<Context>()
        val voice = VoiceController(context)
        try {
            assertEquals(0f, voice.audioLevel.value, 0.0001f)
            assertEquals(0f, voice.speakingLevel.value, 0.0001f)
            assertFalse(voice.isRealSpeakingMeteringActive.value)
            voice.speak("Systems ready.")
            voice.stopSpeaking()
            assertFalse(voice.isSpeaking.value)
            assertEquals(0f, voice.speakingLevel.value, 0.0001f)
        } finally {
            voice.release()
        }
    }

    @Test
    fun `manifest resolves ACTION_ASSIST and ACTION_VOICE_COMMAND to MainActivity while preserving ACTION_SEND`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pm = context.packageManager

        val assistMatches = pm.queryIntentActivities(
            Intent(Intent.ACTION_ASSIST).addCategory(Intent.CATEGORY_DEFAULT).setPackage(context.packageName),
            PackageManager.MATCH_DEFAULT_ONLY
        )
        assertTrue(
            "MainActivity must resolve ACTION_ASSIST",
            assistMatches.any { it.activityInfo.name == MainActivity::class.java.name }
        )

        val voiceCmdMatches = pm.queryIntentActivities(
            Intent(Intent.ACTION_VOICE_COMMAND).addCategory(Intent.CATEGORY_DEFAULT).setPackage(context.packageName),
            PackageManager.MATCH_DEFAULT_ONLY
        )
        assertTrue(
            "MainActivity must resolve ACTION_VOICE_COMMAND",
            voiceCmdMatches.any { it.activityInfo.name == MainActivity::class.java.name }
        )

        val sendMatches = pm.queryIntentActivities(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                setPackage(context.packageName)
            },
            PackageManager.MATCH_DEFAULT_ONLY
        )
        assertTrue(
            "MainActivity must preserve ACTION_SEND text/plain",
            sendMatches.any { it.activityInfo.name == MainActivity::class.java.name }
        )
    }

    @Test
    fun `assistant launch helper and viewmodel enforce cold warm activation and duplicate prevention`() {
        val assistIntent = Intent(Intent.ACTION_ASSIST)
        assertTrue(AssistantLaunchIntentHelper.shouldHandleAssistantIntent(assistIntent, savedInstanceStatePresent = false))
        assertFalse(AssistantLaunchIntentHelper.shouldHandleAssistantIntent(assistIntent, savedInstanceStatePresent = true))
        AssistantLaunchIntentHelper.markHandled(assistIntent)
        assertFalse(AssistantLaunchIntentHelper.shouldHandleAssistantIntent(assistIntent, savedInstanceStatePresent = false))

        assertTrue(AssistantLaunchIntentHelper.shouldHandleAssistantIntent(Intent(Intent.ACTION_VOICE_COMMAND), false))
        assertTrue(AssistantLaunchIntentHelper.shouldHandleAssistantIntent(Intent(AssistantLaunchIntentHelper.ACTION_VOICE_INTERACTION_SESSION), false))
        assertFalse(AssistantLaunchIntentHelper.shouldHandleAssistantIntent(Intent(Intent.ACTION_MAIN), false))
        assertFalse(AssistantLaunchIntentHelper.shouldHandleAssistantIntent(Intent(Intent.ACTION_SEND), false))

        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = JarvisViewModel(app)
        try {
            vm.stopAssistantSession()
            val baseLaunchId = vm.assistantLaunchEvent.value?.launchId ?: 0L
            vm.openOverlay(OverlayType.SETTINGS)
            assertEquals(OverlayType.SETTINGS, vm.activeOverlay.value)

            assertTrue(vm.onAssistantActivated(Intent.ACTION_ASSIST, nowMs = 10_000L))
            val first = vm.assistantLaunchEvent.value
            assertNotNull(first)
            assertEquals(baseLaunchId + 1L, first?.launchId)
            assertEquals(Intent.ACTION_ASSIST, first?.action)
            assertEquals(OverlayType.NONE, vm.activeOverlay.value)

            // Duplicate activation within debounce window must be dropped
            assertFalse(vm.onAssistantActivated(Intent.ACTION_ASSIST, nowMs = 10_150L))
            assertEquals(baseLaunchId + 1L, vm.assistantLaunchEvent.value?.launchId)

            // Subsequent activation after debounce window must emit new event
            assertTrue(vm.onAssistantActivated(Intent.ACTION_VOICE_COMMAND, nowMs = 10_600L))
            assertEquals(baseLaunchId + 2L, vm.assistantLaunchEvent.value?.launchId)
            assertEquals(Intent.ACTION_VOICE_COMMAND, vm.assistantLaunchEvent.value?.action)

            vm.markAssistantVoiceTriggered(baseLaunchId + 2L)
            vm.markAssistantEntranceCompleted(baseLaunchId + 2L)
            assertTrue(vm.assistantLaunchEvent.value?.voiceTriggered == true)
            assertTrue(vm.assistantLaunchEvent.value?.entranceCompleted == true)
            vm.onActivityStopped()
        } finally {
            vm.stopAssistantSession()
        }
    }

    @Test
    fun `main activity handles cold start warm start config recreation voice-only back behavior and mic permissions`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val runtime = com.jarvis.app.platform.JarvisAssistantRuntime.getInstance(app)
        runtime.stopAssistant()

        // Case 1: Microphone permission DENIED -> cold start with ACTION_ASSIST opens voice-only mode safely without crashing
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val deniedController = Robolectric.buildActivity(MainActivity::class.java, Intent(Intent.ACTION_ASSIST))
        deniedController.create().start().resume()
        ShadowLooper.runUiThreadTasks()
        val deniedActivity = deniedController.get()
        val deniedVm = ViewModelProvider(deniedActivity)[JarvisViewModel::class.java]
        assertEquals(Intent.ACTION_ASSIST, deniedVm.assistantLaunchEvent.value?.action)
        assertTrue(deniedVm.assistantLaunchEvent.value?.voiceTriggered == true)
        assertTrue(deniedVm.isVoiceOnlyUiActive.value)
        assertTrue(deniedVm.isVoiceSessionActive.value)
        assertFalse(deniedVm.voiceController.isListening.value)
        // Explicit permission denial callback leaves voice-only UI safe with banner feedback
        deniedActivity.onAssistantMicPermissionResult(false)
        assertTrue(deniedVm.bannerMessage.value?.contains("denied", ignoreCase = true) == true)
        deniedController.pause().stop().destroy()
        runtime.stopAssistant()

        // Case 2: Microphone permission GRANTED -> cold start, config-change no-replay, warm-start, Back hide, Stop Assistant, ACTION_MAIN
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val grantedController = Robolectric.buildActivity(MainActivity::class.java, Intent(Intent.ACTION_ASSIST))
        grantedController.create().start().resume()
        ShadowLooper.runUiThreadTasks()
        val grantedActivity = grantedController.get()
        val grantedVm = ViewModelProvider(grantedActivity)[JarvisViewModel::class.java]
        val initialEvent = grantedVm.assistantLaunchEvent.value
        assertNotNull(initialEvent)
        assertEquals(Intent.ACTION_ASSIST, initialEvent?.action)
        assertTrue(initialEvent?.voiceTriggered == true)
        assertTrue(grantedVm.isVoiceOnlyUiActive.value)
        assertTrue(grantedVm.isVoiceSessionActive.value)
        grantedActivity.onAssistantMicPermissionResult(true)

        // Pressing Back hides voice-only UI without terminating active voice session
        grantedActivity.onVoiceOnlyBackPressed()
        assertFalse(grantedVm.isVoiceOnlyUiActive.value)
        assertTrue(grantedVm.isVoiceSessionActive.value)

        // Activity onStop preserves active voice session
        grantedController.pause().stop()
        assertTrue(grantedVm.isVoiceSessionActive.value)
        grantedController.start().resume()

        // Configuration change recreation (savedInstanceState != null) must NOT re-fire assistant launch
        val launchCountBeforeRotate = grantedVm.assistantLaunchEvent.value?.launchId
        val rotatedController = Robolectric.buildActivity(
            MainActivity::class.java,
            Intent(Intent.ACTION_ASSIST)
        )
        rotatedController.create(android.os.Bundle().apply { putBoolean("rotated", true) }).start().resume()
        ShadowLooper.runUiThreadTasks()
        val rotatedVm = ViewModelProvider(rotatedController.get())[JarvisViewModel::class.java]
        assertEquals(launchCountBeforeRotate, rotatedVm.assistantLaunchEvent.value?.launchId)
        rotatedController.pause().stop().destroy()

        // Warm start via onNewIntent after debounce window re-enters voice-only orb UI
        Thread.sleep(400L)
        grantedController.newIntent(Intent(Intent.ACTION_VOICE_COMMAND))
        ShadowLooper.runUiThreadTasks()
        val warmEvent = grantedVm.assistantLaunchEvent.value
        assertNotNull(warmEvent)
        assertEquals(Intent.ACTION_VOICE_COMMAND, warmEvent?.action)
        assertTrue((warmEvent?.launchId ?: 0L) > initialEvent!!.launchId)
        assertTrue(warmEvent?.voiceTriggered == true)
        assertTrue(grantedVm.isVoiceOnlyUiActive.value)

        // Normal launcher ACTION_MAIN shows regular AssistantMainScreen (exits voice-only UI)
        grantedController.newIntent(Intent(Intent.ACTION_MAIN))
        ShadowLooper.runUiThreadTasks()
        assertFalse(grantedVm.isVoiceOnlyUiActive.value)
        assertTrue(grantedVm.isVoiceSessionActive.value)

        // Stop Assistant action terminates background voice session cleanly
        grantedVm.stopAssistantSession()
        assertFalse(grantedVm.isVoiceSessionActive.value)
        assertFalse(grantedVm.isVoiceOnlyUiActive.value)

        grantedController.pause().stop().destroy()
    }

    @Test
    fun `manifest declares VoiceInteractionService VoiceInteractionSessionService and microphone foreground service`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pm = context.packageManager

        val pkgInfo = pm.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES or PackageManager.GET_META_DATA
        )
        val perms = pkgInfo.requestedPermissions?.toSet().orEmpty()
        assertTrue(perms.contains(Manifest.permission.FOREGROUND_SERVICE))
        assertTrue(perms.contains(Manifest.permission.FOREGROUND_SERVICE_MICROPHONE))
        assertTrue(perms.contains(Manifest.permission.RECORD_AUDIO))

        val visMatches = pm.queryIntentServices(
            Intent("android.service.voice.VoiceInteractionService").setPackage(context.packageName),
            PackageManager.GET_META_DATA
        )
        val visService = visMatches.firstOrNull {
            it.serviceInfo.name == com.jarvis.app.platform.JarvisVoiceInteractionService::class.java.name
        }?.serviceInfo
        assertNotNull("JarvisVoiceInteractionService must resolve VoiceInteractionService intent", visService)
        assertEquals(Manifest.permission.BIND_VOICE_INTERACTION, visService?.permission)
        assertEquals(
            R.xml.jarvis_voice_interaction,
            visService?.metaData?.getInt("android.voice_interaction")
        )

        val sessionServiceInfo = pkgInfo.services?.firstOrNull {
            it.name == com.jarvis.app.platform.JarvisVoiceInteractionSessionService::class.java.name
        }
        assertNotNull("JarvisVoiceInteractionSessionService must be registered", sessionServiceInfo)
        assertEquals(Manifest.permission.BIND_VOICE_INTERACTION, sessionServiceInfo?.permission)

        val fgsInfo = pkgInfo.services?.firstOrNull {
            it.name == com.jarvis.app.platform.VoiceAssistantForegroundService::class.java.name
        }
        assertNotNull("VoiceAssistantForegroundService must be registered", fgsInfo)
        assertTrue(
            "VoiceAssistantForegroundService must declare microphone foregroundServiceType",
            (fgsInfo!!.foregroundServiceType and android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) != 0
        )
    }

    @Test
    fun `voice interaction session hides on back press without terminating active session`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val runtime = com.jarvis.app.platform.JarvisAssistantRuntime.getInstance(app)
        runtime.stopAssistant()

        val serviceController = Robolectric.buildService(
            com.jarvis.app.platform.JarvisVoiceInteractionSessionService::class.java
        )
        val sessionService = serviceController.create().get()
        val session = sessionService.onNewSession(null) as com.jarvis.app.platform.JarvisVoiceInteractionSession
        try {
            session.onCreate()
            val contentView = session.onCreateContentView()
            assertNotNull(contentView)

            // Simulate active session + visible voice-only UI then Back press
            runtime.activateVoiceOnlyUi(AssistantLaunchIntentHelper.ACTION_VOICE_INTERACTION_SESSION, nowMs = 50_000L)
            assertTrue(runtime.isVoiceOnlyUiActive.value)
            assertTrue(runtime.isVoiceSessionActive.value)

            session.onBackPressed()
            assertFalse("Back must hide voice-only UI", runtime.isVoiceOnlyUiActive.value)
            assertTrue("Back must keep active voice session alive", runtime.isVoiceSessionActive.value)

            runtime.stopAssistant()
            assertFalse(runtime.isVoiceSessionActive.value)
        } finally {
            runtime.stopAssistant()
            session.onDestroy()
            serviceController.destroy()
        }
    }

    @Test
    fun `voice foreground service posts ongoing notification with Stop Assistant action and handles mic permission`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val runtime = com.jarvis.app.platform.JarvisAssistantRuntime.getInstance(app)
        runtime.stopAssistant()

        // 1. Denied mic permission -> start helper refuses to start microphone FGS cleanly
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        assertFalse(com.jarvis.app.platform.VoiceAssistantForegroundService.start(app))

        val deniedFgs = Robolectric.buildService(
            com.jarvis.app.platform.VoiceAssistantForegroundService::class.java,
            Intent(app, com.jarvis.app.platform.VoiceAssistantForegroundService::class.java).apply {
                action = com.jarvis.app.platform.VoiceAssistantForegroundService.ACTION_START_ASSISTANT
            }
        ).create()
        deniedFgs.startCommand(0, 1)
        assertNull(Shadows.shadowOf(deniedFgs.get()).lastForegroundNotification)
        deniedFgs.destroy()

        // 2. Granted mic permission -> FGS starts with ongoing notification and Stop Assistant action
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        assertTrue(com.jarvis.app.platform.VoiceAssistantForegroundService.start(app))
        runtime.activateVoiceOnlyUi(Intent.ACTION_ASSIST, nowMs = 80_000L)

        val fgsController = Robolectric.buildService(
            com.jarvis.app.platform.VoiceAssistantForegroundService::class.java,
            Intent(app, com.jarvis.app.platform.VoiceAssistantForegroundService::class.java).apply {
                action = com.jarvis.app.platform.VoiceAssistantForegroundService.ACTION_START_ASSISTANT
            }
        ).create()
        fgsController.startCommand(0, 1)
        ShadowLooper.runUiThreadTasks()

        val notif = Shadows.shadowOf(fgsController.get()).lastForegroundNotification
        assertNotNull("Ongoing foreground notification must be posted", notif)
        val stopLabel = app.getString(R.string.action_stop_assistant)
        assertTrue(
            "Foreground notification must expose Stop Assistant action",
            notif.actions?.any { it.title?.toString() == stopLabel } == true
        )

        // 3. ACTION_STOP_ASSISTANT terminates background voice session and stops service
        val stopController = Robolectric.buildService(
            com.jarvis.app.platform.VoiceAssistantForegroundService::class.java,
            Intent(app, com.jarvis.app.platform.VoiceAssistantForegroundService::class.java).apply {
                action = com.jarvis.app.platform.VoiceAssistantForegroundService.ACTION_STOP_ASSISTANT
            }
        )
        stopController.startCommand(0, 2)
        ShadowLooper.runUiThreadTasks()
        assertFalse(runtime.isVoiceSessionActive.value)
        assertFalse(runtime.isVoiceOnlyUiActive.value)

        fgsController.destroy()
        stopController.destroy()
    }

    private class InMemorySecretStorage : SecretStorage {
        private val map = mutableMapOf<String, String>()
        override suspend fun setSecret(key: String, value: String): Boolean {
            if (value.isBlank()) map.remove(key) else map[key] = value.trim()
            return true
        }

        override suspend fun getSecret(key: String): String = map[key].orEmpty()
    }

    private class InMemoryRemoteIdentitySigner : RemoteIdentitySigner {
        private val keyPair = generateEcKeyPair()
        private var counter = 0L
        private val pubB64 = DeviceIdentityManager.b64Url(keyPair.public.encoded)
        private val pubHash = DeviceIdentityManager.sha256(keyPair.public.encoded)
        private val deviceId = "phone-${pubHash.joinToString("") { "%02x".format(it) }.take(32)}"
        private val fingerprint = "sha256:${DeviceIdentityManager.b64Url(pubHash)}"

        override fun info() = DeviceIdentityManager.Info(deviceId, pubB64, fingerprint)

        override fun signAuth(hostId: String, nonce: String): DeviceIdentityManager.Signed {
            val next = ++counter
            val material = "aura-auth-v1\n${hostId.trim()}\n$deviceId\n${nonce.trim()}\n$next"
            return DeviceIdentityManager.Signed(deviceId, next, sign(material), pubB64, fingerprint)
        }

        override fun signEnvelope(
            connectionNonce: String,
            messageType: String,
            taskId: String,
            payloadDigest: String
        ): DeviceIdentityManager.Signed {
            val next = ++counter
            val material =
                "aura-envelope-v1\ndevice\n$deviceId\n${connectionNonce.trim()}\n$next\n${messageType.trim()}\n${taskId.trim()}\n${payloadDigest.trim()}"
            return DeviceIdentityManager.Signed(deviceId, next, sign(material))
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
        ): Boolean = DeviceIdentityManager.verifyHostEnvelopeStatic(
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

        private fun sign(material: String): String {
            val signer = Signature.getInstance("SHA256withECDSA")
            signer.initSign(keyPair.private)
            signer.update(material.toByteArray(StandardCharsets.UTF_8))
            return DeviceIdentityManager.b64Url(signer.sign())
        }
    }

    private class LoopbackAuthWsServer(
        private val hostId: String,
        @Volatile var hostKeys: KeyPair,
        private val observedFrames: MutableList<JSONObject> = mutableListOf(),
        private val tamperTaskResultDigest: Boolean = false,
        private val replayTaskResultCounter: Boolean = false
    ) {
        private val socket = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort
        val verificationErrors = CopyOnWriteArrayList<String>()
        private val running = AtomicBoolean(true)
        private var acceptThread: Thread? = null

        fun start() {
            acceptThread = thread(isDaemon = true) {
                while (running.get()) {
                    try {
                        val client = socket.accept()
                        thread(isDaemon = true) { handleClient(client) }
                    } catch (_: Exception) {
                        if (!running.get()) break
                    }
                }
            }
        }

        fun close() {
            running.set(false)
            try {
                socket.close()
            } catch (_: Exception) {
            }
        }

        private fun handleClient(client: java.net.Socket) {
            client.use { s ->
                s.soTimeout = 5000
                val input = BufferedInputStream(s.getInputStream())
                val output = s.getOutputStream()
                val headers = readHttpHeaders(input)
                val wsKey = headers["sec-websocket-key"] ?: return
                val accept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1")
                        .digest("${wsKey.trim()}258EAFA5-E914-47DA-95CA-C5AB0DC85B11".toByteArray(StandardCharsets.UTF_8))
                )
                val upgrade = "HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $accept\r\n\r\n"
                output.write(upgrade.toByteArray(StandardCharsets.UTF_8))
                output.flush()

                val activeKeys = hostKeys
                val pubB64 = DeviceIdentityManager.b64Url(activeKeys.public.encoded)
                val fp = DeviceIdentityManager.computeFingerprint(pubB64)!!
                val nonce = "srv-nonce-1234567890abcd"
                var hostCounter = 1L

                val helloText = readWsTextFrame(input) ?: return
                val hello = JSONObject(helloText)
                if (hello.optString("type") != "auth.hello") return
                val clientPubKeyBytes = DeviceIdentityManager.decodeB64(hello.getString("public_key"))
                val clientPubKey = java.security.KeyFactory.getInstance("EC")
                    .generatePublic(java.security.spec.X509EncodedKeySpec(clientPubKeyBytes))

                val challenge = JSONObject()
                    .put("type", "auth.challenge")
                    .put("host_id", hostId)
                    .put("nonce", nonce)
                    .put("public_key", pubB64)
                    .put("fingerprint", fp)
                writeWsTextFrame(output, challenge.toString())

                val authRespText = readWsTextFrame(input) ?: return
                val authResp = JSONObject(authRespText)
                if (authResp.optString("type") != "auth.response") return
                val authMaterial = "aura-auth-v1\n${hostId.trim()}\n${authResp.getString("device_id")}\n$nonce\n${authResp.getLong("counter")}"
                if (!verifyEc(clientPubKey, authMaterial, authResp.getString("signature"))) {
                    verificationErrors.add("auth.response invalid client signature")
                    return
                }

                val okDigest = DeviceIdentityManager.sha256Hex(
                    DeviceIdentityManager.canonicalResponsePayload("ok", "")
                )
                val okSig = signHostEnvelope(activeKeys, hostId, nonce, hostCounter, "auth.ok", "auth", okDigest)
                val okFrame = JSONObject()
                    .put("type", "auth.ok")
                    .put("task_id", "auth")
                    .put("status", "ok")
                    .put("counter", hostCounter)
                    .put("payload_digest", okDigest)
                    .put("signature", okSig)
                writeWsTextFrame(output, okFrame.toString())

                while (running.get()) {
                    val msg = readWsTextFrame(input) ?: break
                    val json = JSONObject(msg)
                    val msgType = json.optString("type")
                    if (msgType == "task.submit" || msgType == "remote.input") {
                        val envMaterial =
                            "aura-envelope-v1\ndevice\n${json.getString("device_id")}\n$nonce\n${json.getLong("counter")}\n$msgType\n${json.getString("task_id")}\n${json.getString("payload_digest")}"
                        if (!verifyEc(clientPubKey, envMaterial, json.getString("signature"))) {
                            verificationErrors.add("$msgType invalid client signature")
                        }
                    }
                    observedFrames.add(json)
                    if (msgType == "task.submit") {
                        val respCounter = if (replayTaskResultCounter) hostCounter else ++hostCounter
                        val summary = "Executed on $hostId"
                        val digest = if (tamperTaskResultDigest) {
                            DeviceIdentityManager.sha256Hex("tampered")
                        } else {
                            DeviceIdentityManager.sha256Hex(
                                DeviceIdentityManager.canonicalResponsePayload("ok", summary)
                            )
                        }
                        val taskId = json.getString("task_id")
                        val sig = signHostEnvelope(activeKeys, hostId, nonce, respCounter, "task.result", taskId, digest)
                        val result = JSONObject()
                            .put("type", "task.result")
                            .put("task_id", taskId)
                            .put("status", "ok")
                            .put("summary", summary)
                            .put("counter", respCounter)
                            .put("payload_digest", digest)
                            .put("signature", sig)
                        writeWsTextFrame(output, result.toString())
                    }
                }
            }
        }

        private fun verifyEc(pubKey: java.security.PublicKey, material: String, sigB64: String): Boolean = try {
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(pubKey)
            verifier.update(material.toByteArray(StandardCharsets.UTF_8))
            verifier.verify(DeviceIdentityManager.decodeB64(sigB64))
        } catch (_: Exception) {
            false
        }

        private fun readHttpHeaders(input: InputStream): Map<String, String> {
            val buf = ByteArrayOutputStream()
            var state = 0
            while (true) {
                val b = input.read()
                if (b == -1) break
                buf.write(b)
                state = when {
                    b == '\r'.code && (state == 0 || state == 2) -> state + 1
                    b == '\n'.code && (state == 1 || state == 3) -> state + 1
                    else -> 0
                }
                if (state == 4) break
            }
            val raw = buf.toString("UTF-8")
            val map = mutableMapOf<String, String>()
            raw.split("\r\n").drop(1).forEach { line ->
                val idx = line.indexOf(':')
                if (idx > 0) {
                    map[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                }
            }
            return map
        }

        private fun readWsTextFrame(input: InputStream): String? {
            while (true) {
                val b1 = input.read()
                if (b1 == -1) return null
                val b2 = input.read()
                if (b2 == -1) return null
                val opcode = b1 and 0x0F
                val masked = (b2 and 0x80) != 0
                var len = (b2 and 0x7F).toLong()
                if (len == 126L) {
                    val hi = input.read()
                    val lo = input.read()
                    if (hi == -1 || lo == -1) return null
                    len = ((hi shl 8) or lo).toLong()
                } else if (len == 127L) {
                    var full = 0L
                    repeat(8) {
                        val v = input.read()
                        if (v == -1) return null
                        full = (full shl 8) or v.toLong()
                    }
                    len = full
                }
                val mask = ByteArray(4)
                if (masked) {
                    var off = 0
                    while (off < 4) {
                        val r = input.read(mask, off, 4 - off)
                        if (r <= 0) return null
                        off += r
                    }
                }
                val payload = ByteArray(len.toInt())
                var read = 0
                while (read < payload.size) {
                    val r = input.read(payload, read, payload.size - read)
                    if (r <= 0) return null
                    read += r
                }
                if (masked) {
                    for (i in payload.indices) {
                        payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                    }
                }
                when (opcode) {
                    0x8 -> return null // Close
                    0x9, 0xA -> continue // Ping/Pong
                    0x1 -> return String(payload, StandardCharsets.UTF_8)
                    else -> continue
                }
            }
        }

        private fun writeWsTextFrame(output: OutputStream, text: String) {
            val data = text.toByteArray(StandardCharsets.UTF_8)
            val out = ByteArrayOutputStream()
            out.write(0x81)
            when {
                data.size < 126 -> out.write(data.size)
                data.size <= 65535 -> {
                    out.write(126)
                    out.write((data.size ushr 8) and 0xFF)
                    out.write(data.size and 0xFF)
                }
                else -> {
                    out.write(127)
                    val len = data.size.toLong()
                    for (shift in 56 downTo 0 step 8) {
                        out.write(((len ushr shift) and 0xFF).toInt())
                    }
                }
            }
            out.write(data)
            output.write(out.toByteArray())
            output.flush()
        }
    }

    companion object {
        private fun generateEcKeyPair(): KeyPair {
            val gen = KeyPairGenerator.getInstance("EC")
            gen.initialize(ECGenParameterSpec("secp256r1"))
            return gen.generateKeyPair()
        }

        private fun signHostEnvelope(
            keyPair: KeyPair,
            hostId: String,
            nonce: String,
            counter: Long,
            messageType: String,
            taskId: String,
            payloadDigest: String
        ): String {
            val material =
                "aura-envelope-v1\nhost\n${hostId.trim()}\n${nonce.trim()}\n$counter\n${messageType.trim()}\n${taskId.trim()}\n${payloadDigest.trim()}"
            val signer = Signature.getInstance("SHA256withECDSA")
            signer.initSign(keyPair.private)
            signer.update(material.toByteArray(StandardCharsets.UTF_8))
            return DeviceIdentityManager.b64Url(signer.sign())
        }
    }
}
