package ru.kuznetsov.pomnilka

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDate

/** Запись справочника: что делать, как часто, когда в последний раз и когда следующий. */
data class Task(
    val id: Long = 0,
    val title: String,
    val keywords: String = "",
    val intervalDays: Int = 7,
    val lastDone: Long? = null,      // epoch millis
    val nextDue: Long? = null,       // LocalDate.toEpochDay()
    val calEventId: Long? = null,    // запланированное событие в календаре
    val photoPath: String? = null    // фото объекта (красуля, фильтр…)
) {
    val nextDueDate: LocalDate? get() = nextDue?.let { LocalDate.ofEpochDay(it) }
    fun toRef() = TaskRef(id, title, keywords)
}

data class LogEntry(val id: Long, val taskId: Long, val doneAt: Long, val text: String)

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "pomnilka.db", null, 1) {

    companion object {
        @Volatile private var inst: Db? = null
        fun get(ctx: Context): Db = inst ?: synchronized(this) {
            inst ?: Db(ctx.applicationContext).also { inst = it }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE tasks(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                keywords TEXT NOT NULL DEFAULT '',
                interval_days INTEGER NOT NULL DEFAULT 7,
                last_done INTEGER,
                next_due INTEGER,
                cal_event_id INTEGER,
                photo TEXT)"""
        )
        db.execSQL(
            """CREATE TABLE log(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                task_id INTEGER NOT NULL,
                done_at INTEGER NOT NULL,
                text TEXT NOT NULL DEFAULT '')"""
        )
        db.execSQL("CREATE INDEX log_task ON log(task_id, done_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    private fun Cursor.longOrNull(col: String): Long? {
        val i = getColumnIndexOrThrow(col)
        return if (isNull(i)) null else getLong(i)
    }

    private fun Cursor.toTask() = Task(
        id = getLong(getColumnIndexOrThrow("id")),
        title = getString(getColumnIndexOrThrow("title")),
        keywords = getString(getColumnIndexOrThrow("keywords")),
        intervalDays = getInt(getColumnIndexOrThrow("interval_days")),
        lastDone = longOrNull("last_done"),
        nextDue = longOrNull("next_due"),
        calEventId = longOrNull("cal_event_id"),
        photoPath = getString(getColumnIndexOrThrow("photo"))
    )

    private fun Task.values() = ContentValues().apply {
        put("title", title)
        put("keywords", keywords)
        put("interval_days", intervalDays)
        if (lastDone != null) put("last_done", lastDone) else putNull("last_done")
        if (nextDue != null) put("next_due", nextDue) else putNull("next_due")
        if (calEventId != null) put("cal_event_id", calEventId) else putNull("cal_event_id")
        if (photoPath != null) put("photo", photoPath) else putNull("photo")
    }

    fun allTasks(): List<Task> =
        readableDatabase.rawQuery("SELECT * FROM tasks ORDER BY next_due IS NULL, next_due, title", null).use { c ->
            buildList { while (c.moveToNext()) add(c.toTask()) }
        }

    fun task(id: Long): Task? =
        readableDatabase.rawQuery("SELECT * FROM tasks WHERE id=?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) c.toTask() else null
        }

    fun insert(t: Task): Long = writableDatabase.insert("tasks", null, t.values())

    fun update(t: Task) {
        writableDatabase.update("tasks", t.values(), "id=?", arrayOf(t.id.toString()))
    }

    fun delete(id: Long) {
        writableDatabase.delete("log", "task_id=?", arrayOf(id.toString()))
        writableDatabase.delete("tasks", "id=?", arrayOf(id.toString()))
    }

    fun addLog(taskId: Long, at: Long, text: String): Long =
        writableDatabase.insert("log", null, ContentValues().apply {
            put("task_id", taskId); put("done_at", at); put("text", text)
        })

    fun deleteLog(id: Long) {
        writableDatabase.delete("log", "id=?", arrayOf(id.toString()))
    }

    fun history(taskId: Long, limit: Int = 50): List<LogEntry> =
        readableDatabase.rawQuery(
            "SELECT * FROM log WHERE task_id=? ORDER BY done_at DESC LIMIT $limit", arrayOf(taskId.toString())
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(
                    LogEntry(
                        c.getLong(c.getColumnIndexOrThrow("id")),
                        c.getLong(c.getColumnIndexOrThrow("task_id")),
                        c.getLong(c.getColumnIndexOrThrow("done_at")),
                        c.getString(c.getColumnIndexOrThrow("text"))
                    )
                )
            }
        }
}
