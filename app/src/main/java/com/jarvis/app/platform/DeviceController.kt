package com.jarvis.app.platform

import android.Manifest
import android.app.NotificationManager
import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.jarvis.app.domain.model.AgendaItem
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class ActionOutcome(
    val ok: Boolean,
    val summary: String
)

class DeviceController(private val context: Context) {

    private var torchEnabled = false

    fun toggleFlashlight(enable: Boolean? = null): ActionOutcome {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cm.cameraIdList.firstOrNull { id ->
                cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return ActionOutcome(false, "No camera flash hardware found on this device.")
            val next = enable ?: !torchEnabled
            cm.setTorchMode(cameraId, next)
            torchEnabled = next
            ActionOutcome(true, if (next) "Flashlight turned on." else "Flashlight turned off.")
        } catch (e: Exception) {
            ActionOutcome(false, "Couldn't toggle flashlight: ${e.message ?: "unavailable"}")
        }
    }

    fun adjustVolume(direction: String, levelPercent: Int? = null): ActionOutcome {
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            when {
                levelPercent != null -> {
                    val target = ((levelPercent.coerceIn(0, 100) / 100f) * max).toInt()
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
                    ActionOutcome(true, "Media volume set to ${levelPercent.coerceIn(0, 100)}%.")
                }
                direction == "up" -> {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    ActionOutcome(true, "Turned media volume up.")
                }
                direction == "down" -> {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    ActionOutcome(true, "Turned media volume down.")
                }
                direction == "mute" -> {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                    ActionOutcome(true, "Media volume muted.")
                }
                direction == "unmute" -> {
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                    ActionOutcome(true, "Media volume unmuted.")
                }
                else -> ActionOutcome(false, "Unsupported volume adjustment.")
            }
        } catch (e: Exception) {
            ActionOutcome(false, "Couldn't adjust volume: ${e.message}")
        }
    }

    fun setDoNotDisturb(enable: Boolean): ActionOutcome {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            openSettingsScreen(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            return ActionOutcome(false, "Opened Do Not Disturb access settings so you can grant permission.")
        }
        return try {
            nm.setInterruptionFilter(
                if (enable) NotificationManager.INTERRUPTION_FILTER_PRIORITY
                else NotificationManager.INTERRUPTION_FILTER_ALL
            )
            ActionOutcome(true, if (enable) "Do Not Disturb enabled." else "Do Not Disturb disabled.")
        } catch (e: Exception) {
            ActionOutcome(false, "Couldn't update Do Not Disturb: ${e.message}")
        }
    }

    fun sendMediaKey(action: String): ActionOutcome {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val code = when (action) {
            "play_pause", "pause", "play" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> return ActionOutcome(false, "Unknown media control: $action")
        }
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return ActionOutcome(true, "Sent media $action.")
    }

    fun openApplication(nameOrPackage: String): ActionOutcome {
        val query = nameOrPackage.trim()
        if (query.isEmpty()) return ActionOutcome(false, "Tell me which app to open.")
        val pm = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(mainIntent, 0)
        val match = apps.firstOrNull {
            val label = it.loadLabel(pm).toString()
            label.equals(query, ignoreCase = true) || it.activityInfo.packageName.equals(query, ignoreCase = true)
        } ?: apps.firstOrNull {
            val label = it.loadLabel(pm).toString()
            label.contains(query, ignoreCase = true)
        }
        if (match == null) return ActionOutcome(false, "I couldn't find an app matching \"$query\".")
        val launch = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return ActionOutcome(false, "That app can't be launched directly.")
        return try {
            context.startActivity(launch)
            ActionOutcome(true, "Opened ${match.loadLabel(pm)}.")
        } catch (e: Exception) {
            ActionOutcome(false, "Couldn't open ${match.loadLabel(pm)}: ${e.message}")
        }
    }

    fun openUrl(url: String): ActionOutcome {
        val normalized = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(normalized))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ActionOutcome(true, "Opened $normalized")
        } catch (e: Exception) {
            ActionOutcome(false, "Couldn't open URL: ${e.message}")
        }
    }

    fun openSettingsScreen(action: String): ActionOutcome {
        return try {
            val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ActionOutcome(true, "Opened Android settings.")
        } catch (e: Exception) {
            ActionOutcome(false, "Couldn't open settings: ${e.message}")
        }
    }

    fun setTimer(seconds: Int, label: String = ""): ActionOutcome {
        if (seconds <= 0) return ActionOutcome(false, "Timer length must be at least 1 second.")
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val mins = seconds / 60
            val rem = seconds % 60
            val formatted = if (mins > 0 && rem == 0) "$mins minute" else "$seconds second"
            ActionOutcome(true, "Started a $formatted timer${if (label.isNotBlank()) " for $label" else ""}.")
        } catch (e: Exception) {
            ActionOutcome(false, "No Clock app available to start a timer: ${e.message}")
        }
    }

    fun setAlarm(hour: Int, minute: Int, label: String = "", days: List<Int> = emptyList()): ActionOutcome {
        if (hour !in 0..23 || minute !in 0..59) {
            return ActionOutcome(false, "Invalid alarm time ($hour:$minute).")
        }
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                if (days.isNotEmpty()) putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days))
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ActionOutcome(true, String.format(Locale.getDefault(), "Alarm set for %02d:%02d%s.", hour, minute, if (label.isNotBlank()) " ($label)" else ""))
        } catch (e: Exception) {
            ActionOutcome(false, "Couldn't set alarm: ${e.message}")
        }
    }

    fun showAlarms(): ActionOutcome = openClockView(AlarmClock.ACTION_SHOW_ALARMS, "Opened Clock alarms.")
    fun showTimers(): ActionOutcome {
        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) AlarmClock.ACTION_SHOW_TIMERS else AlarmClock.ACTION_SHOW_ALARMS
        return openClockView(action, "Opened Clock timers.")
    }

    private fun openClockView(action: String, successMsg: String): ActionOutcome {
        return try {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ActionOutcome(true, successMsg)
        } catch (e: Exception) {
            ActionOutcome(false, "Couldn't open Clock: ${e.message}")
        }
    }

    fun navigateMaps(destination: String, mode: String = "driving"): ActionOutcome {
        if (destination.isBlank()) return ActionOutcome(false, "Where would you like directions to?")
        val modeCode = when (mode.lowercase()) {
            "walking", "walk" -> "w"
            "bicycling", "bike", "cycling" -> "b"
            else -> "d"
        }
        return try {
            val uri = Uri.parse("google.navigation:q=${Uri.encode(destination)}&mode=$modeCode")
            val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ActionOutcome(true, "Started navigation to $destination.")
        } catch (_: Exception) {
            openUrl("https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(destination)}")
        }
    }

    fun playMedia(query: String, app: String = ""): ActionOutcome {
        if (query.isBlank()) return ActionOutcome(false, "Tell me what you want to play.")
        val intent = Intent("android.media.action.MEDIA_PLAY_FROM_SEARCH").apply {
            putExtra(SearchManager.QUERY, query)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            when (app.lowercase()) {
                "spotify" -> setPackage("com.spotify.music")
                "youtube_music" -> setPackage("com.google.android.apps.youtube.music")
                "youtube" -> setPackage("com.google.android.youtube")
            }
        }
        return try {
            context.startActivity(intent)
            ActionOutcome(true, "Playing \"$query\"${if (app.isNotBlank()) " on $app" else ""}.")
        } catch (_: Exception) {
            openUrl("https://www.youtube.com/results?search_query=${Uri.encode(query)}")
        }
    }

    fun dialNumberOrTarget(target: String): ActionOutcome {
        val clean = target.trim()
        if (clean.isEmpty()) return ActionOutcome(false, "Who should I call?")
        return try {
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(clean)}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ActionOutcome(true, "Opened phone dialer for $clean.")
        } catch (e: Exception) {
            ActionOutcome(false, "Couldn't open dialer: ${e.message}")
        }
    }

    fun composeMessage(to: String, body: String, app: String = "sms"): ActionOutcome {
        return try {
            if (app.equals("whatsapp", ignoreCase = true) && to.any { it.isDigit() }) {
                val digits = to.filter { it.isDigit() }
                val uri = Uri.parse("https://wa.me/$digits?text=${Uri.encode(body)}")
                context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ActionOutcome(true, "Opened WhatsApp chat with $to.")
            } else {
                val uri = Uri.parse("smsto:${Uri.encode(to)}")
                val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
                    putExtra("sms_body", body)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                ActionOutcome(true, "Prepared message to $to.")
            }
        } catch (e: Exception) {
            ActionOutcome(false, "Couldn't prepare message: ${e.message}")
        }
    }

    fun readClipboard(): String {
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    fun copyToClipboard(label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    fun listGrantedFolder(treeUriStr: String): List<String> {
        if (treeUriStr.isBlank()) return emptyList()
        return try {
            val treeUri = Uri.parse(treeUriStr)
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            val result = mutableListOf<String>()
            context.contentResolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
                null,
                null,
                null
            )?.use { cursor ->
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext() && result.size < 40) {
                    val name = if (nameCol >= 0) cursor.getString(nameCol) else continue
                    val mime = if (mimeCol >= 0) cursor.getString(mimeCol) else ""
                    val suffix = if (mime == DocumentsContract.Document.MIME_TYPE_DIR) "/" else ""
                    result.add("$name$suffix")
                }
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun readGrantedFileText(treeUriStr: String, fileName: String): ActionOutcome {
        if (treeUriStr.isBlank()) {
            return ActionOutcome(false, "No folder granted yet. Pick a folder in Settings -> Files first.")
        }
        return try {
            val treeUri = Uri.parse(treeUriStr)
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            var matchedDocId: String? = null
            var matchedName = fileName
            context.contentResolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol)
                    if (name.equals(fileName, ignoreCase = true) || name.contains(fileName, ignoreCase = true)) {
                        matchedDocId = cursor.getString(idCol)
                        matchedName = name
                        break
                    }
                }
            }
            val id = matchedDocId ?: return ActionOutcome(false, "File \"$fileName\" wasn't found in your granted folder.")
            val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
            val content = context.contentResolver.openInputStream(fileUri)?.bufferedReader()?.use { reader ->
                val buf = CharArray(8000)
                val read = reader.read(buf)
                if (read > 0) String(buf, 0, read) else ""
            }.orEmpty()
            ActionOutcome(true, "Contents of $matchedName:\n$content")
        } catch (e: Exception) {
            ActionOutcome(false, "Couldn't read file: ${e.message}")
        }
    }

    fun hasCalendarPermission(): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    }

    fun readTodaysCalendarEvents(): List<AgendaItem> {
        if (!hasCalendarPermission()) return emptyList()
        val cal = Calendar.getInstance()
        val dayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        val startMs = cal.timeInMillis
        cal.add(Calendar.DAY_OF_MONTH, 1)
        val endMs = cal.timeInMillis

        val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val items = mutableListOf<AgendaItem>()
        try {
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
                .appendPath(startMs.toString())
                .appendPath(endMs.toString())
                .build()
            context.contentResolver.query(
                uri,
                arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY),
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { cursor ->
                val titleIdx = cursor.getColumnIndex(CalendarContract.Instances.TITLE)
                val beginIdx = cursor.getColumnIndex(CalendarContract.Instances.BEGIN)
                val allDayIdx = cursor.getColumnIndex(CalendarContract.Instances.ALL_DAY)
                while (cursor.moveToNext() && items.size < 20) {
                    val title = cursor.getString(titleIdx)?.trim().orEmpty()
                    if (title.isEmpty()) continue
                    val allDay = cursor.getInt(allDayIdx) == 1
                    val timeLabel = if (allDay) "All day" else timeFmt.format(Date(cursor.getLong(beginIdx)))
                    items.add(AgendaItem(dayKey = dayKey, timeLabel = timeLabel, task = title, source = "calendar"))
                }
            }
        } catch (_: Exception) {
            // Keep graceful empty list if calendar provider is unavailable
        }
        return items
    }

    fun insertCalendarEvent(title: String, startMs: Long): ActionOutcome {
        return try {
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, title)
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startMs)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, startMs + 3_600_000L)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ActionOutcome(true, "Opened calendar event editor for \"$title\".")
        } catch (e: Exception) {
            ActionOutcome(false, "Couldn't open Calendar: ${e.message}")
        }
    }
}
