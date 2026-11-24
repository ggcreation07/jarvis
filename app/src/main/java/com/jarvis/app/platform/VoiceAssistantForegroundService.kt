package com.jarvis.app.platform

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.jarvis.app.AssistantOverlayActivity
import com.jarvis.app.R
import com.jarvis.app.domain.model.HudStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class VoiceAssistantForegroundService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var statusObserverJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val runtime = JarvisAssistantRuntime.getInstance(applicationContext)

        if (action == ACTION_STOP_ASSISTANT) {
            runtime.stopAssistant()
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }

        val hasMicPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasMicPermission) {
            stopSelf()
            return START_NOT_STICKY
        }

        ensureNotificationChannel()
        val notification = buildNotification(runtime.hudStatus.value)
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            0
        }

        val started = runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
        }.isSuccess
        if (!started) {
            stopSelf()
            return START_NOT_STICKY
        }

        observeRuntimeStatus(runtime)
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        JarvisAssistantRuntime.getInstance(applicationContext).stopAssistant()
        stopForegroundAndSelf()
        super.onTimeout(startId, fgsType)
    }

    override fun onDestroy() {
        statusObserverJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun observeRuntimeStatus(runtime: JarvisAssistantRuntime) {
        if (statusObserverJob != null) return
        statusObserverJob = serviceScope.launch {
            combine(runtime.isVoiceSessionActive, runtime.hudStatus) { active, status ->
                active to status
            }.collect { (active, status) ->
                if (!active) {
                    stopForegroundAndSelf()
                } else {
                    val manager = getSystemService(NotificationManager::class.java)
                    manager?.notify(NOTIFICATION_ID, buildNotification(status))
                }
            }
        }
    }

    internal fun buildNotification(status: HudStatus): Notification {
        val statusText = when (status) {
            HudStatus.LISTENING -> getString(R.string.voice_notif_listening)
            HudStatus.THINKING, HudStatus.WORKING -> getString(R.string.voice_notif_thinking)
            HudStatus.SPEAKING -> getString(R.string.voice_notif_speaking)
            HudStatus.IDLE -> getString(R.string.voice_notif_ready)
        }

        val openOrbIntent = Intent(this, AssistantOverlayActivity::class.java).apply {
            action = Intent.ACTION_ASSIST
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            101,
            openOrbIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, VoiceAssistantForegroundService::class.java).apply {
            action = ACTION_STOP_ASSISTANT
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            102,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_jarvis)
            .setContentTitle(getString(R.string.voice_notif_title))
            .setContentText(statusText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.action_stop_assistant),
                stopPendingIntent
            )
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.voice_notif_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.voice_notif_ready)
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun stopForegroundAndSelf() {
        runCatching {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
        stopSelf()
    }

    companion object {
        const val CHANNEL_ID = "jarvis_voice_assistant"
        const val NOTIFICATION_ID = 7310
        const val ACTION_START_ASSISTANT = "com.jarvis.app.action.START_VOICE_ASSISTANT"
        const val ACTION_STOP_ASSISTANT = "com.jarvis.app.action.STOP_VOICE_ASSISTANT"

        fun start(context: Context): Boolean {
            val appCtx = context.applicationContext
            val hasMic = ContextCompat.checkSelfPermission(
                appCtx,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasMic) return false
            val intent = Intent(appCtx, VoiceAssistantForegroundService::class.java).apply {
                action = ACTION_START_ASSISTANT
            }
            return runCatching {
                ContextCompat.startForegroundService(appCtx, intent)
            }.isSuccess
        }

        fun stop(context: Context) {
            val appCtx = context.applicationContext
            runCatching {
                appCtx.stopService(Intent(appCtx, VoiceAssistantForegroundService::class.java))
            }
        }
    }
}
