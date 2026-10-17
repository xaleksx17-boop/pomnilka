package ru.kuznetsov.pomnilka

import android.content.Context

/** Результат отметки — нужен для «Отменить». */
data class DoneResult(val task: Task, val prev: Task, val logId: Long, val doneCalId: Long?)

object Actions {

    /**
     * Отметить выполнение задачи.
     * interval: голосовое «через N» (только следующий раз) или «каждые N» (меняет справочник).
     */
    fun markDone(ctx: Context, task: Task, at: Long, text: String, interval: Interval? = null): DoneResult {
        val db = Db.get(ctx)
        val newInterval = interval?.takeIf { it.permanent }?.days ?: task.intervalDays
        val step = interval?.days ?: newInterval
        val isLatest = at >= (task.lastDone ?: 0L)
        val nextDue = if (isLatest) Fmt.toLocalDate(at).plusDays(step.toLong()).toEpochDay() else task.nextDue
        val logId = db.addLog(task.id, at, text)
        var t = task.copy(
            intervalDays = newInterval,
            lastDone = if (isLatest) at else task.lastDone,
            nextDue = nextDue
        )
        db.update(t)
        val calDone = CalendarSync.logDone(ctx, t, at, text)
        t = CalendarSync.syncPlanned(ctx, t)
        Reminders.cancelNotification(ctx, t.id)
        Reminders.schedule(ctx, t)
        return DoneResult(t, task, logId, calDone)
    }

    fun undo(ctx: Context, r: DoneResult) {
        val db = Db.get(ctx)
        val current = db.task(r.task.id) ?: return
        db.deleteLog(r.logId)
        CalendarSync.deleteEvent(ctx, r.doneCalId)
        CalendarSync.deleteEvent(ctx, current.calEventId)
        val restored = r.prev.copy(calEventId = null, photoPath = current.photoPath, title = current.title, keywords = current.keywords)
        db.update(restored)
        val t = CalendarSync.syncPlanned(ctx, restored)
        Reminders.schedule(ctx, t)
    }

    /** Сохранить задачу из редактора (новую или изменённую) и пересинхронизировать календарь и будильник. */
    fun save(ctx: Context, task: Task): Task {
        val db = Db.get(ctx)
        val withId = if (task.id == 0L) task.copy(id = db.insert(task)) else task.also { db.update(it) }
        val t = CalendarSync.syncPlanned(ctx, withId)
        Reminders.schedule(ctx, t)
        return t
    }

    fun delete(ctx: Context, task: Task) {
        CalendarSync.deleteEvent(ctx, task.calEventId)
        Reminders.cancel(ctx, task.id)
        Photos.delete(task.photoPath)
        PhotoMatcher.deleteReferences(ctx, task.id)
        Db.get(ctx).delete(task.id)
    }
}
