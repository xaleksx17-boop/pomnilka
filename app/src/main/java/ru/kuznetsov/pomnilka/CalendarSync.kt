package ru.kuznetsov.pomnilka

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

data class CalendarInfo(val id: Long, val name: String, val account: String) {
    override fun toString() = if (account.isBlank() || account == name) name else "$name ($account)"
}

/** Работа с системным календарём Android через CalendarContract. */
object CalendarSync {
    private const val TAG = "CalendarSync"
    private const val LOCAL_ACCOUNT = "Помнилка"

    fun hasPermission(ctx: Context) =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun active(ctx: Context): Long? {
        val p = Prefs(ctx)
        if (!p.calendarEnabled || p.calendarId < 0 || !hasPermission(ctx)) return null
        return p.calendarId
    }

    fun listCalendars(ctx: Context): List<CalendarInfo> {
        if (!hasPermission(ctx)) return emptyList()
        val proj = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME
        )
        val sel = "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${CalendarContract.Calendars.VISIBLE} = 1"
        val args = arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
        return try {
            ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, proj, sel, args, null)?.use { c ->
                buildList {
                    while (c.moveToNext()) add(CalendarInfo(c.getLong(0), c.getString(1) ?: "?", c.getString(2) ?: ""))
                }
            } ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "listCalendars", e); emptyList()
        }
    }

    /** Локальный календарь на телефоне — если нет Google-аккаунта или не хочется в него писать. */
    fun createLocalCalendar(ctx: Context): Long? {
        if (!hasPermission(ctx)) return null
        listCalendars(ctx).firstOrNull { it.account == LOCAL_ACCOUNT }?.let { return it.id }
        val uri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT)
            .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            .build()
        val v = ContentValues().apply {
            put(CalendarContract.Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT)
            put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(CalendarContract.Calendars.NAME, LOCAL_ACCOUNT)
            put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, LOCAL_ACCOUNT)
            put(CalendarContract.Calendars.CALENDAR_COLOR, Color.rgb(46, 125, 50))
            put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER)
            put(CalendarContract.Calendars.OWNER_ACCOUNT, LOCAL_ACCOUNT)
            put(CalendarContract.Calendars.VISIBLE, 1)
            put(CalendarContract.Calendars.SYNC_EVENTS, 1)
            put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
        }
        return try {
            ctx.contentResolver.insert(uri, v)?.let { ContentUris.parseId(it) }
        } catch (e: Exception) {
            Log.w(TAG, "createLocalCalendar", e); null
        }
    }

    private fun insertEvent(ctx: Context, calId: Long, title: String, descr: String, start: Long, durMin: Int, alert: Boolean): Long? {
        val v = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calId)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DESCRIPTION, descr)
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, start + durMin * 60_000L)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            put(CalendarContract.Events.HAS_ALARM, if (alert) 1 else 0)
        }
        return try {
            val id = ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v)?.let { ContentUris.parseId(it) }
            if (id != null && alert) {
                ctx.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, id)
                    put(CalendarContract.Reminders.MINUTES, 0)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                })
            }
            id
        } catch (e: Exception) {
            Log.w(TAG, "insertEvent", e); null
        }
    }

    fun deleteEvent(ctx: Context, eventId: Long?) {
        if (eventId == null || !hasPermission(ctx)) return
        try {
            val uri: Uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
            ctx.contentResolver.delete(uri, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "deleteEvent", e)
        }
    }

    /** Отметка о выполнении («✓ Полить красулю»). Возвращает id события или null. */
    fun logDone(ctx: Context, task: Task, at: Long, note: String): Long? {
        val cal = active(ctx) ?: return null
        if (!Prefs(ctx).calendarLogDone) return null
        return insertEvent(ctx, cal, "✓ ${task.title}", note, at, 15, false)
    }

    /**
     * Перевыставляет запланированное событие для задачи: удаляет старое, создаёт новое на nextDue.
     * Возвращает задачу с обновлённым calEventId (уже сохранённую в БД).
     */
    fun syncPlanned(ctx: Context, task: Task): Task {
        val p = Prefs(ctx)
        deleteEvent(ctx, task.calEventId)
        var t = task.copy(calEventId = null)
        val cal = active(ctx)
        val due = task.nextDueDate
        if (cal != null && due != null) {
            val start = due.atTime(p.remindHour, p.remindMinute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val descr = buildString {
                append("Интервал: ${Fmt.days(task.intervalDays)}")
                task.lastDone?.let { append("\nПоследний раз: ${Fmt.dateTime(it)}") }
                append("\nСоздано приложением «Помнилка»")
            }
            t = t.copy(calEventId = insertEvent(ctx, cal, task.title, descr, start, 30, p.calendarAlert))
        }
        Db.get(ctx).update(t)
        return t
    }

    /** После смены календаря/настроек — перевыставить всё. */
    fun resyncAll(ctx: Context) {
        Db.get(ctx).allTasks().forEach { syncPlanned(ctx, it) }
    }

    @Suppress("unused")
    private fun today() = LocalDate.now()
}
