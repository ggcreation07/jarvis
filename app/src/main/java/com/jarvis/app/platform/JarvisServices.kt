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

class JarvisNotificationListener : NotificationListenerService() {
    companion object {
        @Volatile
        var instance: JarvisNotificationListener? = null

        fun isEnabled(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        instance = null
    }

    fun readActiveItems(filterApp: String = "", filterFrom: String = ""): List<NotificationItem> {
        val pm = packageManager
        val list = (activeNotifications ?: emptyArray())
            .filter { sbn ->
                val n = sbn.notification
                sbn.packageName != packageName &&
                    !sbn.isOngoing &&
                    (n.flags and Notification.FLAG_GROUP_SUMMARY) == 0
            }
            .mapNotNull { sbn ->
                val n = sbn.notification
                val extras = n.extras
                val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
                val title = (style?.conversationTitle ?: extras.getCharSequence(Notification.EXTRA_TITLE))?.toString().orEmpty().trim()
                val text = if (style != null && style.messages.isNotEmpty()) {
                    style.messages.takeLast(2).joinToString(" | ") { it.text?.toString().orEmpty() }
                } else {
                    (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                        ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty().trim()
                }
                if (title.isBlank() && text.isBlank()) return@mapNotNull null
                val app = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString()
                } catch (_: Exception) {
                    sbn.packageName
                }
                NotificationItem(sbn.key, sbn.packageName, app, title, text, sbn.postTime)
            }
            .sortedByDescending { it.postTime }

        return list.filter { item ->
            val okApp = filterApp.isBlank() || item.app.contains(filterApp, ignoreCase = true)
            val okFrom = filterFrom.isBlank() || item.title.contains(filterFrom, ignoreCase = true)
            okApp && okFrom
        }
    }
}

class JarvisAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile
        var instance: JarvisAccessibilityService? = null

        @Volatile
        var isConnected: Boolean = false
            private set

        private val _isConnectedState = MutableStateFlow(false)
        val isConnectedState: StateFlow<Boolean> = _isConnectedState.asStateFlow()

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
        }

        internal fun resetConnectionStateForTest() {
            updateConnectionState(null, false)
        }
    }

    public override fun onServiceConnected() {
        super.onServiceConnected()
        updateConnectionState(this, true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

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
