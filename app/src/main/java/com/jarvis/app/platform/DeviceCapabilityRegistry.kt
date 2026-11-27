package com.jarvis.app.platform

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CapabilityAvailabilityStatus {
    AVAILABLE,
    UNSUPPORTED_HARDWARE,
    PERMISSION_DENIED,
    SERVICE_DISCONNECTED,
    RESTRICTED_SYSTEM_ACTION,
    UNAVAILABLE
}

enum class DeviceActionId(val label: String) {
    VOLUME_CONTROL("Media volume control"),
    FLASHLIGHT("Camera flashlight"),
    OPEN_APP("Open installed apps"),
    DO_NOT_DISTURB("Do Not Disturb"),
    MEDIA_CONTROL("Media playback keys"),
    NAVIGATION_BACK("Navigate Back"),
    NAVIGATION_HOME("Navigate Home"),
    NAVIGATION_RECENTS("Open Recent Apps"),
    NAVIGATION_NOTIFICATIONS("Open Notifications"),
    NAVIGATION_QUICK_SETTINGS("Open Quick Settings"),
    SCREEN_READ("Read visible screen"),
    UI_FIND("Find UI elements"),
    UI_CLICK("Click UI elements"),
    UI_TYPE_TEXT("Type text in permitted fields"),
    UI_SCROLL("Scroll UI containers"),
    ANDROID_SETTINGS("Open Android Settings"),
    READ_NOTIFICATIONS("Read active notifications"),
    ALARM_TIMER("Alarms & timers"),
    SPEECH_RECOGNITION("Speech recognition"),
    OFFLINE_SPEECH_RECOGNITION("Offline speech recognition"),
    TEXT_TO_SPEECH("Text-to-speech"),
    CALENDAR_READ("Read calendar events"),
    WIFI_DIRECT_TOGGLE("Direct Wi-Fi toggle"),
    BLUETOOTH_DIRECT_TOGGLE("Direct Bluetooth toggle"),
    MOBILE_DATA_TOGGLE("Direct Mobile Data toggle")
}

data class HardwareFeatureSnapshot(
    val hasCameraFlash: Boolean,
    val hasCamera: Boolean,
    val hasMicrophone: Boolean,
    val hasAudioOutput: Boolean,
    val hasWifi: Boolean,
    val hasBluetooth: Boolean,
    val hasTelephony: Boolean
)

data class DeviceCapabilityStatus(
    val actionId: DeviceActionId,
    val label: String,
    val status: CapabilityAvailabilityStatus,
    val reason: String,
    val settingsAction: String? = null
) {
    val isAvailable: Boolean
        get() = status == CapabilityAvailabilityStatus.AVAILABLE
}

data class DeviceCapabilitiesSnapshot(
    val apiLevel: Int,
    val androidVersion: String,
    val hardware: HardwareFeatureSnapshot,
    val flashlightAvailable: Boolean,
    val flashlightCameraId: String?,
    val isVolumeFixed: Boolean,
    val accessibilitySettingsEnabled: Boolean,
    val accessibilityServiceConnected: Boolean,
    val notificationListenerEnabled: Boolean,
    val notificationListenerConnected: Boolean,
    val dndPolicyAccessGranted: Boolean,
    val recordAudioGranted: Boolean,
    val calendarPermissionGranted: Boolean,
    val speechRecognitionAvailable: Boolean,
    val offlineSpeechRecognitionConfirmed: Boolean,
    val ttsReady: Boolean,
    val capabilities: Map<DeviceActionId, DeviceCapabilityStatus>,
    val capturedAtMs: Long
) {
    fun statusFor(actionId: DeviceActionId): DeviceCapabilityStatus? = capabilities[actionId]

    fun isActionAvailable(actionId: DeviceActionId): Boolean =
        capabilities[actionId]?.isAvailable == true

    fun availableActions(): List<DeviceCapabilityStatus> =
        capabilities.values.filter { it.isAvailable }

    fun restrictedOrUnavailableActions(): List<DeviceCapabilityStatus> =
        capabilities.values.filter { !it.isAvailable }

    fun summaryReport(): String {
        val availableNames = availableActions().joinToString(", ") { it.label }
        val unavailableLines = restrictedOrUnavailableActions().joinToString("\n") {
            "- ${it.label}: ${it.status.name} (${it.reason})"
        }
        return buildString {
            append("Android ${androidVersion.ifBlank { apiLevel.toString() }} (API $apiLevel)\n")
            append("Available: ${availableNames.ifBlank { "None" }}")
            if (unavailableLines.isNotBlank()) {
                append("\nUnavailable / Restricted:\n")
                append(unavailableLines)
            }
        }
    }
}

class DeviceCapabilityRegistry(
    private val context: Context,
    private val ttsReadyProvider: () -> Boolean = { false },
    private val speechAvailableProvider: (() -> Boolean)? = null,
    private val offlineSpeechConfirmedProvider: (() -> Boolean)? = null,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private val appContext = context.applicationContext

    private val _capabilities = MutableStateFlow(discoverSnapshot())
    val capabilities: StateFlow<DeviceCapabilitiesSnapshot> = _capabilities.asStateFlow()

    fun current(): DeviceCapabilitiesSnapshot = _capabilities.value

    fun refresh(ttsReadyOverride: Boolean? = null): DeviceCapabilitiesSnapshot {
        val updated = discoverSnapshot(ttsReadyOverride)
        _capabilities.value = updated
        return updated
    }

    fun canResolveSettingsAction(action: String): Boolean {
        return try {
            val pm = appContext.packageManager
            val intent = Intent(action)
            intent.resolveActivity(pm) != null
        } catch (_: Exception) {
            false
        }
    }

    private fun findFlashCameraId(): String? {
        return try {
            val cm = appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
            cm.cameraIdList.firstOrNull { id ->
                runCatching {
                    cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }.getOrDefault(false)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun discoverSnapshot(ttsReadyOverride: Boolean? = null): DeviceCapabilitiesSnapshot {
        val pm = appContext.packageManager
        val flashCameraId = findFlashCameraId()
        val hasFlashFeature = runCatching { pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH) }.getOrDefault(false)
        val hasCamera = runCatching {
            pm.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) ||
                @Suppress("DEPRECATION")
                pm.hasSystemFeature(PackageManager.FEATURE_CAMERA)
        }.getOrDefault(false)
        val hasMic = runCatching { pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE) }.getOrDefault(false)
        val hasAudioOut = runCatching { pm.hasSystemFeature(PackageManager.FEATURE_AUDIO_OUTPUT) }.getOrDefault(false)
        val hasWifi = runCatching { pm.hasSystemFeature(PackageManager.FEATURE_WIFI) }.getOrDefault(false)
        val hasBluetooth = runCatching { pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH) }.getOrDefault(false)
        val hasTelephony = runCatching { pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) }.getOrDefault(false)

        val audioManager = runCatching {
            appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        }.getOrNull()
        val isVolumeFixed = runCatching { audioManager?.isVolumeFixed == true }.getOrDefault(false)

        val nm = runCatching {
            appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        }.getOrNull()
        val dndGranted = runCatching { nm?.isNotificationPolicyAccessGranted == true }.getOrDefault(false)

        val a11yEnabled = runCatching { JarvisAccessibilityService.isEnabled(appContext) }.getOrDefault(false)
        val a11yConnected = JarvisAccessibilityService.isConnectedNow()

        val notifEnabled = runCatching { JarvisNotificationListener.isEnabled(appContext) }.getOrDefault(false)
        val notifConnected = JarvisNotificationListener.isConnectedNow()

        val recordAudioGranted = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val calendarGranted = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED

        val speechAvailable = speechAvailableProvider?.invoke()
            ?: runCatching { SpeechRecognizer.isRecognitionAvailable(appContext) }.getOrDefault(false)

        val offlineSpeechConfirmed = offlineSpeechConfirmedProvider?.invoke()
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext) }.getOrDefault(false)
            } else {
                false
            }

        val ttsReady = ttsReadyOverride ?: runCatching { ttsReadyProvider() }.getOrDefault(false)
        val flashlightAvailable = flashCameraId != null

        val hardware = HardwareFeatureSnapshot(
            hasCameraFlash = hasFlashFeature || flashlightAvailable,
            hasCamera = hasCamera || flashlightAvailable,
            hasMicrophone = hasMic,
            hasAudioOutput = hasAudioOut || audioManager != null,
            hasWifi = hasWifi,
            hasBluetooth = hasBluetooth,
            hasTelephony = hasTelephony
        )

        val statusMap = linkedMapOf<DeviceActionId, DeviceCapabilityStatus>()

        fun put(
            id: DeviceActionId,
            status: CapabilityAvailabilityStatus,
            reason: String,
            settingsAction: String? = null
        ) {
            statusMap[id] = DeviceCapabilityStatus(
                actionId = id,
                label = id.label,
                status = status,
                reason = reason,
                settingsAction = settingsAction
            )
        }

        // Volume & media
        when {
            audioManager == null -> put(
                DeviceActionId.VOLUME_CONTROL,
                CapabilityAvailabilityStatus.UNAVAILABLE,
                "AudioManager service is unavailable."
            )
            isVolumeFixed -> put(
                DeviceActionId.VOLUME_CONTROL,
                CapabilityAvailabilityStatus.RESTRICTED_SYSTEM_ACTION,
                "Device volume is fixed by the system and cannot be adjusted programmatically.",
                Settings.ACTION_SOUND_SETTINGS
            )
            else -> put(
                DeviceActionId.VOLUME_CONTROL,
                CapabilityAvailabilityStatus.AVAILABLE,
                "Media volume adjustment is available."
            )
        }

        if (audioManager != null) {
            put(
                DeviceActionId.MEDIA_CONTROL,
                CapabilityAvailabilityStatus.AVAILABLE,
                "Media transport key dispatch is available."
            )
        } else {
            put(
                DeviceActionId.MEDIA_CONTROL,
                CapabilityAvailabilityStatus.UNAVAILABLE,
                "AudioManager service is unavailable."
            )
        }

        // Flashlight
        if (flashlightAvailable) {
            put(
                DeviceActionId.FLASHLIGHT,
                CapabilityAvailabilityStatus.AVAILABLE,
                "Camera flash unit is available."
            )
        } else {
            put(
                DeviceActionId.FLASHLIGHT,
                CapabilityAvailabilityStatus.UNSUPPORTED_HARDWARE,
                "No camera flash hardware is available on this device."
            )
        }

        // Open apps & Settings & Alarm/Timer
        put(
            DeviceActionId.OPEN_APP,
            CapabilityAvailabilityStatus.AVAILABLE,
            "PackageManager launcher resolution is available."
        )
        put(
            DeviceActionId.ANDROID_SETTINGS,
            CapabilityAvailabilityStatus.AVAILABLE,
            "Android system settings intents are available."
        )
        put(
            DeviceActionId.ALARM_TIMER,
            CapabilityAvailabilityStatus.AVAILABLE,
            "Clock alarm/timer intents are available."
        )

        // DND
        if (dndGranted) {
            put(
                DeviceActionId.DO_NOT_DISTURB,
                CapabilityAvailabilityStatus.AVAILABLE,
                "Notification Policy access is granted."
            )
        } else {
            put(
                DeviceActionId.DO_NOT_DISTURB,
                CapabilityAvailabilityStatus.PERMISSION_DENIED,
                "Do Not Disturb access permission has not been granted yet.",
                Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
            )
        }

        // Accessibility-dependent capabilities
        val a11yStatus = CapabilityAvailabilityStatus.SERVICE_DISCONNECTED
        val a11yReason = if (a11yEnabled) {
            "JARVIS Phone Control is enabled in Settings, but the Accessibility Service is disconnected."
        } else {
            "JARVIS Phone Control Accessibility Service is not enabled."
        }
        listOf(
            DeviceActionId.NAVIGATION_BACK,
            DeviceActionId.NAVIGATION_HOME,
            DeviceActionId.NAVIGATION_RECENTS,
            DeviceActionId.NAVIGATION_NOTIFICATIONS,
            DeviceActionId.NAVIGATION_QUICK_SETTINGS,
            DeviceActionId.SCREEN_READ,
            DeviceActionId.UI_FIND,
            DeviceActionId.UI_CLICK,
            DeviceActionId.UI_TYPE_TEXT,
            DeviceActionId.UI_SCROLL
        ).forEach { actionId ->
            if (a11yConnected) {
                put(
                    actionId,
                    CapabilityAvailabilityStatus.AVAILABLE,
                    "Accessibility Phone Control is connected."
                )
            } else {
                put(
                    actionId,
                    a11yStatus,
                    a11yReason,
                    Settings.ACTION_ACCESSIBILITY_SETTINGS
                )
            }
        }

        // Notifications
        when {
            notifConnected -> put(
                DeviceActionId.READ_NOTIFICATIONS,
                CapabilityAvailabilityStatus.AVAILABLE,
                "Notification Listener is connected."
            )
            notifEnabled -> put(
                DeviceActionId.READ_NOTIFICATIONS,
                CapabilityAvailabilityStatus.SERVICE_DISCONNECTED,
                "Notification access is enabled in Settings, but listener service is not connected yet.",
                Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
            )
            else -> put(
                DeviceActionId.READ_NOTIFICATIONS,
                CapabilityAvailabilityStatus.PERMISSION_DENIED,
                "Notification access permission has not been granted.",
                Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
            )
        }

        // Speech Recognition & Offline STT
        when {
            !recordAudioGranted -> {
                put(
                    DeviceActionId.SPEECH_RECOGNITION,
                    CapabilityAvailabilityStatus.PERMISSION_DENIED,
                    "RECORD_AUDIO permission is required for voice recognition."
                )
                put(
                    DeviceActionId.OFFLINE_SPEECH_RECOGNITION,
                    CapabilityAvailabilityStatus.PERMISSION_DENIED,
                    "RECORD_AUDIO permission is required before speech recognition can run."
                )
            }
            !speechAvailable -> {
                put(
                    DeviceActionId.SPEECH_RECOGNITION,
                    CapabilityAvailabilityStatus.UNAVAILABLE,
                    "No Android SpeechRecognizer service is available on this device."
                )
                put(
                    DeviceActionId.OFFLINE_SPEECH_RECOGNITION,
                    CapabilityAvailabilityStatus.UNAVAILABLE,
                    "No Android SpeechRecognizer service is available on this device."
                )
            }
            else -> {
                put(
                    DeviceActionId.SPEECH_RECOGNITION,
                    CapabilityAvailabilityStatus.AVAILABLE,
                    "Android SpeechRecognizer is available."
                )
                if (offlineSpeechConfirmed) {
                    put(
                        DeviceActionId.OFFLINE_SPEECH_RECOGNITION,
                        CapabilityAvailabilityStatus.AVAILABLE,
                        "On-device offline speech recognition is confirmed available on Android ${Build.VERSION.SDK_INT}."
                    )
                } else {
                    put(
                        DeviceActionId.OFFLINE_SPEECH_RECOGNITION,
                        CapabilityAvailabilityStatus.UNAVAILABLE,
                        "Offline speech recognition could not be confirmed on this device; online recognizer availability may still work."
                    )
                }
            }
        }

        // TTS
        if (ttsReady) {
            put(
                DeviceActionId.TEXT_TO_SPEECH,
                CapabilityAvailabilityStatus.AVAILABLE,
                "Text-to-Speech engine is ready."
            )
        } else {
            put(
                DeviceActionId.TEXT_TO_SPEECH,
                CapabilityAvailabilityStatus.UNAVAILABLE,
                "Text-to-Speech engine is not initialized or unavailable."
            )
        }

        // Calendar
        if (calendarGranted) {
            put(
                DeviceActionId.CALENDAR_READ,
                CapabilityAvailabilityStatus.AVAILABLE,
                "Calendar read permission is granted."
            )
        } else {
            put(
                DeviceActionId.CALENDAR_READ,
                CapabilityAvailabilityStatus.PERMISSION_DENIED,
                "READ_CALENDAR permission is not granted."
            )
        }

        // Restricted system toggles on modern Android
        put(
            DeviceActionId.WIFI_DIRECT_TOGGLE,
            CapabilityAvailabilityStatus.RESTRICTED_SYSTEM_ACTION,
            "Android restricts apps from toggling Wi-Fi silently; Jarvis can open Wi-Fi Settings instead.",
            Settings.ACTION_WIFI_SETTINGS
        )
        put(
            DeviceActionId.BLUETOOTH_DIRECT_TOGGLE,
            CapabilityAvailabilityStatus.RESTRICTED_SYSTEM_ACTION,
            "Android requires user interaction in Bluetooth Settings to change Bluetooth state safely; Jarvis can open Bluetooth Settings.",
            Settings.ACTION_BLUETOOTH_SETTINGS
        )
        put(
            DeviceActionId.MOBILE_DATA_TOGGLE,
            CapabilityAvailabilityStatus.RESTRICTED_SYSTEM_ACTION,
            "Direct Mobile Data toggling requires system/carrier privileges; Jarvis can open Network & Internet Settings instead.",
            Settings.ACTION_WIRELESS_SETTINGS
        )

        return DeviceCapabilitiesSnapshot(
            apiLevel = Build.VERSION.SDK_INT,
            androidVersion = Build.VERSION.RELEASE.orEmpty(),
            hardware = hardware,
            flashlightAvailable = flashlightAvailable,
            flashlightCameraId = flashCameraId,
            isVolumeFixed = isVolumeFixed,
            accessibilitySettingsEnabled = a11yEnabled,
            accessibilityServiceConnected = a11yConnected,
            notificationListenerEnabled = notifEnabled,
            notificationListenerConnected = notifConnected,
            dndPolicyAccessGranted = dndGranted,
            recordAudioGranted = recordAudioGranted,
            calendarPermissionGranted = calendarGranted,
            speechRecognitionAvailable = speechAvailable,
            offlineSpeechRecognitionConfirmed = offlineSpeechConfirmed,
            ttsReady = ttsReady,
            capabilities = statusMap.toMap(),
            capturedAtMs = nowMs()
        )
    }
}
