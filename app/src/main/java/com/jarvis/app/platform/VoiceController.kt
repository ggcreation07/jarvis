package com.jarvis.app.platform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.media.audiofx.Visualizer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarvis.app.domain.brain.LanguageDetectionManager
import com.jarvis.app.domain.brain.UserLanguage
import com.jarvis.app.domain.model.BrainConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

internal class BargeInSpeechDetector(
    private val hasHardwareAec: Boolean,
    private val warmupMs: Long = if (hasHardwareAec) 300L else 380L,
    private val requiredSpeechMs: Long = if (hasHardwareAec) 180L else 260L,
    private val frameDurationMs: Long = 20L
) {
    private var elapsedMs: Long = 0L
    private var accumulatedSpeechMs: Long = 0L
    private var silenceHangoverMs: Long = 0L
    private var noiseFloor: Float = if (hasHardwareAec) 0.012f else 0.025f
    private var triggered: Boolean = false

    fun reset() {
        elapsedMs = 0L
        accumulatedSpeechMs = 0L
        silenceHangoverMs = 0L
        noiseFloor = if (hasHardwareAec) 0.012f else 0.025f
        triggered = false
    }

    fun processFrame(rmsNormalized: Float, ttsSpeakingLevel: Float): Boolean {
        if (triggered) return false
        val clampedRms = rmsNormalized.coerceIn(0f, 1f)
        val clampedTts = ttsSpeakingLevel.coerceIn(0f, 1f)
        elapsedMs += frameDurationMs

        val minBase = if (hasHardwareAec) 0.038f else 0.072f
        val floorMultiplier = if (hasHardwareAec) 2.2f else 2.2f
        val echoMargin = if (hasHardwareAec) clampedTts * 0.018f else clampedTts * 0.055f
        val threshold = maxOf(minBase, noiseFloor * floorMultiplier + echoMargin)

        if (elapsedMs <= warmupMs) {
            noiseFloor = (noiseFloor * 0.80f + clampedRms * 0.20f).coerceIn(0.005f, 0.09f)
            return false
        }

        if (clampedRms >= threshold) {
            accumulatedSpeechMs += frameDurationMs
            silenceHangoverMs = 0L
            if (accumulatedSpeechMs >= requiredSpeechMs) {
                triggered = true
                return true
            }
        } else {
            noiseFloor = (noiseFloor * 0.94f + clampedRms * 0.06f).coerceIn(0.005f, 0.09f)
            silenceHangoverMs += frameDurationMs
            if (silenceHangoverMs > 60L) {
                accumulatedSpeechMs = 0L
            }
        }
        return false
    }
}

internal fun computePcm16RmsNormalized(buffer: ShortArray, readCount: Int): Float {
    if (readCount <= 0) return 0f
    var sumSq = 0.0
    val count = readCount.coerceAtMost(buffer.size)
    for (i in 0 until count) {
        val sample = buffer[i] / 32768.0
        sumSq += sample * sample
    }
    return sqrt(sumSq / count).toFloat().coerceIn(0f, 1f)
}

enum class VoiceControllerState {
    IDLE,
    LISTENING,
    PROCESSING,
    SPEAKING,
    STOPPED
}

enum class RecognitionFailureKind {
    SILENCE_OR_EMPTY,
    RECOGNIZER_BUSY,
    TEMPORARY,
    PERMISSION_DENIED,
    UNAVAILABLE
}

data class RecognitionFailure(
    val kind: RecognitionFailureKind,
    val errorCode: Int?,
    val message: String,
    val sessionDurationMs: Long
)

class VoiceController(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private data class RecognitionSession(
        val sessionId: Long,
        val startedAtMs: Long,
        val onFinalResult: (String) -> Unit,
        val onFailure: (String) -> Unit,
        val onRecognitionFailure: ((RecognitionFailure) -> Unit)?,
        var hasReadyForSpeech: Boolean = false,
        var hasBegunSpeech: Boolean = false,
        var hasEndedSpeech: Boolean = false,
        var stopRequested: Boolean = false,
        var terminated: Boolean = false
    )

    private var speechRecognizer: SpeechRecognizer? = null
    private var listenerAttachedToRecognizer: Boolean = false
    private var activeSession: RecognitionSession? = null
    private var pendingSessionStart: Boolean = false
    private var nextSessionId: Long = 0L

    private var listenAttemptCountForTest: Int = 0
    private var recognizerCreationCountForTest: Int = 0
    private var recognizerCancelCountForTest: Int = 0
    private val transitionLogs = mutableListOf<String>()

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsAudioSessionId: Int = AudioManager.AUDIO_SESSION_ID_GENERATE
    private var ttsAudioFocusRequest: AudioFocusRequest? = null

    @Volatile
    private var activeUtteranceId: String? = null

    @Volatile
    private var activeUtteranceGroupPrefix: String? = null

    @Volatile
    private var lastStartedUtteranceId: String? = null

    @Volatile
    private var utteranceInterruptedOrStopped: Boolean = false

    @Volatile
    private var pendingBargeInCallback: (() -> Unit)? = null

    private val bargeInLock = Any()
    private var bargeInMonitorJob: Job? = null
    private var activeAudioRecord: AudioRecord? = null

    private var visualizer: Visualizer? = null
    private var fallbackMeterJob: Job? = null

    @Volatile
    private var hasReceivedRealVisualizerEnergy = false

    @Volatile
    private var fallbackTargetLevel = 0.25f

    private val _voiceState = MutableStateFlow(VoiceControllerState.IDLE)
    val voiceState: StateFlow<VoiceControllerState> = _voiceState.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _ttsNaturalCompletions = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val ttsNaturalCompletions: SharedFlow<String> = _ttsNaturalCompletions.asSharedFlow()

    private val _ttsSpeechFailures = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val ttsSpeechFailures: SharedFlow<String> = _ttsSpeechFailures.asSharedFlow()

    private val _partialTranscript = MutableStateFlow("")
    val partialTranscript: StateFlow<String> = _partialTranscript.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private val _speakingLevel = MutableStateFlow(0f)
    val speakingLevel: StateFlow<Float> = _speakingLevel.asStateFlow()

    private val _isRealSpeakingMeteringActive = MutableStateFlow(false)
    val isRealSpeakingMeteringActive: StateFlow<Boolean> = _isRealSpeakingMeteringActive.asStateFlow()

    private val _voiceError = MutableStateFlow<String?>(null)
    val voiceError: StateFlow<String?> = _voiceError.asStateFlow()

    val voiceSelectionManager = VoiceSelectionManager()
    val languageDetectionManager = LanguageDetectionManager()

    private inline fun runOnMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post { block() }
        }
    }

    private fun logEvent(message: String) {
        synchronized(transitionLogs) {
            if (transitionLogs.size >= MAX_TRANSITION_LOGS) {
                transitionLogs.removeAt(0)
            }
            transitionLogs.add(message)
        }
        runCatching { Log.d(TAG, message) }
    }

    private fun transitionTo(newState: VoiceControllerState, reason: String) {
        val prev = _voiceState.value
        if (prev != newState) {
            _voiceState.value = newState
            _isListening.value = (newState == VoiceControllerState.LISTENING)
            _isSpeaking.value = (newState == VoiceControllerState.SPEAKING)
            logEvent("STATE $prev -> $newState (reason=$reason)")
        } else {
            _isListening.value = (newState == VoiceControllerState.LISTENING)
            _isSpeaking.value = (newState == VoiceControllerState.SPEAKING)
            logEvent("STATE $prev (kept, reason=$reason)")
        }
    }

    fun getAvailableMaleVoices(): List<AvailableVoiceInfo> =
        voiceSelectionManager.getAvailableMaleVoices(tts)

    fun setVoicesForTest(voices: Set<Voice>) {
        voiceSelectionManager.voiceProviderOverride = { voices }
    }

    private val ttsAudioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                scope.launch(Dispatchers.Main.immediate) {
                    if (_isSpeaking.value || activeUtteranceId != null) {
                        stopSpeaking()
                    }
                }
            }
        }
    }

    init {
        initAudioSession()
        initTts()
    }

    private fun initAudioSession() {
        ttsAudioSessionId = runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val id = am?.generateAudioSessionId() ?: AudioManager.ERROR
            if (id != AudioManager.ERROR && id > 0) id else AudioManager.AUDIO_SESSION_ID_GENERATE
        }.getOrDefault(AudioManager.AUDIO_SESSION_ID_GENERATE)
    }

    private fun isMatchingActiveUtterance(utteranceId: String?): Boolean {
        val current = activeUtteranceId ?: return false
        if (utteranceId == null || utteranceId == current) return true
        val prefix = activeUtteranceGroupPrefix
        return prefix != null && utteranceId.startsWith("${prefix}_")
    }

    private fun isFinalActiveUtterance(utteranceId: String?): Boolean {
        val current = activeUtteranceId ?: return false
        return utteranceId == null || utteranceId == current
    }

    private fun handleUtteranceError(utteranceId: String?) {
        val current = activeUtteranceId
        if (current == null || !isMatchingActiveUtterance(utteranceId)) {
            return
        }
        val wasInterrupted = utteranceInterruptedOrStopped
        activeUtteranceId = null
        activeUtteranceGroupPrefix = null
        pendingBargeInCallback = null
        finishSpeakingState(reason = "tts_error(utt=$current, interrupted=$wasInterrupted)")
        if (!wasInterrupted) {
            _voiceError.value = "Voice playback error."
            _ttsSpeechFailures.tryEmit(current)
        }
    }

    private fun initTts() {
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    runCatching {
                        tts?.language = Locale.getDefault()
                        tts?.setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                    }
                    ttsReady = true
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            runOnMain {
                                val current = activeUtteranceId
                                if (current == null || !isMatchingActiveUtterance(utteranceId) || utteranceInterruptedOrStopped) {
                                    logEvent("TTS_IGNORED onStart(utt=$utteranceId, active=$current)")
                                    return@runOnMain
                                }
                                val wasAlreadyMetering = (_voiceState.value == VoiceControllerState.SPEAKING && fallbackMeterJob != null)
                                transitionTo(VoiceControllerState.SPEAKING, "tts_onStart(utt=$utteranceId)")
                                if (!wasAlreadyMetering) {
                                    startSpeakingMetering()
                                    val bargeInCallback = pendingBargeInCallback
                                    if (bargeInCallback != null) {
                                        startBargeInMonitor(current, bargeInCallback)
                                    }
                                }
                            }
                        }

                        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                            runOnMain {
                                if (activeUtteranceId == null || !isMatchingActiveUtterance(utteranceId) || utteranceInterruptedOrStopped) {
                                    return@runOnMain
                                }
                                if (!hasReceivedRealVisualizerEnergy) {
                                    val span = (end - start).coerceIn(1, 16)
                                    fallbackTargetLevel = (0.34f + (span / 16f) * 0.44f).coerceIn(0.32f, 0.82f)
                                }
                            }
                        }

                        override fun onDone(utteranceId: String?) {
                            runOnMain {
                                val current = activeUtteranceId
                                if (current == null || !isFinalActiveUtterance(utteranceId)) {
                                    logEvent("TTS_IGNORED onDone(utt=$utteranceId, active=$current)")
                                    return@runOnMain
                                }
                                val wasInterrupted = utteranceInterruptedOrStopped
                                activeUtteranceId = null
                                activeUtteranceGroupPrefix = null
                                pendingBargeInCallback = null
                                finishSpeakingState(reason = "tts_onDone(utt=$current, interrupted=$wasInterrupted)")
                                if (!wasInterrupted) {
                                    _ttsNaturalCompletions.tryEmit(current)
                                }
                            }
                        }

                        override fun onStop(utteranceId: String?, interrupted: Boolean) {
                            runOnMain {
                                val current = activeUtteranceId
                                if (current == null || !isMatchingActiveUtterance(utteranceId)) {
                                    logEvent("TTS_IGNORED onStop(utt=$utteranceId, active=$current)")
                                    return@runOnMain
                                }
                                activeUtteranceId = null
                                activeUtteranceGroupPrefix = null
                                pendingBargeInCallback = null
                                finishSpeakingState(reason = "tts_onStop(utt=$utteranceId, interrupted=$interrupted)")
                            }
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            runOnMain { handleUtteranceError(utteranceId) }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            runOnMain { handleUtteranceError(utteranceId) }
                        }
                    })
                } else {
                    ttsReady = false
                    _voiceError.value = "Text-to-speech engine failed to initialize."
                    runCatching { tts?.shutdown() }
                    tts = null
                }
            }
        } catch (_: Exception) {
            ttsReady = false
            tts = null
            _voiceError.value = "Text-to-speech engine unavailable."
        }
    }

    private fun startSpeakingMetering() {
        stopSpeakingMeteringOnly()
        hasReceivedRealVisualizerEnergy = false
        _isRealSpeakingMeteringActive.value = false
        fallbackTargetLevel = 0.42f

        val hasRecordPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasRecordPerm && ttsAudioSessionId > 0) {
            val attached = runCatching {
                val range = Visualizer.getCaptureSizeRange()
                val captureSize = range.getOrElse(1) { 256 }.coerceIn(128, 512)
                val maxRate = Visualizer.getMaxCaptureRate().coerceAtLeast(2000)
                Visualizer(ttsAudioSessionId).apply {
                    enabled = false
                    this.captureSize = captureSize
                    setDataCaptureListener(
                        object : Visualizer.OnDataCaptureListener {
                            override fun onWaveFormDataCapture(
                                vis: Visualizer?,
                                waveform: ByteArray?,
                                samplingRate: Int
                            ) {
                                if (!_isSpeaking.value || waveform == null || waveform.isEmpty()) return
                                var sumSq = 0.0
                                for (byteVal in waveform) {
                                    val centered = ((byteVal.toInt() and 0xFF) - 128) / 128f
                                    sumSq += centered * centered
                                }
                                val rms = sqrt(sumSq / waveform.size).toFloat()
                                val normalized = (rms * 3.4f).coerceIn(0f, 1f)
                                if (normalized > 0.015f) {
                                    hasReceivedRealVisualizerEnergy = true
                                    _isRealSpeakingMeteringActive.value = true
                                    _speakingLevel.value = normalized
                                } else if (hasReceivedRealVisualizerEnergy) {
                                    _speakingLevel.value = (_speakingLevel.value * 0.65f).coerceAtLeast(0.04f)
                                }
                            }

                            override fun onFftDataCapture(
                                vis: Visualizer?,
                                fft: ByteArray?,
                                samplingRate: Int
                            ) = Unit
                        },
                        (maxRate / 2).coerceAtLeast(1000),
                        true,
                        false
                    )
                    enabled = true
                }
            }.getOrNull()
            visualizer = attached
        }

        // Smooth UtteranceProgressListener speech-state fallback when real Visualizer metering
        // is unsupported, unpermitted, or emits silent frames for the TTS audio session.
        fallbackMeterJob = scope.launch {
            var phase = 0f
            var current = 0.24f
            while (isActive && _isSpeaking.value) {
                if (!hasReceivedRealVisualizerEnergy) {
                    phase = (phase + 0.38f) % (2f * PI.toFloat())
                    val cadence = 0.5f + 0.5f * sin(phase)
                    val target = (0.16f + fallbackTargetLevel * (0.45f + 0.55f * cadence)).coerceIn(0.14f, 0.82f)
                    current += (target - current) * 0.35f
                    fallbackTargetLevel = (fallbackTargetLevel * 0.88f).coerceAtLeast(0.28f)
                    if (!hasReceivedRealVisualizerEnergy) {
                        _speakingLevel.value = abs(current)
                    }
                }
                delay(45L)
            }
        }
    }

    private fun stopSpeakingMeteringOnly() {
        fallbackMeterJob?.cancel()
        fallbackMeterJob = null
        runCatching {
            visualizer?.setDataCaptureListener(null, 0, false, false)
            visualizer?.enabled = false
            visualizer?.release()
        }
        visualizer = null
        hasReceivedRealVisualizerEnergy = false
        _isRealSpeakingMeteringActive.value = false
    }

    private fun finishSpeakingState(reason: String = "tts_finished") {
        _speakingLevel.value = 0f
        abandonTtsAudioFocus()
        stopSpeakingMeteringOnly()
        if (_voiceState.value == VoiceControllerState.SPEAKING) {
            transitionTo(VoiceControllerState.IDLE, reason)
        }
    }

    private fun requestTtsAudioFocus() {
        runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = ttsAudioFocusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener(ttsAudioFocusListener)
                    .build()
                    .also { ttsAudioFocusRequest = it }
                am.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    ttsAudioFocusListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        }
    }

    private fun abandonTtsAudioFocus() {
        runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ttsAudioFocusRequest?.let { req ->
                    am.abandonAudioFocusRequest(req)
                }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(ttsAudioFocusListener)
            }
        }
    }

    private fun startBargeInMonitor(utteranceId: String, onBargeInDetected: () -> Unit) {
        if (activeUtteranceId != utteranceId ||
            utteranceInterruptedOrStopped ||
            isListeningOrPending() ||
            !hasRecordAudioPermission()
        ) {
            return
        }

        synchronized(bargeInLock) {
            bargeInMonitorJob?.cancel()
            runCatching { activeAudioRecord?.stop() }
            bargeInMonitorJob = scope.launch(Dispatchers.IO) {
                val sampleRate = 16000
                val minBuf = runCatching {
                    AudioRecord.getMinBufferSize(
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    )
                }.getOrDefault(AudioRecord.ERROR)
                if (minBuf <= 0) return@launch

                val frameSamples = 320 // 20ms @ 16kHz
                val bufferBytes = maxOf(minBuf * 2, frameSamples * 4 * 2)
                val record = runCatching {
                    AudioRecord.Builder()
                        .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(sampleRate)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .build()
                        )
                        .setBufferSizeInBytes(bufferBytes)
                        .build()
                }.getOrNull() ?: return@launch

                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    runCatching { record.release() }
                    return@launch
                }

                val aec = if (runCatching { AcousticEchoCanceler.isAvailable() }.getOrDefault(false)) {
                    runCatching {
                        AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
                    }.getOrNull()
                } else {
                    null
                }
                val ns = if (runCatching { NoiseSuppressor.isAvailable() }.getOrDefault(false)) {
                    runCatching {
                        NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true }
                    }.getOrNull()
                } else {
                    null
                }

                val hasHardwareAec = aec?.enabled == true
                val detector = BargeInSpeechDetector(hasHardwareAec = hasHardwareAec)
                val pcmBuffer = ShortArray(frameSamples)
                var detected = false

                synchronized(bargeInLock) {
                    activeAudioRecord = record
                }

                try {
                    record.startRecording()
                    while (isActive && activeUtteranceId == utteranceId && !utteranceInterruptedOrStopped) {
                        val read = record.read(pcmBuffer, 0, pcmBuffer.size)
                        if (read > 0) {
                            val rms = computePcm16RmsNormalized(pcmBuffer, read)
                            if (detector.processFrame(rms, _speakingLevel.value)) {
                                detected = true
                                break
                            }
                        } else if (read < 0) {
                            break
                        }
                    }
                } catch (_: Exception) {
                    // Gracefully exit if AudioRecord fails or is interrupted during teardown.
                } finally {
                    synchronized(bargeInLock) {
                        if (activeAudioRecord === record) {
                            activeAudioRecord = null
                        }
                    }
                    runCatching { record.stop() }
                    runCatching { aec?.release() }
                    runCatching { ns?.release() }
                    runCatching { record.release() }
                }

                if (detected && activeUtteranceId == utteranceId && !utteranceInterruptedOrStopped) {
                    scope.launch(Dispatchers.Main.immediate) {
                        handleBargeInConfirmed(utteranceId, onBargeInDetected)
                    }
                }
            }
        }
    }

    private fun stopBargeInMonitor(): Job? {
        return synchronized(bargeInLock) {
            val job = bargeInMonitorJob
            bargeInMonitorJob = null
            job?.cancel()
            runCatching { activeAudioRecord?.stop() }
            job
        }
    }

    private fun handleBargeInConfirmed(utteranceId: String, onBargeInDetected: () -> Unit) {
        if (activeUtteranceId != utteranceId || utteranceInterruptedOrStopped) return
        stopSpeaking(interruptedByBargeIn = true)
        onBargeInDetected()
    }

    fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    fun isListeningOrPending(): Boolean =
        _voiceState.value == VoiceControllerState.LISTENING ||
            _voiceState.value == VoiceControllerState.PROCESSING ||
            pendingSessionStart ||
            (activeSession != null && activeSession?.terminated == false && activeSession?.stopRequested == false)

    fun isTtsReady(): Boolean = ttsReady

    fun isSpeechRecognitionAvailable(): Boolean =
        SpeechRecognizer.isRecognitionAvailable(context)

    fun isOfflineSpeechRecognitionConfirmed(): Boolean {
        if (!isSpeechRecognitionAvailable()) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
        } else {
            false
        }
    }

    fun resumeFromStopped(reason: String = "explicit_resume") {
        runOnMain {
            if (_voiceState.value == VoiceControllerState.STOPPED) {
                transitionTo(VoiceControllerState.IDLE, reason)
            }
        }
    }

    fun markProcessingFinished(reason: String = "processing_finished") {
        runOnMain {
            if (_voiceState.value == VoiceControllerState.PROCESSING && activeSession == null && !pendingSessionStart) {
                transitionTo(VoiceControllerState.IDLE, reason)
            }
        }
    }

    fun stopSession(reason: String = "explicit_stop") {
        runOnMain {
            logEvent("STOP_SESSION reason=$reason state=${_voiceState.value}")
            pendingSessionStart = false
            val session = activeSession
            if (session != null && !session.terminated) {
                session.terminated = true
                activeSession = null
                cancelRecognizerIntentionally(reason = "stopSession:$reason")
            } else {
                activeSession = null
            }
            _audioLevel.value = 0f
            _partialTranscript.value = ""
            stopSpeakingInternal(reason = "stopSession:$reason", interruptedByBargeIn = true, targetState = VoiceControllerState.STOPPED)
            transitionTo(VoiceControllerState.STOPPED, reason)
        }
    }

    fun startListening(
        onFinalResult: (String) -> Unit,
        onFailure: (String) -> Unit,
        onRecognitionFailure: ((RecognitionFailure) -> Unit)? = null,
        isAutoRestart: Boolean = false
    ) {
        runOnMain {
            if (isAutoRestart && _voiceState.value == VoiceControllerState.STOPPED) {
                logEvent("START_LISTENING_BLOCKED reason=session_stopped_no_auto_restart")
                return@runOnMain
            }

            if (!hasRecordAudioPermission()) {
                val msg = "Microphone permission needed."
                _voiceError.value = msg
                transitionTo(VoiceControllerState.STOPPED, "permission_denied")
                onFailure(msg)
                onRecognitionFailure?.invoke(
                    RecognitionFailure(
                        kind = RecognitionFailureKind.PERMISSION_DENIED,
                        errorCode = SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                        message = msg,
                        sessionDurationMs = 0L
                    )
                )
                return@runOnMain
            }
            if (!isSpeechRecognitionAvailable()) {
                val msg = "Speech recognition is unavailable on this device."
                _voiceError.value = msg
                transitionTo(VoiceControllerState.STOPPED, "recognizer_unavailable")
                onFailure(msg)
                onRecognitionFailure?.invoke(
                    RecognitionFailure(
                        kind = RecognitionFailureKind.UNAVAILABLE,
                        errorCode = null,
                        message = msg,
                        sessionDurationMs = 0L
                    )
                )
                return@runOnMain
            }

            val currentState = _voiceState.value
            val currentSession = activeSession
            if (currentState == VoiceControllerState.LISTENING ||
                currentState == VoiceControllerState.PROCESSING ||
                pendingSessionStart ||
                (currentSession != null && !currentSession.terminated && !currentSession.stopRequested)
            ) {
                logEvent(
                    "START_LISTENING_IGNORED_DUPLICATE state=$currentState pending=$pendingSessionStart activeSession=${currentSession?.sessionId}"
                )
                return@runOnMain
            }

            // If a prior stopListening() left a terminating session open without results yet, cancel it before starting fresh.
            if (currentSession != null && currentSession.stopRequested && !currentSession.terminated) {
                currentSession.terminated = true
                activeSession = null
                cancelRecognizerIntentionally(reason = "replace_stopped_session")
            }

            if (currentState == VoiceControllerState.SPEAKING || _isSpeaking.value || activeUtteranceId != null) {
                stopSpeakingInternal(
                    reason = "startListening_interrupt",
                    interruptedByBargeIn = true,
                    targetState = VoiceControllerState.IDLE
                )
            }

            if (_voiceState.value == VoiceControllerState.STOPPED) {
                transitionTo(VoiceControllerState.IDLE, "explicit_user_restart")
            }

            nextSessionId += 1L
            val session = RecognitionSession(
                sessionId = nextSessionId,
                startedAtMs = System.currentTimeMillis(),
                onFinalResult = onFinalResult,
                onFailure = onFailure,
                onRecognitionFailure = onRecognitionFailure
            )
            activeSession = session
            pendingSessionStart = true
            _voiceError.value = null
            _partialTranscript.value = ""
            transitionTo(VoiceControllerState.LISTENING, "startListening(session=${session.sessionId}, autoRestart=$isAutoRestart)")

            val monitorJob = stopBargeInMonitor()
            if (monitorJob != null && monitorJob.isActive) {
                scope.launch(Dispatchers.Main.immediate) {
                    withTimeoutOrNull(250L) { monitorJob.join() }
                    if (activeSession === session && !session.terminated && _voiceState.value == VoiceControllerState.LISTENING) {
                        beginSpeechRecognizerSession(session)
                    } else {
                        pendingSessionStart = false
                        logEvent("START_LISTENING_ABORTED_AFTER_BARGE_JOIN session=${session.sessionId} state=${_voiceState.value}")
                    }
                }
            } else {
                beginSpeechRecognizerSession(session)
            }
        }
    }

    private val masterRecognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            runOnMain {
                val session = activeSession
                if (session == null || session.terminated || _voiceState.value == VoiceControllerState.STOPPED) {
                    logEvent("CALLBACK_IGNORED onReadyForSpeech (session=${session?.sessionId}, state=${_voiceState.value})")
                    return@runOnMain
                }
                session.hasReadyForSpeech = true
                transitionTo(VoiceControllerState.LISTENING, "onReadyForSpeech(session=${session.sessionId})")
            }
        }

        override fun onBeginningOfSpeech() {
            runOnMain {
                val session = activeSession
                if (session == null || session.terminated || _voiceState.value == VoiceControllerState.STOPPED) {
                    logEvent("CALLBACK_IGNORED onBeginningOfSpeech (session=${session?.sessionId}, state=${_voiceState.value})")
                    return@runOnMain
                }
                session.hasBegunSpeech = true
                transitionTo(VoiceControllerState.LISTENING, "onBeginningOfSpeech(session=${session.sessionId})")
            }
        }

        override fun onRmsChanged(rmsdB: Float) {
            runOnMain {
                val session = activeSession
                if (session == null || session.terminated || _voiceState.value != VoiceControllerState.LISTENING) return@runOnMain
                val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                _audioLevel.value = normalized
            }
        }

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() {
            runOnMain {
                val session = activeSession
                if (session == null || session.terminated || session.hasEndedSpeech || _voiceState.value == VoiceControllerState.STOPPED) {
                    logEvent(
                        "CALLBACK_IGNORED onEndOfSpeech (session=${session?.sessionId}, ended=${session?.hasEndedSpeech}, state=${_voiceState.value})"
                    )
                    return@runOnMain
                }
                session.hasEndedSpeech = true
                _audioLevel.value = 0f
                transitionTo(VoiceControllerState.PROCESSING, "onEndOfSpeech(session=${session.sessionId})")
            }
        }

        override fun onError(error: Int) {
            runOnMain {
                val session = activeSession
                if (session == null || session.terminated) {
                    logEvent("CALLBACK_IGNORED onError($error) (no active or already terminated session, state=${_voiceState.value})")
                    return@runOnMain
                }
                session.terminated = true
                activeSession = null
                pendingSessionStart = false
                _audioLevel.value = 0f

                val durationMs = (System.currentTimeMillis() - session.startedAtMs).coerceAtLeast(0L)
                val (kind, msg) = classifyRecognitionError(error)

                // Do NOT call speechRecognizer.cancel() on every callback or timeout.
                // Only recycle the single-owner recognizer if Android reports BUSY or CLIENT wedge.
                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
                    logEvent("RECOGNIZER_RECYCLE reason=onError($error) session=${session.sessionId}")
                    destroyRecognizerSafely(cancelFirst = false, reason = "onError_wedge_$error")
                } else {
                    logEvent("SESSION_ENDED_ON_ERROR code=$error kind=$kind session=${session.sessionId} durationMs=$durationMs")
                }

                if (_voiceState.value == VoiceControllerState.STOPPED) {
                    logEvent("CALLBACK_POST_STOP_IGNORED onError($error) session=${session.sessionId}")
                    return@runOnMain
                }

                if (kind == RecognitionFailureKind.PERMISSION_DENIED ||
                    kind == RecognitionFailureKind.UNAVAILABLE
                ) {
                    _voiceError.value = msg
                    transitionTo(VoiceControllerState.STOPPED, "onError_unrecoverable_$error")
                } else {
                    _voiceError.value = if (kind == RecognitionFailureKind.SILENCE_OR_EMPTY) null else msg
                    transitionTo(VoiceControllerState.IDLE, "onError_recoverable_$error")
                }

                session.onFailure(msg)
                session.onRecognitionFailure?.invoke(
                    RecognitionFailure(
                        kind = kind,
                        errorCode = error,
                        message = msg,
                        sessionDurationMs = durationMs
                    )
                )
            }
        }

        override fun onResults(results: Bundle?) {
            runOnMain {
                val session = activeSession
                if (session == null || session.terminated) {
                    logEvent("CALLBACK_IGNORED onResults (no active or already terminated session, state=${_voiceState.value})")
                    return@runOnMain
                }
                session.terminated = true
                activeSession = null
                pendingSessionStart = false
                _audioLevel.value = 0f

                val durationMs = (System.currentTimeMillis() - session.startedAtMs).coerceAtLeast(0L)
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                _partialTranscript.value = ""

                if (_voiceState.value == VoiceControllerState.STOPPED) {
                    logEvent("CALLBACK_POST_STOP_IGNORED onResults session=${session.sessionId}")
                    return@runOnMain
                }

                if (text.isNotEmpty()) {
                    transitionTo(
                        VoiceControllerState.PROCESSING,
                        "onResults_valid(session=${session.sessionId}, chars=${text.length})"
                    )
                    session.onFinalResult(text)
                } else {
                    transitionTo(VoiceControllerState.IDLE, "onResults_empty(session=${session.sessionId})")
                    val msg = "Didn't catch anything."
                    session.onFailure(msg)
                    session.onRecognitionFailure?.invoke(
                        RecognitionFailure(
                            kind = RecognitionFailureKind.SILENCE_OR_EMPTY,
                            errorCode = null,
                            message = msg,
                            sessionDurationMs = durationMs
                        )
                    )
                }
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            runOnMain {
                val session = activeSession
                if (session == null || session.terminated || _voiceState.value == VoiceControllerState.STOPPED) {
                    return@runOnMain
                }
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (partial.isNotBlank()) {
                    _partialTranscript.value = partial
                }
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun ensureRecognizer(): SpeechRecognizer {
        val existing = speechRecognizer
        if (existing != null) {
            if (!listenerAttachedToRecognizer) {
                existing.setRecognitionListener(masterRecognitionListener)
                listenerAttachedToRecognizer = true
            }
            return existing
        }
        recognizerCreationCountForTest += 1
        logEvent("RECOGNIZER_CREATE count=$recognizerCreationCountForTest")
        return SpeechRecognizer.createSpeechRecognizer(context).also { created ->
            speechRecognizer = created
            created.setRecognitionListener(masterRecognitionListener)
            listenerAttachedToRecognizer = true
        }
    }

    private fun cancelRecognizerIntentionally(reason: String) {
        val rec = speechRecognizer ?: return
        recognizerCancelCountForTest += 1
        logEvent("RECOGNIZER_CANCEL reason=$reason cancelCount=$recognizerCancelCountForTest")
        runCatching { rec.cancel() }
    }

    private fun destroyRecognizerSafely(cancelFirst: Boolean = false, reason: String = "unspecified") {
        val old = speechRecognizer
        speechRecognizer = null
        listenerAttachedToRecognizer = false
        if (old != null) {
            logEvent("RECOGNIZER_DESTROY reason=$reason cancelFirst=$cancelFirst")
            if (cancelFirst) {
                recognizerCancelCountForTest += 1
                runCatching { old.cancel() }
            }
            runCatching { old.destroy() }
        }
    }

    private fun beginSpeechRecognizerSession(session: RecognitionSession) {
        if (activeSession !== session || session.terminated || _voiceState.value == VoiceControllerState.STOPPED) {
            pendingSessionStart = false
            return
        }
        pendingSessionStart = false
        listenAttemptCountForTest += 1
        try {
            val recognizer = ensureRecognizer()
            val activeLangTag = languageDetectionManager.getActiveLanguage().ttsLocale.toLanguageTag()
            val primaryLanguageTag = Locale.getDefault().toLanguageTag().ifBlank { "en-IN" }
            val supportedLanguages = listOf(primaryLanguageTag, activeLangTag, "en-IN", "hi-IN", "mr-IN", "en-US")
                .distinct()
                .toTypedArray()

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, primaryLanguageTag)
                putExtra(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES, supportedLanguages)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
            }
            logEvent("RECOGNIZER_START_LISTENING session=${session.sessionId} attempt=$listenAttemptCountForTest")
            recognizer.startListening(intent)
        } catch (e: Exception) {
            if (activeSession !== session || session.terminated) return
            session.terminated = true
            activeSession = null
            pendingSessionStart = false
            _audioLevel.value = 0f
            destroyRecognizerSafely(cancelFirst = true, reason = "startListening_exception")
            val durationMs = (System.currentTimeMillis() - session.startedAtMs).coerceAtLeast(0L)
            val msg = e.localizedMessage ?: "Couldn't start microphone."
            val kind = if (!hasRecordAudioPermission()) {
                RecognitionFailureKind.PERMISSION_DENIED
            } else {
                RecognitionFailureKind.TEMPORARY
            }
            _voiceError.value = msg
            if (kind == RecognitionFailureKind.PERMISSION_DENIED) {
                transitionTo(VoiceControllerState.STOPPED, "startListening_exception_perm_denied")
            } else {
                transitionTo(VoiceControllerState.IDLE, "startListening_exception_temp")
            }
            session.onFailure(msg)
            session.onRecognitionFailure?.invoke(
                RecognitionFailure(
                    kind = kind,
                    errorCode = null,
                    message = msg,
                    sessionDurationMs = durationMs
                )
            )
        }
    }

    internal fun classifyRecognitionError(error: Int): Pair<RecognitionFailureKind, String> = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH ->
            RecognitionFailureKind.SILENCE_OR_EMPTY to "Didn't catch that — please try again."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            RecognitionFailureKind.SILENCE_OR_EMPTY to "No speech heard."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            RecognitionFailureKind.RECOGNIZER_BUSY to "Speech recognizer was busy — retrying."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            RecognitionFailureKind.PERMISSION_DENIED to "Microphone permission needed."
        SpeechRecognizer.ERROR_AUDIO ->
            RecognitionFailureKind.TEMPORARY to "Microphone audio error."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            RecognitionFailureKind.TEMPORARY to "Speech recognition network error."
        SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_CLIENT ->
            RecognitionFailureKind.TEMPORARY to "Temporary speech service issue — retrying."
        10, 11 -> // ERROR_TOO_MANY_REQUESTS, ERROR_SERVER_DISCONNECTED on API 31+
            RecognitionFailureKind.TEMPORARY to "Temporary speech service issue — retrying."
        else ->
            RecognitionFailureKind.TEMPORARY to "Voice input error ($error)."
    }

    fun stopListening(reason: String = "manual_stop_listening") {
        runOnMain {
            val session = activeSession
            pendingSessionStart = false
            _audioLevel.value = 0f
            session?.stopRequested = true
            logEvent("STOP_LISTENING reason=$reason session=${session?.sessionId}")
            if (_voiceState.value == VoiceControllerState.LISTENING) {
                transitionTo(VoiceControllerState.IDLE, reason)
            }
            runCatching { speechRecognizer?.stopListening() }
        }
    }

    fun cancelListening(reason: String = "intentional_cancel", enterStopped: Boolean = false) {
        runOnMain {
            cancelListeningInternal(reason = reason, enterStopped = enterStopped)
        }
    }

    private fun cancelListeningInternal(reason: String, enterStopped: Boolean) {
        val session = activeSession
        val hadActiveOrPending = (session != null && !session.terminated) || pendingSessionStart
        if (session != null) {
            session.terminated = true
        }
        activeSession = null
        pendingSessionStart = false
        _audioLevel.value = 0f
        _partialTranscript.value = ""
        if (hadActiveOrPending) {
            cancelRecognizerIntentionally(reason = reason)
        } else {
            logEvent("CANCEL_LISTENING_NOOP reason=$reason state=${_voiceState.value}")
        }
        when {
            enterStopped -> transitionTo(VoiceControllerState.STOPPED, reason)
            _voiceState.value == VoiceControllerState.LISTENING ||
                _voiceState.value == VoiceControllerState.PROCESSING ->
                transitionTo(VoiceControllerState.IDLE, reason)
        }
    }

    private fun configureTtsForText(
        text: String,
        detectedLanguage: UserLanguage? = null,
        brainConfig: BrainConfig? = null
    ): Boolean {
        val ttsInstance = tts ?: return false

        val targetLanguage = detectedLanguage ?: languageDetectionManager.detect(
            rawInput = text,
            preferredLanguage = brainConfig?.preferredLanguage ?: "auto",
            autoDetect = brainConfig?.autoLanguageDetectionEnabled ?: true
        ).language

        val outcome = voiceSelectionManager.selectMaleVoice(
            tts = ttsInstance,
            targetLanguage = targetLanguage,
            preferredVoiceName = brainConfig?.selectedVoiceName.orEmpty(),
            allowFallbackWithApproval = brainConfig?.allowVoiceFallbackWithApproval ?: false,
            configuredElevenLabsMaleVoiceId = brainConfig?.elevenLabsVoiceId.orEmpty()
        )

        return when (outcome) {
            is VoiceSelectionOutcome.Selected -> {
                runCatching {
                    ttsInstance.voice = outcome.voice
                    ttsInstance.language = outcome.voice.locale
                    ttsInstance.setSpeechRate(1.02f)
                    val isExplicit = voiceSelectionManager.isExplicitMaleVoice(outcome.voice)
                    ttsInstance.setPitch(if (isExplicit) 1.0f else 0.92f)
                }
                true
            }
            is VoiceSelectionOutcome.FallbackApproved -> {
                runCatching {
                    ttsInstance.language = outcome.locale
                    ttsInstance.setSpeechRate(1.02f)
                    ttsInstance.setPitch(outcome.pitch)
                }
                true
            }
            is VoiceSelectionOutcome.Unavailable -> {
                _voiceError.value = outcome.reason
                false
            }
        }
    }

    internal fun sanitizeTextForSpeech(text: String): String {
        if (text.isBlank()) return ""
        return text
            .replace(Regex("\\[(CHART|TABLE|FLOWCHART|SCHEDULE)][\\s\\S]*?\\[/\\1]"), "")
            // Markdown images and links: drop images, keep readable link label for links.
            .replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), "")
            .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
            // Bare URLs (http/https or www.*) without touching dotted abbreviations like J.A.R.V.I.S or decimals.
            .replace(Regex("https?://[^\\s)\\]>\"']+"), "")
            .replace(Regex("\\bwww\\.[a-zA-Z0-9-]+(?:\\.[a-zA-Z0-9-]+)+(?:/[^\\s)\\]>\"']*)?"), "")
            // Empty parentheses left behind if a URL was wrapped like "(https://...)".
            .replace(Regex("\\(\\s*\\)"), "")
            // Line-start Markdown/numbered list markers and blockquotes, plus inline bullet symbols.
            .replace(Regex("(?m)^\\s*(?:[-*+>]+|\\d+[.)])\\s+"), "")
            .replace(Regex("[•◦▪▫‣⁃]"), "")
            .replace(Regex("[*_`#]"), "")
            // Normalize spacing introduced by removed URLs/markers while preserving line breaks.
            .replace(Regex("[ \\t]{2,}"), " ")
            .replace(Regex("(?m)^[ \\t]+|[ \\t]+$"), "")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    internal fun splitTextForTtsChunks(
        text: String,
        maxChunkLen: Int = MAX_TTS_CHUNK_CHARS
    ): List<String> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.length <= maxChunkLen) return listOf(trimmed)

        val chunks = mutableListOf<String>()
        var start = 0
        while (start < trimmed.length) {
            val remaining = trimmed.length - start
            if (remaining <= maxChunkLen) {
                val tail = trimmed.substring(start).trim()
                if (tail.isNotEmpty()) chunks.add(tail)
                break
            }
            val limit = start + maxChunkLen
            var splitAt = -1
            for (i in limit downTo (start + maxChunkLen / 2)) {
                val c = trimmed[i - 1]
                if (c == '\n' || c == '.' || c == '!' || c == '?' || c == '।') {
                    splitAt = i
                    break
                }
            }
            if (splitAt <= start) {
                for (i in limit downTo (start + maxChunkLen / 2)) {
                    if (trimmed[i - 1].isWhitespace()) {
                        splitAt = i
                        break
                    }
                }
            }
            if (splitAt <= start) {
                splitAt = limit
            }
            val part = trimmed.substring(start, splitAt).trim()
            if (part.isNotEmpty()) {
                chunks.add(part)
            }
            start = splitAt
            while (start < trimmed.length && trimmed[start].isWhitespace()) {
                start++
            }
        }
        return chunks
    }

    fun speak(
        text: String,
        enableBargeIn: Boolean = false,
        detectedLanguage: UserLanguage? = null,
        brainConfig: BrainConfig? = null,
        onBargeInDetected: (() -> Unit)? = null
    ): Boolean {
        if (text.isBlank()) {
            markProcessingFinished("speak_blank_text")
            return false
        }
        val ttsInstance = tts
        if (!ttsReady || ttsInstance == null) {
            _voiceError.value = "Text-to-speech is unavailable right now."
            markProcessingFinished("speak_tts_unavailable")
            return false
        }
        val clean = sanitizeTextForSpeech(text)
        val chunks = splitTextForTtsChunks(clean)
        if (chunks.isEmpty()) {
            markProcessingFinished("speak_empty_chunks")
            return false
        }

        // Only cancel recognition if a recognition session is actively open (never during PROCESSING after onResults).
        if (_voiceState.value == VoiceControllerState.LISTENING ||
            pendingSessionStart ||
            (activeSession != null && activeSession?.terminated == false)
        ) {
            cancelListeningInternal(reason = "preempt_for_tts", enterStopped = false)
        }
        stopBargeInMonitor()

        if (!configureTtsForText(clean, detectedLanguage, brainConfig)) {
            markProcessingFinished("speak_voice_unavailable")
            return false
        }

        val baseId = UUID.randomUUID().toString()
        val finalUtteranceId = if (chunks.size == 1) baseId else "${baseId}_final"
        utteranceInterruptedOrStopped = false
        activeUtteranceGroupPrefix = if (chunks.size > 1) baseId else null
        activeUtteranceId = finalUtteranceId
        lastStartedUtteranceId = finalUtteranceId
        pendingBargeInCallback = if (enableBargeIn) onBargeInDetected else null

        requestTtsAudioFocus()

        val params = if (ttsAudioSessionId > 0) {
            Bundle().apply {
                putInt(TextToSpeech.Engine.KEY_PARAM_SESSION_ID, ttsAudioSessionId)
            }
        } else {
            null
        }

        for (i in chunks.indices) {
            val queueMode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            val chunkUtteranceId = if (i == chunks.lastIndex) finalUtteranceId else "${baseId}_$i"
            val res = runCatching {
                ttsInstance.speak(chunks[i], queueMode, params, chunkUtteranceId)
            }.getOrDefault(TextToSpeech.ERROR)
            if (res != TextToSpeech.SUCCESS) {
                activeUtteranceId = null
                activeUtteranceGroupPrefix = null
                pendingBargeInCallback = null
                finishSpeakingState(reason = "speak_queue_failed")
                if (_voiceState.value == VoiceControllerState.PROCESSING) {
                    transitionTo(VoiceControllerState.IDLE, "speak_queue_failed")
                }
                _voiceError.value = "Couldn't play voice response."
                return false
            }
        }
        transitionTo(VoiceControllerState.SPEAKING, "speak_queued(utt=$finalUtteranceId, chunks=${chunks.size})")
        return true
    }

    fun stopSpeaking(interruptedByBargeIn: Boolean = false) {
        runOnMain {
            stopSpeakingInternal(
                reason = if (interruptedByBargeIn) "stopSpeaking_barge_in" else "stopSpeaking_manual",
                interruptedByBargeIn = interruptedByBargeIn,
                targetState = if (_voiceState.value == VoiceControllerState.STOPPED) VoiceControllerState.STOPPED else VoiceControllerState.IDLE
            )
        }
    }

    private fun stopSpeakingInternal(
        reason: String,
        interruptedByBargeIn: Boolean,
        targetState: VoiceControllerState
    ) {
        val hadSpeech = activeUtteranceId != null || _voiceState.value == VoiceControllerState.SPEAKING || _isSpeaking.value
        utteranceInterruptedOrStopped = true
        activeUtteranceId = null
        activeUtteranceGroupPrefix = null
        pendingBargeInCallback = null
        stopBargeInMonitor()
        finishSpeakingState(reason = reason)
        if (_voiceState.value == VoiceControllerState.SPEAKING || (hadSpeech && targetState == VoiceControllerState.STOPPED)) {
            transitionTo(targetState, reason)
        }
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
    }

    internal fun lastStartedUtteranceId(): String? = lastStartedUtteranceId

    internal fun triggerTestUtteranceCompletion(utteranceId: String) {
        runOnMain {
            activeUtteranceId = utteranceId
            activeUtteranceGroupPrefix = null
            lastStartedUtteranceId = utteranceId
            utteranceInterruptedOrStopped = false
            transitionTo(VoiceControllerState.SPEAKING, "test_utterance_start($utteranceId)")
            activeUtteranceId = null
            pendingBargeInCallback = null
            finishSpeakingState(reason = "test_utterance_done($utteranceId)")
            _ttsNaturalCompletions.tryEmit(utteranceId)
        }
    }

    internal fun triggerTestUtteranceFailure(utteranceId: String) {
        runOnMain {
            activeUtteranceId = utteranceId
            activeUtteranceGroupPrefix = null
            lastStartedUtteranceId = utteranceId
            utteranceInterruptedOrStopped = false
            transitionTo(VoiceControllerState.SPEAKING, "test_utterance_start_fail($utteranceId)")
            handleUtteranceError(utteranceId)
        }
    }

    internal fun setTtsReadyForTest(ready: Boolean) {
        ttsReady = ready
    }

    internal fun simulateUtteranceStartForTest(utteranceId: String) {
        runOnMain {
            activeUtteranceId = utteranceId
            activeUtteranceGroupPrefix = null
            lastStartedUtteranceId = utteranceId
            utteranceInterruptedOrStopped = false
            transitionTo(VoiceControllerState.SPEAKING, "test_simulate_utterance_start($utteranceId)")
        }
    }

    internal fun simulateRecognitionReadyForTest() {
        masterRecognitionListener.onReadyForSpeech(Bundle())
    }

    internal fun simulateBeginningOfSpeechForTest() {
        masterRecognitionListener.onBeginningOfSpeech()
    }

    internal fun simulateEndOfSpeechForTest() {
        masterRecognitionListener.onEndOfSpeech()
    }

    internal fun simulateRecognitionErrorForTest(error: Int) {
        masterRecognitionListener.onError(error)
    }

    internal fun simulateRecognitionResultsForTest(results: List<String>) {
        val bundle = Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, ArrayList(results))
        }
        masterRecognitionListener.onResults(bundle)
    }

    internal fun getListenAttemptCountForTest(): Int = listenAttemptCountForTest
    internal fun getRecognizerCreationCountForTest(): Int = recognizerCreationCountForTest
    internal fun getRecognizerCancelCountForTest(): Int = recognizerCancelCountForTest
    internal fun getTransitionLogsForTest(): List<String> = synchronized(transitionLogs) { transitionLogs.toList() }

    internal fun resetListenAttemptCountForTest() {
        listenAttemptCountForTest = 0
        recognizerCreationCountForTest = 0
        recognizerCancelCountForTest = 0
        synchronized(transitionLogs) { transitionLogs.clear() }
    }

    fun release() {
        runOnMain {
            stopSession(reason = "release")
            destroyRecognizerSafely(cancelFirst = false, reason = "release")
            stopSpeakingMeteringOnly()
            abandonTtsAudioFocus()
            try {
                tts?.shutdown()
            } catch (_: Exception) {
            }
            scope.cancel()
        }
    }

    companion object {
        private const val TAG = "JarvisVoice"
        private const val MAX_TRANSITION_LOGS = 200
        internal const val MAX_TTS_CHUNK_CHARS = 3500
    }
}
