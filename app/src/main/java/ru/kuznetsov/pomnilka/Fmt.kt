package ru.kuznetsov.pomnilka

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

object Fmt {
    private val ru = Locale("ru")
    private val dateF = DateTimeFormatter.ofPattern("d MMMM", ru)
    private val dateYF = DateTimeFormatter.ofPattern("d MMMM yyyy", ru)
    private val dtF = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", ru)

    fun plural(n: Int, one: String, few: String, many: String): String {
        val a = kotlin.math.abs(n) % 100
        val b = a % 10
        return when {
            a in 11..14 -> many
            b == 1 -> one
            b in 2..4 -> few
            else -> many
        }
    }

    fun days(n: Int): String = when {
        n % 30 == 0 && n >= 30 -> (n / 30).let { "$it ${plural(it, "месяц", "месяца", "месяцев")}" }
        n % 7 == 0 && n >= 7 -> (n / 7).let { "$it ${plural(it, "неделя", "недели", "недель")}" }
        else -> "$n ${plural(n, "день", "дня", "дней")}"
    }

    fun toLocalDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()

    fun date(d: LocalDate): String =
        if (d.year == LocalDate.now().year) d.format(dateF) else d.format(dateYF)

    fun dateTime(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(dtF)

    /** «сегодня», «завтра», «через 5 дней», «просрочено на 2 дня» */
    fun relative(d: LocalDate): String {
        val n = ChronoUnit.DAYS.between(LocalDate.now(), d).toInt()
        return when {
            n == 0 -> "сегодня"
            n == 1 -> "завтра"
            n == -1 -> "вчера (просрочено)"
            n > 1 -> "через $n ${plural(n, "день", "дня", "дней")}"
            else -> "просрочено на ${-n} ${plural(-n, "день", "дня", "дней")}"
        }
    }

    fun ago(millis: Long): String {
        val n = ChronoUnit.DAYS.between(toLocalDate(millis), LocalDate.now()).toInt()
        return when (n) {
            0 -> "сегодня"
            1 -> "вчера"
            else -> "$n ${plural(n, "день", "дня", "дней")} назад"
        }
    }
}
