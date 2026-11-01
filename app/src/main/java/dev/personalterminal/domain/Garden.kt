package dev.personalterminal.domain

import java.time.LocalDate

/**
 * The ASCII garden: every logged focus session grows the plant, days without one wilt it, and a
 * year of sessions renders as a field. Pure functions over `focus_sessions` rows — no extra
 * tables, so the upgrade gate never has to care.
 */
object Garden {
    data class Day(val date: LocalDate, val minutes: Int, val sessions: Int)

    data class Stats(
        val stage: Int,
        val wilted: Boolean,
        val totalMinutes: Int,
        val totalSessions: Int,
        val dayStreak: Int,
        val weekMinutes: Int,
        val nextAt: Int?,
        val lastDay: LocalDate?,
    )

    /** Growth ladder in cumulative focus minutes: seed → sprout → sapling → bloom → old growth. */
    private val STAGE_AT = intArrayOf(0, 15, 60, 180, 480)
    val STAGE_NAMES = arrayOf("seed", "sprout", "sapling", "bloom", "old growth")

    fun stage(totalMinutes: Int): Int {
        var s = 0
        for (i in STAGE_AT.indices) if (totalMinutes >= STAGE_AT[i]) s = i
        return s
    }

    fun nextThreshold(totalMinutes: Int): Int? {
        val s = stage(totalMinutes)
        return if (s >= STAGE_AT.lastIndex) null else STAGE_AT[s + 1]
    }

    fun stats(days: List<Day>, today: LocalDate): Stats {
        val totalMinutes = days.sumOf { it.minutes }
        val stage = stage(totalMinutes)
        val lastDay = days.maxOfOrNull { it.date }
        // The plant droops once a whole day passed without a session (today still counts as watered).
        val wilted = stage >= 1 && (lastDay?.isBefore(today.minusDays(1)) ?: false)
        val active = days.filter { it.sessions > 0 }.map { it.date }.toSet()
        var streak = 0
        var cursor = if (today in active) today else today.minusDays(1)
        while (cursor in active) { streak++; cursor = cursor.minusDays(1) }
        val weekMinutes = days.filter { it.date >= today.minusDays(6) }.sumOf { it.minutes }
        return Stats(
            stage = stage, wilted = wilted, totalMinutes = totalMinutes,
            totalSessions = days.sumOf { it.sessions }, dayStreak = streak,
            weekMinutes = weekMinutes, nextAt = nextThreshold(totalMinutes), lastDay = lastDay,
        )
    }

    /** Big plant art for a stage; the wilted variant droops whatever has grown. */
    fun art(stage: Int, wilted: Boolean): List<String> {
        if (stage == 0) return listOf(
            "      ( . )",
            "        `",
            "  ~~~~~~~~~~~~~~~~~",
        )
        if (wilted) return listOf(
            """      \ | /""",
            """       \|/""",
            """    .--\_/--.""",
            """   '  droop  '""",
            "  ~~~~~~~~~~~~~~~~~",
        )
        return when (stage) {
            1 -> listOf(
                "        |",
                "       / \\",
                "  ~~~~~~~~~~~~~~~~~",
            )
            2 -> listOf(
                """      \ | /""",
                "       |",
                "      /|\\",
                "  ~~~~~~~~~~~~~~~~~",
            )
            3 -> listOf(
                "      |",
                """     \ | /""",
                "    (  *  )",
                "      |",
                "     /|\\",
                "  ~~~~~~~~~~~~~~~~~",
            )
            else -> listOf(
                "   .  *  .",
                """  \  | |  /""",
                "  .-*-- * *-.",
                "     / | \\",
                "      |||",
                "  ~~~~~~~~~~~~~~~~~",
            )
        }
    }

    /**
     * 52-week field, one row per weekday (Mon–Sun), one char per day:
     * `·` none · `.` &lt;15m · `:` &lt;30m · `o` &lt;60m · `*` ≥60m · ` ` future.
     */
    fun field(days: List<Day>, today: LocalDate): List<String> {
        val map = days.associateBy { it.date }
        val endOfWeek = today.plusDays((7 - today.dayOfWeek.value).toLong()) // the coming Saturday+1 = end of week
        val startOfWeek = endOfWeek.minusDays(364)                          // Monday 51 weeks back
        return (0 until 7).map { dow ->
            (0 until 52).joinToString("") { w ->
                val date = startOfWeek.plusDays(w * 7L + dow)
                when {
                    date.isAfter(today) -> " "
                    else -> grade(map[date]?.minutes ?: 0)
                }
            }
        }
    }

    private fun grade(minutes: Int): String = when {
        minutes <= 0 -> "·"
        minutes < 15 -> "."
        minutes < 30 -> ":"
        minutes < 60 -> "o"
        else -> "*"
    }

    fun legend(): String = "· none  . <15m  : <30m  o <60m  * 60m+   (mon → sun, 52 weeks)"
}
