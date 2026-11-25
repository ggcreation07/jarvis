package com.jarvis.app.platform

import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.util.Locale

enum class ScreenInteractionStatus {
    SUCCESS,
    NOT_FOUND,
    AMBIGUOUS,
    NOT_CLICKABLE,
    NOT_EDITABLE,
    PASSWORD_FIELD,
    NOT_SCROLLABLE,
    SCREEN_CHANGED,
    SERVICE_DISCONNECTED,
    ACTION_FAILED
}

data class UiElementQuery(
    val text: String = "",
    val contentDescription: String = "",
    val viewId: String = "",
    val hint: String = "",
    val className: String = "",
    val expectedPackage: String = "",
    val expectedFingerprint: String = "",
    val clickableOnly: Boolean = false
) {
    fun hasPrimarySelector(): Boolean =
        text.isNotBlank() || contentDescription.isNotBlank() || viewId.isNotBlank() || hint.isNotBlank()
}

data class MatchedUiElement(
    val snapshot: UiNodeSnapshot,
    val fingerprint: String,
    val displayLabel: String,
    val clickableResolved: Boolean,
    val usedClickableAncestor: Boolean,
    val resolvedClickTargetLabel: String,
    val isSensitive: Boolean,
    internal val nodePath: List<Int>,
    internal val clickPath: List<Int>?
)

data class FindUiElementsResult(
    val status: ScreenInteractionStatus,
    val message: String,
    val packageName: String = "",
    val matches: List<MatchedUiElement> = emptyList()
) {
    fun toUntrustedDataBlock(): String {
        val sb = StringBuilder()
        sb.appendLine(ScreenReaderEngine.UNTRUSTED_HEADER)
        sb.appendLine("status=${status.name} package=${sanitizeBlockValue(packageName)} matches=${matches.size}")
        matches.forEachIndexed { idx, match ->
            val n = match.snapshot
            sb.append("[match#$idx d=${n.depth}")
            if (n.viewId.isNotBlank()) sb.append(" id=${sanitizeBlockValue(n.viewId)}")
            if (n.className.isNotBlank()) sb.append(" cls=${sanitizeBlockValue(n.className.substringAfterLast('.'))}")
            sb.append(" bounds=${n.bounds.left},${n.bounds.top},${n.bounds.right},${n.bounds.bottom}")
            sb.append(" clickable=${match.clickableResolved} ancestor=${match.usedClickableAncestor} sensitive=${match.isSensitive}")
            sb.append(" fp=\"${sanitizeBlockValue(match.fingerprint)}\"")
            if (n.text.isNotBlank()) sb.append(" text=\"${sanitizeBlockValue(n.text)}\"")
            if (n.contentDescription.isNotBlank()) sb.append(" desc=\"${sanitizeBlockValue(n.contentDescription)}\"")
            if (n.hintText.isNotBlank()) sb.append(" hint=\"${sanitizeBlockValue(n.hintText)}\"")
            sb.appendLine("]")
        }
        sb.append(ScreenReaderEngine.UNTRUSTED_FOOTER)
        return sb.toString()
    }

    private fun sanitizeBlockValue(value: String): String =
        value
            .replace(Regex("\\[\\s*/?\\s*UNTRUSTED_SCREEN_CONTENT[^\\]]*\\]", RegexOption.IGNORE_CASE), "[redacted-delimiter]")
            .replace("\"", "'")
}

data class ClickInspectionCandidate(
    val packageName: String,
    val fingerprint: String,
    val displayLabel: String,
    val viewId: String,
    val className: String,
    val usedAncestor: Boolean,
    val isSensitive: Boolean
)

sealed interface ClickPrecheckOutcome {
    data class Ready(val candidate: ClickInspectionCandidate) : ClickPrecheckOutcome
    data class Failed(
        val status: ScreenInteractionStatus,
        val message: String,
        val packageName: String = ""
    ) : ClickPrecheckOutcome
}

data class ClickUiElementResult(
    val status: ScreenInteractionStatus,
    val message: String,
    val packageName: String = "",
    val targetLabel: String = "",
    val fingerprint: String = "",
    val usedAncestor: Boolean = false
)

enum class ScrollDirection {
    UP,
    DOWN;

    companion object {
        fun parseOrNull(raw: String): ScrollDirection? = when (raw.trim().uppercase(Locale.ROOT)) {
            "UP" -> UP
            "DOWN" -> DOWN
            else -> null
        }
    }
}

data class TypeTextInspectionCandidate(
    val packageName: String,
    val fingerprint: String,
    val displayLabel: String,
    val viewId: String,
    val className: String,
    val isSensitive: Boolean
)

sealed interface TypeTextPrecheckOutcome {
    data class Ready(val candidate: TypeTextInspectionCandidate) : TypeTextPrecheckOutcome
    data class Failed(
        val status: ScreenInteractionStatus,
        val message: String,
        val packageName: String = ""
    ) : TypeTextPrecheckOutcome
}

data class TypeTextResult(
    val status: ScreenInteractionStatus,
    val message: String,
    val packageName: String = "",
    val fieldLabel: String = "",
    val fingerprint: String = "",
    val verified: Boolean = false
)

data class ScrollUiResult(
    val status: ScreenInteractionStatus,
    val message: String,
    val packageName: String = "",
    val targetLabel: String = "",
    val fingerprint: String = "",
    val direction: String = ""
)

object SensitiveUiActionPolicy {
    private val permissionOrInstallerPackages = setOf(
        "android",
        "com.android.settings",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller"
    )

    private val permissionPackageActionPattern = Regex(
        "\\b(allow|grant|accept|install|uninstall|continue|ok|always|while\\s+using|only\\s+this\\s+time|delete|remove|force\\s+stop|disable|clear\\s+storage|clear\\s+data|activate|trust)\\b"
    )

    private val sensitiveActionPatterns = listOf(
        // Send / outbound messaging / email
        Regex("\\b(send|reply|forward|dispatch|transmit|deliver|mail|email)\\b"),
        // Submit / finalize / checkout
        Regex("\\b(submit|confirm|place\\s+order|checkout|check\\s+out|complete\\s+order|save\\s+changes)\\b"),
        // Publish / post / broadcast
        Regex("\\b(publish|post|tweet|repost|broadcast|upload|go\\s+live)\\b"),
        // Pay / purchase / financial
        Regex("\\b(pay|payment|purchase|buy|subscribe|donate|transfer|withdraw|deposit|billing|upi)\\b"),
        // Delete / erase / wipe / deactivate / force stop / clear storage
        Regex("\\b(delete|remove|erase|wipe|destroy|purge|clear\\s+(?:all|data|history|cache|storage|account)|deactivate|close\\s+account|force\\s+stop)\\b"),
        // Uninstall / install
        Regex("\\b(uninstall|install|sideload)\\b"),
        // Grant permissions / system privileges
        Regex("\\b(allow|grant|permit|permission|permissions|authorize|always\\s+allow|while\\s+using|only\\s+this\\s+time|enable\\s+accessibility)\\b"),
        // Account / security / privacy settings
        Regex("\\b(account|accounts|password|passcode|pin|biometric|fingerprint|face\\s+unlock|2fa|two[- ]factor|mfa|security|privacy|sign\\s+out|log\\s+out|logout|revoke|disconnect|device\\s+admin|factory\\s+reset|reset\\s+phone|reset\\s+network)\\b")
    )

    // Fields where Jarvis must NEVER type under any circumstances (even with confirmation).
    private val blockedCredentialFieldPatterns = listOf(
        Regex("\\b(password|passcode|passphrase|pwd|new\\s+password|current\\s+password|confirm\\s+password)\\b"),
        Regex("\\b(otp|one\\s+time\\s+(?:code|pass(?:word)?|pin)|verification\\s+code|auth(?:entication)?\\s+code|security\\s+code|2fa|two\\s+factor|mfa|totp)\\b"),
        Regex("\\b(pin|mpin|upi\\s*pin|pin\\s*code|enter\\s*pin|atm\\s*pin|security\\s*pin)\\b"),
        Regex("\\b(cvv|cvc|cid|card\\s*number|credit\\s*card|debit\\s*card|cardholder|expiry|expiration\\s*date|exp\\s*date|iban|routing\\s*number|bank\\s*account|ssn|social\\s*security|aadhaar|secret\\s*key|api\\s*key|private\\s*key|seed\\s*phrase|recovery\\s*phrase|master\\s*key)\\b")
    )

    // Editable fields that prepare or trigger outbound, financial, destructive, permission, or account/security actions.
    private val sensitiveInputFieldPatterns = listOf(
        Regex("\\b(send|compose|message|chat|reply|forward|mail|email|recipient|to\\s+address|subject)\\b"),
        Regex("\\b(submit|publish|post|comment|tweet|repost|caption|status\\s+update|broadcast|feedback|form)\\b"),
        Regex("\\b(pay|payment|purchase|buy|checkout|order|billing|amount|transfer|donate|subscribe|upi|promo|coupon)\\b"),
        Regex("\\b(delete|remove|erase|wipe|deactivate|close\\s+account|type\\s+delete|confirm\\s+delete)\\b"),
        Regex("\\b(install|uninstall|permission|grant|allow|authorize)\\b"),
        Regex("\\b(account|username|user\\s+name|security|privacy|recovery|device\\s+admin|reset)\\b")
    )

    // On-screen action cues indicating text entered on the current screen may be immediately submitted/sent/paid/deleted.
    private val screenActionCuePatterns = listOf(
        Regex("\\b(send|reply|forward|mail|email)\\b"),
        Regex("\\b(submit|publish|post|tweet|repost|checkout|place\\s+order|complete\\s+order)\\b"),
        Regex("\\b(pay|pay\\s+now|purchase|buy|buy\\s+now|transfer|donate|subscribe)\\b"),
        Regex("\\b(delete|confirm\\s+delete|erase|wipe|deactivate|close\\s+account)\\b"),
        Regex("\\b(install|uninstall|allow|grant|authorize|save\\s+changes)\\b")
    )

    fun normalizeForMatch(raw: String): String {
        if (raw.isBlank()) return ""
        return raw
            .substringAfter(":id/")
            .substringAfterLast('/')
            .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
            .replace(Regex("([a-z])([0-9])"), "$1 $2")
            .replace(Regex("[_\\-.]+"), " ")
            .replace(Regex("\\s+"), " ")
            .lowercase(Locale.ROOT)
            .trim()
    }

    fun isSensitiveText(vararg fields: String, packageName: String = ""): Boolean {
        val normalizedFields = fields
            .map { normalizeForMatch(it) }
            .filter { it.isNotBlank() }
        if (normalizedFields.isEmpty()) return false
        val combined = normalizedFields.joinToString(" ")

        if (packageName.lowercase(Locale.ROOT) in permissionOrInstallerPackages &&
            permissionPackageActionPattern.containsMatchIn(combined)
        ) {
            return true
        }
        return sensitiveActionPatterns.any { it.containsMatchIn(combined) }
    }

    fun isSensitiveCandidate(
        packageName: String,
        targetSnapshot: UiNodeSnapshot,
        ancestorSnapshot: UiNodeSnapshot? = null
    ): Boolean {
        return isSensitiveText(
            targetSnapshot.text,
            targetSnapshot.contentDescription,
            targetSnapshot.hintText,
            targetSnapshot.viewId,
            ancestorSnapshot?.text.orEmpty(),
            ancestorSnapshot?.contentDescription.orEmpty(),
            ancestorSnapshot?.viewId.orEmpty(),
            packageName = packageName
        )
    }

    fun isBlockedCredentialText(vararg fields: String): Boolean {
        val normalizedFields = fields
            .map { normalizeForMatch(it) }
            .filter { it.isNotBlank() }
        if (normalizedFields.isEmpty()) return false
        val combined = normalizedFields.joinToString(" ")
        return blockedCredentialFieldPatterns.any { it.containsMatchIn(combined) }
    }

    fun isBlockedCredentialField(
        snapshot: UiNodeSnapshot,
        ancestorFields: List<String> = emptyList()
    ): Boolean {
        if (snapshot.isPassword) return true
        return isBlockedCredentialText(
            snapshot.hintText,
            snapshot.contentDescription,
            snapshot.viewId,
            snapshot.text,
            *ancestorFields.toTypedArray()
        )
    }

    fun isSensitiveInputText(
        vararg fields: String,
        packageName: String = "",
        screenActionLabels: List<String> = emptyList()
    ): Boolean {
        val normalizedPackage = packageName.lowercase(Locale.ROOT)
        if (normalizedPackage in permissionOrInstallerPackages) {
            return true
        }
        val normalizedFields = fields
            .map { normalizeForMatch(it) }
            .filter { it.isNotBlank() }
        if (normalizedFields.isNotEmpty()) {
            val combined = normalizedFields.joinToString(" ")
            if (sensitiveInputFieldPatterns.any { it.containsMatchIn(combined) }) {
                return true
            }
        }
        val normalizedScreenCues = screenActionLabels
            .map { normalizeForMatch(it) }
            .filter { it.isNotBlank() }
        if (normalizedScreenCues.any { cue -> screenActionCuePatterns.any { it.containsMatchIn(cue) } }) {
            return true
        }
        return false
    }

    fun isSensitiveInputCandidate(
        packageName: String,
        snapshot: UiNodeSnapshot,
        ancestorFields: List<String> = emptyList(),
        screenActionLabels: List<String> = emptyList()
    ): Boolean {
        return isSensitiveInputText(
            snapshot.hintText,
            snapshot.contentDescription,
            snapshot.viewId,
            snapshot.text,
            *ancestorFields.toTypedArray(),
            packageName = packageName,
            screenActionLabels = screenActionLabels
        )
    }
}

class ScreenInteractionEngine(
    private val windowSource: AccessibilityWindowSource,
    private val maxDepth: Int = ScreenReaderEngine.MAX_TRAVERSAL_DEPTH,
    private val maxNodes: Int = ScreenReaderEngine.MAX_NODES,
    private val maxAncestorHops: Int = MAX_ANCESTOR_HOPS,
    private val maxAttempts: Int = ScreenReaderEngine.DEFAULT_MAX_ATTEMPTS,
    private val retryDelayMs: Long = ScreenReaderEngine.DEFAULT_RETRY_DELAY_MS,
    private val onNodeRecycled: ((AccessibilityNodeInfo) -> Unit)? = null,
    private val waitConfig: UiWaitConfig = UiWaitConfig(),
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        const val MAX_ANCESTOR_HOPS = 4

        fun buildFingerprint(snap: UiNodeSnapshot): String = listOf(
            snap.packageName,
            snap.viewId,
            snap.className,
            snap.text,
            snap.contentDescription,
            snap.hintText,
            "${snap.bounds.left},${snap.bounds.top},${snap.bounds.right},${snap.bounds.bottom}"
        ).joinToString("|")

        fun buildDisplayLabel(snap: UiNodeSnapshot): String =
            snap.text.ifBlank { snap.contentDescription }
                .ifBlank { snap.hintText }
                .ifBlank { snap.viewId.substringAfterLast('/') }
                .ifBlank { snap.className.substringAfterLast('.') }
                .ifBlank { "UI element" }
    }

    private enum class MatchQuality {
        NONE,
        PARTIAL,
        EXACT
    }

    private data class TraversalMatch(
        val element: MatchedUiElement,
        val quality: MatchQuality,
        val ancestorContextFields: List<String> = emptyList(),
        val isCredentialField: Boolean = false
    )

    private data class TreeInspection(
        val packageName: String,
        val matches: List<TraversalMatch>,
        val matchedPasswordOnly: Boolean,
        val screenActionLabels: List<String> = emptyList(),
        val scrollableElements: List<MatchedUiElement> = emptyList(),
        val loadingIndicators: List<String> = emptyList()
    )

    private sealed interface RootAcquisition {
        data class Ready(val root: AccessibilityNodeInfo, val source: String) : RootAcquisition
        data class Error(val status: ScreenInteractionStatus, val message: String) : RootAcquisition
    }

    suspend fun findElements(query: UiElementQuery): FindUiElementsResult {
        if (!query.hasPrimarySelector()) {
            return FindUiElementsResult(
                status = ScreenInteractionStatus.ACTION_FAILED,
                message = "Provide at least one selector (text, content_description, view_id, or hint) to locate a UI element."
            )
        }
        val serviceError = checkServiceConnected()
        if (serviceError != null) {
            return FindUiElementsResult(
                status = ScreenInteractionStatus.SERVICE_DISCONNECTED,
                message = serviceError
            )
        }

        val outcome = withExternalRootWithRetries { root ->
            val rootPkg = safePkg(root)
            if (query.expectedPackage.isNotBlank() &&
                !rootPkg.equals(query.expectedPackage.trim(), ignoreCase = true)
            ) {
                return@withExternalRootWithRetries FindUiElementsResult(
                    status = ScreenInteractionStatus.SCREEN_CHANGED,
                    message = "Active screen package changed (expected '${query.expectedPackage}', found '$rootPkg').",
                    packageName = rootPkg
                )
            }

            val inspection = inspectTree(root, query)
            val resolvedMatches = resolvePrioritizedMatches(inspection.matches)

            if (resolvedMatches.isEmpty()) {
                if (inspection.matchedPasswordOnly) {
                    return@withExternalRootWithRetries FindUiElementsResult(
                        status = ScreenInteractionStatus.NOT_CLICKABLE,
                        message = "Matching element is a password field, which is excluded from UI interaction.",
                        packageName = inspection.packageName
                    )
                }
                return@withExternalRootWithRetries FindUiElementsResult(
                    status = ScreenInteractionStatus.NOT_FOUND,
                    message = "No matching visible UI element found on '${inspection.packageName}'.",
                    packageName = inspection.packageName
                )
            }

            val filtered = if (query.clickableOnly) {
                resolvedMatches.filter { it.clickableResolved }
            } else {
                resolvedMatches
            }
            if (filtered.isEmpty()) {
                return@withExternalRootWithRetries FindUiElementsResult(
                    status = ScreenInteractionStatus.NOT_CLICKABLE,
                    message = "Found ${resolvedMatches.size} matching element(s), but none are enabled and clickable.",
                    packageName = inspection.packageName
                )
            }

            val finalStatus = if (filtered.size == 1) {
                ScreenInteractionStatus.SUCCESS
            } else {
                ScreenInteractionStatus.AMBIGUOUS
            }
            val msg = if (finalStatus == ScreenInteractionStatus.SUCCESS) {
                "Found 1 matching element '${filtered.first().displayLabel}' in ${inspection.packageName}."
            } else {
                "Found ${filtered.size} matching elements in ${inspection.packageName}; refine your selector to uniquely identify one element."
            }
            FindUiElementsResult(
                status = finalStatus,
                message = msg,
                packageName = inspection.packageName,
                matches = filtered
            )
        }

        return when (outcome) {
            is RootOutcome.Value -> outcome.value
            is RootOutcome.Failed -> FindUiElementsResult(
                status = outcome.status,
                message = outcome.message
            )
        }
    }

    suspend fun inspectForClick(query: UiElementQuery): ClickPrecheckOutcome {
        if (!query.hasPrimarySelector()) {
            return ClickPrecheckOutcome.Failed(
                status = ScreenInteractionStatus.ACTION_FAILED,
                message = "Specify text, content_description, view_id, or hint to identify one UI element to click."
            )
        }
        val serviceError = checkServiceConnected()
        if (serviceError != null) {
            return ClickPrecheckOutcome.Failed(
                status = ScreenInteractionStatus.SERVICE_DISCONNECTED,
                message = serviceError
            )
        }

        val outcome = withExternalRootWithRetries { root ->
            val rootPkg = safePkg(root)
            if (query.expectedPackage.isNotBlank() &&
                !rootPkg.equals(query.expectedPackage.trim(), ignoreCase = true)
            ) {
                return@withExternalRootWithRetries ClickPrecheckOutcome.Failed(
                    status = ScreenInteractionStatus.SCREEN_CHANGED,
                    message = "Foreground app changed from '${query.expectedPackage}' to '$rootPkg'.",
                    packageName = rootPkg
                )
            }

            val inspection = inspectTree(root, query)
            val resolved = deduplicateSameClickTarget(resolvePrioritizedMatches(inspection.matches))

            if (resolved.isEmpty()) {
                return@withExternalRootWithRetries if (inspection.matchedPasswordOnly) {
                    ClickPrecheckOutcome.Failed(
                        status = ScreenInteractionStatus.NOT_CLICKABLE,
                        message = "Password fields are excluded from UI interaction.",
                        packageName = inspection.packageName
                    )
                } else if (query.expectedFingerprint.isNotBlank()) {
                    ClickPrecheckOutcome.Failed(
                        status = ScreenInteractionStatus.SCREEN_CHANGED,
                        message = "The expected UI target is no longer present on '${inspection.packageName}'.",
                        packageName = inspection.packageName
                    )
                } else {
                    ClickPrecheckOutcome.Failed(
                        status = ScreenInteractionStatus.NOT_FOUND,
                        message = "No matching visible element found in '${inspection.packageName}'.",
                        packageName = inspection.packageName
                    )
                }
            }

            if (resolved.size > 1) {
                return@withExternalRootWithRetries ClickPrecheckOutcome.Failed(
                    status = ScreenInteractionStatus.AMBIGUOUS,
                    message = "Matched ${resolved.size} elements in '${inspection.packageName}'; refusing ambiguous click.",
                    packageName = inspection.packageName
                )
            }

            val match = resolved.first()
            if (query.expectedFingerprint.isNotBlank() &&
                match.fingerprint != query.expectedFingerprint
            ) {
                return@withExternalRootWithRetries ClickPrecheckOutcome.Failed(
                    status = ScreenInteractionStatus.SCREEN_CHANGED,
                    message = "Target UI element changed since inspection; refusing stale click.",
                    packageName = inspection.packageName
                )
            }

            if (!match.clickableResolved || match.clickPath == null) {
                return@withExternalRootWithRetries ClickPrecheckOutcome.Failed(
                    status = ScreenInteractionStatus.NOT_CLICKABLE,
                    message = "Element '${match.displayLabel}' is visible, but neither it nor a safe ancestor is enabled and clickable.",
                    packageName = inspection.packageName
                )
            }

            ClickPrecheckOutcome.Ready(
                ClickInspectionCandidate(
                    packageName = inspection.packageName,
                    fingerprint = match.fingerprint,
                    displayLabel = match.displayLabel,
                    viewId = match.snapshot.viewId,
                    className = match.snapshot.className,
                    usedAncestor = match.usedClickableAncestor,
                    isSensitive = match.isSensitive
                )
            )
        }

        return when (outcome) {
            is RootOutcome.Value -> outcome.value
            is RootOutcome.Failed -> ClickPrecheckOutcome.Failed(
                status = outcome.status,
                message = outcome.message
            )
        }
    }

    suspend fun clickElement(query: UiElementQuery): ClickUiElementResult {
        if (!query.hasPrimarySelector()) {
            return ClickUiElementResult(
                status = ScreenInteractionStatus.ACTION_FAILED,
                message = "Specify text, content_description, view_id, or hint to identify one UI element to click."
            )
        }
        val serviceError = checkServiceConnected()
        if (serviceError != null) {
            return ClickUiElementResult(
                status = ScreenInteractionStatus.SERVICE_DISCONNECTED,
                message = serviceError
            )
        }

        val outcome = withExternalRootWithRetries { root ->
            val rootPkg = safePkg(root)
            if (query.expectedPackage.isNotBlank() &&
                !rootPkg.equals(query.expectedPackage.trim(), ignoreCase = true)
            ) {
                return@withExternalRootWithRetries ClickUiElementResult(
                    status = ScreenInteractionStatus.SCREEN_CHANGED,
                    message = "Screen changed before click: expected '${query.expectedPackage}', found '$rootPkg'.",
                    packageName = rootPkg
                )
            }

            val inspection = inspectTree(root, query)
            val resolved = deduplicateSameClickTarget(resolvePrioritizedMatches(inspection.matches))

            if (resolved.isEmpty()) {
                return@withExternalRootWithRetries if (inspection.matchedPasswordOnly) {
                    ClickUiElementResult(
                        status = ScreenInteractionStatus.NOT_CLICKABLE,
                        message = "Password fields are excluded from UI interaction.",
                        packageName = inspection.packageName
                    )
                } else if (query.expectedFingerprint.isNotBlank()) {
                    ClickUiElementResult(
                        status = ScreenInteractionStatus.SCREEN_CHANGED,
                        message = "Target element changed or disappeared before click.",
                        packageName = inspection.packageName
                    )
                } else {
                    ClickUiElementResult(
                        status = ScreenInteractionStatus.NOT_FOUND,
                        message = "No matching visible element found in '${inspection.packageName}'.",
                        packageName = inspection.packageName
                    )
                }
            }

            if (resolved.size > 1) {
                return@withExternalRootWithRetries ClickUiElementResult(
                    status = ScreenInteractionStatus.AMBIGUOUS,
                    message = "Matched ${resolved.size} elements in '${inspection.packageName}'; refusing ambiguous click.",
                    packageName = inspection.packageName
                )
            }

            val match = resolved.first()
            if (query.expectedFingerprint.isNotBlank() &&
                match.fingerprint != query.expectedFingerprint
            ) {
                return@withExternalRootWithRetries ClickUiElementResult(
                    status = ScreenInteractionStatus.SCREEN_CHANGED,
                    message = "Target element changed since confirmation or inspection; click aborted.",
                    packageName = inspection.packageName
                )
            }

            val clickPath = match.clickPath
            if (!match.clickableResolved || clickPath == null) {
                return@withExternalRootWithRetries ClickUiElementResult(
                    status = ScreenInteractionStatus.NOT_CLICKABLE,
                    message = "Element '${match.displayLabel}' is not enabled and clickable (and has no safe clickable ancestor).",
                    packageName = inspection.packageName,
                    targetLabel = match.displayLabel,
                    fingerprint = match.fingerprint
                )
            }

            // Immediate live revalidation on fresh nodes right before ACTION_CLICK
            val targetStillValid = walkPath(root, match.nodePath) { liveTarget ->
                val targetSnap = buildSnapshot(0, match.nodePath.size, liveTarget, inspection.packageName, isPasswordNode(liveTarget))
                !isOwnPackage(targetSnap.packageName) &&
                    !targetSnap.isPassword &&
                    targetSnap.isVisibleToUser &&
                    targetSnap.isEnabled &&
                    buildFingerprint(targetSnap) == match.fingerprint
            }
            if (!targetStillValid) {
                return@withExternalRootWithRetries ClickUiElementResult(
                    status = ScreenInteractionStatus.SCREEN_CHANGED,
                    message = "Target element state changed during immediate pre-click revalidation.",
                    packageName = inspection.packageName,
                    targetLabel = match.displayLabel,
                    fingerprint = match.fingerprint
                )
            }

            val clicked = walkPath(root, clickPath) { clickNode ->
                val pkg = safePkg(clickNode).ifBlank { inspection.packageName }
                val visible = safeBool { clickNode.isVisibleToUser }
                val enabled = safeBool { clickNode.isEnabled }
                val clickable = safeBool { clickNode.isClickable }
                if (isOwnPackage(pkg) || isPasswordNode(clickNode) || !visible || !enabled || !clickable) {
                    false
                } else {
                    try {
                        clickNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    } catch (_: Exception) {
                        false
                    }
                }
            }

            if (clicked) {
                val via = if (match.usedClickableAncestor) " via clickable ancestor" else ""
                ClickUiElementResult(
                    status = ScreenInteractionStatus.SUCCESS,
                    message = "Clicked '${match.displayLabel}'$via in ${inspection.packageName}.",
                    packageName = inspection.packageName,
                    targetLabel = match.displayLabel,
                    fingerprint = match.fingerprint,
                    usedAncestor = match.usedClickableAncestor
                )
            } else {
                ClickUiElementResult(
                    status = ScreenInteractionStatus.ACTION_FAILED,
                    message = "Click action on '${match.displayLabel}' was rejected or failed.",
                    packageName = inspection.packageName,
                    targetLabel = match.displayLabel,
                    fingerprint = match.fingerprint,
                    usedAncestor = match.usedClickableAncestor
                )
            }
        }

        return when (outcome) {
            is RootOutcome.Value -> outcome.value
            is RootOutcome.Failed -> ClickUiElementResult(
                status = outcome.status,
                message = outcome.message
            )
        }
    }

    suspend fun inspectForTypeText(query: UiElementQuery, textToEnter: String): TypeTextPrecheckOutcome {
        if (textToEnter.isBlank()) {
            return TypeTextPrecheckOutcome.Failed(
                status = ScreenInteractionStatus.ACTION_FAILED,
                message = "Provide explicit non-empty text from the user's request before typing."
            )
        }
        if (!query.hasPrimarySelector()) {
            return TypeTextPrecheckOutcome.Failed(
                status = ScreenInteractionStatus.ACTION_FAILED,
                message = "Specify text, content_description, view_id, or hint to uniquely identify one input field."
            )
        }
        val serviceError = checkServiceConnected()
        if (serviceError != null) {
            return TypeTextPrecheckOutcome.Failed(
                status = ScreenInteractionStatus.SERVICE_DISCONNECTED,
                message = serviceError
            )
        }

        val outcome = withExternalRootWithRetries { root ->
            val rootPkg = safePkg(root)
            if (query.expectedPackage.isNotBlank() &&
                !rootPkg.equals(query.expectedPackage.trim(), ignoreCase = true)
            ) {
                return@withExternalRootWithRetries TypeTextPrecheckOutcome.Failed(
                    status = ScreenInteractionStatus.SCREEN_CHANGED,
                    message = "Foreground app changed from '${query.expectedPackage}' to '$rootPkg'.",
                    packageName = rootPkg
                )
            }

            val inspection = inspectTree(root, query)
            val resolved = resolvePrioritizedTraversalMatches(inspection.matches)

            if (resolved.isEmpty()) {
                return@withExternalRootWithRetries if (inspection.matchedPasswordOnly) {
                    TypeTextPrecheckOutcome.Failed(
                        status = ScreenInteractionStatus.PASSWORD_FIELD,
                        message = "Refusing text input: password fields are strictly excluded.",
                        packageName = inspection.packageName
                    )
                } else if (query.expectedFingerprint.isNotBlank()) {
                    TypeTextPrecheckOutcome.Failed(
                        status = ScreenInteractionStatus.SCREEN_CHANGED,
                        message = "The expected input field is no longer present on '${inspection.packageName}'.",
                        packageName = inspection.packageName
                    )
                } else {
                    TypeTextPrecheckOutcome.Failed(
                        status = ScreenInteractionStatus.NOT_FOUND,
                        message = "No matching visible input field found in '${inspection.packageName}'.",
                        packageName = inspection.packageName
                    )
                }
            }

            if (resolved.size > 1) {
                return@withExternalRootWithRetries TypeTextPrecheckOutcome.Failed(
                    status = ScreenInteractionStatus.AMBIGUOUS,
                    message = "Matched ${resolved.size} elements in '${inspection.packageName}'; refusing ambiguous text input.",
                    packageName = inspection.packageName
                )
            }

            val traversalMatch = resolved.first()
            val match = traversalMatch.element
            if (query.expectedFingerprint.isNotBlank() &&
                match.fingerprint != query.expectedFingerprint
            ) {
                return@withExternalRootWithRetries TypeTextPrecheckOutcome.Failed(
                    status = ScreenInteractionStatus.SCREEN_CHANGED,
                    message = "Target input field changed since inspection; refusing stale text input.",
                    packageName = inspection.packageName
                )
            }

            if (traversalMatch.isCredentialField ||
                SensitiveUiActionPolicy.isBlockedCredentialField(
                    match.snapshot,
                    traversalMatch.ancestorContextFields
                )
            ) {
                return@withExternalRootWithRetries TypeTextPrecheckOutcome.Failed(
                    status = ScreenInteractionStatus.PASSWORD_FIELD,
                    message = "Refusing text input into '${match.displayLabel}': password, OTP, PIN, or payment credential fields are strictly blocked.",
                    packageName = inspection.packageName
                )
            }

            if (!match.snapshot.isVisibleToUser || !match.snapshot.isEnabled || !match.snapshot.isEditable) {
                return@withExternalRootWithRetries TypeTextPrecheckOutcome.Failed(
                    status = ScreenInteractionStatus.NOT_EDITABLE,
                    message = "Element '${match.displayLabel}' in '${inspection.packageName}' is not enabled and editable.",
                    packageName = inspection.packageName
                )
            }

            val isSensitiveInput = SensitiveUiActionPolicy.isSensitiveInputCandidate(
                packageName = inspection.packageName,
                snapshot = match.snapshot,
                ancestorFields = traversalMatch.ancestorContextFields,
                screenActionLabels = inspection.screenActionLabels
            )

            TypeTextPrecheckOutcome.Ready(
                TypeTextInspectionCandidate(
                    packageName = inspection.packageName,
                    fingerprint = match.fingerprint,
                    displayLabel = match.displayLabel,
                    viewId = match.snapshot.viewId,
                    className = match.snapshot.className,
                    isSensitive = isSensitiveInput
                )
            )
        }

        return when (outcome) {
            is RootOutcome.Value -> outcome.value
            is RootOutcome.Failed -> TypeTextPrecheckOutcome.Failed(
                status = outcome.status,
                message = outcome.message
            )
        }
    }

    suspend fun typeText(query: UiElementQuery, textToEnter: String): TypeTextResult {
        if (textToEnter.isBlank()) {
            return TypeTextResult(
                status = ScreenInteractionStatus.ACTION_FAILED,
                message = "Provide explicit non-empty text from the user's request before typing."
            )
        }
        if (!query.hasPrimarySelector()) {
            return TypeTextResult(
                status = ScreenInteractionStatus.ACTION_FAILED,
                message = "Specify text, content_description, view_id, or hint to uniquely identify one editable field."
            )
        }
        val serviceError = checkServiceConnected()
        if (serviceError != null) {
            return TypeTextResult(
                status = ScreenInteractionStatus.SERVICE_DISCONNECTED,
                message = serviceError
            )
        }

        val outcome = withExternalRootWithRetries { root ->
            val rootPkg = safePkg(root)
            if (query.expectedPackage.isNotBlank() &&
                !rootPkg.equals(query.expectedPackage.trim(), ignoreCase = true)
            ) {
                return@withExternalRootWithRetries TypeTextResult(
                    status = ScreenInteractionStatus.SCREEN_CHANGED,
                    message = "Screen changed before typing: expected '${query.expectedPackage}', found '$rootPkg'.",
                    packageName = rootPkg
                )
            }

            val inspection = inspectTree(root, query)
            val resolved = resolvePrioritizedTraversalMatches(inspection.matches)

            if (resolved.isEmpty()) {
                return@withExternalRootWithRetries if (inspection.matchedPasswordOnly) {
                    TypeTextResult(
                        status = ScreenInteractionStatus.PASSWORD_FIELD,
                        message = "Refusing text input: password fields are strictly excluded.",
                        packageName = inspection.packageName
                    )
                } else if (query.expectedFingerprint.isNotBlank()) {
                    TypeTextResult(
                        status = ScreenInteractionStatus.SCREEN_CHANGED,
                        message = "Target input field changed or disappeared before typing.",
                        packageName = inspection.packageName
                    )
                } else {
                    TypeTextResult(
                        status = ScreenInteractionStatus.NOT_FOUND,
                        message = "No matching visible input field found in '${inspection.packageName}'.",
                        packageName = inspection.packageName
                    )
                }
            }

            if (resolved.size > 1) {
                return@withExternalRootWithRetries TypeTextResult(
                    status = ScreenInteractionStatus.AMBIGUOUS,
                    message = "Matched ${resolved.size} elements in '${inspection.packageName}'; refusing ambiguous text input.",
                    packageName = inspection.packageName
                )
            }

            val traversalMatch = resolved.first()
            val match = traversalMatch.element
            if (query.expectedFingerprint.isNotBlank() &&
                match.fingerprint != query.expectedFingerprint
            ) {
                return@withExternalRootWithRetries TypeTextResult(
                    status = ScreenInteractionStatus.SCREEN_CHANGED,
                    message = "Target input field changed since confirmation or inspection; text input aborted.",
                    packageName = inspection.packageName
                )
            }

            if (traversalMatch.isCredentialField ||
                SensitiveUiActionPolicy.isBlockedCredentialField(
                    match.snapshot,
                    traversalMatch.ancestorContextFields
                )
            ) {
                return@withExternalRootWithRetries TypeTextResult(
                    status = ScreenInteractionStatus.PASSWORD_FIELD,
                    message = "Refusing text input into '${match.displayLabel}': password, OTP, PIN, or payment credential fields are strictly blocked.",
                    packageName = inspection.packageName,
                    fieldLabel = match.displayLabel,
                    fingerprint = match.fingerprint
                )
            }

            if (!match.snapshot.isVisibleToUser || !match.snapshot.isEnabled || !match.snapshot.isEditable) {
                return@withExternalRootWithRetries TypeTextResult(
                    status = ScreenInteractionStatus.NOT_EDITABLE,
                    message = "Element '${match.displayLabel}' in '${inspection.packageName}' is not enabled and editable.",
                    packageName = inspection.packageName,
                    fieldLabel = match.displayLabel,
                    fingerprint = match.fingerprint
                )
            }

            var liveOutcome = ScreenInteractionStatus.SCREEN_CHANGED
            var verifiedAfterSet = false
            walkPath(root, match.nodePath) { liveTarget ->
                val livePassword = isPasswordNode(liveTarget)
                val liveSnap = buildSnapshot(0, match.nodePath.size, liveTarget, inspection.packageName, livePassword)
                when {
                    isOwnPackage(liveSnap.packageName) -> {
                        liveOutcome = ScreenInteractionStatus.SCREEN_CHANGED
                        false
                    }
                    livePassword ||
                        SensitiveUiActionPolicy.isBlockedCredentialField(
                            liveSnap,
                            traversalMatch.ancestorContextFields
                        ) -> {
                        liveOutcome = ScreenInteractionStatus.PASSWORD_FIELD
                        false
                    }
                    buildFingerprint(liveSnap) != match.fingerprint -> {
                        liveOutcome = ScreenInteractionStatus.SCREEN_CHANGED
                        false
                    }
                    !liveSnap.isVisibleToUser || !liveSnap.isEnabled || !liveSnap.isEditable -> {
                        liveOutcome = ScreenInteractionStatus.NOT_EDITABLE
                        false
                    }
                    else -> {
                        val setArgs = Bundle().apply {
                            putCharSequence(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                textToEnter
                            )
                        }
                        val performed = try {
                            liveTarget.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)
                        } catch (_: Exception) {
                            false
                        }
                        if (!performed) {
                            liveOutcome = ScreenInteractionStatus.ACTION_FAILED
                            false
                        } else {
                            try {
                                liveTarget.refresh()
                            } catch (_: Exception) {
                                // Ignore if refresh() is unavailable or unmocked.
                            }
                            val directText = try {
                                liveTarget.text?.toString().orEmpty()
                            } catch (_: Exception) {
                                ""
                            }
                            val verified = if (directText == textToEnter) {
                                true
                            } else {
                                verifyFieldTextFromFreshRoot(
                                    expectedPackage = inspection.packageName,
                                    nodePath = match.nodePath,
                                    expectedText = textToEnter
                                )
                            }
                            if (verified) {
                                verifiedAfterSet = true
                                liveOutcome = ScreenInteractionStatus.SUCCESS
                                true
                            } else {
                                liveOutcome = ScreenInteractionStatus.ACTION_FAILED
                                false
                            }
                        }
                    }
                }
            }

            when (liveOutcome) {
                ScreenInteractionStatus.SUCCESS -> TypeTextResult(
                    status = ScreenInteractionStatus.SUCCESS,
                    message = "Entered ${textToEnter.length} characters into '${match.displayLabel}' in ${inspection.packageName} and verified the field updated.",
                    packageName = inspection.packageName,
                    fieldLabel = match.displayLabel,
                    fingerprint = match.fingerprint,
                    verified = verifiedAfterSet
                )
                ScreenInteractionStatus.PASSWORD_FIELD -> TypeTextResult(
                    status = ScreenInteractionStatus.PASSWORD_FIELD,
                    message = "Refusing text input into '${match.displayLabel}': password or credential field detected during live revalidation.",
                    packageName = inspection.packageName,
                    fieldLabel = match.displayLabel,
                    fingerprint = match.fingerprint
                )
                ScreenInteractionStatus.NOT_EDITABLE -> TypeTextResult(
                    status = ScreenInteractionStatus.NOT_EDITABLE,
                    message = "Element '${match.displayLabel}' is no longer enabled and editable.",
                    packageName = inspection.packageName,
                    fieldLabel = match.displayLabel,
                    fingerprint = match.fingerprint
                )
                ScreenInteractionStatus.SCREEN_CHANGED -> TypeTextResult(
                    status = ScreenInteractionStatus.SCREEN_CHANGED,
                    message = "Target field state changed during immediate pre-input revalidation; aborted.",
                    packageName = inspection.packageName,
                    fieldLabel = match.displayLabel,
                    fingerprint = match.fingerprint
                )
                else -> TypeTextResult(
                    status = ScreenInteractionStatus.ACTION_FAILED,
                    message = "Text input into '${match.displayLabel}' failed or could not be verified on the field.",
                    packageName = inspection.packageName,
                    fieldLabel = match.displayLabel,
                    fingerprint = match.fingerprint,
                    verified = false
                )
            }
        }

        return when (outcome) {
            is RootOutcome.Value -> outcome.value
            is RootOutcome.Failed -> TypeTextResult(
                status = outcome.status,
                message = outcome.message
            )
        }
    }

    suspend fun scrollContainer(query: UiElementQuery, directionRaw: String): ScrollUiResult {
        val direction = ScrollDirection.parseOrNull(directionRaw)
            ?: return ScrollUiResult(
                status = ScreenInteractionStatus.ACTION_FAILED,
                message = "Unsupported scroll direction '${directionRaw.trim()}'. Only UP and DOWN are supported."
            )

        val serviceError = checkServiceConnected()
        if (serviceError != null) {
            return ScrollUiResult(
                status = ScreenInteractionStatus.SERVICE_DISCONNECTED,
                message = serviceError,
                direction = direction.name
            )
        }

        val outcome = withExternalRootWithRetries { root ->
            val rootPkg = safePkg(root)
            if (query.expectedPackage.isNotBlank() &&
                !rootPkg.equals(query.expectedPackage.trim(), ignoreCase = true)
            ) {
                return@withExternalRootWithRetries ScrollUiResult(
                    status = ScreenInteractionStatus.SCREEN_CHANGED,
                    message = "Screen changed before scrolling: expected '${query.expectedPackage}', found '$rootPkg'.",
                    packageName = rootPkg,
                    direction = direction.name
                )
            }

            val inspection = inspectTree(root, query)
            val candidates: List<MatchedUiElement>
            var matchedNonScrollable = false

            if (query.hasPrimarySelector()) {
                val matched = resolvePrioritizedMatches(inspection.matches)
                if (matched.isEmpty()) {
                    return@withExternalRootWithRetries if (query.expectedFingerprint.isNotBlank()) {
                        ScrollUiResult(
                            status = ScreenInteractionStatus.SCREEN_CHANGED,
                            message = "Expected scroll container changed or disappeared before scrolling.",
                            packageName = inspection.packageName,
                            direction = direction.name
                        )
                    } else {
                        ScrollUiResult(
                            status = ScreenInteractionStatus.NOT_FOUND,
                            message = "No matching UI container found in '${inspection.packageName}'.",
                            packageName = inspection.packageName,
                            direction = direction.name
                        )
                    }
                }
                val scrollable = matched.filter {
                    it.snapshot.isVisibleToUser && it.snapshot.isEnabled && it.snapshot.isScrollable
                }
                if (scrollable.isEmpty()) {
                    matchedNonScrollable = true
                }
                candidates = scrollable
            } else if (query.className.isNotBlank()) {
                val qCls = query.className.trim()
                candidates = inspection.scrollableElements.filter { el ->
                    val simpleCls = el.snapshot.className.substringAfterLast('.')
                    el.snapshot.className.equals(qCls, ignoreCase = true) ||
                        simpleCls.equals(qCls, ignoreCase = true)
                }
            } else {
                candidates = inspection.scrollableElements
            }

            if (matchedNonScrollable) {
                return@withExternalRootWithRetries ScrollUiResult(
                    status = ScreenInteractionStatus.NOT_SCROLLABLE,
                    message = "Matched UI element in '${inspection.packageName}' is not enabled and scrollable.",
                    packageName = inspection.packageName,
                    direction = direction.name
                )
            }

            if (candidates.isEmpty()) {
                return@withExternalRootWithRetries if (query.expectedFingerprint.isNotBlank()) {
                    ScrollUiResult(
                        status = ScreenInteractionStatus.SCREEN_CHANGED,
                        message = "Expected scroll container is no longer present on '${inspection.packageName}'.",
                        packageName = inspection.packageName,
                        direction = direction.name
                    )
                } else {
                    ScrollUiResult(
                        status = ScreenInteractionStatus.NOT_FOUND,
                        message = "No visible enabled scrollable container found in '${inspection.packageName}'.",
                        packageName = inspection.packageName,
                        direction = direction.name
                    )
                }
            }

            if (candidates.size > 1 && !areStrictlyNestedScrollChain(candidates)) {
                return@withExternalRootWithRetries ScrollUiResult(
                    status = ScreenInteractionStatus.AMBIGUOUS,
                    message = "Found ${candidates.size} scrollable containers in '${inspection.packageName}'; specify view_id, content_description, or class_name to target one container.",
                    packageName = inspection.packageName,
                    direction = direction.name
                )
            }

            // For nested scroll chains (e.g. NestedScrollView -> RecyclerView), try innermost first,
            // falling back to outer ancestor containers only if inner is at its scroll boundary.
            val orderedTargets = if (candidates.size == 1) {
                candidates
            } else {
                candidates.sortedByDescending { it.nodePath.size }
            }

            if (query.expectedFingerprint.isNotBlank() &&
                orderedTargets.none { it.fingerprint == query.expectedFingerprint }
            ) {
                return@withExternalRootWithRetries ScrollUiResult(
                    status = ScreenInteractionStatus.SCREEN_CHANGED,
                    message = "Scroll container changed since inspection; scroll aborted.",
                    packageName = inspection.packageName,
                    direction = direction.name
                )
            }

            val scrollAction = when (direction) {
                ScrollDirection.DOWN -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                ScrollDirection.UP -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            }

            var chosenTarget = orderedTargets.first()
            var liveOutcome = ScreenInteractionStatus.ACTION_FAILED

            for (target in orderedTargets) {
                if (query.expectedFingerprint.isNotBlank() && target.fingerprint != query.expectedFingerprint) {
                    continue
                }
                var attemptStatus = ScreenInteractionStatus.SCREEN_CHANGED
                walkPath(root, target.nodePath) { liveNode ->
                    val livePassword = isPasswordNode(liveNode)
                    val liveSnap = buildSnapshot(0, target.nodePath.size, liveNode, inspection.packageName, livePassword)
                    when {
                        isOwnPackage(liveSnap.packageName) || livePassword -> {
                            attemptStatus = ScreenInteractionStatus.SCREEN_CHANGED
                            false
                        }
                        buildFingerprint(liveSnap) != target.fingerprint -> {
                            attemptStatus = ScreenInteractionStatus.SCREEN_CHANGED
                            false
                        }
                        !liveSnap.isVisibleToUser || !liveSnap.isEnabled || !liveSnap.isScrollable -> {
                            attemptStatus = ScreenInteractionStatus.NOT_SCROLLABLE
                            false
                        }
                        else -> {
                            val scrolled = try {
                                liveNode.performAction(scrollAction)
                            } catch (_: Exception) {
                                false
                            }
                            attemptStatus = if (scrolled) {
                                ScreenInteractionStatus.SUCCESS
                            } else {
                                ScreenInteractionStatus.ACTION_FAILED
                            }
                            scrolled
                        }
                    }
                }
                chosenTarget = target
                liveOutcome = attemptStatus
                if (attemptStatus == ScreenInteractionStatus.SUCCESS ||
                    attemptStatus == ScreenInteractionStatus.SCREEN_CHANGED ||
                    attemptStatus == ScreenInteractionStatus.NOT_SCROLLABLE
                ) {
                    break
                }
            }

            when (liveOutcome) {
                ScreenInteractionStatus.SUCCESS -> ScrollUiResult(
                    status = ScreenInteractionStatus.SUCCESS,
                    message = "Scrolled ${direction.name} on '${chosenTarget.displayLabel}' in ${inspection.packageName}.",
                    packageName = inspection.packageName,
                    targetLabel = chosenTarget.displayLabel,
                    fingerprint = chosenTarget.fingerprint,
                    direction = direction.name
                )
                ScreenInteractionStatus.NOT_SCROLLABLE -> ScrollUiResult(
                    status = ScreenInteractionStatus.NOT_SCROLLABLE,
                    message = "Container '${chosenTarget.displayLabel}' is no longer enabled and scrollable.",
                    packageName = inspection.packageName,
                    targetLabel = chosenTarget.displayLabel,
                    fingerprint = chosenTarget.fingerprint,
                    direction = direction.name
                )
                ScreenInteractionStatus.SCREEN_CHANGED -> ScrollUiResult(
                    status = ScreenInteractionStatus.SCREEN_CHANGED,
                    message = "Scroll container changed during immediate pre-scroll revalidation.",
                    packageName = inspection.packageName,
                    targetLabel = chosenTarget.displayLabel,
                    fingerprint = chosenTarget.fingerprint,
                    direction = direction.name
                )
                else -> ScrollUiResult(
                    status = ScreenInteractionStatus.ACTION_FAILED,
                    message = "Scroll ${direction.name} on '${chosenTarget.displayLabel}' returned false (already at boundary or rejected by container).",
                    packageName = inspection.packageName,
                    targetLabel = chosenTarget.displayLabel,
                    fingerprint = chosenTarget.fingerprint,
                    direction = direction.name
                )
            }
        }

        return when (outcome) {
            is RootOutcome.Value -> outcome.value
            is RootOutcome.Failed -> ScrollUiResult(
                status = outcome.status,
                message = outcome.message,
                direction = direction.name
            )
        }
    }

    private sealed interface RootOutcome<out T> {
        data class Value<T>(val value: T) : RootOutcome<T>
        data class Failed(val status: ScreenInteractionStatus, val message: String) : RootOutcome<Nothing>
    }

    private suspend fun <T> withExternalRootWithRetries(block: (AccessibilityNodeInfo) -> T): RootOutcome<T> {
        val attempts = maxAttempts.coerceAtLeast(1)
        val loadingMaxPolls = waitConfig.maxPolls.coerceAtLeast(1)
        val startMs = nowMs()
        var lastStatus = ScreenInteractionStatus.ACTION_FAILED
        var lastMessage = "Screen content is unavailable."
        var attempt = 0
        var loadingPolls = 0

        while (true) {
            currentCoroutineContext().ensureActive()
            if (!windowSource.isServiceConnected()) {
                return RootOutcome.Failed(
                    status = ScreenInteractionStatus.SERVICE_DISCONNECTED,
                    message = checkServiceConnected() ?: "Accessibility Phone Control is disconnected."
                )
            }
            attempt += 1
            when (val acquired = acquireSingleExternalRoot()) {
                is RootAcquisition.Ready -> {
                    val root = acquired.root
                    var recycledEarly = false
                    try {
                        val loadingIndicators = detectLoadingIndicatorsInRoot(root)
                        if (loadingIndicators.isNotEmpty()) {
                            recycleTracked(root)
                            recycledEarly = true
                            loadingPolls += 1
                            val elapsedMs = (nowMs() - startMs).coerceAtLeast(0L)
                            if (waitConfig.loadingTimeoutMs > 0L &&
                                loadingPolls < loadingMaxPolls &&
                                elapsedMs < waitConfig.loadingTimeoutMs
                            ) {
                                val pollMs = waitConfig.pollIntervalMs.coerceAtLeast(1L)
                                val remaining = (waitConfig.loadingTimeoutMs - elapsedMs).coerceAtLeast(1L)
                                delay(minOf(pollMs, remaining))
                                continue
                            }
                            return RootOutcome.Failed(
                                status = ScreenInteractionStatus.ACTION_FAILED,
                                message = "Timed out after ${elapsedMs}ms while screen remained in loading state (${loadingIndicators.joinToString(", ")})."
                            )
                        }
                        return RootOutcome.Value(block(root))
                    } finally {
                        if (!recycledEarly) {
                            recycleTracked(root)
                        }
                    }
                }
                is RootAcquisition.Error -> {
                    lastStatus = acquired.status
                    lastMessage = acquired.message
                    if (attempt >= attempts) {
                        break
                    }
                    val delayMs = retryDelayMs.coerceAtLeast(0L)
                    if (delayMs > 0L) {
                        delay(delayMs)
                    }
                }
            }
        }
        return RootOutcome.Failed(lastStatus, lastMessage)
    }

    private fun acquireSingleExternalRoot(): RootAcquisition {
        var sawOwnApp = false
        var emptyStubActiveRoot: AccessibilityNodeInfo? = null
        val activeRoot = try {
            windowSource.obtainActiveRoot()
        } catch (e: Exception) {
            return RootAcquisition.Error(
                ScreenInteractionStatus.ACTION_FAILED,
                "Failed to access active window: ${e.message ?: "unknown error"}"
            )
        }

        if (activeRoot != null) {
            val activePkg = safePkg(activeRoot)
            if (!isOwnPackage(activePkg)) {
                if (!isEmptyStubNode(activeRoot)) {
                    return RootAcquisition.Ready(activeRoot, "rootInActiveWindow")
                }
                emptyStubActiveRoot = activeRoot
            } else {
                sawOwnApp = true
                recycleTracked(activeRoot)
            }
        }

        val fallbackRoots = try {
            windowSource.obtainFallbackWindowRoots()
        } catch (_: Exception) {
            emptyList()
        }

        if (fallbackRoots.isEmpty()) {
            if (emptyStubActiveRoot != null) {
                return RootAcquisition.Ready(emptyStubActiveRoot, "rootInActiveWindow")
            }
            return if (sawOwnApp) {
                RootAcquisition.Error(
                    ScreenInteractionStatus.ACTION_FAILED,
                    "JARVIS's own window is excluded from screen interaction."
                )
            } else {
                RootAcquisition.Error(
                    ScreenInteractionStatus.ACTION_FAILED,
                    "Screen content is unavailable because the active window root was null."
                )
            }
        }

        var chosenIndex = -1
        for (i in fallbackRoots.indices) {
            val pkg = safePkg(fallbackRoots[i])
            if (isOwnPackage(pkg)) {
                sawOwnApp = true
                continue
            }
            chosenIndex = i
            break
        }

        return if (chosenIndex >= 0) {
            recycleTracked(emptyStubActiveRoot)
            val chosen = fallbackRoots[chosenIndex]
            for (i in fallbackRoots.indices) {
                if (i != chosenIndex) recycleTracked(fallbackRoots[i])
            }
            RootAcquisition.Ready(chosen, "foreground_windows")
        } else {
            fallbackRoots.forEach { recycleTracked(it) }
            if (emptyStubActiveRoot != null) {
                return RootAcquisition.Ready(emptyStubActiveRoot, "rootInActiveWindow")
            }
            RootAcquisition.Error(
                ScreenInteractionStatus.ACTION_FAILED,
                if (sawOwnApp) {
                    "JARVIS's own window is excluded from screen interaction."
                } else {
                    "No external app window available for screen interaction."
                }
            )
        }
    }

    private fun isEmptyStubNode(node: AccessibilityNodeInfo): Boolean {
        val children = try {
            node.childCount
        } catch (_: Exception) {
            0
        }
        if (children > 0) return false
        val text = try { node.text?.toString().orEmpty() } catch (_: Exception) { "" }
        val desc = try { node.contentDescription?.toString().orEmpty() } catch (_: Exception) { "" }
        val viewId = try { node.viewIdResourceName.orEmpty() } catch (_: Exception) { "" }
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try { node.hintText?.toString().orEmpty() } catch (_: Exception) { "" }
        } else {
            ""
        }
        return text.isBlank() &&
            desc.isBlank() &&
            viewId.isBlank() &&
            hint.isBlank() &&
            !safeBool { node.isClickable } &&
            !safeBool { node.isEditable } &&
            !safeBool { node.isScrollable }
    }

    private fun areStrictlyNestedScrollChain(candidates: List<MatchedUiElement>): Boolean {
        if (candidates.size <= 1) return true
        val sorted = candidates.sortedBy { it.nodePath.size }
        for (i in 0 until sorted.lastIndex) {
            val outer = sorted[i].nodePath
            val inner = sorted[i + 1].nodePath
            if (inner.size <= outer.size) return false
            if (inner.subList(0, outer.size) != outer) return false
        }
        return true
    }

    private fun detectLoadingIndicatorsInRoot(root: AccessibilityNodeInfo): List<String> {
        val indicators = mutableListOf<String>()
        var visited = 0
        val rootPkg = safePkg(root).ifBlank { "unknown" }

        fun scan(node: AccessibilityNodeInfo, depth: Int, parentInWebView: Boolean) {
            if (visited >= maxNodes) return
            val pkg = safePkg(node)
            if (pkg.isNotEmpty() && isOwnPackage(pkg)) return
            visited += 1

            val isPass = isPasswordNode(node)
            val snap = buildSnapshot(visited - 1, depth, node, rootPkg, isPass)
            if (!isPass) {
                ScreenReadinessDetector.detectLoadingIndicator(snap)?.let { indicators.add(it) }
            }

            val inWebView = parentInWebView || snap.className.contains("WebView", ignoreCase = true)
            val effectiveMaxDepth = if (inWebView) maxDepth + ScreenReaderEngine.WEBVIEW_EXTRA_DEPTH else maxDepth
            if (visited >= maxNodes || depth >= effectiveMaxDepth) return

            val childCount = try { node.childCount } catch (_: Exception) { 0 }
            for (i in 0 until childCount) {
                if (visited >= maxNodes) break
                val child = try { node.getChild(i) } catch (_: Exception) { null } ?: continue
                try {
                    scan(child, depth + 1, inWebView)
                } finally {
                    recycleTracked(child)
                }
            }
        }

        scan(root, 0, false)
        return indicators.distinct()
    }

    private fun verifyFieldTextFromFreshRoot(
        expectedPackage: String,
        nodePath: List<Int>,
        expectedText: String
    ): Boolean {
        val fresh = acquireSingleExternalRoot()
        if (fresh !is RootAcquisition.Ready) return false
        return try {
            val freshRoot = fresh.root
            val freshPkg = safePkg(freshRoot)
            if (isOwnPackage(freshPkg) ||
                (expectedPackage.isNotBlank() && !freshPkg.equals(expectedPackage, ignoreCase = true))
            ) {
                false
            } else {
                walkPath(freshRoot, nodePath) { liveNode ->
                    val pkg = safePkg(liveNode).ifBlank { freshPkg }
                    if (isOwnPackage(pkg) || isPasswordNode(liveNode) || !safeBool { liveNode.isEditable }) {
                        false
                    } else {
                        val updated = try {
                            liveNode.text?.toString().orEmpty()
                        } catch (_: Exception) {
                            ""
                        }
                        updated == expectedText
                    }
                }
            }
        } finally {
            recycleTracked(fresh.root)
        }
    }

    private data class StackFrame(
        val path: List<Int>,
        val snapshot: UiNodeSnapshot,
        val isPassword: Boolean
    )

    private fun inspectTree(root: AccessibilityNodeInfo, query: UiElementQuery): TreeInspection {
        val rootPkg = safePkg(root).ifBlank { "unknown" }
        val matches = mutableListOf<TraversalMatch>()
        val screenActionLabels = mutableListOf<String>()
        val scrollableElements = mutableListOf<MatchedUiElement>()
        val loadingIndicators = mutableListOf<String>()
        var matchedPasswordOnly = false
        var visitedCount = 0

        fun traverse(
            node: AccessibilityNodeInfo,
            depth: Int,
            path: List<Int>,
            ancestors: List<StackFrame>,
            parentPassword: Boolean,
            parentInWebView: Boolean
        ) {
            if (visitedCount >= maxNodes) return

            val nodePkg = safePkg(node)
            if (nodePkg.isNotEmpty() && isOwnPackage(nodePkg)) return

            visitedCount += 1
            val isPass = parentPassword || isPasswordNode(node)
            val snap = buildSnapshot(
                index = visitedCount - 1,
                depth = depth,
                node = node,
                fallbackPackage = rootPkg,
                isPasswordNode = isPass
            )
            val inWebView = parentInWebView || snap.className.contains("WebView", ignoreCase = true)

            if (!isPass) {
                ScreenReadinessDetector.detectLoadingIndicator(snap)?.let { loadingIndicators.add(it) }
            }

            // Password fields are completely excluded from interaction; detect id/hint/desc probes to fail closed cleanly.
            if (isPass) {
                if (queryMatchesExcludedPassword(snap, node, query)) {
                    matchedPasswordOnly = true
                }
            } else if (snap.isVisibleToUser) {
                val ancestorContext = ancestors.takeLast(3)
                    .filter { !it.isPassword }
                    .flatMap { frame ->
                        listOf(
                            frame.snapshot.viewId,
                            frame.snapshot.hintText,
                            frame.snapshot.contentDescription,
                            frame.snapshot.text
                        )
                    }
                    .filter { it.isNotBlank() }

                val label = ScreenReaderEngine.sanitizeUntrustedField(buildDisplayLabel(snap), maxLength = 120)
                val fingerprint = buildFingerprint(snap)

                if (snap.isEnabled && (snap.isClickable || snap.className.endsWith("Button", ignoreCase = true))) {
                    if (screenActionLabels.size < 40) {
                        listOf(snap.text, snap.contentDescription, snap.viewId)
                            .filter { it.isNotBlank() }
                            .forEach { if (screenActionLabels.size < 40) screenActionLabels.add(it) }
                    }
                }

                if (snap.isEnabled && snap.isScrollable) {
                    val isScrollSensitive = SensitiveUiActionPolicy.isSensitiveCandidate(
                        packageName = snap.packageName,
                        targetSnapshot = snap
                    )
                    scrollableElements.add(
                        MatchedUiElement(
                            snapshot = snap,
                            fingerprint = fingerprint,
                            displayLabel = label,
                            clickableResolved = false,
                            usedClickableAncestor = false,
                            resolvedClickTargetLabel = label,
                            isSensitive = isScrollSensitive,
                            nodePath = path,
                            clickPath = null
                        )
                    )
                }

                val quality = evaluateMatchQuality(snap, query, inWebView)
                if (quality != MatchQuality.NONE) {
                    val resolution = resolveClickPath(snap, path, ancestors)
                    val sensitive = SensitiveUiActionPolicy.isSensitiveCandidate(
                        packageName = snap.packageName,
                        targetSnapshot = snap,
                        ancestorSnapshot = resolution.ancestorSnapshot
                    )
                    val credentialBlocked = SensitiveUiActionPolicy.isBlockedCredentialField(
                        snapshot = snap,
                        ancestorFields = ancestorContext
                    )
                    matches.add(
                        TraversalMatch(
                            element = MatchedUiElement(
                                snapshot = snap,
                                fingerprint = fingerprint,
                                displayLabel = label,
                                clickableResolved = resolution.clickPath != null,
                                usedClickableAncestor = resolution.usedAncestor,
                                resolvedClickTargetLabel = resolution.ancestorSnapshot?.let { buildDisplayLabel(it) } ?: label,
                                isSensitive = sensitive,
                                nodePath = path,
                                clickPath = resolution.clickPath
                            ),
                            quality = quality,
                            ancestorContextFields = ancestorContext,
                            isCredentialField = credentialBlocked
                        )
                    )
                }
            }

            val effectiveMaxDepth = if (inWebView) maxDepth + ScreenReaderEngine.WEBVIEW_EXTRA_DEPTH else maxDepth
            if (visitedCount >= maxNodes || depth >= effectiveMaxDepth) return

            val nextAncestors = ancestors + StackFrame(path = path, snapshot = snap, isPassword = isPass)
            val childCount = try {
                node.childCount
            } catch (_: Exception) {
                0
            }
            for (i in 0 until childCount) {
                if (visitedCount >= maxNodes) break
                val child = try {
                    node.getChild(i)
                } catch (_: Exception) {
                    null
                } ?: continue
                try {
                    traverse(
                        node = child,
                        depth = depth + 1,
                        path = path + i,
                        ancestors = nextAncestors,
                        parentPassword = isPass,
                        parentInWebView = inWebView
                    )
                } finally {
                    recycleTracked(child)
                }
            }
        }

        traverse(
            node = root,
            depth = 0,
            path = emptyList(),
            ancestors = emptyList(),
            parentPassword = false,
            parentInWebView = false
        )

        return TreeInspection(
            packageName = rootPkg,
            matches = matches,
            matchedPasswordOnly = matchedPasswordOnly,
            screenActionLabels = screenActionLabels,
            scrollableElements = scrollableElements,
            loadingIndicators = loadingIndicators.distinct()
        )
    }

    private data class ClickPathResolution(
        val clickPath: List<Int>?,
        val usedAncestor: Boolean,
        val ancestorSnapshot: UiNodeSnapshot?
    )

    private fun resolveClickPath(
        target: UiNodeSnapshot,
        targetPath: List<Int>,
        ancestors: List<StackFrame>
    ): ClickPathResolution {
        if (!target.isVisibleToUser || !target.isEnabled || target.isPassword) {
            return ClickPathResolution(null, false, null)
        }
        if (target.isClickable) {
            return ClickPathResolution(targetPath, false, null)
        }
        // Safe clickable-ancestor lookup: bounded hops, must be visible, enabled, clickable, non-scrollable, non-password, non-own-package.
        var hops = 0
        for (frame in ancestors.asReversed()) {
            hops += 1
            if (hops > maxAncestorHops) break
            val anc = frame.snapshot
            if (frame.isPassword || anc.isPassword || isOwnPackage(anc.packageName)) continue
            if (anc.isScrollable) continue
            if (anc.isVisibleToUser && anc.isEnabled && anc.isClickable) {
                return ClickPathResolution(
                    clickPath = frame.path,
                    usedAncestor = true,
                    ancestorSnapshot = anc
                )
            }
        }
        return ClickPathResolution(null, false, null)
    }

    private fun evaluateMatchQuality(
        snap: UiNodeSnapshot,
        query: UiElementQuery,
        inWebView: Boolean = false
    ): MatchQuality {
        if (query.className.isNotBlank()) {
            val qCls = query.className.trim()
            val simpleCls = snap.className.substringAfterLast('.')
            if (!snap.className.equals(qCls, ignoreCase = true) &&
                !simpleCls.equals(qCls, ignoreCase = true)
            ) {
                return MatchQuality.NONE
            }
        }

        val checks = mutableListOf<MatchQuality>()

        if (query.viewId.isNotBlank()) {
            val qId = query.viewId.trim()
            val idMatched = when {
                snap.viewId.equals(qId, ignoreCase = true) -> MatchQuality.EXACT
                snap.viewId.endsWith(":id/$qId", ignoreCase = true) ||
                    snap.viewId.endsWith("/$qId", ignoreCase = true) -> MatchQuality.EXACT
                snap.viewId.contains(qId, ignoreCase = true) -> MatchQuality.PARTIAL
                else -> MatchQuality.NONE
            }
            if (idMatched == MatchQuality.NONE) return MatchQuality.NONE
            checks.add(idMatched)
        }

        if (query.text.isNotBlank()) {
            val qText = query.text.trim()
            val textMatched = when {
                snap.text.equals(qText, ignoreCase = true) -> MatchQuality.EXACT
                snap.text.contains(qText, ignoreCase = true) -> MatchQuality.PARTIAL
                inWebView && snap.text.isBlank() && snap.contentDescription.equals(qText, ignoreCase = true) -> MatchQuality.EXACT
                inWebView && snap.text.isBlank() && snap.contentDescription.contains(qText, ignoreCase = true) -> MatchQuality.PARTIAL
                else -> MatchQuality.NONE
            }
            if (textMatched == MatchQuality.NONE) return MatchQuality.NONE
            checks.add(textMatched)
        }

        if (query.contentDescription.isNotBlank()) {
            val qDesc = query.contentDescription.trim()
            val descMatched = when {
                snap.contentDescription.equals(qDesc, ignoreCase = true) -> MatchQuality.EXACT
                snap.contentDescription.contains(qDesc, ignoreCase = true) -> MatchQuality.PARTIAL
                else -> MatchQuality.NONE
            }
            if (descMatched == MatchQuality.NONE) return MatchQuality.NONE
            checks.add(descMatched)
        }

        if (query.hint.isNotBlank()) {
            val qHint = query.hint.trim()
            val hintMatched = when {
                snap.hintText.equals(qHint, ignoreCase = true) -> MatchQuality.EXACT
                snap.hintText.contains(qHint, ignoreCase = true) -> MatchQuality.PARTIAL
                else -> MatchQuality.NONE
            }
            if (hintMatched == MatchQuality.NONE) return MatchQuality.NONE
            checks.add(hintMatched)
        }

        if (checks.isEmpty()) return MatchQuality.NONE
        return if (checks.all { it == MatchQuality.EXACT }) MatchQuality.EXACT else MatchQuality.PARTIAL
    }

    private fun queryMatchesExcludedPassword(
        snap: UiNodeSnapshot,
        node: AccessibilityNodeInfo,
        query: UiElementQuery
    ): Boolean {
        if (query.viewId.isNotBlank()) {
            val qId = query.viewId.trim()
            if (snap.viewId.equals(qId, ignoreCase = true) ||
                snap.viewId.endsWith(":id/$qId", ignoreCase = true) ||
                snap.viewId.endsWith("/$qId", ignoreCase = true) ||
                snap.viewId.contains(qId, ignoreCase = true)
            ) {
                return true
            }
        }
        if (query.hint.isNotBlank() && snap.hintText.contains(query.hint.trim(), ignoreCase = true)) {
            return true
        }
        if (query.contentDescription.isNotBlank()) {
            val rawDesc = try {
                node.contentDescription?.toString().orEmpty()
            } catch (_: Exception) {
                ""
            }
            if (rawDesc.contains(query.contentDescription.trim(), ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    private fun resolvePrioritizedMatches(matches: List<TraversalMatch>): List<MatchedUiElement> {
        if (matches.isEmpty()) return emptyList()
        val exact = matches.filter { it.quality == MatchQuality.EXACT }
        return if (exact.isNotEmpty()) {
            exact.map { it.element }
        } else {
            matches.map { it.element }
        }
    }

    private fun resolvePrioritizedTraversalMatches(matches: List<TraversalMatch>): List<TraversalMatch> {
        if (matches.isEmpty()) return emptyList()
        val exact = matches.filter { it.quality == MatchQuality.EXACT }
        return if (exact.isNotEmpty()) exact else matches
    }

    private fun deduplicateSameClickTarget(matches: List<MatchedUiElement>): List<MatchedUiElement> {
        if (matches.size <= 1) return matches
        val result = mutableListOf<MatchedUiElement>()
        val seenClickPaths = mutableSetOf<List<Int>>()
        for (m in matches) {
            val cp = m.clickPath
            if (cp == null) {
                result.add(m)
            } else if (seenClickPaths.add(cp)) {
                result.add(m)
            }
        }
        return result
    }

    private fun walkPath(
        root: AccessibilityNodeInfo,
        path: List<Int>,
        block: (AccessibilityNodeInfo) -> Boolean
    ): Boolean {
        fun step(current: AccessibilityNodeInfo, index: Int): Boolean {
            if (index >= path.size) {
                return block(current)
            }
            val childIdx = path[index]
            val next = try {
                current.getChild(childIdx)
            } catch (_: Exception) {
                null
            } ?: return false
            return try {
                step(next, index + 1)
            } finally {
                recycleTracked(next)
            }
        }
        return step(root, 0)
    }

    private fun buildSnapshot(
        index: Int,
        depth: Int,
        node: AccessibilityNodeInfo,
        fallbackPackage: String,
        isPasswordNode: Boolean
    ): UiNodeSnapshot {
        val boundsRect = Rect()
        try {
            node.getBoundsInScreen(boundsRect)
        } catch (_: Exception) {
            boundsRect.setEmpty()
        }

        val rawText = try {
            node.text?.toString().orEmpty()
        } catch (_: Exception) {
            ""
        }
        val rawDesc = try {
            node.contentDescription?.toString().orEmpty()
        } catch (_: Exception) {
            ""
        }
        val rawHint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                node.hintText?.toString().orEmpty()
            } catch (_: Exception) {
                ""
            }
        } else {
            ""
        }

        val maskedText = if (isPasswordNode) {
            ScreenReaderEngine.PASSWORD_MASK
        } else {
            ScreenReaderEngine.sanitizeUntrustedField(rawText)
        }
        val maskedDesc = if (isPasswordNode) {
            if (rawDesc.isNotEmpty()) ScreenReaderEngine.PASSWORD_MASK else ""
        } else {
            ScreenReaderEngine.sanitizeUntrustedField(rawDesc)
        }
        val maskedHint = if (isPasswordNode && rawHint.isNotEmpty() && (rawHint == rawText || rawHint == rawDesc)) {
            ScreenReaderEngine.PASSWORD_MASK
        } else {
            ScreenReaderEngine.sanitizeUntrustedField(rawHint)
        }

        val viewId = try {
            node.viewIdResourceName.orEmpty()
        } catch (_: Exception) {
            ""
        }
        val pkg = safePkg(node).ifBlank { fallbackPackage }
        val cls = try {
            node.className?.toString().orEmpty()
        } catch (_: Exception) {
            ""
        }

        return UiNodeSnapshot(
            index = index,
            depth = depth,
            packageName = ScreenReaderEngine.sanitizeUntrustedField(pkg, maxLength = 120),
            className = ScreenReaderEngine.sanitizeUntrustedField(cls, maxLength = 120),
            text = maskedText,
            contentDescription = maskedDesc,
            hintText = maskedHint,
            viewId = ScreenReaderEngine.sanitizeUntrustedField(viewId, maxLength = 160),
            bounds = NodeBounds(
                left = boundsRect.left,
                top = boundsRect.top,
                right = boundsRect.right,
                bottom = boundsRect.bottom
            ),
            isClickable = safeBool { node.isClickable },
            isScrollable = safeBool { node.isScrollable },
            isEditable = safeBool { node.isEditable },
            isPassword = isPasswordNode,
            isVisibleToUser = safeBool { node.isVisibleToUser },
            isEnabled = safeBool { node.isEnabled },
            isFocused = safeBool { node.isFocused }
        )
    }

    private fun checkServiceConnected(): String? {
        if (windowSource.isServiceConnected()) return null
        return if (windowSource.isSettingsEnabled()) {
            "Accessibility Phone Control is enabled in Android Settings, but the service is disconnected. Reconnect JARVIS Phone Control in Accessibility Settings."
        } else {
            "Accessibility Phone Control is not connected. Enable JARVIS Phone Control in Android Accessibility Settings."
        }
    }

    private fun isOwnPackage(pkg: String): Boolean {
        if (pkg.isBlank()) return false
        return pkg.equals(ScreenReaderEngine.JARVIS_APP_PACKAGE, ignoreCase = true) ||
            windowSource.ownPackages.any { it.isNotBlank() && pkg.equals(it, ignoreCase = true) }
    }

    private fun isPasswordNode(node: AccessibilityNodeInfo): Boolean =
        safeBool { node.isPassword } || ScreenReaderEngine.isPasswordInputType(safeInt { node.inputType })

    private fun safePkg(node: AccessibilityNodeInfo): String = try {
        node.packageName?.toString().orEmpty().trim()
    } catch (_: Exception) {
        ""
    }

    private inline fun safeBool(block: () -> Boolean): Boolean = try {
        block()
    } catch (_: Exception) {
        false
    }

    private inline fun safeInt(block: () -> Int): Int = try {
        block()
    } catch (_: Exception) {
        0
    }

    private fun recycleTracked(node: AccessibilityNodeInfo?) {
        if (node == null) return
        try {
            ScreenReaderEngine.recycleNodeSafely(node)
        } finally {
            onNodeRecycled?.invoke(node)
        }
    }
}
