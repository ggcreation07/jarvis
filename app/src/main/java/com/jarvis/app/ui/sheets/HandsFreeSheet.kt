package com.jarvis.app.ui.sheets

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Matrix
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.app.R
import com.jarvis.app.domain.model.HandPose
import com.jarvis.app.domain.model.HandsFreeActivity
import com.jarvis.app.platform.GestureLatch
import com.jarvis.app.platform.MediaPipeHandDetector
import com.jarvis.app.ui.JarvisViewModel
import com.jarvis.app.ui.components.hudTextFieldColors
import com.jarvis.app.ui.theme.LocalJarvisColors
import kotlinx.coroutines.delay
import java.util.concurrent.Executors

@Composable
fun HandsFreeSheet(
    viewModel: JarvisViewModel,
    onClose: () -> Unit
) {
    val hud = LocalJarvisColors.current
    val context = LocalContext.current
    val activeActivity by viewModel.handsFreeActivity.collectAsStateWithLifecycle()
    val readingText by viewModel.readingText.collectAsStateWithLifecycle()
    val readingPage by viewModel.readingPage.collectAsStateWithLifecycle()
    val currentStep by viewModel.currentStepIndex.collectAsStateWithLifecycle()
    val cameraActive by viewModel.cameraGestureActive.collectAsStateWithLifecycle()
    val practiceMode by viewModel.gesturePracticeMode.collectAsStateWithLifecycle()
    val detectedPose by viewModel.lastDetectedPose.collectAsStateWithLifecycle()

    var hasCameraPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val camPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPerm = granted
        if (granted) viewModel.setCameraGestureActive(true)
        else viewModel.showBanner("Camera permission is needed for hand gesture detection.")
    }

    var stepsEditorText by remember {
        mutableStateOf("1. Boil water with a pinch of salt\n2. Cook pasta for 8 minutes\n3. Toss with sauce and serve")
    }
    var timerSecRemaining by remember { mutableIntStateOf(0) }
    var timerRunning by remember { mutableStateOf(false) }

    LaunchedEffect(timerRunning, timerSecRemaining) {
        if (timerRunning && timerSecRemaining > 0) {
            delay(1000L)
            timerSecRemaining -= 1
            if (timerSecRemaining == 0) {
                timerRunning = false
                viewModel.voiceController.speak("Timer finished!")
            }
        }
    }

    BackHandler {
        viewModel.setCameraGestureActive(false)
        onClose()
    }

    val pages = remember(readingText) {
        val words = readingText.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) listOf("")
        else words.chunked(55).map { it.joinToString(" ") }
    }
    val clampedPage = readingPage.coerceIn(0, (pages.size - 1).coerceAtLeast(0))

    val stepsList = remember(stepsEditorText) {
        stepsEditorText.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }
    val clampedStep = currentStep.coerceIn(0, (stepsList.size - 1).coerceAtLeast(0))

    Surface(color = hud.background.copy(alpha = 0.98f), modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().widthIn(max = 680.dp),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Hands-Free & Gestures", style = MaterialTheme.typography.titleLarge, color = hud.textPrimary, modifier = Modifier.weight(1f))
                        IconButton(
                            onClick = {
                                viewModel.setCameraGestureActive(false)
                                onClose()
                            },
                            modifier = Modifier.testTag("handsfree_close_button")
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.cd_close_sheet), tint = hud.textPrimary)
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HandsFreeActivity.entries.forEach { act ->
                            FilterChip(
                                selected = activeActivity == act,
                                onClick = { viewModel.selectHandsFreeActivity(act) },
                                label = { Text(act.title) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = hud.accent.copy(alpha = 0.22f),
                                    selectedLabelColor = hud.accent
                                ),
                                modifier = Modifier.testTag("activity_chip_${act.id}")
                            )
                        }
                    }
                }

                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(hud.surfaceCard)
                            .border(1.dp, hud.borderSubtle, RoundedCornerShape(16.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Hand Gesture Camera", style = MaterialTheme.typography.titleMedium, color = hud.textPrimary)
                                Text(
                                    if (cameraActive) "Detected: ${detectedPose.displayName} (${detectedPose.hint})"
                                    else activeActivity.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (detectedPose != HandPose.UNKNOWN) hud.accent else hud.textSecondary
                                )
                            }
                            Button(
                                onClick = {
                                    if (cameraActive) {
                                        viewModel.setCameraGestureActive(false)
                                    } else if (hasCameraPerm) {
                                        viewModel.setCameraGestureActive(true)
                                    } else {
                                        camPermLauncher.launch(Manifest.permission.CAMERA)
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (cameraActive) hud.danger else hud.accent,
                                    contentColor = hud.background
                                ),
                                modifier = Modifier.testTag("handsfree_camera_toggle")
                            ) {
                                Icon(if (cameraActive) Icons.Rounded.VideocamOff else Icons.Rounded.CameraAlt, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(if (cameraActive) "Stop" else "Start")
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Practice / test mode (no actions triggered)", style = MaterialTheme.typography.bodySmall, color = hud.textSecondary, modifier = Modifier.weight(1f))
                            Switch(checked = practiceMode, onCheckedChange = { viewModel.setGesturePracticeMode(it) })
                        }

                        if (cameraActive && hasCameraPerm) {
                            GestureCameraPreview(
                                onPoseDetected = { pose -> viewModel.onGestureDetected(pose) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(210.dp)
                                    .clip(RoundedCornerShape(12.dp))
                            )
                        }
                    }
                }

                when (activeActivity) {
                    HandsFreeActivity.READING -> {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(hud.surfaceCard)
                                    .border(1.dp, hud.borderSubtle, RoundedCornerShape(16.dp))
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text("Page ${clampedPage + 1} of ${pages.size}", style = MaterialTheme.typography.labelMedium, color = hud.accent)
                                Text(pages[clampedPage], style = MaterialTheme.typography.bodyLarge, color = hud.textPrimary)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { viewModel.setReadingPage((clampedPage - 1).coerceAtLeast(0)) }) { Text("Previous") }
                                    Button(
                                        onClick = { viewModel.setReadingPage((clampedPage + 1).coerceAtMost(pages.lastIndex)) },
                                        colors = ButtonDefaults.buttonColors(containerColor = hud.accent, contentColor = hud.background)
                                    ) { Text("Next page") }
                                    OutlinedButton(onClick = { viewModel.voiceController.speak(pages[clampedPage]) }) {
                                        Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Read aloud")
                                    }
                                }
                                OutlinedTextField(
                                    value = readingText,
                                    onValueChange = { viewModel.updateReadingText(it) },
                                    label = { Text("Paste article or notes to read") },
                                    maxLines = 5,
                                    colors = hudTextFieldColors(),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    HandsFreeActivity.STEPS -> {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(hud.surfaceCard)
                                    .border(1.dp, hud.borderSubtle, RoundedCornerShape(16.dp))
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text("Step ${clampedStep + 1} of ${stepsList.size.coerceAtLeast(1)}", style = MaterialTheme.typography.labelMedium, color = hud.accent)
                                Text(stepsList.getOrElse(clampedStep) { "Add steps below" }, style = MaterialTheme.typography.titleLarge, color = hud.textPrimary)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { viewModel.setCurrentStep((clampedStep - 1).coerceAtLeast(0)) }) { Text("Prev step") }
                                    Button(
                                        onClick = { viewModel.setCurrentStep((clampedStep + 1).coerceAtMost((stepsList.lastIndex).coerceAtLeast(0))) },
                                        colors = ButtonDefaults.buttonColors(containerColor = hud.accent, contentColor = hud.background)
                                    ) { Text("Next step") }
                                    OutlinedButton(onClick = {
                                        if (timerRunning) timerRunning = false
                                        else {
                                            timerSecRemaining = 180
                                            timerRunning = true
                                        }
                                    }) {
                                        Icon(Icons.Rounded.Timer, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(if (timerRunning) "${timerSecRemaining}s" else "3m timer")
                                    }
                                }
                                OutlinedTextField(
                                    value = stepsEditorText,
                                    onValueChange = { stepsEditorText = it },
                                    label = { Text("Checklist / Recipe steps (one per line)") },
                                    maxLines = 6,
                                    colors = hudTextFieldColors(),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }

                    HandsFreeActivity.MEDIA -> {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(hud.surfaceCard)
                                    .border(1.dp, hud.borderSubtle, RoundedCornerShape(16.dp))
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(onClick = { viewModel.deviceController.sendMediaKey("previous") }) {
                                    Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous track", tint = hud.textPrimary, modifier = Modifier.size(32.dp))
                                }
                                Button(
                                    onClick = { viewModel.deviceController.sendMediaKey("play_pause") },
                                    colors = ButtonDefaults.buttonColors(containerColor = hud.accent, contentColor = hud.background)
                                ) {
                                    Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                                    Icon(Icons.Rounded.Pause, contentDescription = null)
                                }
                                IconButton(onClick = { viewModel.deviceController.sendMediaKey("next") }) {
                                    Icon(Icons.Rounded.SkipNext, contentDescription = "Next track", tint = hud.textPrimary, modifier = Modifier.size(32.dp))
                                }
                            }
                        }
                    }

                    else -> {
                        item {
                            Text(
                                "Supported hand poses: Open Palm (Play/Next), Peace (Capture/Next), Thumbs Up (Confirm), Thumbs Down (Previous), Closed Fist (Stop).",
                                style = MaterialTheme.typography.bodyMedium,
                                color = hud.textSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GestureCameraPreview(
    onPoseDetected: (HandPose) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val detector = remember { MediaPipeHandDetector(context) }
    val latch = remember { GestureLatch() }
    val executor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            detector.close()
            executor.shutdown()
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val future = ProcessCameraProvider.getInstance(ctx)
                future.addListener({
                    try {
                        val provider = future.get()
                        val preview = Preview.Builder().build().apply {
                            surfaceProvider = previewView.surfaceProvider
                        }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(executor) { imageProxy ->
                            try {
                                val bmp = imageProxy.toBitmap()
                                val rot = imageProxy.imageInfo.rotationDegrees
                                val oriented = if (rot != 0) {
                                    val m = Matrix().apply { postRotate(rot.toFloat()) }
                                    android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                                } else bmp
                                val (pose, _) = detector.detect(oriented)
                                val latched = latch.update(pose, System.currentTimeMillis())
                                if (latched != null) {
                                    onPoseDetected(latched)
                                }
                            } catch (_: Exception) {
                            } finally {
                                imageProxy.close()
                            }
                        }
                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_FRONT_CAMERA,
                            preview,
                            analysis
                        )
                    } catch (_: Exception) {
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}
