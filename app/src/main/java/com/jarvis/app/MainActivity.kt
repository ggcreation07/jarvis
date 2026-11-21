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
import com.jarvis.app.domain.model.OverlayType
import com.jarvis.app.platform.RoutineAlarms
import com.jarvis.app.ui.JarvisViewModel
import com.jarvis.app.ui.screens.HomeHudScreen
import com.jarvis.app.ui.sheets.CapabilitiesSheet
import com.jarvis.app.ui.sheets.ChatSheet
import com.jarvis.app.ui.sheets.CommandPaletteDialog
import com.jarvis.app.ui.sheets.CustomizeSheet
import com.jarvis.app.ui.sheets.DevicePowerSheet
import com.jarvis.app.ui.sheets.HandsFreeSheet
import com.jarvis.app.ui.sheets.MemorySheet
import com.jarvis.app.ui.sheets.QuickStartSheet
import com.jarvis.app.ui.sheets.RemoteDesktopScreen
import com.jarvis.app.ui.sheets.RemotePcAndTvSheet
import com.jarvis.app.ui.sheets.SettingsSheet
import com.jarvis.app.ui.sheets.SkillsSheet
import com.jarvis.app.ui.sheets.TasksActivitySheet
import com.jarvis.app.ui.theme.JarvisTheme

class MainActivity : ComponentActivity() {

    private val viewModel: JarvisViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureAppCheck(this, intent)
        enableEdgeToEdge()

        handleIncomingIntent(intent)

        setContent {
            val screenConfig by viewModel.screenConfig.collectAsStateWithLifecycle()
            val activeOverlay by viewModel.activeOverlay.collectAsStateWithLifecycle()
            val bannerMessage by viewModel.bannerMessage.collectAsStateWithLifecycle()
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
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

            JarvisTheme(screenConfig = screenConfig) {
                Box(modifier = Modifier.fillMaxSize()) {
                    HomeHudScreen(
                        viewModel = viewModel,
                        onRequestMicAndToggleVoice = requestMicAndToggleVoice
                    )

                    AnimatedVisibility(
                        visible = activeOverlay != OverlayType.NONE,
                        enter = fadeIn(),
                        exit = fadeOut()
                    ) {
                        when (activeOverlay) {
                            OverlayType.CHAT -> ChatSheet(
                                viewModel = viewModel,
                                onRequestMicAndToggleVoice = requestMicAndToggleVoice,
                                onClose = { viewModel.closeOverlay() }
                            )
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
                            OverlayType.QUICK_START -> QuickStartSheet(
                                viewModel = viewModel,
                                onClose = { viewModel.closeOverlay() }
                            )
                            OverlayType.NONE -> Unit
                        }
                    }

                    if (!screenConfig.quickStartCompleted && activeOverlay == OverlayType.NONE) {
                        QuickStartSheet(
                            viewModel = viewModel,
                            onClose = { viewModel.updateScreen { it.copy(quickStartCompleted = true) } }
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
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshServiceStatus()
        viewModel.refreshCalendarIfEnabled()
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val shared = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!shared.isNullOrBlank()) {
                viewModel.handleIncomingSharedText(shared)
            }
        }
        val routineName = intent.getStringExtra(RoutineAlarms.EXTRA_ROUTINE_NAME)
        if (!routineName.isNullOrBlank()) {
            viewModel.handleRoutineTap(routineName)
        }
    }
}
