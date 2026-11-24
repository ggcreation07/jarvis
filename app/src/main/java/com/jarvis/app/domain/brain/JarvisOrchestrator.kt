package com.jarvis.app.domain.brain

import android.graphics.Bitmap
import com.jarvis.app.data.ai.AiStreamEvent
import com.jarvis.app.data.ai.ByoAiClient
import com.jarvis.app.data.ai.ByoStreamEvent
import com.jarvis.app.data.ai.FirebaseAiService
import com.jarvis.app.data.local.ConfigStore
import com.jarvis.app.data.local.JarvisRepository
import com.jarvis.app.data.security.SecureSecretStore
import com.jarvis.app.domain.model.BYO_LLM_PROVIDER_IDS
import com.jarvis.app.domain.model.BrainConfig
import com.jarvis.app.domain.model.ChatMessageItem
import com.jarvis.app.domain.model.FIREBASE_AI_MODELS
import com.jarvis.app.domain.model.JARVIS_AGENTS
import com.jarvis.app.domain.model.TaskRecordItem
import com.jarvis.app.domain.model.ToolActivityItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface TurnProgressEvent {
    data class AgentSelected(val agentId: String) : TurnProgressEvent
    data class StreamingText(val text: String) : TurnProgressEvent
    data class ToolActivitiesUpdated(val activities: List<ToolActivityItem>) : TurnProgressEvent
    data class SideEffect(val effect: ToolSideEffects) : TurnProgressEvent
    data class Finished(
        val replyText: String,
        val agentId: String,
        val activities: List<ToolActivityItem>,
        val isError: Boolean = false
    ) : TurnProgressEvent
}

class JarvisOrchestrator(
    private val repository: JarvisRepository,
    private val configStore: ConfigStore,
    private val secretStore: SecureSecretStore,
    private val firebaseAiService: FirebaseAiService,
    private val byoAiClient: ByoAiClient,
    private val toolExecutor: ToolExecutor
) {

    fun parseAgentMention(rawText: String, fallbackAgentId: String): Pair<String, String> {
        val trimmed = rawText.trim()
        val match = Regex("^@([a-zA-Z]+)\\s+([\\s\\S]+)$").find(trimmed)
        if (match != null) {
            val handle = "@${match.groupValues[1].lowercase()}"
            val found = JARVIS_AGENTS.firstOrNull { it.handle == handle }
            if (found != null) {
                return found.id to match.groupValues[2].trim()
            }
        }
        return fallbackAgentId to trimmed
    }

    fun executeTurn(
        rawUserInput: String,
        attachedBitmap: Bitmap?,
        history: List<ChatMessageItem>,
        brainConfig: BrainConfig,
        isVoiceTurn: Boolean = false
    ): Flow<TurnProgressEvent> = flow {
        val (agentId, cleanInput) = parseAgentMention(rawUserInput, brainConfig.selectedAgentId)
        emit(TurnProgressEvent.AgentSelected(agentId))

        val taskId = "task_${System.currentTimeMillis()}"
        val activities = mutableListOf<ToolActivityItem>()
        repository.upsertTaskRecord(
            TaskRecordItem(
                id = taskId,
                objective = cleanInput.take(120),
                state = "running",
                agentId = agentId,
                stepsCount = 0,
                summary = "Processing request…"
            )
        )

        // 0. Resolve any pending execution-level confirmation before routing new commands
        if (attachedBitmap != null) {
            toolExecutor.clearPendingConfirmation()
        } else {
            when (
                val confOutcome = toolExecutor.handleConfirmationInput(cleanInput) { effect ->
                    emit(TurnProgressEvent.SideEffect(effect))
                }
            ) {
                is ConfirmationTurnOutcome.Cancelled -> {
                    repository.upsertTaskRecord(
                        TaskRecordItem(taskId, cleanInput.take(120), "cancelled", agentId, 0, confOutcome.replyText.take(160))
                    )
                    emit(TurnProgressEvent.Finished(confOutcome.replyText, agentId, emptyList(), isError = false))
                    return@flow
                }
                is ConfirmationTurnOutcome.Expired -> {
                    repository.upsertTaskRecord(
                        TaskRecordItem(taskId, cleanInput.take(120), "suspended", agentId, 0, confOutcome.replyText.take(160))
                    )
                    emit(TurnProgressEvent.Finished(confOutcome.replyText, agentId, emptyList(), isError = false))
                    return@flow
                }
                is ConfirmationTurnOutcome.NeedsClarification -> {
                    repository.upsertTaskRecord(
                        TaskRecordItem(taskId, cleanInput.take(120), "awaiting_confirmation", agentId, 0, confOutcome.replyText.take(160))
                    )
                    emit(TurnProgressEvent.Finished(confOutcome.replyText, agentId, emptyList(), isError = false))
                    return@flow
                }
                is ConfirmationTurnOutcome.Executed -> {
                    val res = confOutcome.result
                    val toolName = confOutcome.pending.binding.toolName
                    val act = ToolActivityItem(
                        id = "act_${System.currentTimeMillis()}",
                        toolName = toolName,
                        label = toolName.replace("_", " "),
                        status = if (res.ok) "succeeded" else "failed",
                        summary = res.summary
                    )
                    activities.add(act)
                    emit(TurnProgressEvent.ToolActivitiesUpdated(activities.toList()))
                    repository.upsertTaskRecord(
                        TaskRecordItem(
                            id = taskId,
                            objective = cleanInput.take(120),
                            state = if (res.ok) "succeeded" else "suspended",
                            agentId = agentId,
                            stepsCount = 1,
                            summary = res.summary.take(160)
                        )
                    )
                    emit(TurnProgressEvent.Finished(res.summary, agentId, activities.toList(), isError = !res.ok))
                    return@flow
                }
                ConfirmationTurnOutcome.None -> Unit
            }
        }

        // 1. Fast deterministic local match when there is no image attached
        if (attachedBitmap == null) {
            val direct = DirectCommandMatcher.match(cleanInput)
            if (direct is DirectActionMatch.ToolCall) {
                val actId = "act_${System.currentTimeMillis()}"
                activities.add(ToolActivityItem(actId, direct.toolName, direct.toolName.replace("_", " "), "running"))
                emit(TurnProgressEvent.ToolActivitiesUpdated(activities.toList()))
                val jsonArgs = direct.args.mapValues { JsonPrimitive(it.value) }
                val res = toolExecutor.execute(direct.toolName, jsonArgs) { effect ->
                    emit(TurnProgressEvent.SideEffect(effect))
                }
                val awaitingConfirmation = res.isConfirmationRequired()
                activities[0] = activities[0].copy(
                    status = when {
                        res.ok -> "succeeded"
                        awaitingConfirmation -> "awaiting_confirmation"
                        else -> "failed"
                    },
                    summary = res.summary
                )
                emit(TurnProgressEvent.ToolActivitiesUpdated(activities.toList()))
                repository.upsertTaskRecord(
                    TaskRecordItem(
                        id = taskId,
                        objective = cleanInput.take(120),
                        state = when {
                            res.ok -> "succeeded"
                            awaitingConfirmation -> "awaiting_confirmation"
                            else -> "suspended"
                        },
                        agentId = agentId,
                        stepsCount = 1,
                        summary = res.summary
                    )
                )
                emit(
                    TurnProgressEvent.Finished(
                        replyText = res.summary,
                        agentId = agentId,
                        activities = activities.toList(),
                        isError = !res.ok && !awaitingConfirmation
                    )
                )
                return@flow
            }
        }

        // 2. Build rich grounded system prompt with user memory & agent instructions
        val facts = repository.getFactsOnce()
        val systemPrompt = buildSystemPrompt(
            config = brainConfig,
            agentId = agentId,
            facts = facts.map { it.text },
            isVoiceTurn = isVoiceTurn
        )
        val priorTurns = history.filter { !it.isError && it.content.isNotBlank() }.map { it.role to it.content }

        // 3. Check if user explicitly selected a BYO provider with a stored key
        val provider = brainConfig.activeProvider
        if (provider !in setOf("auto", "firebase_ai")) {
            if (provider !in BYO_LLM_PROVIDER_IDS) {
                val msg = "Unsupported provider '$provider'. Select a valid provider in Settings -> Intelligence & Model Routing."
                repository.upsertTaskRecord(TaskRecordItem(taskId, cleanInput.take(120), "suspended", agentId, 0, msg))
                emit(TurnProgressEvent.Finished(msg, agentId, emptyList(), isError = true))
                return@flow
            }
            val keyName = when (provider) {
                "groq" -> SecureSecretStore.KEY_GROQ
                "gemini" -> SecureSecretStore.KEY_GEMINI
                "openrouter" -> SecureSecretStore.KEY_OPENROUTER
                "nvidia" -> SecureSecretStore.KEY_NVIDIA
                "mistral" -> SecureSecretStore.KEY_MISTRAL
                "openai" -> SecureSecretStore.KEY_OPENAI
                "anthropic" -> SecureSecretStore.KEY_ANTHROPIC
                "xai" -> SecureSecretStore.KEY_XAI
                else -> ""
            }
            val userKey = if (keyName.isNotBlank()) secretStore.getSecret(keyName) else ""
            if (userKey.isBlank()) {
                val msg = "No API key saved for $provider yet. Add one in Settings -> API Keys, or switch Intelligence back to Auto / Firebase AI."
                repository.upsertTaskRecord(TaskRecordItem(taskId, cleanInput.take(120), "suspended", agentId, 0, msg))
                emit(TurnProgressEvent.Finished(msg, agentId, emptyList(), isError = true))
                return@flow
            }
            if (attachedBitmap != null) {
                val msg = "Image attachments require Auto / Firebase AI Logic (built-in). Switch provider or remove the image attachment."
                repository.upsertTaskRecord(TaskRecordItem(taskId, cleanInput.take(120), "suspended", agentId, 0, msg))
                emit(TurnProgressEvent.Finished(msg, agentId, emptyList(), isError = true))
                return@flow
            }
            val selectedModel = brainConfig.activeModel.trim()
            if (selectedModel.isEmpty()) {
                val msg = "No model selected for $provider. Open Settings -> Intelligence & Model Routing to fetch and select a model."
                repository.upsertTaskRecord(TaskRecordItem(taskId, cleanInput.take(120), "suspended", agentId, 0, msg))
                emit(TurnProgressEvent.Finished(msg, agentId, emptyList(), isError = true))
                return@flow
            }
            val cached = configStore.loadProviderModelsCache(provider)
            if (cached != null && cached.models.isNotEmpty() && cached.models.none { it.id == selectedModel }) {
                val msg = "Selected model '$selectedModel' is unavailable for $provider. Please open Settings and select another model."
                repository.upsertTaskRecord(TaskRecordItem(taskId, cleanInput.take(120), "suspended", agentId, 0, msg))
                emit(TurnProgressEvent.Finished(msg, agentId, emptyList(), isError = true))
                return@flow
            }
            try {
                val reply = when (provider) {
                    "gemini" -> byoAiClient.chatDirectGeminiRest(userKey, selectedModel, systemPrompt, priorTurns, cleanInput)
                    "anthropic" -> {
                        var completed = ""
                        byoAiClient.streamAnthropicMessages(userKey, selectedModel, systemPrompt, priorTurns, cleanInput)
                            .collect { event ->
                                when (event) {
                                    is ByoStreamEvent.PartialText -> emit(TurnProgressEvent.StreamingText(event.accumulated))
                                    is ByoStreamEvent.Completed -> completed = event.finalText
                                }
                            }
                        completed.ifBlank { "No text returned." }
                    }
                    else -> {
                        var completed = ""
                        byoAiClient.streamOpenAiCompatible(provider, userKey, selectedModel, systemPrompt, priorTurns, cleanInput)
                            .collect { event ->
                                when (event) {
                                    is ByoStreamEvent.PartialText -> emit(TurnProgressEvent.StreamingText(event.accumulated))
                                    is ByoStreamEvent.Completed -> completed = event.finalText
                                }
                            }
                        completed.ifBlank { "No text returned." }
                    }
                }
                repository.upsertTaskRecord(TaskRecordItem(taskId, cleanInput.take(120), "succeeded", agentId, 1, reply.take(160)))
                emit(TurnProgressEvent.Finished(reply, agentId, emptyList(), isError = false))
                return@flow
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val rawErr = e.localizedMessage ?: "$provider request failed."
                val errMsg = byoAiClient.sanitizeSecret(rawErr, userKey)
                repository.upsertTaskRecord(TaskRecordItem(taskId, cleanInput.take(120), "suspended", agentId, 0, errMsg))
                emit(TurnProgressEvent.Finished(errMsg, agentId, emptyList(), isError = true))
                return@flow
            }
        }

        // 4. Default & Primary: Built-in Firebase AI Logic with tool calling + streaming
        if (brainConfig.activeModel !in FIREBASE_AI_MODELS) {
            val msg = "Model '${brainConfig.activeModel}' is not available on built-in Firebase AI Logic. Select a Firebase AI model in Settings."
            repository.upsertTaskRecord(TaskRecordItem(taskId, cleanInput.take(120), "suspended", agentId, 0, msg))
            emit(TurnProgressEvent.Finished(msg, agentId, emptyList(), isError = true))
            return@flow
        }
        try {
            var finalReply = ""
            val confirmationPendingCalls = mutableListOf<String>()
            firebaseAiService.streamWithTools(
                modelName = brainConfig.activeModel,
                systemPrompt = systemPrompt,
                history = priorTurns,
                userMessage = cleanInput,
                attachedImage = attachedBitmap,
                pcPaired = brainConfig.pcPaired && brainConfig.pcHost.isNotBlank(),
                tvPaired = brainConfig.tvPaired && brainConfig.tvHost.isNotBlank(),
                executeTool = { call ->
                    val res = toolExecutor.execute(call.name, call.args) { effect ->
                        emit(TurnProgressEvent.SideEffect(effect))
                    }
                    if (res.isConfirmationRequired()) {
                        confirmationPendingCalls.add(call.name)
                    }
                    res
                }
            ).collect { event ->
                when (event) {
                    is AiStreamEvent.ToolStarted -> {
                        activities.add(
                            ToolActivityItem(
                                id = "${event.toolName}_${activities.size}",
                                toolName = event.toolName,
                                label = event.label,
                                status = "running"
                            )
                        )
                        emit(TurnProgressEvent.ToolActivitiesUpdated(activities.toList()))
                    }
                    is AiStreamEvent.ToolFinished -> {
                        val awaitingConfirmation = confirmationPendingCalls.remove(event.toolName)
                        val idx = activities.indexOfLast { it.toolName == event.toolName && it.status == "running" }
                        if (idx >= 0) {
                            activities[idx] = activities[idx].copy(
                                status = when {
                                    event.ok -> "succeeded"
                                    awaitingConfirmation -> "awaiting_confirmation"
                                    else -> "failed"
                                },
                                summary = event.summary
                            )
                            emit(TurnProgressEvent.ToolActivitiesUpdated(activities.toList()))
                        }
                    }
                    is AiStreamEvent.PartialText -> {
                        emit(TurnProgressEvent.StreamingText(event.accumulated))
                    }
                    is AiStreamEvent.Completed -> {
                        finalReply = event.finalText
                    }
                }
            }
            val finalState = if (activities.any { it.status == "awaiting_confirmation" }) {
                "awaiting_confirmation"
            } else {
                "succeeded"
            }
            repository.upsertTaskRecord(
                TaskRecordItem(
                    id = taskId,
                    objective = cleanInput.take(120),
                    state = finalState,
                    agentId = agentId,
                    stepsCount = activities.size.coerceAtLeast(1),
                    summary = finalReply.take(180)
                )
            )
            emit(TurnProgressEvent.Finished(finalReply, agentId, activities.toList(), isError = false))
        } catch (e: CancellationException) {
            repository.upsertTaskRecord(
                TaskRecordItem(taskId, cleanInput.take(120), "cancelled", agentId, activities.size, "Stopped.")
            )
            throw e
        } catch (e: Exception) {
            val errMsg = e.localizedMessage ?: "Couldn't reach Firebase AI right now."
            repository.upsertTaskRecord(
                TaskRecordItem(taskId, cleanInput.take(120), "suspended", agentId, activities.size, errMsg)
            )
            emit(TurnProgressEvent.Finished(errMsg, agentId, activities.toList(), isError = true))
        }
    }

    internal fun buildSystemPrompt(
        config: BrainConfig,
        agentId: String,
        facts: List<String>,
        isVoiceTurn: Boolean = false
    ): String {
        val now = SimpleDateFormat("EEEE, MMM d, yyyy 'at' h:mm a", Locale.getDefault()).format(Date())
        val agent = JARVIS_AGENTS.firstOrNull { it.id == agentId } ?: JARVIS_AGENTS.first()
        val brevity = when {
            isVoiceTurn ->
                "Voice conversation is ACTIVE: reply naturally in the user's language (Hindi, Marathi, English, or Hinglish). " +
                    "Prefer Devanagari script for Hindi and Marathi replies when appropriate, and natural Roman Hinglish when the user speaks in Romanized Hinglish. " +
                    "Keep spoken replies concise, warm, and conversational (1-3 short sentences unless more detail is explicitly requested)."
            config.conversationMode ->
                "Conversation mode is ON: keep replies natural, crisp, and 1-2 short spoken sentences unless details are asked for."
            else ->
                "Be helpful, direct, grounded in real tool results, and well structured."
        }
        val formattingRule = if (isVoiceTurn) {
            "- Format strictly for spoken audio: never include raw URLs, Markdown links, [CHART]/[TABLE]/[FLOWCHART]/[SCHEDULE] tags, bullet symbols, or unnecessary markdown formatting."
        } else {
            "- If the user asks for structured visuals, you may use [CHART]{\"title\":\"...\",\"type\":\"bar\",\"labels\":[...],\"values\":[...]}[/CHART], [TABLE]{\"title\":\"...\",\"headers\":[...],\"rows\":[[...]]}[/TABLE], [FLOWCHART]{\"title\":\"...\",\"steps\":[...]}[/FLOWCHART], or [SCHEDULE]{\"title\":\"...\",\"items\":[{\"time\":\"...\",\"task\":\"...\"}]}[/SCHEDULE]."
        }
        val factsBlock = if (facts.isNotEmpty()) {
            "\nKnown user facts:\n" + facts.take(15).joinToString("\n") { "- $it" }
        } else ""

        return """
            You are J.A.R.V.I.S., an intelligent Android assistant and HUD.
            Current local time: $now.
            Active specialist role: ${agent.name} (${agent.systemHint}).
            $brevity
            Rules:
            - Never claim you performed a device action, set a timer/alarm, checked weather, read notifications, or changed a setting unless the corresponding tool returned ok=true.
            - If a tool returns status='confirmation_required', explain clearly what will happen and ask the user for explicit confirmation, and never claim the action was already executed.
            $formattingRule
            - For artwork/drawings/wallpapers, call generate_image. For QR codes, call make_qr_code.
            $factsBlock
        """.trimIndent()
    }
}
