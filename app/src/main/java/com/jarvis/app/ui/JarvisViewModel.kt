package com.jarvis.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.app.data.ai.ByoAiClient
import com.jarvis.app.data.ai.FirebaseAiService
import com.jarvis.app.data.ai.KeyCheckStatus
import com.jarvis.app.data.ai.QuotaUsageSnapshot
import com.jarvis.app.data.local.ConfigStore
import com.jarvis.app.data.local.JarvisDatabase
import com.jarvis.app.data.local.JarvisRepository
import com.jarvis.app.data.network.JarvisHttpTools
import com.jarvis.app.data.security.DeviceIdentityManager
import com.jarvis.app.data.security.SecureSecretStore
import com.jarvis.app.domain.brain.JarvisOrchestrator
import com.jarvis.app.domain.brain.ToolExecutor
import com.jarvis.app.domain.brain.TurnProgressEvent
import com.jarvis.app.domain.model.ActionLogItem
import com.jarvis.app.domain.model.AgendaItem
import com.jarvis.app.domain.model.ArchivedThreadSummary
import com.jarvis.app.domain.model.BrainConfig
import com.jarvis.app.domain.model.ChatMessageItem
import com.jarvis.app.domain.model.CustomLookPreset
import com.jarvis.app.domain.model.FactItem
import com.jarvis.app.domain.model.HandPose
import com.jarvis.app.domain.model.HandsFreeActivity
import com.jarvis.app.domain.model.HudStatus
import com.jarvis.app.domain.model.OverlayType
import com.jarvis.app.domain.model.PlaybookItem
import com.jarvis.app.domain.model.RoutineItem
import com.jarvis.app.domain.model.ScreenConfig
import com.jarvis.app.domain.model.TaskRecordItem
import com.jarvis.app.domain.model.TelemetrySnapshot
import com.jarvis.app.domain.model.ToolActivityItem
import com.jarvis.app.domain.model.WeatherSnapshot
import com.jarvis.app.platform.DeviceController
import com.jarvis.app.platform.DeviceTelemetry
import com.jarvis.app.platform.JarvisAccessibilityService
import com.jarvis.app.platform.JarvisNotificationListener
import com.jarvis.app.platform.RemotePcClient
import com.jarvis.app.platform.RoutineAlarms
import com.jarvis.app.platform.TvControlClient
import com.jarvis.app.platform.VoiceController
import com.jarvis.app.platform.WakeOnLan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class JarvisViewModel(application: Application) : AndroidViewModel(application) {

    private val context = application.applicationContext
    private val db = JarvisDatabase.getInstance(context)
    private val repository = JarvisRepository(db.jarvisDao())
    private val configStore = ConfigStore(context)
    val secretStore = SecureSecretStore(context)
    private val httpTools = JarvisHttpTools()
    val telemetry = DeviceTelemetry(context)
    val deviceController = DeviceController(context)
    val voiceController = VoiceController(context)
    private val firebaseAiService = FirebaseAiService()
    private val byoAiClient = ByoAiClient()
    val remotePcClient = RemotePcClient(context)
    private val tvControlClient = TvControlClient()
    private val toolExecutor = ToolExecutor(
        repository = repository,
        configStore = configStore,
        httpTools = httpTools,
        telemetry = telemetry,
        deviceController = deviceController,
        firebaseAiService = firebaseAiService,
        remotePcClient = remotePcClient,
        tvControlClient = tvControlClient
    )
    private val orchestrator = JarvisOrchestrator(
        repository = repository,
        configStore = configStore,
        secretStore = secretStore,
        firebaseAiService = firebaseAiService,
        byoAiClient = byoAiClient,
        toolExecutor = toolExecutor
    )

    val screenConfig: StateFlow<ScreenConfig> = configStore.screenConfig
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ScreenConfig())

    val brainConfig: StateFlow<BrainConfig> = configStore.brainConfig
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), BrainConfig())

    val turns: StateFlow<List<ChatMessageItem>> = repository.currentTurns
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val archivedThreads: StateFlow<List<ArchivedThreadSummary>> = repository.archivedThreads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val facts: StateFlow<List<FactItem>> = repository.facts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val routines: StateFlow<List<RoutineItem>> = repository.routines
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val playbooks: StateFlow<List<PlaybookItem>> = repository.playbooks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val todayKey = RoutineAlarms.isoDate(System.currentTimeMillis())
    private val localAgenda = repository.observeAgendaForDay(todayKey)
    private val calendarAgenda = MutableStateFlow<List<AgendaItem>>(emptyList())

    val todayAgenda: StateFlow<List<AgendaItem>> = combine(localAgenda, calendarAgenda) { local, cal ->
        (local + cal).sortedBy { it.timeLabel }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val actionLogs: StateFlow<List<ActionLogItem>> = repository.actionLogs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val taskRecords: StateFlow<List<TaskRecordItem>> = repository.taskRecords
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _activeOverlay = MutableStateFlow(OverlayType.NONE)
    val activeOverlay: StateFlow<OverlayType> = _activeOverlay.asStateFlow()

    private val _streamingMessage = MutableStateFlow<ChatMessageItem?>(null)
    val streamingMessage: StateFlow<ChatMessageItem?> = _streamingMessage.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    val hudStatus: StateFlow<HudStatus> = combine(
        voiceController.isListening,
        voiceController.isSpeaking,
        _isBusy,
        _streamingMessage
    ) { listening, speaking, busy, streaming ->
        when {
            listening -> HudStatus.LISTENING
            speaking -> HudStatus.SPEAKING
            busy && streaming?.toolActivities?.any { it.status == "running" } == true -> HudStatus.WORKING
            busy -> HudStatus.THINKING
            else -> HudStatus.IDLE
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HudStatus.IDLE)

    private val _telemetryState = MutableStateFlow(telemetry.snapshot())
    val telemetryState: StateFlow<TelemetrySnapshot> = _telemetryState.asStateFlow()

    private val _weatherState = MutableStateFlow(WeatherSnapshot())
    val weatherState: StateFlow<WeatherSnapshot> = _weatherState.asStateFlow()

    private val _generatedBitmaps = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val generatedBitmaps: StateFlow<Map<String, Bitmap>> = _generatedBitmaps.asStateFlow()

    private val _attachedImage = MutableStateFlow<Pair<String, Bitmap>?>(null)
    val attachedImage: StateFlow<Pair<String, Bitmap>?> = _attachedImage.asStateFlow()

    private val _prefillInput = MutableStateFlow("")
    val prefillInput: StateFlow<String> = _prefillInput.asStateFlow()

    private val _bannerMessage = MutableStateFlow<String?>(null)
    val bannerMessage: StateFlow<String?> = _bannerMessage.asStateFlow()

    // Hands-free & Gestures state
    private val _handsFreeActivity = MutableStateFlow(HandsFreeActivity.READING)
    val handsFreeActivity: StateFlow<HandsFreeActivity> = _handsFreeActivity.asStateFlow()

    private val _readingText = MutableStateFlow(
        "Welcome to J.A.R.V.I.S. Hands-Free Reader.\n\nPaste an article, notes, or recipe steps here to read page by page or follow step by step."
    )
    val readingText: StateFlow<String> = _readingText.asStateFlow()

    private val _readingPage = MutableStateFlow(0)
    val readingPage: StateFlow<Int> = _readingPage.asStateFlow()

    private val _currentStepIndex = MutableStateFlow(0)
    val currentStepIndex: StateFlow<Int> = _currentStepIndex.asStateFlow()

    private val _cameraGestureActive = MutableStateFlow(false)
    val cameraGestureActive: StateFlow<Boolean> = _cameraGestureActive.asStateFlow()

    private val _gesturePracticeMode = MutableStateFlow(false)
    val gesturePracticeMode: StateFlow<Boolean> = _gesturePracticeMode.asStateFlow()

    private val _lastDetectedPose = MutableStateFlow(HandPose.UNKNOWN)
    val lastDetectedPose: StateFlow<HandPose> = _lastDetectedPose.asStateFlow()

    // Diagnostics / Keys / Service status
    private val _keyStatuses = MutableStateFlow<Map<String, KeyCheckStatus>>(emptyMap())
    val keyStatuses: StateFlow<Map<String, KeyCheckStatus>> = _keyStatuses.asStateFlow()

    private val _quotaSnapshots = MutableStateFlow<List<QuotaUsageSnapshot>>(emptyList())
    val quotaSnapshots: StateFlow<List<QuotaUsageSnapshot>> = _quotaSnapshots.asStateFlow()

    private val _accessibilityEnabled = MutableStateFlow(false)
    val accessibilityEnabled: StateFlow<Boolean> = _accessibilityEnabled.asStateFlow()

    private val _notificationAccessEnabled = MutableStateFlow(false)
    val notificationAccessEnabled: StateFlow<Boolean> = _notificationAccessEnabled.asStateFlow()

    private val _qrResultBitmap = MutableStateFlow<Bitmap?>(null)
    val qrResultBitmap: StateFlow<Bitmap?> = _qrResultBitmap.asStateFlow()

    private val _clipboardPreview = MutableStateFlow("")
    val clipboardPreview: StateFlow<String> = _clipboardPreview.asStateFlow()

    private val _remoteConnectionStatus = MutableStateFlow("Not connected")
    val remoteConnectionStatus: StateFlow<String> = _remoteConnectionStatus.asStateFlow()

    private var activeTurnJob: Job? = null

    init {
        viewModelScope.launch {
            val (savedText, savedPage) = configStore.loadReadingSession()
            if (savedText.isNotBlank()) {
                _readingText.value = savedText
                _readingPage.value = savedPage
            }
            refreshServiceStatus()
            refreshTelemetryAndWeather()
            refreshKeyStatuses()
            refreshCalendarIfEnabled()
        }
        viewModelScope.launch {
            routines.collect { list ->
                RoutineAlarms.syncRoutines(context, list)
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(15_000L)
                refreshTelemetryOnly()
                refreshServiceStatus()
            }
        }
    }

    fun deviceIdentity(): DeviceIdentityManager.Info = remotePcClient.deviceInfo()

    fun openOverlay(type: OverlayType) {
        _activeOverlay.value = type
        if (type == OverlayType.SKILLS) {
            _clipboardPreview.value = deviceController.readClipboard()
        } else if (type == OverlayType.SETTINGS) {
            refreshServiceStatus()
            _quotaSnapshots.value = byoAiClient.allQuotaSnapshots()
        }
    }

    fun closeOverlay() {
        _activeOverlay.value = OverlayType.NONE
    }

    fun consumeBanner() {
        _bannerMessage.value = null
    }

    fun showBanner(msg: String) {
        _bannerMessage.value = msg
    }

    fun refreshServiceStatus() {
        _accessibilityEnabled.value = JarvisAccessibilityService.isEnabled(context)
        _notificationAccessEnabled.value = JarvisNotificationListener.isEnabled(context)
    }

    private fun refreshTelemetryOnly() {
        val currentNet = _telemetryState.value.network
        val updated = telemetry.snapshot()
        _telemetryState.value = updated.copy(
            network = updated.network.copy(
                publicIp = currentNet.publicIp,
                cityRegion = currentNet.cityRegion
            )
        )
    }

    fun refreshTelemetryAndWeather() {
        viewModelScope.launch {
            refreshTelemetryOnly()
            val cfg = brainConfig.value
            val ip = httpTools.ipGeoLookup()
            if (ip != null) {
                _telemetryState.value = _telemetryState.value.copy(
                    network = telemetry.readNetworkSnapshot(ip.ip, ip.place.ifBlank { "—" })
                )
            }
            val coords: Triple<Double, Double, String>? = when {
                cfg.pinnedLocation.isNotBlank() -> {
                    httpTools.geocode(cfg.pinnedLocation)?.let { Triple(it.lat, it.lon, cfg.pinnedLocation) }
                }
                else -> {
                    val gps = telemetry.lastKnownDeviceLocation()
                    if (gps != null) {
                        Triple(gps.first, gps.second, ip?.place?.ifBlank { "Current location" } ?: "Current location")
                    } else {
                        ip?.let { Triple(it.lat, it.lon, it.place) }
                    }
                }
            }
            if (coords != null) {
                _weatherState.value = httpTools.fetchWeather(coords.first, coords.second, coords.third)
            } else {
                _weatherState.value = WeatherSnapshot(error = "Location unavailable")
            }
        }
    }

    fun refreshCalendarIfEnabled() {
        viewModelScope.launch(Dispatchers.IO) {
            if (brainConfig.value.calendarSyncEnabled && deviceController.hasCalendarPermission()) {
                calendarAgenda.value = deviceController.readTodaysCalendarEvents()
            } else {
                calendarAgenda.value = emptyList()
            }
        }
    }

    fun sendUserMessage(text: String, openChatIfHome: Boolean = false) {
        val trimmed = text.trim()
        val attached = _attachedImage.value
        if (trimmed.isEmpty() && attached == null) return

        val finalPrompt = if (trimmed.isEmpty()) "Analyze this image." else trimmed
        _prefillInput.value = ""
        if (openChatIfHome && _activeOverlay.value == OverlayType.NONE) {
            _activeOverlay.value = OverlayType.CHAT
        }

        activeTurnJob?.cancel()
        activeTurnJob = viewModelScope.launch {
            _isBusy.value = true
            val cfg = brainConfig.value
            val userMsg = ChatMessageItem(
                role = "user",
                content = finalPrompt,
                agentId = cfg.selectedAgentId,
                attachmentLabel = attached?.first.orEmpty()
            )
            repository.addTurn(userMsg)
            _attachedImage.value = null

            val history = repository.getCurrentTurnsOnce().dropLast(1)
            var latestActivities: List<ToolActivityItem> = emptyList()
            var activeAgent = cfg.selectedAgentId
            var generatedImgKey = ""
            var generatedQrText = ""

            _streamingMessage.value = ChatMessageItem(
                role = "assistant",
                content = "",
                agentId = activeAgent,
                isStreaming = true
            )

            try {
                orchestrator.executeTurn(
                    rawUserInput = finalPrompt,
                    attachedBitmap = attached?.second,
                    history = history,
                    brainConfig = cfg
                ).collect { event ->
                    when (event) {
                        is TurnProgressEvent.AgentSelected -> {
                            activeAgent = event.agentId
                            _streamingMessage.value = _streamingMessage.value?.copy(agentId = activeAgent)
                        }
                        is TurnProgressEvent.ToolActivitiesUpdated -> {
                            latestActivities = event.activities
                            _streamingMessage.value = _streamingMessage.value?.copy(toolActivities = latestActivities)
                        }
                        is TurnProgressEvent.StreamingText -> {
                            _streamingMessage.value = _streamingMessage.value?.copy(content = event.text)
                        }
                        is TurnProgressEvent.SideEffect -> {
                            val eff = event.effect
                            eff.openOverlay?.let { _activeOverlay.value = it }
                            if (eff.clearChat) repository.clearCurrentChatWithoutArchiving()
                            eff.generatedBitmap?.let { bmp ->
                                val key = "img_${System.currentTimeMillis()}"
                                generatedImgKey = key
                                _generatedBitmaps.value = _generatedBitmaps.value + (key to bmp)
                            }
                            eff.qrBitmap?.let { bmp ->
                                val key = "qr_${System.currentTimeMillis()}"
                                generatedImgKey = key
                                generatedQrText = eff.qrText
                                _generatedBitmaps.value = _generatedBitmaps.value + (key to bmp)
                            }
                            eff.runRoutinePrompt?.let { rPrompt ->
                                viewModelScope.launch { sendUserMessage(rPrompt, openChatIfHome = false) }
                            }
                        }
                        is TurnProgressEvent.Finished -> {
                            _streamingMessage.value = null
                            _isBusy.value = false
                            val finalMsg = ChatMessageItem(
                                role = "assistant",
                                content = event.replyText,
                                agentId = event.agentId,
                                toolActivities = event.activities,
                                generatedImageKey = generatedImgKey,
                                qrText = generatedQrText,
                                isError = event.isError
                            )
                            repository.addTurn(finalMsg)
                            _quotaSnapshots.value = byoAiClient.allQuotaSnapshots()
                            if (!event.isError && cfg.voiceTtsEnabled && !cfg.muted) {
                                voiceController.speak(event.replyText)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                _streamingMessage.value = null
                _isBusy.value = false
            }
        }
    }

    fun stopCurrentWork() {
        activeTurnJob?.cancel()
        _streamingMessage.value = null
        _isBusy.value = false
        voiceController.stopListening()
        voiceController.stopSpeaking()
    }

    fun toggleVoiceInput() {
        if (voiceController.isSpeaking.value) {
            voiceController.stopSpeaking()
            return
        }
        if (voiceController.isListening.value) {
            voiceController.stopListening()
            return
        }
        voiceController.startListening(
            onFinalResult = { spoken ->
                sendUserMessage(spoken, openChatIfHome = false)
            },
            onFailure = { msg ->
                _bannerMessage.value = msg
            }
        )
    }

    fun attachImageUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bmp = context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)
                }
                if (bmp != null) {
                    val maxDim = 1280
                    val scaled = if (bmp.width > maxDim || bmp.height > maxDim) {
                        val ratio = minOf(maxDim.toFloat() / bmp.width, maxDim.toFloat() / bmp.height)
                        Bitmap.createScaledBitmap(bmp, (bmp.width * ratio).toInt(), (bmp.height * ratio).toInt(), true)
                    } else bmp
                    _attachedImage.value = "Attached photo" to scaled
                }
            } catch (e: Exception) {
                _bannerMessage.value = "Couldn't load photo: ${e.localizedMessage}"
            }
        }
    }

    fun clearAttachedImage() {
        _attachedImage.value = null
    }

    fun prefillChatPrompt(prompt: String) {
        _prefillInput.value = prompt
        _activeOverlay.value = OverlayType.CHAT
    }

    fun clearPrefillPrompt() {
        _prefillInput.value = ""
    }

    fun handleIncomingSharedText(text: String) {
        if (text.isBlank()) return
        _prefillInput.value = text
        _activeOverlay.value = OverlayType.CHAT
    }

    fun handleRoutineTap(routineName: String) {
        viewModelScope.launch {
            val match = repository.getRoutinesOnce().firstOrNull { it.name.equals(routineName, true) }
            if (match != null) {
                repository.upsertRoutine(match.copy(lastRunDate = RoutineAlarms.isoDate(System.currentTimeMillis())))
                sendUserMessage(match.prompt, openChatIfHome = true)
            }
        }
    }

    // Chat thread management
    fun startNewChat() {
        viewModelScope.launch { repository.archiveAndClearCurrentChat() }
    }

    fun restoreThread(threadId: String) {
        viewModelScope.launch { repository.restoreArchivedThread(threadId) }
    }

    fun deleteArchivedThread(threadId: String) {
        viewModelScope.launch { repository.deleteArchivedThread(threadId) }
    }

    // Memory / Facts / Playbooks
    fun addFact(text: String, category: String = "personal") {
        viewModelScope.launch { repository.addFact(text, category) }
    }

    fun forgetFact(id: Long) {
        viewModelScope.launch { repository.forgetFactById(id) }
    }

    fun addPlaybook(name: String, steps: String, triggers: String = "") {
        viewModelScope.launch { repository.addPlaybook(name, steps, triggers) }
    }

    fun updatePlaybookStatus(item: PlaybookItem, newStatus: String) {
        viewModelScope.launch { repository.updatePlaybookStatus(item, newStatus) }
    }

    fun deletePlaybook(id: Long) {
        viewModelScope.launch { repository.deletePlaybook(id) }
    }

    // Routines
    fun saveRoutine(item: RoutineItem) {
        viewModelScope.launch { repository.upsertRoutine(item) }
    }

    fun deleteRoutine(id: Long) {
        viewModelScope.launch { repository.deleteRoutine(id) }
    }

    fun runRoutine(item: RoutineItem) {
        viewModelScope.launch {
            repository.upsertRoutine(item.copy(lastRunDate = RoutineAlarms.isoDate(System.currentTimeMillis())))
            sendUserMessage(item.prompt, openChatIfHome = true)
        }
    }

    // Agenda
    fun addAgendaItem(timeLabel: String, task: String) {
        viewModelScope.launch { repository.addAgendaItem(todayKey, timeLabel, task) }
    }

    fun toggleAgendaItem(item: AgendaItem) {
        if (item.source == "calendar") return
        viewModelScope.launch { repository.updateAgendaItem(item.copy(completed = !item.completed)) }
    }

    fun deleteAgendaItem(id: Long) {
        viewModelScope.launch { repository.deleteAgendaItem(id) }
    }

    // Customize Screen
    fun updateScreen(transform: (ScreenConfig) -> ScreenConfig) {
        viewModelScope.launch { configStore.updateScreenConfig(transform) }
    }

    fun applyBuiltInLook(look: String) {
        updateScreen { cur ->
            if (look == "ironman") {
                cur.copy(
                    look = "ironman",
                    accentKey = "iron",
                    mainHex = "#00E5FF",
                    secondHex = "#FFAA00",
                    fontStyle = "hud",
                    cardStyle = "hud",
                    centerpiece = "reactor",
                    gaugeStyle = "rings",
                    backgroundStyle = "grid"
                )
            } else {
                cur.copy(
                    look = "modern",
                    accentKey = "cyan",
                    mainHex = "#00E5FF",
                    secondHex = "#3B82F6",
                    fontStyle = "clean",
                    cardStyle = "clean",
                    centerpiece = "orb",
                    gaugeStyle = "bars",
                    backgroundStyle = "grid"
                )
            }
        }
    }

    fun saveCustomLookPreset(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        updateScreen { cur ->
            val preset = CustomLookPreset(
                name = clean,
                look = cur.look,
                accentKey = cur.accentKey,
                mainHex = cur.mainHex,
                secondHex = cur.secondHex,
                fontStyle = cur.fontStyle,
                cardStyle = cur.cardStyle,
                centerpiece = cur.centerpiece,
                gaugeStyle = cur.gaugeStyle,
                cornerRadiusDp = cur.cornerRadiusDp,
                glowStrength = cur.glowStrength,
                backgroundStyle = cur.backgroundStyle,
                density = cur.density
            )
            cur.copy(customPresets = (cur.customPresets.filterNot { it.name.equals(clean, true) } + preset).takeLast(6))
        }
    }

    fun applyCustomLookPreset(preset: CustomLookPreset) {
        updateScreen { cur ->
            cur.copy(
                look = preset.look,
                accentKey = preset.accentKey,
                mainHex = preset.mainHex,
                secondHex = preset.secondHex,
                fontStyle = preset.fontStyle,
                cardStyle = preset.cardStyle,
                centerpiece = preset.centerpiece,
                gaugeStyle = preset.gaugeStyle,
                cornerRadiusDp = preset.cornerRadiusDp,
                glowStrength = preset.glowStrength,
                backgroundStyle = preset.backgroundStyle,
                density = preset.density
            )
        }
    }

    fun resetScreenDefaults() {
        updateScreen { ScreenConfig(quickStartCompleted = it.quickStartCompleted) }
    }

    // Brain Config & Settings
    fun updateBrain(transform: (BrainConfig) -> BrainConfig) {
        viewModelScope.launch { configStore.updateBrainConfig(transform) }
    }

    fun toggleMute() {
        viewModelScope.launch {
            configStore.updateBrainConfig { cur ->
                val nextMuted = !cur.muted
                if (nextMuted) voiceController.stopSpeaking()
                cur.copy(muted = nextMuted)
            }
        }
    }

    fun refreshKeyStatuses() {
        viewModelScope.launch {
            val configured = secretStore.allConfiguredKeys()
            val map = mutableMapOf<String, KeyCheckStatus>()
            configured.forEach { (key, has) ->
                map[key] = if (has) KeyCheckStatus.WORKING else KeyCheckStatus.EMPTY
            }
            _keyStatuses.value = map
        }
    }

    fun saveAndVerifyApiKey(secretKeyName: String, providerId: String, rawValue: String) {
        viewModelScope.launch {
            secretStore.setSecret(secretKeyName, rawValue)
            if (rawValue.isBlank()) {
                _keyStatuses.value = _keyStatuses.value + (secretKeyName to KeyCheckStatus.EMPTY)
                return@launch
            }
            _keyStatuses.value = _keyStatuses.value + (secretKeyName to KeyCheckStatus.CHECKING)
            val verdict = byoAiClient.verifyKey(providerId, rawValue)
            _keyStatuses.value = _keyStatuses.value + (secretKeyName to verdict)
        }
    }

    fun setGrantedFolder(uri: Uri) {
        val label = uri.lastPathSegment?.substringAfterLast(':') ?: "Granted folder"
        updateBrain { it.copy(grantedFolderUri = uri.toString(), grantedFolderName = label) }
        _bannerMessage.value = "Folder access saved: $label"
    }

    // Hands-Free & Gestures
    fun selectHandsFreeActivity(activity: HandsFreeActivity) {
        _handsFreeActivity.value = activity
        _currentStepIndex.value = 0
    }

    fun updateReadingText(newText: String) {
        _readingText.value = newText
        _readingPage.value = 0
        viewModelScope.launch { configStore.saveReadingSession(newText, 0) }
    }

    fun setReadingPage(page: Int) {
        _readingPage.value = page.coerceAtLeast(0)
        viewModelScope.launch { configStore.saveReadingSession(_readingText.value, _readingPage.value) }
    }

    fun setCurrentStep(step: Int) {
        _currentStepIndex.value = step.coerceAtLeast(0)
    }

    fun setCameraGestureActive(active: Boolean) {
        _cameraGestureActive.value = active
        if (!active) _lastDetectedPose.value = HandPose.UNKNOWN
    }

    fun setGesturePracticeMode(practice: Boolean) {
        _gesturePracticeMode.value = practice
    }

    fun onGestureDetected(pose: HandPose) {
        _lastDetectedPose.value = pose
        if (_gesturePracticeMode.value || pose == HandPose.UNKNOWN) return
        when (_handsFreeActivity.value) {
            HandsFreeActivity.READING -> when (pose) {
                HandPose.OPEN_PALM, HandPose.PEACE -> setReadingPage(_readingPage.value + 1)
                HandPose.THUMBS_DOWN -> setReadingPage((_readingPage.value - 1).coerceAtLeast(0))
                HandPose.FIST -> setCameraGestureActive(false)
                else -> Unit
            }
            HandsFreeActivity.STEPS -> when (pose) {
                HandPose.OPEN_PALM, HandPose.THUMBS_UP -> setCurrentStep(_currentStepIndex.value + 1)
                HandPose.THUMBS_DOWN -> setCurrentStep((_currentStepIndex.value - 1).coerceAtLeast(0))
                HandPose.FIST -> setCameraGestureActive(false)
                else -> Unit
            }
            HandsFreeActivity.MEDIA -> when (pose) {
                HandPose.OPEN_PALM -> deviceController.sendMediaKey("play_pause")
                HandPose.PEACE -> deviceController.sendMediaKey("next")
                HandPose.THUMBS_DOWN -> deviceController.sendMediaKey("previous")
                HandPose.FIST -> setCameraGestureActive(false)
                else -> Unit
            }
            else -> if (pose == HandPose.FIST) {
                stopCurrentWork()
                setCameraGestureActive(false)
            }
        }
    }

    // Skills: QR & Clipboard
    fun generateQrCode(text: String) {
        _qrResultBitmap.value = httpTools.generateQrBitmap(text)
    }

    fun refreshClipboard() {
        _clipboardPreview.value = deviceController.readClipboard()
    }

    // Remote PC & TV
    fun testRemotePc(host: String, port: Int) {
        viewModelScope.launch {
            _remoteConnectionStatus.value = "Testing $host:$port…"
            val (ok, msg) = remotePcClient.testConnection(host, port)
            _remoteConnectionStatus.value = msg
            if (ok) {
                updateBrain { it.copy(pcHost = host.trim(), pcPort = port, pcPaired = true, pcName = host.trim()) }
            }
        }
    }

    fun wakeConfiguredPc() {
        viewModelScope.launch {
            val cfg = brainConfig.value
            val (_, msg) = WakeOnLan.send(cfg.pcMacAddress, cfg.pcHost)
            _bannerMessage.value = msg
        }
    }

    fun testAndroidTv(host: String, port: Int) {
        viewModelScope.launch {
            val (ok, msg) = tvControlClient.checkTvReachable(host, port)
            _bannerMessage.value = msg
            if (ok) {
                updateBrain { it.copy(tvHost = host.trim(), tvPort = port, tvPaired = true, tvName = host.trim()) }
            }
        }
    }

    fun sendRemoteDesktopInput(action: String, x: Int? = null, y: Int? = null, text: String? = null) {
        val cfg = brainConfig.value
        viewModelScope.launch {
            remotePcClient.sendRemoteInput(cfg.pcHost, cfg.pcPort, action, x, y, text)
        }
    }

    fun openAndroidSettings(action: String) {
        deviceController.openSettingsScreen(action)
    }

    fun openNotificationListenerSettings() {
        deviceController.openSettingsScreen(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    }

    fun openAccessibilitySettings() {
        deviceController.openSettingsScreen(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }

    override fun onCleared() {
        voiceController.release()
        super.onCleared()
    }
}
