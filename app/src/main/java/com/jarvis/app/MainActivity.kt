package com.jarvis.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.app.domain.model.OverlayType
import com.jarvis.app.platform.RoutineAlarms
import com.jarvis.app.ui.JarvisViewModel
import com.jarvis.app.ui.components.AssistantLaunchEntranceOverlay
import com.jarvis.app.ui.components.resolveOrbState
import com.jarvis.app.ui.screens.AssistantMainScreen
import com.jarvis.app.ui.sheets.CapabilitiesSheet
import com.jarvis.app.ui.sheets.CommandPaletteDialog
import com.jarvis.app.ui.sheets.CustomizeSheet
import com.jarvis.app.ui.sheets.DevicePowerSheet
import com.jarvis.app.ui.sheets.HandsFreeSheet
import com.jarvis.app.ui.sheets.MemorySheet
import com.jarvis.app.ui.sheets.QuickStartSheet
import com.jarvis.app.ui.sheets.RemoteDesktopScreen
import com.jarvis.app.ui.sheets.RemotePcAndTvSheet
import com.jarvis.app.ui.sheets.RoutinesSheet
import com.jarvis.app.ui.sheets.SettingsSheet
import com.jarvis.app.ui.sheets.SkillsSheet
import com.jarvis.app.ui.sheets.TasksActivitySheet
import com.jarvis.app.ui.theme.JarvisTheme
import com.jarvis.app.ui.theme.LocalJarvisColors

class MainActivity : ComponentActivity() {

    private val viewModel: JarvisViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureAppCheck(this, intent)
        enableEdgeToEdge()

        handleIncomingIntent(intent, fromSavedState = savedInstanceState != null)

        setContent {
            val screenConfig by viewModel.screenConfig.collectAsStateWithLifecycle()
            val activeOverlay by viewModel.activeOverlay.collectAsStateWithLifecycle()
            val bannerMessage by viewModel.bannerMessage.collectAsStateWithLifecycle()
            val assistantLaunchEvent by viewModel.assistantLaunchEvent.collectAsStateWithLifecycle()
            val isVoiceOnlyUiActive by viewModel.isVoiceOnlyUiActive.collectAsStateWithLifecycle()
            val isVoiceSessionActive by viewModel.isVoiceSessionActive.collectAsStateWithLifecycle()
            val isListening by viewModel.voiceController.isListening.collectAsStateWithLifecycle()
            val isSpeaking by viewModel.voiceController.isSpeaking.collectAsStateWithLifecycle()
            val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
            val hudStatus by viewModel.hudStatus.collectAsStateWithLifecycle()
            val audioLevel by viewModel.voiceController.audioLevel.collectAsStateWithLifecycle()
            val speakingLevel by viewModel.voiceController.speakingLevel.collectAsStateWithLifecycle()
            val snackbarHostState = remember { SnackbarHostState() }

            val micPermissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { granted ->
                if (granted) {
                    viewModel.toggleVoiceInput()
                } else {
                    viewModel.showBanner("Microphone permission is needed for voice input.")
                }
            }

            val notificationPermLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { /* optional notification permission */ }

            LaunchedEffect(Unit) {
                if (!isVoiceOnlyUiActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val hasNotif = ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!hasNotif) {
                        notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }

            LaunchedEffect(bannerMessage) {
                val msg = bannerMessage ?: return@LaunchedEffect
                snackbarHostState.showSnackbar(msg)
                viewModel.consumeBanner()
            }

            val requestMicAndToggleVoice = {
                val hasMic = ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (hasMic) {
                    viewModel.toggleVoiceInput()
                } else {
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
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
                val hud = LocalJarvisColors.current
                Box(modifier = Modifier.fillMaxSize()) {
                    AssistantMainScreen(
                        viewModel = viewModel,
                        onRequestMicAndToggleVoice = requestMicAndToggleVoice
                    )

                        if (isVoiceSessionActive) {
                            Row(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .statusBarsPadding()
                                    .padding(top = 64.dp, end = 14.dp)
                                    .heightIn(min = 48.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(hud.surfacePanel.copy(alpha = 0.94f))
                                    .border(1.dp, hud.danger.copy(alpha = 0.45f), RoundedCornerShape(50))
                                    .testTag("main_stop_assistant_pill")
                                    .clickable { viewModel.stopAssistantSession() }
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Stop,
                                    contentDescription = stringResource(R.string.action_stop_assistant),
                                    tint = hud.danger,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = stringResource(R.string.action_stop_assistant),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = hud.textPrimary
                                )
                            }
                        }

                        AnimatedVisibility(
                            visible = activeOverlay != OverlayType.NONE,
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            when (activeOverlay) {
                                OverlayType.CHAT -> LaunchedEffect(Unit) {
                                    viewModel.closeOverlay()
                                }
                                OverlayType.MEMORY -> MemorySheet(
                                    viewModel = viewModel,
                                    onClose = { viewModel.closeOverlay() }
                                )
                                OverlayType.SKILLS -> SkillsSheet(
                                    viewModel = viewModel,
                                    onClose = { viewModel.closeOverlay() }
                                )
                                OverlayType.CAPABILITIES -> CapabilitiesSheet(
                                    viewModel = viewModel,
                                    onClose = { viewModel.closeOverlay() }
                                )
                                OverlayType.HANDS_FREE -> HandsFreeSheet(
                                    viewModel = viewModel,
                                    onClose = { viewModel.closeOverlay() }
                                )
                                OverlayType.CUSTOMIZE -> CustomizeSheet(
                                    viewModel = viewModel,
                                    onClose = { viewModel.closeOverlay() }
                                )
                                OverlayType.SETTINGS -> SettingsSheet(
                                    viewModel = viewModel,
                                    onClose = { viewModel.closeOverlay() }
                                )
                                OverlayType.REMOTE_PC -> RemotePcAndTvSheet(
                                    viewModel = viewModel,
                                    onClose = { viewModel.closeOverlay() }
                                )
                                OverlayType.REMOTE_DESKTOP -> RemoteDesktopScreen(
                                    viewModel = viewModel,
                                    onClose = { viewModel.closeOverlay() }
                                )
                                OverlayType.DEVICE_POWER -> DevicePowerSheet(
                                    viewModel = viewModel,
                                    onClose = { viewModel.closeOverlay() }
                                )
                                OverlayType.COMMAND_PALETTE -> CommandPaletteDialog(
                                    viewModel = viewModel,
                                    onClose = { viewModel.closeOverlay() }
                                )
                                OverlayType.TASKS_ACTIVITY -> TasksActivitySheet(
                                    viewModel = viewModel,
                                    onClose = { viewModel.closeOverlay() }
                                )
                                OverlayType.ROUTINES -> RoutinesSheet(
                                    viewModel = viewModel,
                                    onClose = { viewModel.closeOverlay() }
                                )
                                OverlayType.QUICK_START -> QuickStartSheet(
                                    viewModel = viewModel,
                                    onClose = { viewModel.closeOverlay() }
                                )
                                OverlayType.NONE -> Unit
                            }
                        }

                        assistantLaunchEvent
                            ?.takeIf { !it.entranceCompleted }
                            ?.let { event ->
                                AssistantLaunchEntranceOverlay(
                                    event = event,
                                    orbState = currentOrbState,
                                    audioLevel = audioLevel,
                                    speakingLevel = speakingLevel,
                                    onEntranceCompleted = { id -> viewModel.markAssistantEntranceCompleted(id) }
                                )
                            }

                    SnackbarHost(
                        hostState = snackbarHostState,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(bottom = 86.dp, start = 16.dp, end = 16.dp)
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
        handleIncomingIntent(intent, fromSavedState = false)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshServiceStatus()
        viewModel.refreshCalendarIfEnabled()
    }

    override fun onStop() {
        super.onStop()
        viewModel.onActivityStopped()
    }

    private fun handleIncomingIntent(intent: Intent?, fromSavedState: Boolean) {
        if (intent == null) return
        if (!fromSavedState && intent.action == Intent.ACTION_MAIN) {
            viewModel.exitVoiceOnlyUiForNormalHome()
        }
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            viewModel.exitVoiceOnlyUiForNormalHome()
            val shared = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!shared.isNullOrBlank()) {
                viewModel.handleIncomingSharedText(shared)
            }
        }
        val routineName = intent.getStringExtra(RoutineAlarms.EXTRA_ROUTINE_NAME)
        if (!routineName.isNullOrBlank()) {
            viewModel.exitVoiceOnlyUiForNormalHome()
            viewModel.handleRoutineTap(routineName)
        }
    }
}
