package com.jarvis.app.platform

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.jarvis.app.domain.model.HandPose

class GestureLatch(
    private val holdMs: Long = 550L,
    private val releaseMs: Long = 380L
) {
    private var candidate = HandPose.UNKNOWN
    private var duration = 0L
    private var samples = 0
    private var lastFrame: Long? = null
    private var lastMatch: Long? = null
    private var interruptedAt: Long? = null
    private var emitted = false
    private var neutralAt: Long? = null
    private var neutralSamples = 0
    private var needsNeutral = true

    fun update(pose: HandPose, nowMs: Long): HandPose? {
        val previous = lastFrame
        if (previous != null && nowMs <= previous) return null
        if (previous != null && nowMs - previous > 400L) {
            clearHold()
            emitted = false
            needsNeutral = true
            neutralAt = null
            neutralSamples = 0
        }
        lastFrame = nowMs
        if (pose == HandPose.UNKNOWN) {
            if (neutralAt == null) neutralAt = nowMs
            neutralSamples++
            if (nowMs - neutralAt!! >= releaseMs && neutralSamples >= 2) {
                emitted = false
                needsNeutral = false
            }
            if (interruptedAt == null) interruptedAt = nowMs
            if (nowMs - interruptedAt!! > 180L) clearHold()
            lastMatch = null
            return null
        } else {
            neutralAt = null
            neutralSamples = 0
        }

        if (candidate != pose || (interruptedAt?.let { nowMs - it > 180L } == true)) {
            clearHold()
            candidate = pose
        }
        lastMatch?.let { duration += nowMs - it }
        lastMatch = nowMs
        interruptedAt = null
        samples++
        val required = if (pose == HandPose.FIST) maxOf(750L, holdMs) else holdMs
        if (samples < 2 || duration < required || (pose != HandPose.FIST && (needsNeutral || emitted))) {
            return null
        }
        emitted = true
        return pose
    }

    private fun clearHold() {
        candidate = HandPose.UNKNOWN
        duration = 0L
        samples = 0
        lastMatch = null
        interruptedAt = null
    }
}

class MediaPipeHandDetector(context: Context) {
    private var recognizer: GestureRecognizer? = null

    init {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath("gesture_recognizer.task")
                .build()
            val options = GestureRecognizer.GestureRecognizerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setNumHands(1)
                .build()
            recognizer = GestureRecognizer.createFromOptions(context, options)
        } catch (e: Exception) {
            Log.w("GestureEngine", "GestureRecognizer init unavailable: ${e.message}")
        }
    }

    fun detect(bitmap: Bitmap): Pair<HandPose, Float> {
        val engine = recognizer ?: return HandPose.UNKNOWN to 0f
        return try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            val result = engine.recognize(mpImage)
            val top = result.gestures().firstOrNull()?.firstOrNull() ?: return HandPose.UNKNOWN to 0f
            val score = top.score()
            if (score < 0.68f) return HandPose.UNKNOWN to score
            val pose = HandPose.entries.firstOrNull { it.rawLabel == top.categoryName() } ?: HandPose.UNKNOWN
            pose to score
        } catch (_: Exception) {
            HandPose.UNKNOWN to 0f
        }
    }

    fun close() {
        try {
            recognizer?.close()
        } catch (_: Exception) {
        }
    }
}
