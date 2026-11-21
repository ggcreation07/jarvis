package com.jarvis.app.platform

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jarvis.app.domain.model.RoutineItem
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

object RoutineAlarms {
    private const val TAG = "RoutineAlarms"
    private const val PREFS = "jarvis_routine_alarms"
    private const val KEY = "routines"
    private const val CHANNEL_ID = "jarvis_reminders"
    private const val NOTIFICATION_ID = 7210
    const val EXTRA_ROUTINE_NAME = "com.jarvis.app.ROUTINE"
    const val EXTRA_ROUTINE_DATE = "com.jarvis.app.ROUTINE_DATE"
    private const val CATCH_UP_MS = 120 * 60_000L

    data class Entry(
        val name: String,
        val hour: Int,
        val minute: Int,
        val days: Set<Int>,
        val lastRun: String,
        val at: Long = -1L
    )

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CHANNEL_ID,
                "JARVIS Routines & Reminders",
                NotificationManager.IMPORTANCE_HIGH
            )
            nm.createNotificationChannel(ch)
        }
    }

    fun isoDate(ms: Long, tz: TimeZone = TimeZone.getDefault()): String {
        val c = Calendar.getInstance(tz).apply { timeInMillis = ms }
        return String.format(
            Locale.US,
            "%04d-%02d-%02d",
            c.get(Calendar.YEAR),
            c.get(Calendar.MONTH) + 1,
            c.get(Calendar.DAY_OF_MONTH)
        )
    }

    private fun parseDays(raw: String): Set<Int> {
        val clean = raw.trim().lowercase()
        return when (clean) {
            "", "daily", "every day" -> setOf(0, 1, 2, 3, 4, 5, 6)
            "weekdays" -> setOf(1, 2, 3, 4, 5)
            "weekends" -> setOf(0, 6)
            else -> {
                val map = mapOf("sun" to 0, "mon" to 1, "tue" to 2, "wed" to 3, "thu" to 4, "fri" to 5, "sat" to 6)
                clean.split(",").mapNotNull { part ->
                    val key = part.trim().take(3)
                    map[key]
                }.toSet().ifEmpty { setOf(0, 1, 2, 3, 4, 5, 6) }
            }
        }
    }

    private fun parseHourMinute(triggerTime: String): Pair<Int, Int>? {
        val m = Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(triggerTime.trim()) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: return null
        return if (h in 0..23 && min in 0..59) h to min else null
    }

    fun nextDue(now: Long, hour: Int, minute: Int, days: Set<Int>, lastRun: String, tz: TimeZone): Long {
        if (days.isEmpty() || hour !in 0..23 || minute !in 0..59) return -1L
        val today = Calendar.getInstance(tz).apply { timeInMillis = now }
        for (i in 0..7) {
            val c = (today.clone() as Calendar).apply {
                add(Calendar.DAY_OF_MONTH, i)
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val at = c.timeInMillis
            if (at > now && (c.get(Calendar.DAY_OF_WEEK) - 1) in days && isoDate(at, tz) != lastRun) {
                return at
            }
        }
        return -1L
    }

    fun syncRoutines(context: Context, routines: List<RoutineItem>) {
        ensureChannel(context)
        val now = System.currentTimeMillis()
        val tz = TimeZone.getDefault()
        val entries = routines.filter { it.enabled }.mapNotNull { r ->
            val hm = parseHourMinute(r.triggerTime) ?: return@mapNotNull null
            val days = parseDays(r.days)
            val at = nextDue(now, hm.first, hm.second, days, r.lastRunDate, tz)
            if (at <= 0L) null
            else Entry(r.name, hm.first, hm.second, days, r.lastRunDate, at)
        }
        store(context, entries)
        arm(context, entries)
    }

    fun onAlarm(context: Context) {
        ensureChannel(context)
        val now = System.currentTimeMillis()
        val tz = TimeZone.getDefault()
        val stored = load(context)
        val due = stored.filter { it.at in 1..now && now - it.at <= CATCH_UP_MS }
        val rolled = stored.mapNotNull { e ->
            val next = if (e.at in 1..now) nextDue(now, e.hour, e.minute, e.days, e.lastRun, tz) else e.at
            if (next > 0) e.copy(at = next) else null
        }
        due.forEach { postNotification(context, it.name, isoDate(it.at, tz)) }
        store(context, rolled)
        arm(context, rolled)
    }

    fun onTimeZoneChanged(context: Context) {
        val now = System.currentTimeMillis()
        val tz = TimeZone.getDefault()
        val list = load(context).mapNotNull { e ->
            val next = nextDue(now, e.hour, e.minute, e.days, e.lastRun, tz)
            if (next > 0) e.copy(at = next) else null
        }
        store(context, list)
        arm(context, list)
    }

    private fun arm(context: Context, list: List<Entry>) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, RoutineAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val at = list.minOfOrNull { it.at }
        if (at == null) {
            am.cancel(pi)
            return
        }
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (canExact) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                return
            } catch (e: SecurityException) {
                Log.w(TAG, "Exact alarm denied: ${e.message}")
            }
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    private fun postNotification(context: Context, name: String, date: String) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_ROUTINE_NAME, name)
            putExtra(EXTRA_ROUTINE_DATE, date)
        } ?: return
        val pi = PendingIntent.getActivity(
            context,
            name.hashCode(),
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Time for $name")
            .setContentText("Tap to run this routine in JARVIS.")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try {
            NotificationManagerCompat.from(context).notify("routine:$name", NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
        }
    }

    private fun load(context: Context): List<Entry> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        val arr = try { JSONArray(raw) } catch (_: Exception) { return emptyList() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name").trim()
            if (name.isEmpty()) return@mapNotNull null
            val daysArr = o.optJSONArray("days") ?: JSONArray()
            val days = (0 until daysArr.length()).map { daysArr.optInt(it, -1) }.filter { it in 0..6 }.toSet()
            Entry(name, o.optInt("hour", -1), o.optInt("minute", -1), days, o.optString("lastRun"), o.optLong("at", -1L))
        }
    }

    private fun store(context: Context, list: List<Entry>) {
        val arr = JSONArray(list.map { e ->
            JSONObject()
                .put("name", e.name)
                .put("hour", e.hour)
                .put("minute", e.minute)
                .put("days", JSONArray(e.days.sorted()))
                .put("lastRun", e.lastRun)
                .put("at", e.at)
        })
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}

class RoutineAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_TIMEZONE_CHANGED) {
            RoutineAlarms.onTimeZoneChanged(context)
        } else {
            RoutineAlarms.onAlarm(context)
        }
    }
}
