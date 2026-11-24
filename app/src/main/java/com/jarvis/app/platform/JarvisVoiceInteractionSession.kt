package com.jarvis.app.platform

import android.content.Context
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.setViewTreeOnBackPressedDispatcherOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.jarvis.app.MainActivity
import com.jarvis.app.domain.model.HudStatus
import com.jarvis.app.ui.components.resolveOrbState
import com.jarvis.app.ui.screens.VoiceOnlyAssistantScreen
import com.jarvis.app.ui.theme.JarvisTheme

class JarvisVoiceInteractionSession(
    context: Context
) : VoiceInteractionSession(context),
    LifecycleOwner,
    SavedStateRegistryOwner,
    OnBackPressedDispatcherOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private var contentComposeView: ComposeView? = null
    private val runtime: JarvisAssistantRuntime by lazy {
        JarvisAssistantRuntime.getInstance(context.applicationContext)
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override val onBackPressedDispatcher: OnBackPressedDispatcher = OnBackPressedDispatcher {
        hideKeepSessionFromBack()
    }

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        moveLifecycleTo(Lifecycle.Event.ON_CREATE)
        attachWindowTreeOwners()
    }

    override fun onCreateContentView(): View {
        attachWindowTreeOwners()
        return ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@JarvisVoiceInteractionSession)
            setViewTreeSavedStateRegistryOwner(this@JarvisVoiceInteractionSession)
            setViewTreeOnBackPressedDispatcherOwner(this@JarvisVoiceInteractionSession)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val screenConfig by runtime.screenConfig.collectAsStateWithLifecycle()
                val isListening by runtime.voiceController.isListening.collectAsStateWithLifecycle()
                val isSpeaking by runtime.voiceController.isSpeaking.collectAsStateWithLifecycle()
                val isBusy by runtime.isBusy.collectAsStateWithLifecycle()
                val hudStatus by runtime.hudStatus.collectAsStateWithLifecycle()
                val audioLevel by runtime.voiceController.audioLevel.collectAsStateWithLifecycle()
                val speakingLevel by runtime.voiceController.speakingLevel.collectAsStateWithLifecycle()
                val launchEvent by runtime.assistantLaunchEvent.collectAsStateWithLifecycle()

                val orbState = remember(isListening, isSpeaking, isBusy, hudStatus) {
                    resolveOrbState(
                        isListening = isListening,
                        isSpeaking = isSpeaking,
                        isBusy = isBusy,
                        status = hudStatus
                    )
                }

                JarvisTheme(screenConfig = screenConfig) {
                    CompositionLocalProvider(
                        LocalOnBackPressedDispatcherOwner provides this@JarvisVoiceInteractionSession
                    ) {
                        VoiceOnlyAssistantScreen(
                            orbState = orbState,
                            audioLevel = audioLevel,
                            speakingLevel = speakingLevel,
                            launchEvent = launchEvent,
                            translucentOverlay = true,
                            onEntranceCompleted = { id -> runtime.markAssistantEntranceCompleted(id) },
                            onOrbClick = {
                                if (hudStatus == HudStatus.LISTENING || hudStatus == HudStatus.SPEAKING || isBusy) {
                                    runtime.stopCurrentWork()
                                } else if (!runtime.hasRecordAudioPermission()) {
                                    launchPermissionHostActivity()
                                } else {
                                    runtime.startAssistantListeningIfIdle(startForegroundServiceIfPermitted = true)
                                }
                            },
                            onHideKeepSession = {
                                runtime.hideVoiceOnlyUiKeepSession()
                                hide()
                            },
                            onStopAssistant = {
                                runtime.stopAssistant()
                            }
                        )
                    }
                }
            }
        }.also { contentComposeView = it }
    }

    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        runCatching {
            window?.window?.let { win ->
                win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                win.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
                win.addFlags(
                    WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                )
            }
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        attachWindowTreeOwners()
        moveLifecycleTo(Lifecycle.Event.ON_START)
        moveLifecycleTo(Lifecycle.Event.ON_RESUME)

        runtime.registerVoiceInteractionSession(
            onHide = { runCatching { hide() } },
            onFinish = { runCatching { finish() } }
        )

        // Always activate voice-only UI and trigger entrance animation so the assistant UI is visible
        if (runtime.activateVoiceOnlyUi(AssistantLaunchIntentHelper.ACTION_VOICE_INTERACTION_SESSION)) {
            runtime.assistantLaunchEvent.value?.let { ev ->
                runtime.markAssistantVoiceTriggered(ev.launchId)
            }
        }

        if (runtime.hasRecordAudioPermission()) {
            runtime.startAssistantListeningIfIdle(startForegroundServiceIfPermitted = true)
        } else {
            runtime.showBanner("Microphone permission needed. Tap the orb to grant.")
        }
    }

    override fun onHide() {
        super.onHide()
        runtime.hideVoiceOnlyUiKeepSession()
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        }
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
    }

    override fun onBackPressed() {
        // Route system Back through OnBackPressedDispatcher so BackHandler runs when active,
        // falling back to hiding the overlay without cancelling background voice listening/TTS/session.
        onBackPressedDispatcher.onBackPressed()
    }

    override fun onDestroy() {
        runtime.unregisterVoiceInteractionSession()
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        if (lifecycleRegistry.currentState != Lifecycle.State.DESTROYED &&
            lifecycleRegistry.currentState != Lifecycle.State.INITIALIZED
        ) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
        runCatching { contentComposeView?.disposeComposition() }
        contentComposeView = null
        super.onDestroy()
    }

    private fun hideKeepSessionFromBack() {
        runtime.hideVoiceOnlyUiKeepSession()
        runCatching { hide() }
    }

    private fun launchPermissionHostActivity() {
        runtime.activateVoiceOnlyUi(AssistantLaunchIntentHelper.ACTION_VOICE_INTERACTION_SESSION)
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_ASSIST
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching { context.startActivity(intent) }
        runCatching { hide() }
    }

    private fun attachWindowTreeOwners() {
        runCatching {
            val decor = window?.window?.decorView ?: return
            decor.setViewTreeLifecycleOwner(this)
            decor.setViewTreeSavedStateRegistryOwner(this)
            decor.setViewTreeOnBackPressedDispatcherOwner(this)
        }
    }

    private fun moveLifecycleTo(event: Lifecycle.Event) {
        runCatching {
            when (event) {
                Lifecycle.Event.ON_CREATE -> if (lifecycleRegistry.currentState == Lifecycle.State.INITIALIZED) {
                    lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
                }
                Lifecycle.Event.ON_START -> {
                    if (lifecycleRegistry.currentState == Lifecycle.State.INITIALIZED) {
                        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
                    }
                    if (!lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                    }
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (!lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
                    }
                    if (!lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
                    }
                }
                else -> lifecycleRegistry.handleLifecycleEvent(event)
            }
        }
    }
}
