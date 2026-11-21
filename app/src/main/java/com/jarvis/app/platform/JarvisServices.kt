package com.jarvis.app.platform

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

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

        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, JarvisAccessibilityService::class.java).flattenToString()
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            return enabled.split(":").any { it.equals(expected, ignoreCase = true) }
        }
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun performHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun performBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun performRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
}
