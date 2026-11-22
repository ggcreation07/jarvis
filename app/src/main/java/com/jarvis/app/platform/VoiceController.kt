package com.jarvis.app.platform

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class VoiceController(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsAudioSessionId: Int = AudioManager.AUDIO_SESSION_ID_GENERATE

    @Volatile
    private var activeUtteranceId: String? = null

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

    private fun initTts() {
        try {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.getDefault()
                    ttsReady = true
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            if (utteranceId != null && activeUtteranceId != null && utteranceId != activeUtteranceId) return
                            _isSpeaking.value = true
                            scope.launch { startSpeakingMetering() }
                        }

                        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                            if (utteranceId != null && activeUtteranceId != null && utteranceId != activeUtteranceId) return
                            if (!hasReceivedRealVisualizerEnergy) {
                                val span = (end - start).coerceIn(1, 16)
                                fallbackTargetLevel = (0.34f + (span / 16f) * 0.44f).coerceIn(0.32f, 0.82f)
                            }
                        }

                        override fun onDone(utteranceId: String?) {
                            if (utteranceId != null && activeUtteranceId != null && utteranceId != activeUtteranceId) return
                            finishSpeakingState()
                        }

                        override fun onStop(utteranceId: String?, interrupted: Boolean) {
                            if (utteranceId != null && activeUtteranceId != null && utteranceId != activeUtteranceId) return
                            finishSpeakingState()
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            if (utteranceId != null && activeUtteranceId != null && utteranceId != activeUtteranceId) return
                            finishSpeakingState()
                        }
                    })
                }
            }
        } catch (_: Exception) {
            ttsReady = false
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
        scope.launch { stopSpeakingMeteringOnly() }
    }

    fun isSpeechRecognitionAvailable(): Boolean =
        SpeechRecognizer.isRecognitionAvailable(context)

    fun startListening(onFinalResult: (String) -> Unit, onFailure: (String) -> Unit) {
        if (!isSpeechRecognitionAvailable()) {
            val msg = "On-device speech recognition is unavailable on this device."
            _voiceError.value = msg
            onFailure(msg)
            return
        }
        stopSpeaking()
        _voiceError.value = null
        _partialTranscript.value = ""

        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        _isListening.value = true
                    }

                    override fun onBeginningOfSpeech() {
                        _isListening.value = true
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                        val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                        _audioLevel.value = normalized
                    }

                    override fun onBufferReceived(buffer: ByteArray?) = Unit

                    override fun onEndOfSpeech() {
                        _isListening.value = false
                        _audioLevel.value = 0f
                    }

                    override fun onError(error: Int) {
                        _isListening.value = false
                        _audioLevel.value = 0f
                        val msg = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "Microphone audio error."
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed."
                            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                                "Speech recognition network error."
                            SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that — please try again."
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard."
                            else -> "Voice input error ($error)."
                        }
                        _voiceError.value = msg
                        onFailure(msg)
                    }

                    override fun onResults(results: Bundle?) {
                        _isListening.value = false
                        _audioLevel.value = 0f
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                            .orEmpty()
                        _partialTranscript.value = ""
                        if (text.isNotEmpty()) {
                            onFinalResult(text)
                        } else {
                            onFailure("Didn't catch anything.")
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            .orEmpty()
                        if (partial.isNotBlank()) {
                            _partialTranscript.value = partial
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
            }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            _isListening.value = false
            val msg = e.localizedMessage ?: "Couldn't start microphone."
            _voiceError.value = msg
            onFailure(msg)
        }
    }

    fun stopListening() {
        _isListening.value = false
        _audioLevel.value = 0f
        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) {
        }
    }

    fun speak(text: String) {
        if (!ttsReady || text.isBlank()) return
        val clean = text
            .replace(Regex("\\[(CHART|TABLE|FLOWCHART|SCHEDULE)][\\s\\S]*?\\[/\\1]"), "")
            .replace(Regex("[*_`#]"), "")
            .trim()
            .take(1200)
        if (clean.isEmpty()) return

        val utteranceId = UUID.randomUUID().toString()
        activeUtteranceId = utteranceId
        val params = if (ttsAudioSessionId > 0) {
            Bundle().apply {
                putInt(TextToSpeech.Engine.KEY_PARAM_SESSION_ID, ttsAudioSessionId)
            }
        } else {
            null
        }
        val res = tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
        if (res != TextToSpeech.SUCCESS) {
            finishSpeakingState()
        }
    }

    fun stopSpeaking() {
        activeUtteranceId = null
        finishSpeakingState()
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
    }

    fun release() {
        activeUtteranceId = null
        stopSpeakingMeteringOnly()
        try {
            speechRecognizer?.destroy()
            tts?.shutdown()
        } catch (_: Exception) {
        }
        scope.cancel()
    }
}
