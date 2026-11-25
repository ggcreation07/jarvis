package com.jarvis.app.platform

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.util.Locale

data class UiWaitConfig(
    val pollIntervalMs: Long = 50L,
    val actionSettleTimeoutMs: Long = 150L,
    val loadingTimeoutMs: Long = 2000L,
    val maxPolls: Int = 40
)

data class ScreenContextFlags(
    val isLoading: Boolean,
    val loadingIndicators: List<String>,
    val hasWebView: Boolean,
    val isSystemDialog: Boolean,
    val isPermissionScreen: Boolean,
    val structuralSignature: String
)

sealed interface UiWaitOutcome {
    data class Satisfied(
        val snapshot: ScreenSnapshot,
        val polls: Int,
        val elapsedMs: Long,
        val sawLoading: Boolean
    ) : UiWaitOutcome

    data class TimedOut(
        val lastSnapshot: ScreenSnapshot?,
        val lastError: ScreenReadResult.Error?,
        val sawLoading: Boolean,
        val loadingIndicators: List<String>,
        val polls: Int,
        val elapsedMs: Long,
        val reason: String
    ) : UiWaitOutcome

    data class ServiceDisconnected(
        val code: ScreenReadErrorCode,
        val message: String,
        val polls: Int
    ) : UiWaitOutcome
}

object ScreenReadinessDetector {
    private val systemPackages = setOf(
        "android",
        "com.android.systemui",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller"
    )

    private val permissionPackages = setOf(
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller"
    )

    private val loadingWidgetClasses = listOf(
        "ProgressBar",
        "CircularProgressIndicator",
        "LinearProgressIndicator",
        "ContentLoadingProgressBar",
        "ShimmerFrameLayout"
    )

    private val loadingIdRegex = Regex(
        "\\b(progress_?bar|loading_?(?:spinner|indicator|overlay|view|progress)?|spinner_?loading|progress_?spinner|shimmer)\\b",
        RegexOption.IGNORE_CASE
    )

    private val loadingTextRegex = Regex(
        "^(?:loading|please\\s+wait|fetching|refreshing|connecting|processing|syncing|almost\\s+there)(?:\\s*\\.{0,3}|…)?$",
        RegexOption.IGNORE_CASE
    )

    private val dialogClassSuffixes = listOf(
        "AlertDialog",
        "Dialog",
        "GrantPermissionsActivity",
        "ResolverActivity",
        "ChooserActivity"
    )

    private val permissionUiCueRegex = Regex(
        "(permission_allow|permission_deny|grant_dialog|while\\s+using\\s+the\\s+app|only\\s+this\\s+time|allow\\s+.+\\s+to\\s+access)",
        RegexOption.IGNORE_CASE
    )

    fun detectLoadingIndicator(node: UiNodeSnapshot): String? {
        if (!node.isVisibleToUser) return null
        val simpleCls = node.className.substringAfterLast('.')
        val isProgressWidget = loadingWidgetClasses.any { simpleCls.equals(it, ignoreCase = true) }
        if (isProgressWidget) {
            return simpleCls.ifBlank { "ProgressBar" }
        }
        if (!node.isClickable && !node.isEditable) {
            val idTail = node.viewId.substringAfter(":id/").substringAfterLast('/')
            if (idTail.isNotBlank() && loadingIdRegex.containsMatchIn(idTail)) {
                return idTail
            }
            val textClean = node.text.trim()
            if (textClean.length in 1..60 && loadingTextRegex.matches(textClean)) {
                return textClean
            }
            val descClean = node.contentDescription.trim()
            if (descClean.length in 1..60 && loadingTextRegex.matches(descClean)) {
                return descClean
            }
        }
        return null
    }

    fun detectLoadingIndicators(snapshot: ScreenSnapshot): List<String> {
        val indicators = mutableListOf<String>()
        for (node in snapshot.nodes) {
            detectLoadingIndicator(node)?.let { indicators.add(it) }
        }
        return indicators.distinct()
    }

    fun hasWebView(snapshot: ScreenSnapshot): Boolean =
        snapshot.nodes.any { node ->
            node.className.contains("WebView", ignoreCase = true)
        }

    fun isPermissionScreen(snapshot: ScreenSnapshot): Boolean {
        val pkgLower = snapshot.packageName.lowercase(Locale.ROOT)
        if (pkgLower in permissionPackages) return true
        return snapshot.nodes.any { node ->
            node.isVisibleToUser && (
                permissionUiCueRegex.containsMatchIn(node.viewId) ||
                    permissionUiCueRegex.containsMatchIn(node.text) ||
                    permissionUiCueRegex.containsMatchIn(node.contentDescription)
                )
        }
    }

    fun isSystemDialog(snapshot: ScreenSnapshot): Boolean {
        val pkgLower = snapshot.packageName.lowercase(Locale.ROOT)
        if (pkgLower in systemPackages) return true
        return snapshot.nodes.any { node ->
            val simpleCls = node.className.substringAfterLast('.')
            dialogClassSuffixes.any { suffix ->
                simpleCls.equals(suffix, ignoreCase = true) || simpleCls.endsWith(suffix, ignoreCase = true)
            } || node.viewId.startsWith("android:id/alertTitle", ignoreCase = true)
        }
    }

    fun structuralSignature(snapshot: ScreenSnapshot): String {
        val sb = StringBuilder()
        sb.append(snapshot.packageName.lowercase(Locale.ROOT)).append('|').append(snapshot.nodes.size)
        for (node in snapshot.nodes) {
            sb.append(';')
                .append(node.depth).append(':')
                .append(node.viewId).append(':')
                .append(node.text).append(':')
                .append(node.contentDescription).append(':')
                .append(node.bounds.left).append(',').append(node.bounds.top).append(',')
                .append(node.bounds.right).append(',').append(node.bounds.bottom).append(':')
                .append(node.isEnabled).append(node.isClickable).append(node.isEditable).append(node.isVisibleToUser)
        }
        return sb.toString()
    }

    fun analyze(snapshot: ScreenSnapshot): ScreenContextFlags {
        val indicators = detectLoadingIndicators(snapshot)
        return ScreenContextFlags(
            isLoading = indicators.isNotEmpty(),
            loadingIndicators = indicators,
            hasWebView = hasWebView(snapshot),
            isSystemDialog = isSystemDialog(snapshot),
            isPermissionScreen = isPermissionScreen(snapshot),
            structuralSignature = structuralSignature(snapshot)
        )
    }
}

object UiWaitPolicy {
    suspend fun waitForCondition(
        timeoutMs: Long,
        pollIntervalMs: Long,
        maxPolls: Int = 40,
        idleTimeoutMs: Long = timeoutMs,
        nowMs: () -> Long = { System.currentTimeMillis() },
        isCancelled: () -> Boolean = { false },
        requireNonLoading: Boolean = true,
        requireStable: Boolean = false,
        readFresh: suspend () -> ScreenReadResult,
        condition: (ScreenSnapshot) -> Boolean
    ): UiWaitOutcome {
        val startMs = nowMs()
        val effectiveMaxPolls = maxPolls.coerceAtLeast(1)
        val effectiveMaxTimeoutMs = timeoutMs.coerceAtLeast(idleTimeoutMs).coerceAtLeast(0L)
        val effectiveIdleTimeoutMs = idleTimeoutMs.coerceAtLeast(0L)
        var polls = 0
        var sawLoading = false
        var lastLoadingIndicators: List<String> = emptyList()
        var lastSnapshot: ScreenSnapshot? = null
        var lastError: ScreenReadResult.Error? = null
        var previousSignature: String? = null

        while (polls < effectiveMaxPolls) {
            if (isCancelled()) {
                throw CancellationException("UI wait cancelled")
            }
            currentCoroutineContext().ensureActive()

            polls += 1
            when (val res = readFresh()) {
                is ScreenReadResult.Error -> {
                    lastError = res
                    if (res.code == ScreenReadErrorCode.SERVICE_NOT_CONNECTED ||
                        res.code == ScreenReadErrorCode.SERVICE_ENABLED_BUT_DISCONNECTED
                    ) {
                        return UiWaitOutcome.ServiceDisconnected(
                            code = res.code,
                            message = res.message,
                            polls = polls
                        )
                    }
                    // Transient null root, empty tree, or transitional own-app window during launch
                    previousSignature = null
                }
                is ScreenReadResult.Success -> {
                    val snap = res.snapshot
                    lastSnapshot = snap
                    lastError = null
                    val flags = ScreenReadinessDetector.analyze(snap)
                    if (flags.isLoading) {
                        sawLoading = true
                        lastLoadingIndicators = flags.loadingIndicators
                    }
                    val loadingClear = !requireNonLoading || !flags.isLoading
                    val stableOk = !requireStable || (previousSignature != null && previousSignature == flags.structuralSignature)
                    val condMet = condition(snap)

                    if (loadingClear && stableOk && condMet) {
                        return UiWaitOutcome.Satisfied(
                            snapshot = snap,
                            polls = polls,
                            elapsedMs = (nowMs() - startMs).coerceAtLeast(0L),
                            sawLoading = sawLoading
                        )
                    }
                    previousSignature = flags.structuralSignature
                }
            }

            val elapsedMs = (nowMs() - startMs).coerceAtLeast(0L)
            val activeTimeoutMs = if (sawLoading || lastError != null) {
                effectiveMaxTimeoutMs
            } else {
                effectiveIdleTimeoutMs
            }
            if (elapsedMs >= activeTimeoutMs || polls >= effectiveMaxPolls) {
                break
            }

            if (pollIntervalMs > 0L) {
                val remaining = (activeTimeoutMs - elapsedMs).coerceAtLeast(1L)
                delay(minOf(pollIntervalMs, remaining))
            }

            if (isCancelled()) {
                throw CancellationException("UI wait cancelled")
            }
            currentCoroutineContext().ensureActive()
        }

        val totalElapsed = (nowMs() - startMs).coerceAtLeast(0L)
        val reason = when {
            sawLoading && lastLoadingIndicators.isNotEmpty() ->
                "Timed out after ${totalElapsed}ms ($polls polls) while screen remained in loading state (${lastLoadingIndicators.joinToString(", ")})."
            lastError != null ->
                "Timed out after ${totalElapsed}ms ($polls polls) waiting for screen content: ${lastError.message}"
            else ->
                "Condition not satisfied within ${totalElapsed}ms ($polls polls)."
        }

        return UiWaitOutcome.TimedOut(
            lastSnapshot = lastSnapshot,
            lastError = lastError,
            sawLoading = sawLoading,
            loadingIndicators = lastLoadingIndicators,
            polls = polls,
            elapsedMs = totalElapsed,
            reason = reason
        )
    }
}
