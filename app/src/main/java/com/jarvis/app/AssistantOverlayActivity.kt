package com.jarvis.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.app.domain.model.HudStatus
import com.jarvis.app.platform.AssistantLaunchIntentHelper
import com.jarvis.app.ui.JarvisViewModel
import com.jarvis.app.ui.components.resolveOrbState
import com.jarvis.app.ui.screens.VoiceOnlyAssistantScreen
import com.jarvis.app.ui.theme.JarvisTheme

class AssistantOverlayActivity : ComponentActivity() {

    private val viewModel: JarvisViewModel by viewModels()

    private val assistantMicPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        onAssistantMicPermissionResult(granted)
    }

    internal fun onAssistantMicPermissionResult(granted: Boolean) {
        if (granted) {
            viewModel.startAssistantListeningIfIdle()
        } else {
            viewModel.showBanner("Microphone permission denied. Tap the orb to enable voice.")
        }
    }

    internal fun onVoiceOnlyBackPressed() {
        // Hide overlay UI while keeping active voice session & foreground service running.
        viewModel.hideVoiceOnlyUiKeepSession()
        finish()
    }

    internal fun onStopAssistantClicked() {
        viewModel.stopAssistantSession()
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureAppCheck(this, intent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        enableEdgeToEdge()

        handleIncomingAssistantIntent(intent, fromSavedState = savedInstanceState != null)

        setContent {
            val screenConfig by viewModel.screenConfig.collectAsStateWithLifecycle()
            val bannerMessage by viewModel.bannerMessage.collectAsStateWithLifecycle()
            val assistantLaunchEvent by viewModel.assistantLaunchEvent.collectAsStateWithLifecycle()
            val isListening by viewModel.voiceController.isListening.collectAsStateWithLifecycle()
            val isSpeaking by viewModel.voiceController.isSpeaking.collectAsStateWithLifecycle()
            val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
            val hudStatus by viewModel.hudStatus.collectAsStateWithLifecycle()
            val audioLevel by viewModel.voiceController.audioLevel.collectAsStateWithLifecycle()
            val speakingLevel by viewModel.voiceController.speakingLevel.collectAsStateWithLifecycle()
            val snackbarHostState = remember { SnackbarHostState() }

            LaunchedEffect(bannerMessage) {
                val msg = bannerMessage ?: return@LaunchedEffect
                snackbarHostState.showSnackbar(msg)
                viewModel.consumeBanner()
            }

            val currentOrbState = remember(isListening, isSpeaking, isBusy, hudStatus) {
                resolveOrbState(
                    isListening = isListening,
                    isSpeaking = isSpeaking,
                    isBusy = isBusy,
                    status = hudStatus
                )
            }

            JarvisTheme(screenConfig = screenConfig) {
                Box(modifier = Modifier.fillMaxSize()) {
                    VoiceOnlyAssistantScreen(
                        orbState = currentOrbState,
                        audioLevel = audioLevel,
                        speakingLevel = speakingLevel,
                        launchEvent = assistantLaunchEvent,
                        translucentOverlay = true,
                        onEntranceCompleted = { id -> viewModel.markAssistantEntranceCompleted(id) },
                        onOrbClick = {
                            if (hudStatus == HudStatus.LISTENING || hudStatus == HudStatus.SPEAKING || isBusy) {
                                viewModel.stopCurrentWork()
                            } else {
                                val hasMic = ContextCompat.checkSelfPermission(
                                    this@AssistantOverlayActivity,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED
                                if (hasMic) {
                                    viewModel.startAssistantListeningIfIdle()
                                } else {
                                    assistantMicPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                        },
                        onHideKeepSession = { onVoiceOnlyBackPressed() },
                        onStopAssistant = { onStopAssistantClicked() }
                    )

                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 92.dp, start = 16.dp, end = 16.dp)
                    ) { data ->
                        Snackbar(snackbarData = data)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingAssistantIntent(intent, fromSavedState = false)
    }

    override fun onStop() {
        super.onStop()
        viewModel.onActivityStopped()
    }

    private fun handleIncomingAssistantIntent(intent: Intent?, fromSavedState: Boolean) {
        if (intent == null) return
        if (AssistantLaunchIntentHelper.shouldHandleAssistantIntent(intent, fromSavedState)) {
            val action = intent.action ?: return
            AssistantLaunchIntentHelper.markHandled(intent)
            if (viewModel.activateVoiceOnlyUi(action)) {
                val launchEvent = viewModel.assistantLaunchEvent.value
                if (launchEvent != null && !launchEvent.voiceTriggered) {
                    viewModel.markAssistantVoiceTriggered(launchEvent.launchId)
                    val hasMic = ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (hasMic) {
                        viewModel.startAssistantListeningIfIdle()
                    } else {
                        assistantMicPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            }
        }
    }
}
