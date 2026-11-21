package com.jarvis.app.domain.brain

import android.graphics.Bitmap
import com.jarvis.app.data.ai.AiStreamEvent
import com.jarvis.app.data.ai.ByoAiClient
import com.jarvis.app.data.ai.FirebaseAiService
import com.jarvis.app.data.local.ConfigStore
import com.jarvis.app.data.local.JarvisRepository
import com.jarvis.app.data.security.SecureSecretStore
import com.jarvis.app.domain.model.BrainConfig
import com.jarvis.app.domain.model.ChatMessageItem
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
        brainConfig: BrainConfig
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
                activities[0] = activities[0].copy(
                    status = if (res.ok) "succeeded" else "failed",
                    summary = res.summary
                )
                emit(TurnProgressEvent.ToolActivitiesUpdated(activities.toList()))
                repository.upsertTaskRecord(
                    TaskRecordItem(
                        id = taskId,
                        objective = cleanInput.take(120),
                        state = if (res.ok) "succeeded" else "suspended",
                        agentId = agentId,
                        stepsCount = 1,
                        summary = res.summary
                    )
                )
                emit(TurnProgressEvent.Finished(res.summary, agentId, activities.toList(), !res.ok))
                return@flow
            }
        }

        // 2. Build rich grounded system prompt with user memory & agent instructions
        val facts = repository.getFactsOnce()
        val systemPrompt = buildSystemPrompt(brainConfig, agentId, facts.map { it.text })
        val priorTurns = history.filter { !it.isError && it.content.isNotBlank() }.map { it.role to it.content }

        // 3. Check if user explicitly selected a BYO provider with a stored key
        val provider = brainConfig.activeProvider
        if (provider !in setOf("auto", "firebase_ai")) {
            val keyName = when (provider) {
                "groq" -> SecureSecretStore.KEY_GROQ
                "gemini" -> SecureSecretStore.KEY_GEMINI
                "openrouter" -> SecureSecretStore.KEY_OPENROUTER
                "nvidia" -> SecureSecretStore.KEY_NVIDIA
                "mistral" -> SecureSecretStore.KEY_MISTRAL
                else -> ""
            }
            val userKey = if (keyName.isNotBlank()) secretStore.getSecret(keyName) else ""
            if (userKey.isBlank()) {
                val msg = "No API key saved for $provider yet. Add one in Settings -> API Keys, or switch Intelligence back to Auto / Firebase AI."
                repository.upsertTaskRecord(TaskRecordItem(taskId, cleanInput.take(120), "suspended", agentId, 0, msg))
                emit(TurnProgressEvent.Finished(msg, agentId, emptyList(), isError = true))
                return@flow
            }
            try {
                val reply = if (provider == "gemini") {
                    byoAiClient.chatDirectGeminiRest(userKey, brainConfig.activeModel, systemPrompt, priorTurns, cleanInput)
                } else {
                    byoAiClient.chatOpenAiCompatible(provider, userKey, brainConfig.activeModel, systemPrompt, priorTurns, cleanInput)
                }
                repository.upsertTaskRecord(TaskRecordItem(taskId, cleanInput.take(120), "succeeded", agentId, 1, reply.take(160)))
                emit(TurnProgressEvent.Finished(reply, agentId, emptyList(), isError = false))
                return@flow
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val errMsg = e.localizedMessage ?: "$provider request failed."
                repository.upsertTaskRecord(TaskRecordItem(taskId, cleanInput.take(120), "suspended", agentId, 0, errMsg))
                emit(TurnProgressEvent.Finished(errMsg, agentId, emptyList(), isError = true))
                return@flow
            }
        }

        // 4. Default & Primary: Built-in Firebase AI Logic with tool calling + streaming
        try {
            var finalReply = ""
            firebaseAiService.streamWithTools(
                modelName = brainConfig.activeModel,
                systemPrompt = systemPrompt,
                history = priorTurns,
                userMessage = cleanInput,
                attachedImage = attachedBitmap,
                pcPaired = brainConfig.pcPaired && brainConfig.pcHost.isNotBlank(),
                tvPaired = brainConfig.tvPaired && brainConfig.tvHost.isNotBlank(),
                executeTool = { call ->
                    toolExecutor.execute(call.name, call.args) { effect ->
                        emit(TurnProgressEvent.SideEffect(effect))
                    }
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
                        val idx = activities.indexOfLast { it.toolName == event.toolName && it.status == "running" }
                        if (idx >= 0) {
                            activities[idx] = activities[idx].copy(
                                status = if (event.ok) "succeeded" else "failed",
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
            repository.upsertTaskRecord(
                TaskRecordItem(
                    id = taskId,
                    objective = cleanInput.take(120),
                    state = "succeeded",
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

    private fun buildSystemPrompt(
        config: BrainConfig,
        agentId: String,
        facts: List<String>
    ): String {
        val now = SimpleDateFormat("EEEE, MMM d, yyyy 'at' h:mm a", Locale.getDefault()).format(Date())
        val agent = JARVIS_AGENTS.firstOrNull { it.id == agentId } ?: JARVIS_AGENTS.first()
        val brevity = if (config.conversationMode) {
            "Conversation mode is ON: keep replies natural, crisp, and 1-2 short spoken sentences unless details are asked for."
        } else {
            "Be helpful, direct, grounded in real tool results, and well structured."
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
            - If the user asks for structured visuals, you may use [CHART]{"title":"...","type":"bar","labels":[...],"values":[...]}[/CHART], [TABLE]{"title":"...","headers":[...],"rows":[[...]]}[/TABLE], [FLOWCHART]{"title":"...","steps":[...]}[/FLOWCHART], or [SCHEDULE]{"title":"...","items":[{"time":"...","task":"..."}]}[/SCHEDULE].
            - For artwork/drawings/wallpapers, call generate_image. For QR codes, call make_qr_code.
            $factsBlock
        """.trimIndent()
    }
}
