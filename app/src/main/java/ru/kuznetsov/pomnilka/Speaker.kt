package ru.kuznetsov.pomnilka

import android.content.Context
import android.speech.tts.TextToSpeech
import java.time.LocalDate
import java.util.Locale

/** Голосовые ответы через встроенный синтезатор речи Android. */
class Speaker(ctx: Context) : TextToSpeech.OnInitListener {
    private var ready = false
    private var pending: String? = null
    private val tts = TextToSpeech(ctx.applicationContext, this)

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val r = tts.setLanguage(Locale("ru", "RU"))
            ready = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
            pending?.let { if (ready) say(it) }
            pending = null
        }
    }

    fun say(text: String) {
        if (!ready) { pending = text; return }
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "pomnilka")
    }

    fun stop() { tts.stop() }
    fun shutdown() { tts.shutdown() }
}

/** Тексты ответов — одни и те же для экрана и для голоса. */
object Answers {
    fun whenNext(t: Task): String {
        val due = t.nextDueDate ?: return "${t.title} — ещё ни разу не отмечено."
        val today = LocalDate.now()
        val head = when {
            due.isBefore(today) -> "${t.title} — ${Fmt.relative(due)}. Пора сделать сегодня."
            due == today -> "${t.title} — сегодня."
            else -> "${t.title} — ${Fmt.relative(due)}, ${Fmt.date(due)}."
        }
        val last = t.lastDone?.let { " Последний раз ${Fmt.ago(it)}." } ?: ""
        return head + last
    }

    fun done(r: DoneResult): String {
        val due = r.task.nextDueDate
        val next = if (due != null) " Следующий раз ${Fmt.relative(due)}, ${Fmt.date(due)}." else ""
        val changed = if (r.task.intervalDays != r.prev.intervalDays) " Теперь каждые ${Fmt.days(r.task.intervalDays)}." else ""
        return "Записала: ${r.task.title.replaceFirstChar { it.lowercase() }}.$next$changed"
    }

    fun agenda(tasks: List<Task>): String {
        val today = LocalDate.now()
        val due = tasks.filter { it.nextDueDate?.let { d -> !d.isAfter(today) } == true }
        if (due.isEmpty()) {
            val nearest = tasks.filter { it.nextDue != null }.minByOrNull { it.nextDue!! }
                ?: return "Дел нет. Справочник пока пуст."
            return "На сегодня дел нет. Ближайшее: ${whenNext(nearest).replaceFirstChar { it.lowercase() }}"
        }
        return "Сегодня: " + due.joinToString(", ") { it.title.replaceFirstChar { c -> c.lowercase() } } + "."
    }
}
