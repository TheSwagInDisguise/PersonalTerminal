package dev.personalterminal.domain

import dev.personalterminal.data.db.Habit
import dev.personalterminal.data.db.isPausedOn
import dev.personalterminal.data.db.ScheduleType
import java.time.DayOfWeek
import java.time.LocalDate

/** Helpers for the habit schedule bitmask: Mon=1, Tue=2 … Sun=64. */
object Schedule {
    fun bit(day: DayOfWeek): Int = 1 shl (day.value - 1)

    fun isDayEnabled(mask: Int, day: DayOfWeek): Boolean = mask and bit(day) != 0

    fun toggle(mask: Int, day: DayOfWeek): Int = mask xor bit(day)

    /** Whether [habit] is expected to be done on [date]. Weekly habits are "due" every day until the quota is met. */
    fun isDue(habit: Habit, date: LocalDate): Boolean = !habit.isPausedOn(date) && when (habit.schedule) {
        ScheduleType.DAILY -> true
        ScheduleType.WEEKLY -> true
        ScheduleType.SPECIFIC_DAYS -> isDayEnabled(habit.daysMask, date.dayOfWeek)
        // Every other week (anchored on the habit's creation week) on the masked weekdays;
        // mask 127 = every day of the "on" week.
        ScheduleType.BIWEEKLY -> isDayEnabled(habit.daysMask, date.dayOfWeek) && weekParity(habit) == weekIndex(date) % 2
        ScheduleType.MONTHLY -> habit.monthDay > 0 &&
            date.dayOfMonth == habit.monthDay.coerceAtMost(date.lengthOfMonth().toLong()).toInt()
    }

    /** Parity (0/1) of the habit's creation week — the anchor for the biweekly rhythm. */
    fun weekParity(habit: Habit): Long {
        val created = java.time.Instant.ofEpochMilli(habit.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        return weekIndex(created) % 2
    }

    /** Weeks since the epoch for [date] (parity source for biweekly schedules). */
    private fun weekIndex(date: LocalDate): Long = weekStart(date).toEpochDay() / 7

    /** "paused → 01 oct" while the habit is paused, else null. */
    fun pauseLabel(habit: Habit, today: LocalDate): String? =
        if (habit.isPausedOn(today)) "paused → " + LocalDate.ofEpochDay(habit.pausedUntil).format(java.time.format.DateTimeFormatter.ofPattern("dd MMM")).lowercase() else null

    /** Parse `until 2026-10-01` / `2026-10-01` / `2w` / `10d` / `1m` into a return date after [today]. */
    fun parseUntil(arg: String, today: LocalDate): LocalDate? {
        val a = arg.trim().removePrefix("until").removePrefix("till").trim().lowercase()
        if (a.isEmpty()) return null
        Regex("(\\d+)\\s*([dwm])").matchEntire(a)?.let { m ->
            val n = m.groupValues[1].toLong()
            return when (m.groupValues[2]) { "d" -> today.plusDays(n); "w" -> today.plusWeeks(n); else -> today.plusMonths(n) }
        }
        return runCatching { LocalDate.parse(a) }.getOrNull()?.takeIf { it.isAfter(today) }
    }

    fun describe(habit: Habit): String = when (habit.schedule) {
        ScheduleType.DAILY -> "daily"
        ScheduleType.WEEKLY -> "${habit.timesPerWeek}x/week"
        ScheduleType.BIWEEKLY -> {
            val days = DayOfWeek.entries.filter { isDayEnabled(habit.daysMask, it) }
            when {
                days.isEmpty() -> "never"
                days.size == 7 -> "every 2 weeks"
                else -> "every 2w · " + days.joinToString(",") { it.name.take(3).lowercase() }
            }
        }
        ScheduleType.MONTHLY -> when {
            habit.monthDay <= 0 -> "never"
            habit.monthDay > 28 -> "monthly · day ${habit.monthDay} (last day in short months)"
            else -> "monthly · ${habit.monthDay}${ordinal(habit.monthDay)}"
        }
        ScheduleType.SPECIFIC_DAYS -> {
            val days = DayOfWeek.entries.filter { isDayEnabled(habit.daysMask, it) }
            when {
                days.size == 7 -> "daily"
                days.isEmpty() -> "never"
                days == listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) -> "weekends"
                days.size == 5 && DayOfWeek.SATURDAY !in days && DayOfWeek.SUNDAY !in days -> "weekdays"
                else -> days.joinToString(",") { it.name.take(3).lowercase() }
            }
        }
    }

    private fun ordinal(n: Int): String = when (n % 100) {
        11, 12, 13 -> "th"
        else -> when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
    }

    /** Monday of the week containing [date]. */
    fun weekStart(date: LocalDate): LocalDate = date.minusDays((date.dayOfWeek.value - 1).toLong())

    /**
     * Weekly-quota outlook for an `x/week` habit on [date]: how many completions are still needed
     * and how many days (incl. today) are left in the week. [remaining] ≥ [daysLeft] means every
     * remaining day is a must; `remaining > daysLeft` means the week is already lost.
     */
    data class WeekOutlook(val done: Int, val quota: Int, val daysLeft: Int) {
        val remaining: Int get() = (quota - done).coerceAtLeast(0)
        val met: Boolean get() = remaining == 0
        val lastChance: Boolean get() = !met && remaining == daysLeft
        val lost: Boolean get() = !met && remaining > daysLeft
        /** "2/3 · 4 days left", "2/3 · last chance today", "1/3 · 2 more, 2 days left", "3/3 ✓", "1/3 · week missed" */
        val label: String
            get() = when {
                met -> "$done/$quota this week ✓"
                lost -> "$done/$quota · week missed"
                lastChance && daysLeft == 1 -> "$done/$quota · last chance today"
                lastChance -> "$done/$quota · every day now ($daysLeft left)"
                remaining == 1 -> "$done/$quota · 1 more, $daysLeft days left"
                else -> "$done/$quota · $remaining more, $daysLeft days left"
            }
    }

    fun weekOutlook(habit: Habit, weekCount: Int, date: LocalDate): WeekOutlook =
        WeekOutlook(done = weekCount, quota = habit.timesPerWeek.coerceAtLeast(1), daysLeft = 7 - (date.dayOfWeek.value - 1))
}
