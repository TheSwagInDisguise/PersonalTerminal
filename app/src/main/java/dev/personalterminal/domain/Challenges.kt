package dev.personalterminal.domain

import dev.personalterminal.data.db.Habit
import java.time.LocalDate

/**
 * Challenges (0.4.0): bounded habit windows with an optional XP stake.
 *
 * `challenge <habit> 30 [3]` stamps [Habit.chStart] / [Habit.chEnd] (inclusive) and optionally
 * [Habit.chStake]. Progress is derived from the scheduled days inside the window and their logs —
 * no extra tables. When the window closes, [dev.personalterminal.data.repo.HabitRepository.settleChallenge]
 * counts the days once, writes the stake to the XP ledger and flips [Habit.chResolved].
 */
object Challenges {
    /** Outcome of a one-time settlement (also what `challenge <habit>` prints afterwards). */
    data class Settlement(val success: Boolean, val xp: Int, val done: Int, val scheduled: Int)

    fun isActive(h: Habit, today: LocalDate): Boolean =
        h.chStart > 0L && !h.chResolved && today.toEpochDay() in h.chStart..h.chEnd

    /** True once the window closed but the stake has not been settled yet. */
    fun isSettling(h: Habit, today: LocalDate): Boolean =
        h.chStart > 0L && !h.chResolved && h.chEnd > 0L && h.chEnd < today.toEpochDay()

    fun hasChallenge(h: Habit): Boolean = h.chStart > 0L && h.chEnd > 0L

    /** 1-based day inside the window, or null when not active today. */
    fun dayOf(h: Habit, today: LocalDate): Int? =
        if (isActive(h, today)) (today.toEpochDay() - h.chStart + 1).toInt() else null

    fun totalDays(h: Habit): Int = if (h.chEnd > 0L && h.chStart > 0L) (h.chEnd - h.chStart + 1).toInt() else 0

    /** `day 12/30` */
    fun label(h: Habit, today: LocalDate): String? =
        dayOf(h, today)?.let { "day $it/${totalDays(h)}" }

    /** `[####----]` progress bar of the window (time-based). */
    fun progressBar(h: Habit, today: LocalDate, width: Int = 10): String {
        val total = totalDays(h)
        if (total <= 0) return ""
        val day = (today.toEpochDay() - h.chStart + 1).toInt().coerceIn(0, total)
        val filled = if (total == 0) 0 else (day * width + total - 1) / total
        return "[" + "#".repeat(filled.coerceAtMost(width)) + "-".repeat((width - filled).coerceAtLeast(0)) + "]"
    }

    /** Half the scheduled days (rounded up) must be completed to win the stake. */
    fun threshold(scheduled: Int): Int = (scheduled + 1) / 2

    fun certificate(h: Habit, s: Settlement, start: LocalDate, end: LocalDate): String {
        val head = if (s.success) "CHALLENGE WON" else "CHALLENGE MISSED"
        val rule = "+".padEnd(33, '-') + "+"
        fun row(text: String) = "| " + text.padEnd(31).take(31) + " |"
        return buildString {
            appendLine(rule)
            appendLine(row(head))
            appendLine(row(h.name))
            appendLine(row("${fmt(start)} → ${fmt(end)}"))
            appendLine(row("${s.done}/${s.scheduled} scheduled days"))
            appendLine(
                row(
                    when {
                        h.chStake <= 0 -> "no stake · just show up"
                        s.success -> "stake ${h.chStake} → +${s.xp} xp"
                        else -> "stake ${h.chStake} → ${s.xp} xp"
                    }
                )
            )
            if (h.chStake > 0 && !s.success) appendLine(row("threshold: ${threshold(s.scheduled)} days"))
            appendLine(rule)
        }
    }

    private fun fmt(d: LocalDate): String =
        d.format(java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy"))

    /** Printable status for `challenge <habit>` while the window is open. */
    fun status(h: Habit, today: LocalDate): String {
        val day = dayOf(h, today) ?: return ""
        val bar = progressBar(h, today, 24)
        return buildString {
            appendLine("challenge · ${h.name}")
            appendLine("day $day/${totalDays(h)}  $bar")
            appendLine("${fmt(LocalDate.ofEpochDay(h.chStart))} → ${fmt(LocalDate.ofEpochDay(h.chEnd))}")
            if (h.chStake > 0) appendLine("stake ${h.chStake} (win ${h.chStake * 10} xp · miss -${h.chStake * 5} xp)")
            append("# schedule: ${dev.personalterminal.domain.Schedule.describe(h)}")
        }
    }
}
