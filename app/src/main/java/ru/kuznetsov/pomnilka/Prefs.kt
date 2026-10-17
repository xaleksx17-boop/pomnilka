package ru.kuznetsov.pomnilka

import android.content.Context

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** auto | google | vosk */
    var engine: String
        get() = sp.getString("engine", "auto") ?: "auto"
        set(v) = sp.edit().putString("engine", v).apply()

    var remindHour: Int
        get() = sp.getInt("remind_hour", 10)
        set(v) = sp.edit().putInt("remind_hour", v).apply()

    var remindMinute: Int
        get() = sp.getInt("remind_minute", 0)
        set(v) = sp.edit().putInt("remind_minute", v).apply()

    var calendarEnabled: Boolean
        get() = sp.getBoolean("cal_enabled", false)
        set(v) = sp.edit().putBoolean("cal_enabled", v).apply()

    var calendarId: Long
        get() = sp.getLong("cal_id", -1L)
        set(v) = sp.edit().putLong("cal_id", v).apply()

    /** Записывать в календарь и выполненные события («✓ Полить красулю»). */
    var calendarLogDone: Boolean
        get() = sp.getBoolean("cal_log_done", true)
        set(v) = sp.edit().putBoolean("cal_log_done", v).apply()

    /** Ставить напоминание ещё и у события календаря (будет дубль с уведомлением приложения). */
    var calendarAlert: Boolean
        get() = sp.getBoolean("cal_alert", false)
        set(v) = sp.edit().putBoolean("cal_alert", v).apply()

    var speakReply: Boolean
        get() = sp.getBoolean("speak", true)
        set(v) = sp.edit().putBoolean("speak", v).apply()
}
