package ru.kuznetsov.pomnilka

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDate

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                handle(ctx.applicationContext, intent)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun handle(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED -> {
                Reminders.rescheduleAll(ctx)
                return
            }
        }
        val id = intent.getLongExtra(Reminders.EXTRA_ID, -1L)
        val task = Db.get(ctx).task(id) ?: return
        when (intent.action) {
            Reminders.ACTION_REMIND -> {
                val due = task.nextDueDate
                if (due != null && !due.isAfter(LocalDate.now())) Reminders.show(ctx, task)
                Reminders.schedule(ctx, task) // если не сделано — напомнит завтра снова
            }
            Reminders.ACTION_DONE -> {
                Actions.markDone(ctx, task, System.currentTimeMillis(), "Отмечено из уведомления")
            }
            Reminders.ACTION_SNOOZE -> {
                Reminders.cancelNotification(ctx, task.id)
                Reminders.schedule(ctx, task) // срок прошёл -> ближайшее время напоминания, т.е. завтра
            }
        }
    }
}
