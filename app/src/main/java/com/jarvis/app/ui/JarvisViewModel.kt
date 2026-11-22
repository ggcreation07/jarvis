package com.jarvis.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import com.jarvis.app.data.ai.KeyCheckStatus
import com.jarvis.app.data.ai.QuotaUsageSnapshot
import com.jarvis.app.data.security.DeviceIdentityManager
import com.jarvis.app.data.security.SecureSecretStore
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
import com.jarvis.app.domain.model.WeatherSnapshot
import com.jarvis.app.platform.AssistantLaunchEvent
import com.jarvis.app.platform.DeviceController
import com.jarvis.app.platform.DeviceTelemetry
import com.jarvis.app.platform.JarvisAssistantRuntime
import com.jarvis.app.platform.RemotePcClient
import com.jarvis.app.platform.VoiceController
import kotlinx.coroutines.flow.StateFlow

class JarvisViewModel(application: Application) : AndroidViewModel(application) {

    val runtime: JarvisAssistantRuntime = JarvisAssistantRuntime.getInstance(application)

    val secretStore: SecureSecretStore get() = runtime.secretStore
    val telemetry: DeviceTelemetry get() = runtime.telemetry
    val deviceController: DeviceController get() = runtime.deviceController
    val voiceController: VoiceController get() = runtime.voiceController
    val remotePcClient: RemotePcClient get() = runtime.remotePcClient

    val screenConfig: StateFlow<ScreenConfig> get() = runtime.screenConfig
    val brainConfig: StateFlow<BrainConfig> get() = runtime.brainConfig
    val turns: StateFlow<List<ChatMessageItem>> get() = runtime.turns
    val archivedThreads: StateFlow<List<ArchivedThreadSummary>> get() = runtime.archivedThreads
    val facts: StateFlow<List<FactItem>> get() = runtime.facts
    val routines: StateFlow<List<RoutineItem>> get() = runtime.routines
    val playbooks: StateFlow<List<PlaybookItem>> get() = runtime.playbooks
    val todayAgenda: StateFlow<List<AgendaItem>> get() = runtime.todayAgenda
    val actionLogs: StateFlow<List<ActionLogItem>> get() = runtime.actionLogs
    val taskRecords: StateFlow<List<TaskRecordItem>> get() = runtime.taskRecords
    val activeOverlay: StateFlow<OverlayType> get() = runtime.activeOverlay
    val streamingMessage: StateFlow<ChatMessageItem?> get() = runtime.streamingMessage
    val isBusy: StateFlow<Boolean> get() = runtime.isBusy
    val hudStatus: StateFlow<HudStatus> get() = runtime.hudStatus
    val telemetryState: StateFlow<TelemetrySnapshot> get() = runtime.telemetryState
    val weatherState: StateFlow<WeatherSnapshot> get() = runtime.weatherState
    val generatedBitmaps: StateFlow<Map<String, Bitmap>> get() = runtime.generatedBitmaps
    val attachedImage: StateFlow<Pair<String, Bitmap>?> get() = runtime.attachedImage
    val prefillInput: StateFlow<String> get() = runtime.prefillInput
    val bannerMessage: StateFlow<String?> get() = runtime.bannerMessage
    val assistantLaunchEvent: StateFlow<AssistantLaunchEvent?> get() = runtime.assistantLaunchEvent
    val isVoiceSessionActive: StateFlow<Boolean> get() = runtime.isVoiceSessionActive
    val isVoiceOnlyUiActive: StateFlow<Boolean> get() = runtime.isVoiceOnlyUiActive

    val handsFreeActivity: StateFlow<HandsFreeActivity> get() = runtime.handsFreeActivity
    val readingText: StateFlow<String> get() = runtime.readingText
    val readingPage: StateFlow<Int> get() = runtime.readingPage
    val currentStepIndex: StateFlow<Int> get() = runtime.currentStepIndex
    val cameraGestureActive: StateFlow<Boolean> get() = runtime.cameraGestureActive
    val gesturePracticeMode: StateFlow<Boolean> get() = runtime.gesturePracticeMode
    val lastDetectedPose: StateFlow<HandPose> get() = runtime.lastDetectedPose

    val keyStatuses: StateFlow<Map<String, KeyCheckStatus>> get() = runtime.keyStatuses
    val quotaSnapshots: StateFlow<List<QuotaUsageSnapshot>> get() = runtime.quotaSnapshots
    val accessibilityEnabled: StateFlow<Boolean> get() = runtime.accessibilityEnabled
    val notificationAccessEnabled: StateFlow<Boolean> get() = runtime.notificationAccessEnabled
    val qrResultBitmap: StateFlow<Bitmap?> get() = runtime.qrResultBitmap
    val clipboardPreview: StateFlow<String> get() = runtime.clipboardPreview
    val remoteConnectionStatus: StateFlow<String> get() = runtime.remoteConnectionStatus

    fun deviceIdentity(): DeviceIdentityManager.Info = runtime.deviceIdentity()

    fun openOverlay(type: OverlayType) = runtime.openOverlay(type)

    fun closeOverlay() = runtime.closeOverlay()

    fun consumeBanner() = runtime.consumeBanner()

    fun showBanner(msg: String) = runtime.showBanner(msg)

    fun refreshServiceStatus() = runtime.refreshServiceStatus()

    fun refreshTelemetryAndWeather() = runtime.refreshTelemetryAndWeather()

    fun refreshCalendarIfEnabled() = runtime.refreshCalendarIfEnabled()

    fun sendUserMessage(text: String, openChatIfHome: Boolean = false) =
        runtime.sendUserMessage(text, openChatIfHome)

    fun stopCurrentWork() = runtime.stopCurrentWork()

    fun toggleVoiceInput() = runtime.toggleVoiceInput()

    fun activateVoiceOnlyUi(
        action: String,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean = runtime.activateVoiceOnlyUi(action, nowMs)

    fun onAssistantActivated(
        action: String,
        nowMs: Long = System.currentTimeMillis()
    ): Boolean = runtime.onAssistantActivated(action, nowMs)

    fun markAssistantVoiceTriggered(launchId: Long) =
        runtime.markAssistantVoiceTriggered(launchId)

    fun markAssistantEntranceCompleted(launchId: Long) =
        runtime.markAssistantEntranceCompleted(launchId)

    fun startAssistantListeningIfIdle(): Boolean =
        runtime.startAssistantListeningIfIdle(startForegroundServiceIfPermitted = true)

    fun hideVoiceOnlyUiKeepSession() = runtime.hideVoiceOnlyUiKeepSession()

    fun exitVoiceOnlyUiForNormalHome() = runtime.exitVoiceOnlyUiForNormalHome()

    fun stopAssistantSession() = runtime.stopAssistant()

    fun onActivityStopped() = runtime.onActivityStopped()

    fun attachImageUri(uri: Uri) = runtime.attachImageUri(uri)

    fun clearAttachedImage() = runtime.clearAttachedImage()

    fun prefillChatPrompt(prompt: String) = runtime.prefillChatPrompt(prompt)

    fun clearPrefillPrompt() = runtime.clearPrefillPrompt()

    fun handleIncomingSharedText(text: String) = runtime.handleIncomingSharedText(text)

    fun handleRoutineTap(routineName: String) = runtime.handleRoutineTap(routineName)

    fun startNewChat() = runtime.startNewChat()

    fun restoreThread(threadId: String) = runtime.restoreThread(threadId)

    fun deleteArchivedThread(threadId: String) = runtime.deleteArchivedThread(threadId)

    fun addFact(text: String, category: String = "personal") = runtime.addFact(text, category)

    fun forgetFact(id: Long) = runtime.forgetFact(id)

    fun addPlaybook(name: String, steps: String, triggers: String = "") =
        runtime.addPlaybook(name, steps, triggers)

    fun updatePlaybookStatus(item: PlaybookItem, newStatus: String) =
        runtime.updatePlaybookStatus(item, newStatus)

    fun deletePlaybook(id: Long) = runtime.deletePlaybook(id)

    fun saveRoutine(item: RoutineItem) = runtime.saveRoutine(item)

    fun deleteRoutine(id: Long) = runtime.deleteRoutine(id)

    fun runRoutine(item: RoutineItem) = runtime.runRoutine(item)

    fun addAgendaItem(timeLabel: String, task: String) = runtime.addAgendaItem(timeLabel, task)

    fun toggleAgendaItem(item: AgendaItem) = runtime.toggleAgendaItem(item)

    fun deleteAgendaItem(id: Long) = runtime.deleteAgendaItem(id)

    fun updateScreen(transform: (ScreenConfig) -> ScreenConfig) = runtime.updateScreen(transform)

    fun applyBuiltInLook(look: String) = runtime.applyBuiltInLook(look)

    fun saveCustomLookPreset(name: String) = runtime.saveCustomLookPreset(name)

    fun applyCustomLookPreset(preset: CustomLookPreset) = runtime.applyCustomLookPreset(preset)

    fun resetScreenDefaults() = runtime.resetScreenDefaults()

    fun updateBrain(transform: (BrainConfig) -> BrainConfig) = runtime.updateBrain(transform)

    fun toggleMute() = runtime.toggleMute()

    fun refreshKeyStatuses() = runtime.refreshKeyStatuses()

    fun saveAndVerifyApiKey(secretKeyName: String, providerId: String, rawValue: String) =
        runtime.saveAndVerifyApiKey(secretKeyName, providerId, rawValue)

    fun setGrantedFolder(uri: Uri) = runtime.setGrantedFolder(uri)

    fun selectHandsFreeActivity(activity: HandsFreeActivity) =
        runtime.selectHandsFreeActivity(activity)

    fun updateReadingText(newText: String) = runtime.updateReadingText(newText)

    fun setReadingPage(page: Int) = runtime.setReadingPage(page)

    fun setCurrentStep(step: Int) = runtime.setCurrentStep(step)

    fun setCameraGestureActive(active: Boolean) = runtime.setCameraGestureActive(active)

    fun setGesturePracticeMode(practice: Boolean) = runtime.setGesturePracticeMode(practice)

    fun onGestureDetected(pose: HandPose) = runtime.onGestureDetected(pose)

    fun generateQrCode(text: String) = runtime.generateQrCode(text)

    fun refreshClipboard() = runtime.refreshClipboard()

    fun testRemotePc(host: String, port: Int) = runtime.testRemotePc(host, port)

    fun wakeConfiguredPc() = runtime.wakeConfiguredPc()

    fun testAndroidTv(host: String, port: Int) = runtime.testAndroidTv(host, port)

    fun sendRemoteDesktopInput(action: String, x: Int? = null, y: Int? = null, text: String? = null) =
        runtime.sendRemoteDesktopInput(action, x, y, text)

    fun openAndroidSettings(action: String) = runtime.openAndroidSettings(action)

    fun openNotificationListenerSettings() = runtime.openNotificationListenerSettings()

    fun openAccessibilitySettings() = runtime.openAccessibilitySettings()

    fun openDefaultAssistantSettings() = runtime.openDefaultAssistantSettings()
}
