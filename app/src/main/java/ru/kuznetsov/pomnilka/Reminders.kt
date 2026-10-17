package ru.kuznetsov.pomnilka

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Собственные напоминания приложения: AlarmManager + уведомления с кнопками «Сделано» / «Завтра». */
object Reminders {
    const val CHANNEL = "reminders"
    const val ACTION_REMIND = "ru.kuznetsov.pomnilka.REMIND"
    const val ACTION_DONE = "ru.kuznetsov.pomnilka.DONE"
    const val ACTION_SNOOZE = "ru.kuznetsov.pomnilka.SNOOZE"
    const val EXTRA_ID = "task_id"

    fun createChannel(ctx: Context) {
        val ch = NotificationChannel(CHANNEL, "Напоминания", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Пора полить, покормить, заменить…"
        }
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun pi(ctx: Context, action: String, taskId: Long): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).setAction(action).putExtra(EXTRA_ID, taskId)
        // requestCode различаем по действию, иначе PendingIntent'ы перетрут друг друга
        val slot = when (action) { ACTION_REMIND -> 0; ACTION_DONE -> 1; else -> 2 }
        val code = (taskId * 4 + slot).toInt()
        return PendingIntent.getBroadcast(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Ставит будильник на ближайшее напоминание по задаче (день nextDue в заданное время; если прошло — завтра). */
    fun schedule(ctx: Context, task: Task) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val p = pi(ctx, ACTION_REMIND, task.id)
        val due = task.nextDueDate
        if (due == null) { am.cancel(p); return }
        val prefs = Prefs(ctx)
        val now = LocalDateTime.now()
        var at = due.atTime(prefs.remindHour, prefs.remindMinute)
        if (!at.isAfter(now)) at = LocalDate.now().atTime(prefs.remindHour, prefs.remindMinute)
        if (!at.isAfter(now)) at = at.plusDays(1)
        val ms = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val exactOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (exactOk) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, p)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, p)
    }

    fun cancel(ctx: Context, taskId: Long) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pi(ctx, ACTION_REMIND, taskId))
        cancelNotification(ctx, taskId)
    }

    fun cancelNotification(ctx: Context, taskId: Long) {
        NotificationManagerCompat.from(ctx).cancel(taskId.toInt())
    }

    fun rescheduleAll(ctx: Context) {
        Db.get(ctx).allTasks().forEach { schedule(ctx, it) }
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun show(ctx: Context, task: Task) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            ctx, task.id.toInt(),
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = buildString {
            task.lastDone?.let { append("Последний раз ${Fmt.ago(it)}") }
            task.nextDueDate?.let { if (isNotEmpty()) append(" · "); append("срок ${Fmt.relative(it)}") }
        }
        val b = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("Пора: ${task.title.replaceFirstChar { it.lowercase() }}")
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .addAction(R.drawable.ic_check, "Сделано", pi(ctx, ACTION_DONE, task.id))
            .addAction(R.drawable.ic_snooze, "Завтра", pi(ctx, ACTION_SNOOZE, task.id))
        Photos.thumbnail(task.photoPath, 256)?.let { bmp ->
            b.setLargeIcon(bmp)
            b.setStyle(NotificationCompat.BigPictureStyle().bigPicture(Photos.thumbnail(task.photoPath, 1024)).bigLargeIcon(null as android.graphics.Bitmap?))
        }
        NotificationManagerCompat.from(ctx).notify(task.id.toInt(), b.build())
    }
}
