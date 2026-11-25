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
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var speechRecognizer: SpeechRecognizer? = null
    private var activeRecognitionListenerForTest: RecognitionListener? = null
    private var listenAttemptCountForTest: Int = 0
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

    @Volatile
    private var isListeningRequested: Boolean = false

    @Volatile
    private var activeRecognitionSessionId: Long = 0L

    private val bargeInLock = Any()
    private var bargeInMonitorJob: Job? = null
    private var activeAudioRecord: AudioRecord? = null

    private var visualizer: Visualizer? = null
    private var fallbackMeterJob: Job? = null

    @Volatile
    private var hasReceivedRealVisualizerEnergy = false

    @Volatile
    private var fallbackTargetLevel = 0.25f

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
        finishSpeakingState()
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
                            val current = activeUtteranceId
                            if (current == null || !isMatchingActiveUtterance(utteranceId) || utteranceInterruptedOrStopped) {
                                return
                            }
                            val wasAlreadySpeaking = _isSpeaking.value
                            _isSpeaking.value = true
                            if (!wasAlreadySpeaking) {
                                scope.launch { startSpeakingMetering() }
                                val bargeInCallback = pendingBargeInCallback
                                if (bargeInCallback != null) {
                                    scope.launch {
                                        startBargeInMonitor(current, bargeInCallback)
                                    }
                                }
                            }
                        }

                        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                            if (activeUtteranceId == null || !isMatchingActiveUtterance(utteranceId) || utteranceInterruptedOrStopped) {
                                return
                            }
                            if (!hasReceivedRealVisualizerEnergy) {
                                val span = (end - start).coerceIn(1, 16)
                                fallbackTargetLevel = (0.34f + (span / 16f) * 0.44f).coerceIn(0.32f, 0.82f)
                            }
                        }

                        override fun onDone(utteranceId: String?) {
                            val current = activeUtteranceId
                            if (current == null || !isFinalActiveUtterance(utteranceId)) {
                                return
                            }
                            val wasInterrupted = utteranceInterruptedOrStopped
                            activeUtteranceId = null
                            activeUtteranceGroupPrefix = null
                            pendingBargeInCallback = null
                            finishSpeakingState()
                            if (!wasInterrupted) {
                                _ttsNaturalCompletions.tryEmit(current)
                            }
                        }

                        override fun onStop(utteranceId: String?, interrupted: Boolean) {
                            if (activeUtteranceId == null || !isMatchingActiveUtterance(utteranceId)) {
                                return
                            }
                            activeUtteranceId = null
                            activeUtteranceGroupPrefix = null
                            pendingBargeInCallback = null
                            finishSpeakingState()
                        }

                        override fun onError(utteranceId: String?, errorCode: Int) {
                            handleUtteranceError(utteranceId)
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            handleUtteranceError(utteranceId)
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

    private fun finishSpeakingState() {
        _isSpeaking.value = false
        _speakingLevel.value = 0f
        abandonTtsAudioFocus()
        scope.launch { stopSpeakingMeteringOnly() }
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

    fun isListeningOrPending(): Boolean = _isListening.value || isListeningRequested

    fun isTtsReady(): Boolean = ttsReady

    fun isSpeechRecognitionAvailable(): Boolean =
        SpeechRecognizer.isRecognitionAvailable(context)

    fun startListening(
        onFinalResult: (String) -> Unit,
        onFailure: (String) -> Unit,
        onRecognitionFailure: ((RecognitionFailure) -> Unit)? = null
    ) {
        if (!hasRecordAudioPermission()) {
            val msg = "Microphone permission needed."
            _voiceError.value = msg
            onFailure(msg)
            onRecognitionFailure?.invoke(
                RecognitionFailure(
                    kind = RecognitionFailureKind.PERMISSION_DENIED,
                    errorCode = SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                    message = msg,
                    sessionDurationMs = 0L
                )
            )
            return
        }
        if (!isSpeechRecognitionAvailable()) {
            val msg = "On-device speech recognition is unavailable on this device."
            _voiceError.value = msg
            onFailure(msg)
            onRecognitionFailure?.invoke(
                RecognitionFailure(
                    kind = RecognitionFailureKind.UNAVAILABLE,
                    errorCode = null,
                    message = msg,
                    sessionDurationMs = 0L
                )
            )
            return
        }
        if (isListeningOrPending()) {
            return
        }

        isListeningRequested = true
        activeRecognitionSessionId += 1L
        val sessionId = activeRecognitionSessionId

        val monitorJob = stopBargeInMonitor()
        if (_isSpeaking.value || activeUtteranceId != null) {
            stopSpeaking()
        }
        _voiceError.value = null
        _partialTranscript.value = ""

        if (monitorJob != null && monitorJob.isActive) {
            scope.launch(Dispatchers.Main.immediate) {
                withTimeoutOrNull(250L) { monitorJob.join() }
                if (sessionId == activeRecognitionSessionId && isListeningRequested) {
                    beginSpeechRecognizerSession(sessionId, onFinalResult, onFailure, onRecognitionFailure)
                }
            }
        } else {
            beginSpeechRecognizerSession(sessionId, onFinalResult, onFailure, onRecognitionFailure)
        }
    }

    private fun destroyRecognizerSafely(cancelFirst: Boolean = false) {
        val old = speechRecognizer
        speechRecognizer = null
        activeRecognitionListenerForTest = null
        if (old != null) {
            if (cancelFirst) {
                runCatching { old.cancel() }
            }
            runCatching { old.destroy() }
        }
    }

    private fun beginSpeechRecognizerSession(
        sessionId: Long,
        onFinalResult: (String) -> Unit,
        onFailure: (String) -> Unit,
        onRecognitionFailure: ((RecognitionFailure) -> Unit)?
    ) {
        if (sessionId != activeRecognitionSessionId || !isListeningRequested) return
        listenAttemptCountForTest += 1
        val sessionStartMs = System.currentTimeMillis()
        var sessionTerminated = false
        try {
            destroyRecognizerSafely(cancelFirst = false)
            val listener = object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    if (sessionTerminated || sessionId != activeRecognitionSessionId) return
                    _isListening.value = true
                }

                override fun onBeginningOfSpeech() {
                    if (sessionTerminated || sessionId != activeRecognitionSessionId) return
                    _isListening.value = true
                }

                override fun onRmsChanged(rmsdB: Float) {
                    if (sessionTerminated || sessionId != activeRecognitionSessionId) return
                    val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                    _audioLevel.value = normalized
                }

                override fun onBufferReceived(buffer: ByteArray?) = Unit

                override fun onEndOfSpeech() {
                    if (sessionTerminated || sessionId != activeRecognitionSessionId) return
                    _isListening.value = false
                    _audioLevel.value = 0f
                }

                override fun onError(error: Int) {
                    if (sessionTerminated || sessionId != activeRecognitionSessionId) return
                    sessionTerminated = true
                    activeRecognitionSessionId += 1L
                    isListeningRequested = false
                    _isListening.value = false
                    _audioLevel.value = 0f
                    val durationMs = (System.currentTimeMillis() - sessionStartMs).coerceAtLeast(0L)
                    destroyRecognizerSafely(cancelFirst = error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
                    val (kind, msg) = classifyRecognitionError(error)
                    _voiceError.value = if (kind == RecognitionFailureKind.SILENCE_OR_EMPTY) null else msg
                    onFailure(msg)
                    onRecognitionFailure?.invoke(
                        RecognitionFailure(
                            kind = kind,
                            errorCode = error,
                            message = msg,
                            sessionDurationMs = durationMs
                        )
                    )
                }

                override fun onResults(results: Bundle?) {
                    if (sessionTerminated || sessionId != activeRecognitionSessionId) return
                    sessionTerminated = true
                    activeRecognitionSessionId += 1L
                    isListeningRequested = false
                    _isListening.value = false
                    _audioLevel.value = 0f
                    val durationMs = (System.currentTimeMillis() - sessionStartMs).coerceAtLeast(0L)
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                        .orEmpty()
                    _partialTranscript.value = ""
                    destroyRecognizerSafely(cancelFirst = false)
                    if (text.isNotEmpty()) {
                        onFinalResult(text)
                    } else {
                        val msg = "Didn't catch anything."
                        onFailure(msg)
                        onRecognitionFailure?.invoke(
                            RecognitionFailure(
                                kind = RecognitionFailureKind.SILENCE_OR_EMPTY,
                                errorCode = null,
                                message = msg,
                                sessionDurationMs = durationMs
                            )
                        )
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    if (sessionTerminated || sessionId != activeRecognitionSessionId) return
                    val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        .orEmpty()
                    if (partial.isNotBlank()) {
                        _partialTranscript.value = partial
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            }
            activeRecognitionListenerForTest = listener
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(listener)
            }

            val primaryLanguageTag = Locale.getDefault().toLanguageTag().ifBlank { "en-IN" }
            val supportedLanguages = listOf(primaryLanguageTag, "en-IN", "hi-IN", "mr-IN", "en-US")
                .distinct()
                .toTypedArray()

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, primaryLanguageTag)
                putExtra(RecognizerIntent.EXTRA_SUPPORTED_LANGUAGES, supportedLanguages)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
            }
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            if (sessionId != activeRecognitionSessionId) return
            sessionTerminated = true
            activeRecognitionSessionId += 1L
            isListeningRequested = false
            _isListening.value = false
            _audioLevel.value = 0f
            destroyRecognizerSafely(cancelFirst = true)
            val durationMs = (System.currentTimeMillis() - sessionStartMs).coerceAtLeast(0L)
            val msg = e.localizedMessage ?: "Couldn't start microphone."
            val kind = if (!hasRecordAudioPermission()) {
                RecognitionFailureKind.PERMISSION_DENIED
            } else {
                RecognitionFailureKind.TEMPORARY
            }
            _voiceError.value = msg
            onFailure(msg)
            onRecognitionFailure?.invoke(
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

    fun stopListening() {
        isListeningRequested = false
        _isListening.value = false
        _audioLevel.value = 0f
        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) {
        }
    }

    fun cancelListening() {
        activeRecognitionSessionId += 1L
        isListeningRequested = false
        _isListening.value = false
        _audioLevel.value = 0f
        _partialTranscript.value = ""
        destroyRecognizerSafely(cancelFirst = true)
    }

    private fun configureTtsForText(text: String) {
        val ttsInstance = tts ?: return
        fun isLangAvailable(loc: Locale): Int =
            runCatching { ttsInstance.isLanguageAvailable(loc) }.getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)

        val hasDevanagari = text.any { it in '\u0900'..'\u097F' }
        val locale = when {
            hasDevanagari -> {
                val mrLocale = Locale("mr", "IN")
                val hiLocale = Locale("hi", "IN")
                if (text.contains("आहे") || text.contains("माझे") || text.contains("काय")) {
                    if (isLangAvailable(mrLocale) >= TextToSpeech.LANG_AVAILABLE) mrLocale else hiLocale
                } else {
                    if (isLangAvailable(hiLocale) >= TextToSpeech.LANG_AVAILABLE) hiLocale else mrLocale
                }
            }
            else -> {
                val defaultLoc = Locale.getDefault()
                val inLocale = Locale("en", "IN")
                when {
                    isLangAvailable(defaultLoc) >= TextToSpeech.LANG_AVAILABLE -> defaultLoc
                    isLangAvailable(inLocale) >= TextToSpeech.LANG_AVAILABLE -> inLocale
                    else -> defaultLoc
                }
            }
        }

        val availability = isLangAvailable(locale)
        val selectedLocale = if (availability >= TextToSpeech.LANG_AVAILABLE) locale else Locale("en", "IN")

        runCatching {
            ttsInstance.language = selectedLocale
            ttsInstance.setSpeechRate(1.02f)
            ttsInstance.setPitch(1.0f)
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
        onBargeInDetected: (() -> Unit)? = null
    ): Boolean {
        if (text.isBlank()) return false
        val ttsInstance = tts
        if (!ttsReady || ttsInstance == null) {
            _voiceError.value = "Text-to-speech is unavailable right now."
            return false
        }
        val clean = sanitizeTextForSpeech(text)
        val chunks = splitTextForTtsChunks(clean)
        if (chunks.isEmpty()) return false

        if (isListeningOrPending()) {
            cancelListening()
        }
        stopBargeInMonitor()

        val baseId = UUID.randomUUID().toString()
        val finalUtteranceId = if (chunks.size == 1) baseId else "${baseId}_final"
        utteranceInterruptedOrStopped = false
        activeUtteranceGroupPrefix = if (chunks.size > 1) baseId else null
        activeUtteranceId = finalUtteranceId
        lastStartedUtteranceId = finalUtteranceId
        pendingBargeInCallback = if (enableBargeIn) onBargeInDetected else null

        configureTtsForText(clean)
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
                finishSpeakingState()
                _voiceError.value = "Couldn't play voice response."
                return false
            }
        }
        return true
    }

    fun stopSpeaking(interruptedByBargeIn: Boolean = false) {
        utteranceInterruptedOrStopped = true
        activeUtteranceId = null
        activeUtteranceGroupPrefix = null
        pendingBargeInCallback = null
        stopBargeInMonitor()
        finishSpeakingState()
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
    }

    internal fun lastStartedUtteranceId(): String? = lastStartedUtteranceId

    internal fun triggerTestUtteranceCompletion(utteranceId: String) {
        activeUtteranceId = utteranceId
        activeUtteranceGroupPrefix = null
        lastStartedUtteranceId = utteranceId
        utteranceInterruptedOrStopped = false
        _isSpeaking.value = true
        activeUtteranceId = null
        pendingBargeInCallback = null
        finishSpeakingState()
        _ttsNaturalCompletions.tryEmit(utteranceId)
    }

    internal fun triggerTestUtteranceFailure(utteranceId: String) {
        activeUtteranceId = utteranceId
        activeUtteranceGroupPrefix = null
        lastStartedUtteranceId = utteranceId
        utteranceInterruptedOrStopped = false
        _isSpeaking.value = true
        handleUtteranceError(utteranceId)
    }

    internal fun setTtsReadyForTest(ready: Boolean) {
        ttsReady = ready
    }

    internal fun simulateUtteranceStartForTest(utteranceId: String) {
        activeUtteranceId = utteranceId
        activeUtteranceGroupPrefix = null
        lastStartedUtteranceId = utteranceId
        utteranceInterruptedOrStopped = false
        _isSpeaking.value = true
    }

    internal fun simulateRecognitionReadyForTest() {
        activeRecognitionListenerForTest?.onReadyForSpeech(Bundle())
    }

    internal fun simulateRecognitionErrorForTest(error: Int) {
        activeRecognitionListenerForTest?.onError(error)
    }

    internal fun simulateRecognitionResultsForTest(results: List<String>) {
        val bundle = Bundle().apply {
            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, ArrayList(results))
        }
        activeRecognitionListenerForTest?.onResults(bundle)
    }

    internal fun getListenAttemptCountForTest(): Int = listenAttemptCountForTest

    internal fun resetListenAttemptCountForTest() {
        listenAttemptCountForTest = 0
    }

    fun release() {
        utteranceInterruptedOrStopped = true
        activeUtteranceId = null
        activeUtteranceGroupPrefix = null
        pendingBargeInCallback = null
        stopBargeInMonitor()
        stopSpeakingMeteringOnly()
        abandonTtsAudioFocus()
        cancelListening()
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
        scope.cancel()
    }

    companion object {
        internal const val MAX_TTS_CHUNK_CHARS = 3500
    }
}
