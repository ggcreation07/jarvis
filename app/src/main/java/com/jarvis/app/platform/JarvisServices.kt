package com.jarvis.app.platform

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NotificationItem(
    val key: String,
    val pkg: String,
    val app: String,
    val title: String,
    val text: String,
    val postTime: Long
)

data class NotificationReadBatch(
    val connected: Boolean,
    val items: List<NotificationItem> = emptyList(),
    val hiddenSensitiveCount: Int = 0,
    val excludedCount: Int = 0
)

object NotificationSensitivityFilter {
    private val explicitKeywordPatterns = listOf(
        Regex(
            "\\b(otp|one[ -]?time\\s+(?:password|passcode|pin|code)|verification\\s+code|auth(?:entication)?\\s+code|security\\s+code|login\\s+code|2fa|two[ -]?factor|mfa|passcode|password|pin\\s*(?:code|number)?|mpin|tpin|upi\\s*pin|cvv|cvc|secret\\s+code|secret\\s+key|recovery\\s+code)\\b",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            "(ओटीपी|पासवर्ड|पिन|वेरिफिकेशन\\s*कोड|सत्यापन\\s*कोड|गोपनीय\\s*कोड)",
            RegexOption.IGNORE_CASE
        )
    )

    private val codeDigitPattern = Regex("\\b\\d{4,8}\\b")
    private val authContextPattern = Regex(
        "\\b(code|verify|verification|authenticate|auth|txn|transaction|debit(?:ed)?|credit(?:ed)?|bank|upi|login|sign[ -]?in|valid\\s+for|do\\s+not\\s+share|never\\s+share|use\\s+\\d{4,8}|enter\\s+\\d{4,8})\\b",
        RegexOption.IGNORE_CASE
    )

    fun isSensitive(
        title: String,
        text: String,
        subText: String = "",
        visibility: Int = Notification.VISIBILITY_PRIVATE
    ): Boolean {
        if (visibility == Notification.VISIBILITY_SECRET) return true
        val combined = listOf(title, text, subText)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()
        if (combined.isBlank()) return false
        if (explicitKeywordPatterns.any { it.containsMatchIn(combined) }) return true
        if (codeDigitPattern.containsMatchIn(combined) && authContextPattern.containsMatchIn(combined)) {
            return true
        }
        return false
    }
}

class JarvisNotificationListener : NotificationListenerService() {
    companion object {
        @Volatile
        var instance: JarvisNotificationListener? = null

        @Volatile
        var isConnected: Boolean = false
            private set

        private val _isConnectedState = MutableStateFlow(false)
        val isConnectedState: StateFlow<Boolean> = _isConnectedState.asStateFlow()

        fun isConnectedNow(): Boolean = isConnected && instance != null

        fun isEnabled(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        private fun updateConnectionState(service: JarvisNotificationListener?, connected: Boolean) {
            instance = service
            isConnected = connected
            _isConnectedState.value = connected
        }

        internal fun resetConnectionStateForTest() {
            activeNotificationsProviderForTest = null
            updateConnectionState(null, false)
        }

        @Volatile
        internal var activeNotificationsProviderForTest: (() -> Array<android.service.notification.StatusBarNotification>?)? = null
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        updateConnectionState(this, true)
    }

    override fun onListenerDisconnected() {
        updateConnectionState(null, false)
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        updateConnectionState(null, false)
        super.onDestroy()
    }

    fun readActiveItems(
        filterApp: String = "",
        filterFrom: String = "",
        excludedPackages: Set<String> = emptySet()
    ): List<NotificationItem> =
        readActiveItemsDetailed(
            filterApp = filterApp,
            filterFrom = filterFrom,
            excludedPackages = excludedPackages
        ).items

    fun readActiveItemsDetailed(
        filterApp: String = "",
        filterFrom: String = "",
        excludedPackages: Set<String> = emptySet()
    ): NotificationReadBatch {
        if (!isConnectedNow()) {
            return NotificationReadBatch(connected = false)
        }
        val pm = packageManager
        val normalizedExclusions = excludedPackages
            .map { it.trim().lowercase(java.util.Locale.ROOT) }
            .filter { it.isNotBlank() }
            .toSet()

        var hiddenSensitiveCount = 0
        var excludedCount = 0
        val safeItems = mutableListOf<NotificationItem>()

        val rawActive = activeNotificationsProviderForTest?.invoke() ?: try {
            activeNotifications
        } catch (_: Exception) {
            null
        }
        val sortedNotifications = (rawActive ?: emptyArray())
            .filter { sbn ->
                val n = sbn.notification
                sbn.packageName != packageName &&
                    !sbn.isOngoing &&
                    (n.flags and Notification.FLAG_GROUP_SUMMARY) == 0
            }
            .sortedByDescending { it.postTime }

        for (sbn in sortedNotifications) {
            val n = sbn.notification
            val pkgName = sbn.packageName.orEmpty()
            val app = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkgName, 0)).toString()
            } catch (_: Exception) {
                pkgName
            }

            if (isExcludedApp(pkgName, app, normalizedExclusions)) {
                excludedCount += 1
                continue
            }

            val extras = n.extras
            val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
            val title = (style?.conversationTitle ?: extras?.getCharSequence(Notification.EXTRA_TITLE))
                ?.toString().orEmpty().trim()
            val subText = extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty().trim()
            val rawText = if (style != null && style.messages.isNotEmpty()) {
                style.messages.takeLast(2).joinToString(" | ") { it.text?.toString().orEmpty() }
            } else {
                (extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)
                    ?: extras?.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty().trim()
            }

            if (NotificationSensitivityFilter.isSensitive(title, rawText, subText, n.visibility)) {
                hiddenSensitiveCount += 1
                continue
            }

            if (title.isBlank() && rawText.isBlank()) continue

            val okApp = filterApp.isBlank() ||
                app.contains(filterApp, ignoreCase = true) ||
                pkgName.contains(filterApp, ignoreCase = true)
            val okFrom = filterFrom.isBlank() || title.contains(filterFrom, ignoreCase = true)
            if (okApp && okFrom) {
                safeItems.add(
                    NotificationItem(
                        key = sbn.key,
                        pkg = pkgName,
                        app = app,
                        title = title,
                        text = rawText,
                        postTime = sbn.postTime
                    )
                )
            }
        }

        return NotificationReadBatch(
            connected = true,
            items = safeItems,
            hiddenSensitiveCount = hiddenSensitiveCount,
            excludedCount = excludedCount
        )
    }

    private fun isExcludedApp(
        pkg: String,
        appLabel: String,
        normalizedExclusions: Set<String>
    ): Boolean {
        if (normalizedExclusions.isEmpty()) return false
        val lowerPkg = pkg.trim().lowercase(java.util.Locale.ROOT)
        val lowerApp = appLabel.trim().lowercase(java.util.Locale.ROOT)
        return normalizedExclusions.any { ex ->
            lowerPkg == ex ||
                lowerApp == ex ||
                (ex.length >= 3 && (lowerPkg.contains(".$ex") || lowerApp.contains(ex)))
        }
    }
}

data class AccessibilityLiveSnapshotState(
    val connected: Boolean = false,
    val activePackage: String = "",
    val lastEventType: Int = 0,
    val lastEventTimeMs: Long = 0L,
    val hasAccessibleText: Boolean = false,
    val latestSnapshot: ScreenSnapshot? = null
)

class JarvisAccessibilityService : AccessibilityService() {
    companion object {
        private const val EVENT_REFRESH_THROTTLE_MS = 120L

        @Volatile
        var instance: JarvisAccessibilityService? = null

        @Volatile
        var isConnected: Boolean = false
            private set

        private val _isConnectedState = MutableStateFlow(false)
        val isConnectedState: StateFlow<Boolean> = _isConnectedState.asStateFlow()

        private val _liveSnapshotState = MutableStateFlow(AccessibilityLiveSnapshotState())
        val liveSnapshotState: StateFlow<AccessibilityLiveSnapshotState> = _liveSnapshotState.asStateFlow()

        @Volatile
        var onLiveSnapshotUpdated: ((ScreenSnapshot) -> Unit)? = null

        @Volatile
        internal var nowMsProvider: () -> Long = { System.currentTimeMillis() }

        fun isConnectedNow(): Boolean = isConnected && instance != null

        fun isEnabled(context: Context): Boolean {
            val accessibilityEnabled = try {
                Settings.Secure.getInt(
                    context.contentResolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    0
                ) == 1
            } catch (_: Exception) {
                false
            }
            if (!accessibilityEnabled) return false

            val expectedComponent = ComponentName(context, JarvisAccessibilityService::class.java)
            val expected = expectedComponent.flattenToString()
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            return enabled.split(":").any { raw ->
                val trimmed = raw.trim()
                trimmed.isNotEmpty() && (
                    trimmed.equals(expected, ignoreCase = true) ||
                        ComponentName.unflattenFromString(trimmed) == expectedComponent
                    )
            }
        }

        private fun updateConnectionState(service: JarvisAccessibilityService?, connected: Boolean) {
            instance = service
            isConnected = connected
            _isConnectedState.value = connected
            if (!connected) {
                _liveSnapshotState.value = AccessibilityLiveSnapshotState()
            } else {
                _liveSnapshotState.value = _liveSnapshotState.value.copy(connected = true)
            }
        }

        internal fun resetConnectionStateForTest() {
            nowMsProvider = { System.currentTimeMillis() }
            onLiveSnapshotUpdated = null
            updateConnectionState(null, false)
        }
    }

    @Volatile
    private var lastEventRefreshMs: Long = 0L

    @Volatile
    private var lastEventTypeHandled: Int = 0

    @Volatile
    private var lastEventPackage: String = ""

    public override fun onServiceConnected() {
        super.onServiceConnected()
        lastEventRefreshMs = 0L
        lastEventTypeHandled = 0
        lastEventPackage = ""
        updateConnectionState(this, true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isConnectedNow() || event == null) return
        val eventType = event.eventType
        val isRelevant = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED ||
            eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
        if (!isRelevant) return

        val now = nowMsProvider()
        val eventPkg = try {
            event.packageName?.toString().orEmpty().trim()
        } catch (_: Exception) {
            ""
        }
        val packageChanged = eventPkg.isNotBlank() && !eventPkg.equals(lastEventPackage, ignoreCase = true)
        val withinThrottle = (now - lastEventRefreshMs) in 0L until EVENT_REFRESH_THROTTLE_MS
        if (withinThrottle &&
            !packageChanged &&
            eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType == lastEventTypeHandled
        ) {
            return
        }

        lastEventRefreshMs = now
        lastEventTypeHandled = eventType
        if (eventPkg.isNotBlank()) {
            lastEventPackage = eventPkg
        }

        val result = ScreenReaderEngine(
            windowSource = createWindowSource(),
            maxAttempts = 1,
            retryDelayMs = 0L,
            nowMs = nowMsProvider
        ).readScreenImmediate()

        if (result is ScreenReadResult.Success) {
            val snapshot = result.snapshot
            _liveSnapshotState.value = AccessibilityLiveSnapshotState(
                connected = true,
                activePackage = snapshot.packageName,
                lastEventType = eventType,
                lastEventTimeMs = now,
                hasAccessibleText = snapshot.hasReadableText(),
                latestSnapshot = snapshot
            )
            onLiveSnapshotUpdated?.invoke(snapshot)
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        updateConnectionState(null, false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        updateConnectionState(null, false)
        super.onDestroy()
    }

    fun performHome(): Boolean =
        isConnected && performGlobalAction(GLOBAL_ACTION_HOME)

    fun performBack(): Boolean =
        isConnected && performGlobalAction(GLOBAL_ACTION_BACK)

    fun performRecents(): Boolean =
        isConnected && performGlobalAction(GLOBAL_ACTION_RECENTS)

    fun performNotifications(): Boolean =
        isConnected && performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    fun performQuickSettings(): Boolean =
        isConnected && performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)

    fun performNavigationAction(action: String): Boolean? = when (action) {
        "back" -> performBack()
        "home" -> performHome()
        "recents" -> performRecents()
        "notifications" -> performNotifications()
        "quick_settings" -> performQuickSettings()
        else -> null
    }

    suspend fun readScreen(
        maxAttempts: Int = ScreenReaderEngine.DEFAULT_MAX_ATTEMPTS,
        retryDelayMs: Long = ScreenReaderEngine.DEFAULT_RETRY_DELAY_MS
    ): ScreenReadResult {
        val engine = ScreenReaderEngine(
            windowSource = createWindowSource(),
            maxAttempts = maxAttempts,
            retryDelayMs = retryDelayMs
        )
        return engine.readScreen()
    }

    suspend fun findUiElements(
        query: UiElementQuery,
        maxAttempts: Int = ScreenReaderEngine.DEFAULT_MAX_ATTEMPTS,
        retryDelayMs: Long = ScreenReaderEngine.DEFAULT_RETRY_DELAY_MS
    ): FindUiElementsResult {
        val engine = ScreenInteractionEngine(
            windowSource = createWindowSource(),
            maxAttempts = maxAttempts,
            retryDelayMs = retryDelayMs
        )
        return engine.findElements(query)
    }

    suspend fun inspectClickCandidate(
        query: UiElementQuery,
        maxAttempts: Int = ScreenReaderEngine.DEFAULT_MAX_ATTEMPTS,
        retryDelayMs: Long = ScreenReaderEngine.DEFAULT_RETRY_DELAY_MS
    ): ClickPrecheckOutcome {
        val engine = ScreenInteractionEngine(
            windowSource = createWindowSource(),
            maxAttempts = maxAttempts,
            retryDelayMs = retryDelayMs
        )
        return engine.inspectForClick(query)
    }

    suspend fun clickUiElement(
        query: UiElementQuery,
        maxAttempts: Int = ScreenReaderEngine.DEFAULT_MAX_ATTEMPTS,
        retryDelayMs: Long = ScreenReaderEngine.DEFAULT_RETRY_DELAY_MS
    ): ClickUiElementResult {
        val engine = ScreenInteractionEngine(
            windowSource = createWindowSource(),
            maxAttempts = maxAttempts,
            retryDelayMs = retryDelayMs
        )
        return engine.clickElement(query)
    }

    suspend fun inspectTypeTextCandidate(
        query: UiElementQuery,
        textToEnter: String,
        maxAttempts: Int = ScreenReaderEngine.DEFAULT_MAX_ATTEMPTS,
        retryDelayMs: Long = ScreenReaderEngine.DEFAULT_RETRY_DELAY_MS
    ): TypeTextPrecheckOutcome {
        val engine = ScreenInteractionEngine(
            windowSource = createWindowSource(),
            maxAttempts = maxAttempts,
            retryDelayMs = retryDelayMs
        )
        return engine.inspectForTypeText(query, textToEnter)
    }

    suspend fun typeTextInUiElement(
        query: UiElementQuery,
        textToEnter: String,
        maxAttempts: Int = ScreenReaderEngine.DEFAULT_MAX_ATTEMPTS,
        retryDelayMs: Long = ScreenReaderEngine.DEFAULT_RETRY_DELAY_MS
    ): TypeTextResult {
        val engine = ScreenInteractionEngine(
            windowSource = createWindowSource(),
            maxAttempts = maxAttempts,
            retryDelayMs = retryDelayMs
        )
        return engine.typeText(query, textToEnter)
    }

    suspend fun scrollUiContainer(
        query: UiElementQuery,
        direction: String,
        maxAttempts: Int = ScreenReaderEngine.DEFAULT_MAX_ATTEMPTS,
        retryDelayMs: Long = ScreenReaderEngine.DEFAULT_RETRY_DELAY_MS
    ): ScrollUiResult {
        val engine = ScreenInteractionEngine(
            windowSource = createWindowSource(),
            maxAttempts = maxAttempts,
            retryDelayMs = retryDelayMs
        )
        return engine.scrollContainer(query, direction)
    }

    private fun createWindowSource(): AccessibilityWindowSource {
        val service = this
        val ownPkg = try {
            service.packageName.orEmpty()
        } catch (_: Exception) {
            ""
        }
        return object : AccessibilityWindowSource {
            override val ownPackages: Set<String> =
                setOf(ownPkg, ScreenReaderEngine.JARVIS_APP_PACKAGE)
                    .filter { it.isNotBlank() }
                    .toSet()

            override fun isSettingsEnabled(): Boolean = try {
                isEnabled(service)
            } catch (_: Exception) {
                false
            }

            override fun isServiceConnected(): Boolean =
                isConnected && instance === service

            override fun obtainActiveRoot(): AccessibilityNodeInfo? = try {
                service.rootInActiveWindow
            } catch (_: Exception) {
                null
            }

            override fun obtainFallbackWindowRoots(): List<AccessibilityNodeInfo> {
                val windowList = try {
                    service.windows
                } catch (_: Exception) {
                    null
                }.orEmpty()
                if (windowList.isEmpty()) return emptyList()
                val ordered = windowList
                    .filter { win ->
                        win.type == AccessibilityWindowInfo.TYPE_APPLICATION ||
                            win.type == AccessibilityWindowInfo.TYPE_SYSTEM
                    }
                    .sortedWith(
                        compareByDescending<AccessibilityWindowInfo> { it.isActive }
                            .thenByDescending { it.isFocused }
                            .thenByDescending { try { it.layer } catch (_: Exception) { 0 } }
                            .thenByDescending { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                    )
                return ordered.mapNotNull { win ->
                    try {
                        win.root
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        }
    }
}
