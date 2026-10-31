package dev.personalterminal.domain

import dev.personalterminal.data.db.FocusSession
import dev.personalterminal.data.db.Habit
import dev.personalterminal.data.db.HabitLog
import dev.personalterminal.data.db.HabitWithLogs
import dev.personalterminal.data.db.Watch
import dev.personalterminal.data.db.WearLog
import dev.personalterminal.data.db.XpEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId

class YearReviewTest {
    private val today = LocalDate.of(2026, 3, 10)
    private fun createdAt(d: LocalDate) = d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun data(): List<HabitWithLogs> {
        val jan1 = LocalDate.of(2026, 1, 1)
        val stretch = Habit(id = 1, name = "stretch", createdAt = createdAt(jan1))
        val read = Habit(id = 2, name = "read", createdAt = createdAt(jan1))
        // stretch: done every day in january (31), nothing since; read: done 10 days in feb, skipped 1
        val stretchLogs = (0 until 31).map { HabitLog(1, jan1.plusDays(it.toLong()).toEpochDay(), 1, true) }
        val readLogs = (0 until 10).map { HabitLog(2, LocalDate.of(2026, 2, 1).plusDays(it.toLong()).toEpochDay(), 1, true, mood = 4) } +
            HabitLog(2, LocalDate.of(2026, 2, 20).toEpochDay(), 0, false, skipped = true, skipReason = "travel")
        return listOf(HabitWithLogs(stretch, stretchLogs, emptyList()), HabitWithLogs(read, readLogs, emptyList()))
    }

    @Test
    fun `counts completions, perfect days, streaks and months`() {
        val watches = listOf(Watch(id = 1, brand = "Omega", model = "Speedmaster", nickname = "speedy"), Watch(id = 2, brand = "Seiko", model = "SPB143"))
        val wear = listOf(
            WearLog(watchId = 1, day = LocalDate.of(2026, 1, 5).toEpochDay(), photoPath = "a.jpg"),
            WearLog(watchId = 1, day = LocalDate.of(2026, 1, 6).toEpochDay()),
            WearLog(watchId = 2, day = LocalDate.of(2026, 2, 6).toEpochDay()),
            WearLog(watchId = 2, day = LocalDate.of(2025, 12, 31).toEpochDay(), photoPath = "old.jpg"), // last year – ignored
        )
        val xp = listOf(XpEvent(day = LocalDate.of(2026, 1, 3).toEpochDay(), amount = 10, reason = "c"), XpEvent(day = LocalDate.of(2026, 2, 3).toEpochDay(), amount = 25, reason = "s"))
        val sessions = listOf(FocusSession(habitId = 2, day = LocalDate.of(2026, 2, 2).toEpochDay(), startedAt = 0, endedAt = 0, minutes = 50))

        val r = YearReview.compute(2026, data(), xp, sessions, wear, watches, today)

        assertEquals(69, r.daysElapsed) // 1 jan … 10 mar
        assertEquals(41, r.completions)
        assertEquals(1, r.skipped)
        // scheduled: 2 habits × 69 days − 1 skipped = 137
        assertEquals(137, r.scheduled)
        assertEquals(0, r.perfectDays) // never both on the same day
        assertEquals(41, r.activeDays)
        assertEquals(35, r.xpEarned)
        assertEquals(listOf(10, 25, 0), r.xpByMonth.take(3))
        assertEquals(listOf(31, 10, 0), r.doneByMonth.take(3))
        assertEquals(Month.JANUARY, r.bestMonth)
        assertEquals(31, r.bestStreak!!.bestStreak)
        assertEquals("stretch", r.bestStreak!!.habit.name)
        assertEquals(50, r.focusMinutes)
        assertEquals(3, r.wearDays)
        assertEquals(1, r.wristShots)
        assertEquals("speedy", r.mostWorn!!.watch.nickname)
        assertEquals(4f, r.moodAvg!!, 0.01f)
    }

    @Test
    fun `card is fixed width and mentions the year`() {
        val r = YearReview.compute(2026, data(), emptyList(), emptyList(), emptyList(), emptyList(), today)
        val card = YearReview.card(r, "user@android")
        val lines = card.lines()
        assertTrue(lines.all { it.length == lines.first().length })
        assertTrue(card.contains("2026"))
        assertTrue(card.contains("review --year"))
        assertNotNull(lines.firstOrNull { it.contains("stretch") })
    }

    @Test
    fun `a year with no data is empty but safe`() {
        val r = YearReview.compute(2024, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), today)
        assertEquals(0, r.completions)
        assertEquals(0f, r.rate, 0f)
        assertEquals(366, r.daysElapsed)
        YearReview.card(r, "x")
    }
}
