package ru.kuznetsov.pomnilka

import kotlin.math.roundToInt

/** Интервал, произнесённый голосом. permanent = «каждые/раз в» (меняет справочник), иначе «через» (только следующий раз). */
data class Interval(val days: Int, val permanent: Boolean)

data class ParsedCommand(
    val raw: String,
    val normalized: String,
    val verb: String?,
    val objectWords: List<String>,
    val dayOffset: Int,
    val interval: Interval?,
    val isUndo: Boolean,
    /** «когда поливать красулю?» — ответить голосом, ничего не отмечать */
    val isQuery: Boolean = false,
    /** «что сегодня?», «что надо сделать?» — перечислить дела на сегодня */
    val isAgenda: Boolean = false
) {
    val objectText: String get() = objectWords.joinToString(" ")

    /** «полил красулю» -> «Полить красулю» */
    fun suggestedTitle(): String {
        val parts = mutableListOf<String>()
        verb?.let { parts += CommandParser.toInfinitive(it) }
        parts += objectWords
        return parts.joinToString(" ").replaceFirstChar { it.uppercase() }
    }
}

data class TaskRef(val id: Long, val title: String, val keywords: String)

object CommandParser {

    private val stopObj = setOf("в", "на", "у", "с", "со", "и", "а", "к", "по", "за", "для", "от", "до", "из", "о", "об", "все", "всю", "весь")
    private val fillers = setOf(
        "я", "мы", "уже", "только", "что", "вот", "ну", "сейчас", "сегодня", "утром", "вечером", "днем",
        "ночью", "тоже", "опять", "снова", "еще", "наконец", "это", "так", "короче", "запиши", "записать", "отметь"
    )
    private val undoPhrases = setOf("отмена", "отмени", "отменить", "отмени последнее", "отменить последнее", "отмена последнего")
    private val intervalLead = setOf(
        "через", "каждые", "каждый", "каждую", "каждое", "раз", "в", "повторять", "повторяй", "повтор",
        "следующий", "следующая", "следующее", "следующие", "следующую", "напомни", "напомнить", "напоминай", "и", "а", "потом"
    )
    private val permanentLead = setOf("каждые", "каждый", "каждую", "каждое", "раз", "повторять", "повторяй", "повтор", "напоминай")
    private val tailCut = setOf("повторять", "повторяй", "напомни", "напомнить", "напоминай")
    private val notVerbs = setOf("пол", "стол", "угол", "мел", "пыл", "ила", "пила", "игла", "мыло", "сало")

    private val ones = mapOf(
        "один" to 1, "одна" to 1, "одну" to 1, "одно" to 1, "два" to 2, "две" to 2, "три" to 3, "четыре" to 4,
        "пять" to 5, "шесть" to 6, "семь" to 7, "восемь" to 8, "девять" to 9
    )
    private val teens = mapOf(
        "десять" to 10, "одиннадцать" to 11, "двенадцать" to 12, "тринадцать" to 13, "четырнадцать" to 14,
        "пятнадцать" to 15, "шестнадцать" to 16, "семнадцать" to 17, "восемнадцать" to 18, "девятнадцать" to 19
    )
    private val tens = mapOf(
        "двадцать" to 20, "тридцать" to 30, "сорок" to 40, "пятьдесят" to 50, "шестьдесят" to 60,
        "семьдесят" to 70, "восемьдесят" to 80, "девяносто" to 90, "сто" to 100
    )

    fun normalize(s: String): String =
        s.lowercase().replace('ё', 'е').replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private fun unitDays(t: String): Int? = when {
        t in setOf("день", "дня", "дней", "денька", "сутки", "суток") -> 1
        t.startsWith("недел") -> 7
        t.startsWith("месяц") -> 30
        t in setOf("год", "года", "лет") -> 365
        else -> null
    }

    /** Число, стоящее непосредственно перед индексом end. Возвращает (значение, индекс начала). */
    private fun numberBefore(tk: List<String>, end: Int): Pair<Double, Int>? {
        val i = end - 1
        if (i < 0) return null
        val t = tk[i]
        t.toIntOrNull()?.let { return it.toDouble() to i }
        when (t) {
            "пару", "пара", "парочку" -> return 2.0 to i
            "полторы", "полтора" -> return 1.5 to i
        }
        ones[t]?.let { v ->
            if (i >= 1) tens[tk[i - 1]]?.let { return (it + v).toDouble() to (i - 1) }
            return v.toDouble() to i
        }
        teens[t]?.let { return it.toDouble() to i }
        tens[t]?.let { return it.toDouble() to i }
        return null
    }

    fun parse(raw: String): ParsedCommand {
        val norm = normalize(raw)
        if (norm in undoPhrases) return ParsedCommand(raw, norm, null, emptyList(), 0, null, true)

        val tk = norm.split(" ").filter { it.isNotBlank() }.toMutableList()

        val agendaStarts = listOf("что сегодня", "что на сегодня", "что надо", "что нужно", "какие дела", "какие задачи", "что делать", "что пора", "дела на сегодня")
        if (agendaStarts.any { norm.startsWith(it) } || norm == "что") {
            return ParsedCommand(raw, norm, null, emptyList(), 0, null, false, isAgenda = true)
        }
        var isQuery = false
        val qWords = setOf("когда", "скоро", "сколько", "давно", "последний")
        if (tk.isNotEmpty() && tk.first() in qWords) {
            isQuery = true
            tk.removeAll { it in qWords || it in setOf("ли", "нужно", "надо", "пора", "следующий", "раз", "дней", "мне", "будет", "назад", "был", "было") }
        }

        // 1. интервал
        var interval: Interval? = null
        val special = mapOf("ежедневно" to 1, "еженедельно" to 7, "ежемесячно" to 30)
        for (i in tk.indices) {
            special[tk[i]]?.let { interval = Interval(it, true); tk.removeAt(i) }
            if (interval != null) break
        }
        if (interval == null) {
            for (u in tk.indices) {
                val ud = unitDays(tk[u]) ?: continue
                var start = u
                var value = 1.0
                val num = numberBefore(tk, u)
                if (num != null) { value = num.first; start = num.second }
                var k = start - 1
                var permanent = false
                var hasLead = false
                while (k >= 0 && tk[k] in intervalLead) {
                    hasLead = true
                    if (tk[k] in permanentLead) permanent = true
                    k--
                }
                if (num == null && !hasLead) continue
                interval = Interval((value * ud).roundToInt().coerceAtLeast(1), permanent)
                for (r in u downTo k + 1) tk.removeAt(r)
                break
            }
        }

        // 2. хвост «следующий полив», «повторять…»
        val cut = tk.indexOfFirst { it.startsWith("следующ") || it in tailCut }
        if (cut >= 0) while (tk.size > cut) tk.removeAt(tk.size - 1)

        // 3. дата
        var dayOffset = 0
        if (tk.remove("позавчера")) dayOffset = -2
        if (tk.remove("вчера")) dayOffset = -1
        tk.removeAll { it in fillers }

        // 4. глагол
        var verbIdx = -1
        if (tk.isNotEmpty() && isVerbLike(tk.first())) verbIdx = 0
        else if (tk.size > 1 && isVerbLike(tk.last())) verbIdx = tk.size - 1
        else verbIdx = tk.indexOfFirst { isVerbLike(it) }
        val verb = if (verbIdx >= 0) tk[verbIdx] else null
        val obj = tk.filterIndexed { i, w -> i != verbIdx && w !in stopObj }

        return ParsedCommand(raw, norm, verb, obj, dayOffset, if (isQuery) null else interval, false, isQuery = isQuery)
    }

    fun isVerbLike(t: String): Boolean {
        if (t.length < 3 || t in notVerbs) return false
        return listOf("л", "ла", "ли", "ло", "лся", "лась", "лись", "ть", "ться").any { t.endsWith(it) }
    }

    fun toInfinitive(v: String): String {
        val vowels = "аеиоуыэюя"
        return when {
            v.endsWith("ть") || v.endsWith("ться") -> v
            v.endsWith("лся") -> v.dropLast(3) + "ться"
            v.endsWith("лась") || v.endsWith("лись") -> v.dropLast(4) + "ться"
            v.endsWith("ла") || v.endsWith("ли") || v.endsWith("ло") -> toInfinitive(v.dropLast(1))
            v.endsWith("л") && v.length >= 3 && v[v.length - 2] in vowels -> v.dropLast(1) + "ть"
            else -> v
        }
    }

    private fun commonPrefix(a: String, b: String): Int {
        val n = minOf(a.length, b.length)
        var i = 0
        while (i < n && a[i] == b[i]) i++
        return i
    }

    /** Совпадение слов с учётом падежных окончаний: красулю ~ красуля, кота ~ кот. */
    fun stemMatch(a: String, b: String): Boolean {
        if (a == b) return true
        val m = minOf(a.length, b.length)
        val cp = commonPrefix(a, b)
        if (m <= 3) return cp == m && kotlin.math.abs(a.length - b.length) <= 2
        return cp >= maxOf(3, m - 2)
    }

    /** полил ~ полить, подкормил ~ подкормить, но поменял !~ помыть. */
    fun verbMatch(a: String, b: String): Boolean {
        fun raw(x: String, y: String): Boolean {
            val m = minOf(x.length, y.length)
            return commonPrefix(x, y) >= minOf(5, m - 1).coerceAtLeast(2)
        }
        val x = toInfinitive(a)
        val y = toInfinitive(b)
        return raw(x, y) || raw(stripPrefix(x), stripPrefix(y))
    }

    private val prefixes = listOf("пере", "под", "при", "раз", "рас", "про", "вы", "за", "на", "по", "об", "от", "до", "с", "у", "о")

    /** накормил -> кормил, покормить -> кормить */
    private fun stripPrefix(v: String): String {
        val p = prefixes.filter { v.startsWith(it) && v.length - it.length >= 3 }.maxByOrNull { it.length } ?: return v
        return v.substring(p.length)
    }

    private fun words(s: String) = normalize(s).split(" ").filter { it.length >= 2 && it !in stopObj }

    /** Лучшие совпадения из справочника (несколько — если неоднозначно). */
    fun match(cmd: ParsedCommand, tasks: List<TaskRef>): List<TaskRef> {
        if (cmd.objectWords.isEmpty()) return emptyList()
        val scored = tasks.map { t ->
            val titleWords = words(t.title)
            val objWords = titleWords + words(t.keywords)
            val objScore = cmd.objectWords.count { w -> objWords.any { stemMatch(w, it) } }
            val tVerb = titleWords.firstOrNull()?.takeIf { isVerbLike(it) }
            val verbCandidates = listOfNotNull(tVerb) + words(t.keywords).filter { isVerbLike(it) }
            val verbOk = cmd.verb != null && verbCandidates.any {
                verbMatch(cmd.verb, it) || (cmd.isQuery && commonPrefix(cmd.verb, it) >= 4)
            }
            // «подкормил фикус» не должен засчитаться как «Полить фикус»
            // в вопросе («когда поливать…») глагол в другой форме — не мешаем
            val verbConflict = !cmd.isQuery && cmd.verb != null && tVerb != null && !verbOk
            t to if (objScore == 0 || verbConflict) 0 else objScore * 10 + (if (verbOk) 3 else 0)
        }.filter { it.second > 0 }
        if (scored.isEmpty()) return emptyList()
        val best = scored.maxOf { it.second }
        return scored.filter { it.second == best }.map { it.first }
    }
}
