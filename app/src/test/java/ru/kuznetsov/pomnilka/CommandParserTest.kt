package ru.kuznetsov.pomnilka

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandParserTest {
    private val tasks = listOf(
        TaskRef(1, "Полить красулю", "толстянка"),
        TaskRef(2, "Подкормить красулю", ""),
        TaskRef(3, "Полить фикус", ""),
        TaskRef(4, "Покормить кота", "")
    )

    private fun ids(text: String) = CommandParser.match(CommandParser.parse(text), tasks).map { it.id }

    @Test fun simpleDone() = assertEquals(listOf(1L), ids("полил красулю"))
    @Test fun verbDisambiguates() = assertEquals(listOf(2L), ids("подкормил красулю"))
    @Test fun synonym() = assertEquals(listOf(1L), ids("полил толстянку"))
    @Test fun wrongVerbIsNew() = assertTrue(ids("подкормил фикус").isEmpty())
    @Test fun yesterday() = assertEquals(-1, CommandParser.parse("вчера полила фикус").dayOffset)
    @Test fun oneTimeInterval() = assertEquals(Interval(10, false), CommandParser.parse("полил красулю, следующий через 10 дней").interval)
    @Test fun permanentInterval() = assertEquals(Interval(14, true), CommandParser.parse("полил кактус каждые две недели").interval)
    @Test fun query() {
        val c = CommandParser.parse("когда поливать красулю")
        assertTrue(c.isQuery)
        assertEquals(listOf(1L), CommandParser.match(c, tasks).map { it.id })
    }
    @Test fun agenda() = assertTrue(CommandParser.parse("что сегодня").isAgenda)
    @Test fun title() = assertEquals("Полить кактус", CommandParser.parse("полил кактус").suggestedTitle())
}
