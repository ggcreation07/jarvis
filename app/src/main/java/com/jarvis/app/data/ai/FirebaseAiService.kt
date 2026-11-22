package com.jarvis.app.data.ai

import android.graphics.Bitmap
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class ToolCallExecutionResult(
    val toolName: String,
    val ok: Boolean,
    val summary: String,
    val payload: JsonObject
)

sealed interface AiStreamEvent {
    data class PartialText(val accumulated: String) : AiStreamEvent
    data class ToolStarted(val toolName: String, val label: String) : AiStreamEvent
    data class ToolFinished(val toolName: String, val ok: Boolean, val summary: String) : AiStreamEvent
    data class Completed(val finalText: String) : AiStreamEvent
}

open class FirebaseAiService {

    companion object {
        private const val TAG = "FirebaseAiService"
        private const val AI_TIMEOUT_MS = 60_000L
        private const val MAX_TOOL_ROUNDS = 4
    }

    private val imageModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-nano-banana-2.1",
            generationConfig = generationConfig {
                responseModalities = listOf(ResponseModality.IMAGE)
            }
        )
    }

    private fun buildToolDeclarations(pcPaired: Boolean, tvPaired: Boolean): List<FunctionDeclaration> {
        val list = mutableListOf(
            FunctionDeclaration(
                "get_weather",
                "Get current weather and 3-day forecast for the user's current or pinned location.",
                emptyMap()
            ),
            FunctionDeclaration(
                "get_news",
                "Fetch latest news headlines, optionally filtered by topic.",
                mapOf("topic" to Schema.string("Optional topic such as tech, world, business, science, sports")),
                optionalParameters = listOf("topic")
            ),
            FunctionDeclaration(
                "find_places",
                "Find nearby places like cafes, restaurants, pharmacies, ATMs around the user.",
                mapOf("query" to Schema.string("Place category or name, e.g. 'coffee' or 'pharmacy'"))
            ),
            FunctionDeclaration(
                "get_directions",
                "Compute travel distance and driving time to a destination.",
                mapOf("destination" to Schema.string("Destination address or place name"))
            ),
            FunctionDeclaration(
                "read_webpage",
                "Fetch and extract readable text from a public https:// URL.",
                mapOf("url" to Schema.string("Full https:// webpage URL"))
            ),
            FunctionDeclaration(
                "make_qr_code",
                "Generate a scannable QR code image for text or a URL.",
                mapOf("text" to Schema.string("Exact text or URL to encode in the QR code"))
            ),
            FunctionDeclaration(
                "generate_image",
                "Generate a real AI picture/wallpaper/illustration from a prompt and show it in chat.",
                mapOf("prompt" to Schema.string("Detailed visual description of the image to create"))
            ),
            FunctionDeclaration(
                "remember_fact",
                "Save a durable fact or preference about the user in long-term memory.",
                mapOf("text" to Schema.string("Fact in third person, e.g. 'User prefers concise replies'"))
            ),
            FunctionDeclaration(
                "forget_fact",
                "Remove remembered facts matching text or 'everything'.",
                mapOf("text" to Schema.string("Text to match, or 'everything'"))
            ),
            FunctionDeclaration(
                "manage_schedule",
                "Read, add, or clear items in the user's daily agenda.",
                mapOf(
                    "action" to Schema.enumeration(listOf("get", "add", "clear"), "Schedule action"),
                    "time" to Schema.string("Time label such as '14:00' or 'Morning'"),
                    "task" to Schema.string("Task description")
                ),
                optionalParameters = listOf("time", "task")
            ),
            FunctionDeclaration(
                "manage_routine",
                "List, add, or run a named multi-step routine.",
                mapOf(
                    "action" to Schema.enumeration(listOf("list", "add", "run"), "Routine action"),
                    "name" to Schema.string("Name of the routine"),
                    "prompt" to Schema.string("Steps in plain English when adding a routine"),
                    "time" to Schema.string("Optional HH:mm 24h time to schedule automatically")
                ),
                optionalParameters = listOf("name", "prompt", "time")
            ),
            FunctionDeclaration(
                "set_timer",
                "Start a countdown timer in the device Clock app.",
                mapOf(
                    "seconds" to Schema.integer("Timer duration in seconds"),
                    "label" to Schema.string("Optional timer label")
                ),
                optionalParameters = listOf("label")
            ),
            FunctionDeclaration(
                "set_alarm",
                "Set an alarm in the device Clock app.",
                mapOf(
                    "hour" to Schema.integer("Hour 0-23"),
                    "minute" to Schema.integer("Minute 0-59"),
                    "label" to Schema.string("Optional alarm label")
                ),
                optionalParameters = listOf("label")
            ),
            FunctionDeclaration(
                "device_control",
                "Control phone hardware or settings: flashlight_on, flashlight_off, volume_up, volume_down, mute, unmute, dnd_on, dnd_off, media_play_pause, media_next, media_prev, wifi_settings, bluetooth_settings.",
                mapOf("action" to Schema.string("One of the supported device actions"))
            ),
            FunctionDeclaration(
                "open_application",
                "Open an installed Android application by name.",
                mapOf("name" to Schema.string("App name, e.g. 'Spotify', 'YouTube', 'Calendar'"))
            ),
            FunctionDeclaration(
                "navigate_to",
                "Start turn-by-turn navigation in Google Maps.",
                mapOf(
                    "destination" to Schema.string("Destination place or address"),
                    "mode" to Schema.enumeration(listOf("driving", "walking", "bicycling"), "Travel mode")
                ),
                optionalParameters = listOf("mode")
            ),
            FunctionDeclaration(
                "play_media",
                "Play music or video on Spotify or YouTube Music.",
                mapOf(
                    "query" to Schema.string("Song, artist, or playlist query"),
                    "app" to Schema.enumeration(listOf("spotify", "youtube_music", "youtube"), "Preferred app")
                ),
                optionalParameters = listOf("app")
            ),
            FunctionDeclaration(
                "read_notifications",
                "Read recent active notifications from the phone.",
                mapOf("app" to Schema.string("Optional app filter")),
                optionalParameters = listOf("app")
            ),
            FunctionDeclaration(
                "read_clipboard",
                "Read the current text in the device clipboard.",
                emptyMap()
            ),
            FunctionDeclaration(
                "list_directory",
                "List files in the user's granted Storage Access Framework folder.",
                emptyMap()
            ),
            FunctionDeclaration(
                "read_file",
                "Read text contents of a file from the user's granted folder.",
                mapOf("path" to Schema.string("Filename to read"))
            ),
            FunctionDeclaration(
                "customize_screen",
                "Change JARVIS HUD look, preset (modern/ironman), or color accent.",
                mapOf(
                    "action" to Schema.enumeration(listOf("set_look", "set_accent", "set_background", "reset"), "Screen customization action"),
                    "value" to Schema.string("Target value such as 'ironman', 'modern', 'cyan', 'amber', 'gold', 'grid', 'aurora'")
                ),
                optionalParameters = listOf("value")
            ),
            FunctionDeclaration(
                "control_interface",
                "Navigate JARVIS app panels: open_memory, open_skills, open_capabilities, open_settings, open_customize, open_power, open_remote, clear_chat.",
                mapOf("action" to Schema.string("UI panel action"))
            )
        )
        if (pcPaired) {
            list.add(
                FunctionDeclaration(
                    "pc_task",
                    "Send a task to the user's paired Windows PC.",
                    mapOf(
                        "goal" to Schema.string("Task to run on the PC"),
                        "kind" to Schema.enumeration(listOf("browser", "computer"), "Target environment")
                    ),
                    optionalParameters = listOf("kind")
                )
            )
            list.add(
                FunctionDeclaration(
                    "wake_pc",
                    "Send a Wake-on-LAN packet to wake the paired PC.",
                    emptyMap()
                )
            )
        }
        if (tvPaired) {
            list.add(
                FunctionDeclaration(
                    "tv_control",
                    "Check or wake the configured Android TV on local Wi-Fi.",
                    mapOf("action" to Schema.enumeration(listOf("status", "wake"), "TV action"))
                )
            )
        }
        return list
    }

    open fun streamWithTools(
        modelName: String,
        systemPrompt: String,
        history: List<Pair<String, String>>,
        userMessage: String,
        attachedImage: Bitmap?,
        pcPaired: Boolean,
        tvPaired: Boolean,
        executeTool: suspend (FunctionCallPart) -> ToolCallExecutionResult
    ): Flow<AiStreamEvent> = flow {
        val selectedModel = when (modelName) {
            "gemini-3.5-flash-lite", "gemini-4-argon", "gemini-3.8-flash" -> modelName
            else -> "gemini-3.8-flash"
        }
        val declarations = buildToolDeclarations(pcPaired, tvPaired)
        val model = Firebase.ai.generativeModel(
            modelName = selectedModel,
            systemInstruction = content { text(systemPrompt) },
            tools = listOf(Tool.functionDeclarations(declarations))
        )

        val chatHistory = history.takeLast(12).map { (role, text) ->
            content(if (role == "assistant") "model" else "user") { text(text) }
        }
        val chat = model.startChat(history = chatHistory)

        var nextMessage: Content = content("user") {
            if (attachedImage != null) image(attachedImage)
            text(userMessage)
        }

        val accumulatedFinal = StringBuilder()

        for (round in 0 until MAX_TOOL_ROUNDS) {
            val response = try {
                withTimeout(AI_TIMEOUT_MS) {
                    chat.sendMessage(nextMessage)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Firebase AI generate error", e)
                throw e
            }

            val functionCalls = response.functionCalls
            if (functionCalls.isEmpty()) {
                val text = response.text.orEmpty().trim()
                if (text.isNotEmpty()) {
                    accumulatedFinal.append(text)
                    emit(AiStreamEvent.PartialText(accumulatedFinal.toString()))
                }
                emit(AiStreamEvent.Completed(accumulatedFinal.toString().ifBlank { "Done." }))
                return@flow
            }

            val toolResponses = mutableListOf<FunctionResponsePart>()
            for (call in functionCalls) {
                emit(AiStreamEvent.ToolStarted(call.name, friendlyToolLabel(call.name)))
                val execResult = executeTool(call)
                emit(AiStreamEvent.ToolFinished(call.name, execResult.ok, execResult.summary))
                val respJson = buildJsonObject {
                    put("ok", execResult.ok)
                    put("summary", execResult.summary)
                    execResult.payload.forEach { (k, v) -> put(k, v) }
                }
                toolResponses.add(FunctionResponsePart(call.name, respJson))
            }

            nextMessage = content("user") {
                toolResponses.forEach { part(it) }
            }
        }

        // Final summary round if max rounds reached
        val finalStream = withTimeout(AI_TIMEOUT_MS) {
            chat.sendMessageStream(content("user") {
                text("Summarize what you completed and found cleanly without calling more tools.")
            })
        }
        finalStream.collect { chunk ->
            val piece = chunk.text.orEmpty()
            if (piece.isNotEmpty()) {
                accumulatedFinal.append(piece)
                emit(AiStreamEvent.PartialText(accumulatedFinal.toString()))
            }
        }
        emit(AiStreamEvent.Completed(accumulatedFinal.toString().ifBlank { "Done." }))
    }

    suspend fun generateImage(prompt: String): Bitmap {
        return withTimeout(AI_TIMEOUT_MS) {
            val response = imageModel.generateContent(prompt)
            val part = response.candidates.firstOrNull()
                ?.content?.parts
                ?.filterIsInstance<com.google.firebase.ai.type.ImagePart>()
                ?.firstOrNull()
            part?.image ?: throw IllegalStateException("No image was returned by the model.")
        }
    }

    private fun friendlyToolLabel(name: String): String = when (name) {
        "get_weather" -> "Checking weather"
        "get_news" -> "Reading headlines"
        "find_places" -> "Finding nearby places"
        "get_directions" -> "Calculating route"
        "read_webpage" -> "Reading webpage"
        "make_qr_code" -> "Creating QR code"
        "generate_image" -> "Generating image"
        "remember_fact" -> "Saving to memory"
        "forget_fact" -> "Updating memory"
        "manage_schedule" -> "Checking agenda"
        "manage_routine" -> "Running routine"
        "set_timer" -> "Starting timer"
        "set_alarm" -> "Setting alarm"
        "device_control" -> "Adjusting device"
        "open_application" -> "Opening app"
        "navigate_to" -> "Opening Maps"
        "play_media" -> "Starting media"
        "read_notifications" -> "Reading notifications"
        "read_clipboard" -> "Reading clipboard"
        "list_directory", "read_file" -> "Accessing files"
        "customize_screen" -> "Updating HUD look"
        "control_interface" -> "Opening panel"
        "pc_task", "wake_pc" -> "Contacting paired PC"
        "tv_control" -> "Checking Android TV"
        else -> name
    }
}
