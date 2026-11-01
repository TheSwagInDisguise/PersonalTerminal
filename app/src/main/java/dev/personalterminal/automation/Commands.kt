package dev.personalterminal.automation

import dev.personalterminal.domain.AppClock
import android.content.Context
import dev.personalterminal.PersonalTerminalApp
import dev.personalterminal.data.db.Habit
import dev.personalterminal.data.db.HabitType
import dev.personalterminal.domain.HabitStats
import dev.personalterminal.domain.Schedule
import dev.personalterminal.domain.Strength
import dev.personalterminal.domain.Targets
import dev.personalterminal.domain.Sleep
import dev.personalterminal.data.db.isPausedOn
import dev.personalterminal.data.db.checklistItems
import dev.personalterminal.data.db.hasItem
import dev.personalterminal.data.db.Watch
import dev.personalterminal.data.db.displayName
import dev.personalterminal.data.db.owned
import dev.personalterminal.data.db.inRepair
import dev.personalterminal.data.db.sold
import dev.personalterminal.data.db.wished
import dev.personalterminal.domain.Templates
import dev.personalterminal.timer.PomodoroService
import dev.personalterminal.ui.navigation.Routes
import java.time.LocalDate

/**
 * The shell behind the Today prompt and the automation receiver.
 *
 * ```
 * done <habit>              toggle / complete          undo <habit>        un-complete
 * add <n> <habit>           counter +n / timer +n min  set <n> <habit>     absolute value
 * skip <habit> [-- reason]  skip today                 slip <habit>        log a slip (avoid-habit)
 * note <habit> -- <text>    completion note            mood <1-5> [habit]  mood for a habit
 * timer [min] [habit]       start focus                stopwatch [habit]   start stopwatch
 * timer stop | pause | resume
 * wear <watch>              log today's watch          watch next          rotation suggestion
 * shield <habit>            repair the latest gap      habit add [template]
 * yesterday <habit>         late-log the previous day (repair window, before 12:00)
 * pause <habit> [until d|2w] hide until a date       resume <habit>      bring it back early
 * sleep 23:30 / wake 06:45  sleep anchors (night → wake day)   slept 23:30 06:45  both at once
 * strap <strap> <watch>     fit a strap (logged as a swap)     strap <strap> drawer  take it off
 * stats [habit]             30/90/365-day rates, best weekday, streaks as a monospace block
 * ls | status | review | help | theme <name> | goto <route>
 * ```
 */
object Commands {
    data class Result(val ok: Boolean, val message: String, val navigate: String? = null)

    private fun ok(msg: String, nav: String? = null) = Result(true, msg, nav)
    private fun err(msg: String) = Result(false, msg)

    suspend fun run(context: Context, app: PersonalTerminalApp, line: String, date: LocalDate = AppClock.today()): Result {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return err("")
        val (head, restAndComment) = split(trimmed)
        val (rest, comment) = restAndComment.splitComment()
        return when (head.lowercase()) {
            "done", "do", "check", "complete", "x" -> habit(app, rest)?.let { h ->
                val st = status(app, h.id, date)
                if (h.negative) { app.habits.logSlip(h.id, slipped = false, date = date); ok("[✓] ${h.name} — clean today") }
                else if (st?.completed == true) ok("[✓] ${h.name} already done · use `undo ${h.name}`")
                else { app.habits.toggle(h.id, date); if (comment.isNotBlank()) app.habits.annotate(h.id, date, note = comment); ok("[✓] ${h.name} done" + xpHint()) }
            } ?: noHabit(rest)
            "yesterday", "yday", "late" -> habit(app, rest)?.let { h ->
                // repair window: `yesterday <habit>` ticks the previous scheduled day without spending a shield
                val info = app.habits.streakFor(h.id, date)
                val day = info?.lateLogDay
                when {
                    day != null -> { app.habits.logLate(h.id, day); ok("[✓] ${h.name} logged for ${day.dayOfWeek.name.lowercase().take(3)} ${day.dayOfMonth} · streak ${app.habits.streakFor(h.id, date)?.current ?: 0}d") }
                    info?.repairableDay != null -> err("repair window closed · `shield ${h.name}` to bridge ${info.repairableDay}")
                    else -> err("${h.name}: nothing to repair")
                }
            } ?: noHabit(rest)
            "strength", "str" -> {
                // `strength` → every habit weakest first · `strength <habit>` → sparkline + trend
                val rows = app.habits.strengths(date)
                if (rest.isBlank()) {
                    if (rows.isEmpty()) err("no habits yet")
                    else ok("strength (weakest first)\n" + rows.sortedBy { it.second.score }.joinToString("\n") { (h, r) ->
                        "%-14s %3d%% %s %s".format(h.name.take(14), r.score, r.arrow, Strength.sparkline(r.history))
                    } + "\n# ${Strength.SLIPPING}% and below = slipping · one miss costs ~7 pts, one day back earns ~7")
                } else habit(app, rest)?.let { h ->
                    val r = rows.firstOrNull { it.first.id == h.id }?.second ?: return err("${h.name} is archived")
                    ok("${h.name}: ${Strength.label(r)} · week ago ${r.weekAgo}%\n${Strength.sparkline(r.history, 16)} 28 scheduled days" + if (r.slipping) "\n# slipping – a minimum version (`min ${h.name} n`) keeps the chain on thin days" else "")
                } ?: noHabit(rest)
            }
            "min", "minimum" -> {
                // `min read 2` sets the minimum version · `min read` logs it today · `min read 0` clears
                val parts = rest.trim().split(Regex("\\s+"))
                val n = parts.lastOrNull()?.toIntOrNull()
                val ref = if (n != null) parts.dropLast(1).joinToString(" ") else rest
                habit(app, ref)?.let { h ->
                    when {
                        h.type == HabitType.CHECKBOX || h.type == HabitType.CHECKLIST -> err("${h.name} has no numeric target – minimums are for counters and timers")
                        n == null && h.minTarget <= 0 -> err("${h.name} has no minimum yet · `min ${h.name} 2` sets one")
                        n == null -> { app.habits.logMinimum(h.id, date); val st = status(app, h.id, date); ok("[~] ${h.name} ${st?.value ?: h.minTarget}/${st?.target ?: h.target} ${h.unit} · minimum reached, streak safe".trim()) }
                        n == 0 -> { app.habits.setMinimum(h.id, 0); ok("${h.name}: minimum cleared") }
                        n >= Targets.target(h, date) -> err("minimum must be below the target (${Targets.target(h, date)})")
                        else -> { app.habits.setMinimum(h.id, n); ok("${h.name}: minimum $n ${h.unit} · reaching it keeps the streak as [~] min".trim()) }
                    }
                } ?: noHabit(ref)
            }
            "ramp" -> {
                // `ramp read 30 8` → target grows 10 → 30 over 8 weeks · `ramp read off` clears · `ramp read` shows
                val parts = rest.trim().split(Regex("\\s+"))
                val nums = parts.takeLastWhile { it.toIntOrNull() != null }.map { it.toInt() }
                val off = parts.lastOrNull()?.lowercase() in setOf("off", "clear", "stop")
                val ref = parts.dropLast(if (off) 1 else nums.size).joinToString(" ")
                habit(app, ref)?.let { h ->
                    when {
                        h.type == HabitType.CHECKBOX || h.type == HabitType.CHECKLIST -> err("${h.name} has no numeric target to ramp")
                        off -> { app.habits.setRamp(h.id, 0, 0); ok("${h.name}: ramp cleared · target stays ${h.target}") }
                        nums.isEmpty() -> ok(Targets.rampLabel(h, date)?.let { "${h.name}: $it · today ${Targets.target(h, date)} ${h.unit}".trim() } ?: "${h.name}: no ramp · `ramp ${h.name} <to> <weeks>`")
                        nums.size < 2 -> err("usage: ramp <habit> <to> <weeks>  e.g. ramp read 30 8")
                        nums[0] == h.target -> err("target is already ${h.target}")
                        else -> { app.habits.setRamp(h.id, nums[0], nums[1], date); ok("${h.name}: ${h.target} → ${nums[0]} over ${nums[1].coerceIn(1, 52)} weeks · first step next week") }
                    }
                } ?: noHabit(ref)
            }
            "after", "stack" -> {
                // `after journal meditate` → journal follows meditate · `after journal none` clears · `after journal` shows
                val parts = rest.trim().split(Regex("\\s+"), limit = 2)
                habit(app, parts.getOrNull(0) ?: "")?.let { h ->
                    val anchorRef = parts.getOrNull(1)?.trim() ?: ""
                    when {
                        anchorRef.isBlank() -> {
                            val a = app.habits.allHabits().firstOrNull { it.id == h.anchorId }
                            ok(if (a == null) "${h.name} follows nothing · `after ${h.name} <anchor>`" else "${a.name} → ${h.name}" + if (h.anchorRemind) " · nudged when ${a.name} is ticked" else "")
                        }
                        anchorRef.lowercase() in setOf("none", "off", "clear", "nothing") -> { app.habits.setAnchor(h.id, 0L); ok("${h.name} unstacked") }
                        else -> habit(app, anchorRef)?.let { a ->
                            if (a.id == h.id) err("a habit can't follow itself")
                            else if (app.habits.setAnchor(h.id, a.id)) ok("${a.name} → ${h.name} · ${h.name} waits until ${a.name} is ticked, then nudges you")
                            else err("that would loop the stack")
                        } ?: noHabit(anchorRef)
                    }
                } ?: noHabit(parts.getOrNull(0) ?: "")
            }
            "area" -> {
                // `area run body` · `area run none` · `area` lists the balance
                val parts = rest.trim().split(Regex("\\s+"))
                if (rest.isBlank()) {
                    val hwl = app.habits.activeWithLogs().map { it.habit to it.logs }
                    val scores = dev.personalterminal.domain.Areas.balance(hwl, date.minusDays(6), date)
                    if (scores.isEmpty()) err("no areas yet · `area <habit> body|mind|work|people|home|money`") else ok(dev.personalterminal.domain.Areas.radar(scores) + "\n" + dev.personalterminal.domain.Areas.summary(scores))
                } else {
                    val areaId = parts.last().lowercase()
                    val ref = parts.dropLast(1).joinToString(" ")
                    val area = dev.personalterminal.domain.Areas.byId(areaId)
                    habit(app, ref)?.let { h ->
                        when {
                            areaId in setOf("none", "off", "clear") -> { app.habits.setArea(h.id, ""); ok("${h.name}: area cleared") }
                            area == null -> err("areas: " + dev.personalterminal.domain.Areas.all.joinToString(" ") { it.id })
                            else -> { app.habits.setArea(h.id, area.id); ok("${h.name} → ${area.glyph} ${area.label}") }
                        }
                    } ?: noHabit(ref)
                }
            }
            "sort" -> {
                val mode = rest.trim().lowercase()
                when (mode) {
                    "strength", "weakest", "weak" -> { app.prefs.setTodaySort("strength"); ok("today: weakest first") }
                    "", "routine", "default", "order" -> { app.prefs.setTodaySort(""); ok("today: routine order") }
                    else -> err("sort strength | sort routine")
                }
            }
            "stats", "stat", "show" -> {
                if (rest.isBlank()) {
                    // no habit → one line per habit, 30-day rate
                    val rows = app.habits.activeWithLogs().map { hwl ->
                        val r = HabitStats.report(hwl.habit, hwl.logs, hwl.shields, date, AppClock.now().hour)
                        val w = r.windows.first()
                        "%-14s %3d%% ⚡%d".format(hwl.habit.name.take(14), (w.rate * 100).toInt(), r.streak.current)
                    }
                    if (rows.isEmpty()) err("no habits yet") else ok("30d rates\n" + rows.joinToString("\n") + "\n`stats <habit>` for the full block")
                } else habit(app, rest)?.let { h ->
                    val hwl = app.habits.activeWithLogs().firstOrNull { it.habit.id == h.id } ?: return err("${h.name} is archived")
                    ok(HabitStats.render(HabitStats.report(h, hwl.logs, hwl.shields, date, AppClock.now().hour)))
                } ?: noHabit(rest)
            }
            "sleep", "bed", "bedtime" -> {
                // `sleep 23:30` (evening → tonight's night, logged on tomorrow) · `sleep` shows last night
                val arg = rest.trim()
                if (arg.isBlank() || arg == "status" || arg == "ls") {
                    val log = app.habits.sleep(date)
                    val stats = Sleep.stats(app.habits.sleepRange(date.minusDays(29), date))
                    ok(listOfNotNull(Sleep.summary(log) ?: "no sleep logged for ${date} · `sleep 23:30` tonight, `wake 06:45` tomorrow", stats?.line).joinToString("\n"))
                } else {
                    val clock = Sleep.parseClock(arg) ?: return err("can't read '$arg' · try `sleep 23:30` or `sleep 11pm`")
                    val wakeDay = if (date == AppClock.today()) Sleep.wakeDayForBedCommand(AppClock.now()) else date
                    app.habits.logSleep(wakeDay, bedMinutes = Sleep.bedMinutesFor(clock), note = comment.ifBlank { null })
                    ok("☾ bed ${Sleep.formatClock(clock)} · night of ${wakeDay.minusDays(1)} → `wake HH:MM` in the morning")
                }
            }
            "wake", "woke", "up" -> {
                val arg = rest.trim()
                if (arg.isBlank()) return err("usage: wake 06:45")
                val clock = Sleep.parseClock(arg) ?: return err("can't read '$arg' · try `wake 06:45`")
                app.habits.logSleep(date, wakeMinutes = clock, note = comment.ifBlank { null })
                val log = app.habits.sleep(date)
                ok("☼ up ${Sleep.formatClock(clock)}" + (Sleep.durationMinutes(log)?.let { " · slept ${Sleep.formatDuration(it)}" } ?: " · `sleep HH:MM` to add the bedtime"))
            }
            "slept" -> {
                // `slept 23:30 06:45` – both anchors for last night in one go
                val parts = rest.trim().split(Regex("\\s+|\\s*(->|→|-)\\s*")).filter { it.isNotBlank() }
                val bed = parts.getOrNull(0)?.let { Sleep.parseClock(it) }
                val wake = parts.getOrNull(1)?.let { Sleep.parseClock(it) }
                if (bed == null || wake == null) return err("usage: slept 23:30 06:45")
                app.habits.logSleep(date, bedMinutes = Sleep.bedMinutesFor(bed), wakeMinutes = wake, note = comment.ifBlank { null })
                ok(Sleep.summary(app.habits.sleep(date)) ?: "logged")
            }
            "pause" -> {
                // `pause run until 2026-10-01` · `pause run 2w` · `pause run` (list / default 1w)
                val m = Regex("^(.*?)\\s*(until\\s+\\S+|till\\s+\\S+|\\d+\\s*[dwm]|\\d{4}-\\d{2}-\\d{2})$", RegexOption.IGNORE_CASE).matchEntire(rest.trim())
                val ref = m?.groupValues?.get(1)?.trim() ?: rest.trim()
                val untilArg = m?.groupValues?.get(2) ?: "1w"
                if (ref.isBlank()) {
                    val paused = app.habits.allHabits().filter { !it.archived && it.isPausedOn(date) }
                    ok(if (paused.isEmpty()) "nothing paused · pause <habit> [until yyyy-mm-dd | 2w | 10d | 1m]" else paused.joinToString("\n") { "‖ ${it.name} · ${Schedule.pauseLabel(it, date)}" })
                } else habit(app, ref)?.let { h ->
                    val until = Schedule.parseUntil(untilArg, date) ?: return err("can't read '$untilArg' · try `until 2026-10-01`, `2w`, `10d`, `1m`")
                    app.habits.pauseHabit(h, until); ok("‖ ${h.name} paused → $until · `resume ${h.name}` to bring it back early")
                } ?: noHabit(ref)
            }
            "resume", "unpause" -> habit(app, rest)?.let { h ->
                if (!h.isPausedOn(date)) err("${h.name} isn't paused") else { app.habits.pauseHabit(h, null); ok("▶ ${h.name} back on the list") }
            } ?: noHabit(rest)
            "undo", "uncheck" -> habit(app, rest)?.let { h ->
                if (h.type == HabitType.CHECKBOX && !h.negative) { if (status(app, h.id, date)?.completed == true) app.habits.toggle(h.id, date) }
                else app.habits.setValue(h.id, 0, date)
                ok("[ ] ${h.name} reset")
            } ?: noHabit(rest)
            "add", "inc", "+", "plus" -> numberThenHabit(app, rest, 1)?.let { (n, h) ->
                app.habits.addValue(h.id, n, date); val s = status(app, h.id, date); ok("${h.name} ${s?.value ?: n}/${s?.target ?: h.target} ${h.unit}".trim() + if (s?.partial == true) " · [~] min" else "")
            } ?: err("usage: add <n> <habit>")
            "sub", "-", "minus", "dec" -> numberThenHabit(app, rest, 1)?.let { (n, h) ->
                app.habits.addValue(h.id, -n, date); val s = status(app, h.id, date); ok("${h.name} ${s?.value ?: 0}/${s?.target ?: h.target}")
            } ?: err("usage: sub <n> <habit>")
            "set" -> numberThenHabit(app, rest, null)?.let { (n, h) -> app.habits.setValue(h.id, n, date); ok("${h.name} = $n ${h.unit}".trim()) } ?: err("usage: set <n> <habit>")
            "skip" -> habit(app, rest)?.let { h -> app.habits.skip(h.id, comment, date); ok("[»] ${h.name} skipped" + if (comment.isNotBlank()) " ($comment)" else "") } ?: noHabit(rest)
            "unskip" -> habit(app, rest)?.let { h -> app.habits.unskip(h.id, date); ok("${h.name} un-skipped") } ?: noHabit(rest)
            "vault" -> ok("watch vault", Routes.WATCH_BOX)
            "insure", "report" -> ok("collection report", Routes.COLLECTION_REPORT)
            "away", "insurance" -> {
                if (rest.isBlank() || rest.trim() == "list" || rest.trim() == "ls") {
                    val rules = app.habits.skipRules()
                    ok(if (rules.isEmpty()) "no insurance rules · `away travel 3d` / `away sick 2026-09-12 2026-09-19` / `away travel` (open)"
                    else rules.joinToString("\n") { (if (it.enabled) "[✓] " else "[ ] ") + dev.personalterminal.domain.SkipRules.describe(it) }, Routes.SKIP_RULES)
                } else {
                    val rule = dev.personalterminal.domain.SkipRules.parseRange(rest, date) ?: return err("usage: away <reason> [<n>d | yyyy-mm-dd [yyyy-mm-dd]]")
                    app.habits.saveSkipRule(rule, date)
                    ok("insurance on · " + dev.personalterminal.domain.SkipRules.describe(rule) + (if (rule.toDay == null) " · say `back` when you return" else ""), Routes.SKIP_RULES)
                }
            }
            "back", "home" -> {
                val open = app.habits.skipRules().filter { it.enabled && it.kind == dev.personalterminal.data.db.SkipRule.KIND_RANGE && it.toDay == null }
                if (open.isEmpty()) err("no open-ended insurance rule is running") else { open.forEach { app.habits.endSkipRule(it.id, date) }; ok("welcome back · ${open.joinToString { it.name }} ended today, tomorrow counts again") }
            }
            "slip", "fail" -> habit(app, rest)?.let { h ->
                if (!h.negative) err("${h.name} is not an avoid-habit") else { app.habits.logSlip(h.id, true, date); ok("[✗] ${h.name} — slip logged, tomorrow is a new day") }
            } ?: noHabit(rest)
            "note" -> habit(app, rest)?.let { h ->
                if (comment.isBlank()) err("usage: note <habit> -- <text>") else { app.habits.annotate(h.id, date, note = comment); ok("note saved for ${h.name}") }
            } ?: noHabit(rest)
            "mood" -> {
                val parts = rest.split(" ", limit = 2)
                val m = parts.firstOrNull()?.toIntOrNull()
                if (m == null || m !in 1..5) err("usage: mood <1-5> [habit]")
                else {
                    val h = parts.getOrNull(1)?.let { habit(app, it) } ?: app.habits.daySummary(date).due.lastOrNull { it.completed }?.habit
                    if (h == null) err("no habit to attach the mood to") else { app.habits.annotate(h.id, date, mood = m); ok("mood ${"★".repeat(m)}${"☆".repeat(5 - m)} → ${h.name}") }
                }
            }
            "timer", "focus", "pomo" -> timer(context, app, rest)
            "stopwatch", "sw" -> {
                val h = if (rest.isBlank()) null else habit(app, rest) ?: return noHabit(rest)
                PomodoroService.startStopwatch(context, h?.id ?: 0L, h?.name ?: "")
                ok("stopwatch running" + (h?.let { " → ${it.name}" } ?: ""), Routes.timer(h?.id ?: 0L))
            }
            "wear", "w" -> {
                if (rest.isBlank()) return ok("open wear log", Routes.wearLog(date.toEpochDay()))
                watch(app, rest)?.let { w -> app.watches.logWear(w.id, date, note = comment); app.habits.mutations.value = System.currentTimeMillis(); ok("⌚ ${w.displayName} on the wrist today") } ?: err("no watch matches '$rest'")
            }
            "strap", "straps" -> {
                // `strap` → straps page · `strap <strap> <watch>` fits it (logs a swap) · `strap <strap> drawer` · `strap <strap>` shows where it is
                val arg = rest.trim()
                if (arg.isBlank() || arg == "ls") return ok("straps", Routes.STRAPS)
                val straps = app.watches.straps()
                val words = arg.split(Regex("\\s+|\\s*(->|→|on)\\s+")).filter { it.isNotBlank() }
                var match: Pair<dev.personalterminal.data.db.Strap, String>? = null
                for (split in words.size downTo 1) {
                    val ref = words.take(split).joinToString(" ")
                    val st = straps.firstOrNull { it.name.equals(ref, true) } ?: straps.filter { it.name.contains(ref, true) }.takeIf { it.size == 1 }?.first()
                    if (st != null) { match = st to words.drop(split).joinToString(" "); break }
                }
                val (strap, target) = match ?: return err("no strap matches '$arg' · `strap` lists them")
                when {
                    target.isBlank() -> {
                        val on = strap.watchId?.let { id -> app.watches.allWatches().firstOrNull { it.id == id } }
                        val days = app.watches.strapFittedDays(strap)
                        ok(if (on == null) "${strap.name}: in the drawer" else "${strap.name}: on ${on.displayName}" + (days?.let { " for ${it}d" } ?: ""))
                    }
                    target.lowercase() in setOf("drawer", "off", "none") -> { app.watches.fitStrap(strap, null, date, comment); ok("${strap.name} → drawer") }
                    else -> watch(app, target)?.let { w -> app.watches.fitStrap(strap, w.id, date, comment); ok("${strap.name} → ${w.displayName} · swap logged") } ?: err("no watch matches '$target'")
                }
            }
            "watch" -> {
                val (sub, arg) = split(rest.trim())
                when (sub.lowercase()) {
                    "next", "suggest" -> ok(app.watches.suggestNext()?.let { "watch next → ${it.first.displayName}: ${it.second}" } ?: "add a watch first", Routes.WATCHES)
                    "vault", "box", "grid" -> ok("watch vault", Routes.WATCH_BOX)
                    "stats" -> ok("watch stats", Routes.WATCH_STATS)
                    "insure", "report", "documents" -> ok("collection report", Routes.COLLECTION_REPORT)
                    "sheet", "contact" -> ok("contact sheet", Routes.CONTACT_SHEET)
                    "", "ls" -> ok("open watches", Routes.WATCHES)
                    "challenges", "challenge", "rotation" -> {
                        val list = app.watches.challenges(date)
                        if (list.isEmpty()) err("rotation challenges need at least two watches") else ok("rotation challenges\n" + list.joinToString("\n") { it.line + "\n    # " + it.detail }, Routes.WATCHES)
                    }
                    "sold", "sell" -> {
                        // watch sold <watch> [price]
                        val parts = arg.trim().split(Regex("\\s+"))
                        val price = parts.lastOrNull()?.toDoubleOrNull()
                        val ref = if (price != null) parts.dropLast(1).joinToString(" ") else arg
                        watch(app, ref)?.let { w ->
                            if (w.sold) return err("${w.displayName} is already marked sold")
                            app.watches.markSold(w, price, date); app.habits.mutations.value = System.currentTimeMillis()
                            val gain = if (price != null && w.purchasePrice != null) price - w.purchasePrice else null
                            ok("${w.displayName} → sold" + (price?.let { " for ${w.currency} ${"%,.0f".format(it)}".trimEnd() } ?: "") + (gain?.let { g -> " · ${if (g >= 0) "+" else ""}${"%,.0f".format(g)} realised" } ?: "") + " · history kept", Routes.watchDetail(w.id))
                        } ?: err("no watch matches '$ref'")
                    }
                    "repair", "service", "away" -> watch(app, arg)?.let { w ->
                        if (w.inRepair) err("${w.displayName} is already at the watchmaker since ${LocalDate.ofEpochDay(w.statusDay)}")
                        else { app.watches.sendForRepair(w, date, comment); app.habits.mutations.value = System.currentTimeMillis(); ok("${w.displayName} → in repair · `watch back ${w.displayName}` when it returns", Routes.watchDetail(w.id)) }
                    } ?: err("no watch matches '$arg'")
                    "back", "returned", "own", "keep" -> {
                        // watch back <watch> [cost]
                        val parts = arg.trim().split(Regex("\\s+"))
                        val cost = parts.lastOrNull()?.toDoubleOrNull()
                        val ref = if (cost != null) parts.dropLast(1).joinToString(" ") else arg
                        watch(app, ref)?.let { w ->
                            when {
                                w.inRepair -> { app.watches.backFromRepair(w, date, cost, comment); app.habits.mutations.value = System.currentTimeMillis(); ok("${w.displayName} is back on the roster" + (cost?.let { " · ${w.currency} ${"%,.0f".format(it)} logged".trimEnd() } ?: ""), Routes.watchDetail(w.id)) }
                                w.sold || w.wished -> { app.watches.markOwned(w, date, cost); app.habits.mutations.value = System.currentTimeMillis(); ok("${w.displayName} → owned", Routes.watchDetail(w.id)) }
                                else -> err("${w.displayName} is already in the collection")
                            }
                        } ?: err("no watch matches '$ref'")
                    }
                    "buy", "bought", "acquire" -> {
                        // watch buy <wishlist watch> [paid]
                        val parts = arg.trim().split(Regex("\\s+"))
                        val paid = parts.lastOrNull()?.toDoubleOrNull()
                        val ref = if (paid != null) parts.dropLast(1).joinToString(" ") else arg
                        watch(app, ref)?.let { w ->
                            if (!w.wished) err("${w.displayName} is not on the wishlist") else { app.watches.acquire(w, paid, date); app.habits.mutations.value = System.currentTimeMillis(); ok("⌚ ${w.displayName} → owned · congratulations", Routes.watchDetail(w.id)) }
                        } ?: err("no wishlist watch matches '$ref'")
                    }
                    "uptime" -> uptime(app)
                    else -> watch(app, rest)?.let { ok("open ${it.displayName}", Routes.watchDetail(it.id)) } ?: err("no watch matches '$rest'")
                }
            }
            "uptime" -> uptime(app)
            "wish", "wishlist" -> {
                // wish → list · wish <brand model> [target] → add · wish rm <ref>
                val (sub, arg) = split(rest.trim())
                when {
                    rest.isBlank() || rest == "ls" -> {
                        val l = app.watches.lifecycle()
                        if (l.wishlist.isEmpty()) ok("wishlist empty · `wish Tudor BB58 4500` adds one", Routes.WISHLIST)
                        else ok("wishlist\n" + l.wishlist.joinToString("\n") { w ->
                            val t = w.targetPrice
                            "  ${w.displayName.take(18).padEnd(18)} " + (if (t != null && t > 0) "${"%,.0f".format(w.savedSoFar)}/${"%,.0f".format(t)} · ${((w.savedSoFar / t) * 100).toInt().coerceIn(0, 100)}% funded" else "no target")
                        }, Routes.WISHLIST)
                    }
                    sub == "rm" || sub == "remove" -> watch(app, arg, anyStatus = true)?.let { w ->
                        if (!w.wished) err("${w.displayName} is not on the wishlist") else { app.watches.deleteWatch(w); ok("${w.displayName} removed from the wishlist") }
                    } ?: err("no wishlist watch matches '$arg'")
                    else -> {
                        val parts = rest.trim().split(Regex("\\s+"))
                        val target = parts.lastOrNull()?.toDoubleOrNull()
                        val words = if (target != null) parts.dropLast(1) else parts
                        if (words.isEmpty()) return err("usage: wish <brand> <model> [target price]")
                        val brand = words.first(); val model = words.drop(1).joinToString(" ")
                        val cur = app.watches.allWatches().firstNotNullOfOrNull { it.currency.takeIf { c -> c.isNotBlank() } } ?: ""
                        val id = app.watches.saveWatch(Watch(brand = brand, model = model, status = Watch.STATUS_WISHLIST, statusDay = date.toEpochDay(), targetPrice = target, currency = cur, notes = comment))
                        app.habits.mutations.value = System.currentTimeMillis()
                        ok("☆ $brand $model on the wishlist" + (target?.let { " · target ${"%,.0f".format(it)}" } ?: "") + " · `save 200 ${model.ifBlank { brand }}` builds the fund", Routes.watchDetail(id))
                    }
                }
            }
            "save", "fund" -> {
                // save 200 <wishlist watch> · save -50 <watch>
                val parts = rest.trim().split(Regex("\\s+"), limit = 2)
                val amount = parts.firstOrNull()?.toDoubleOrNull() ?: return err("usage: save <amount> <wishlist watch>")
                val w = watch(app, parts.getOrNull(1) ?: "", anyStatus = true)?.takeIf { it.wished }
                    ?: app.watches.lifecycle().wishlist.singleOrNull()
                    ?: return err("which wishlist watch? · `wish` lists them")
                app.watches.addSavings(w, amount); app.habits.mutations.value = System.currentTimeMillis()
                val fresh = app.watches.watch(w.id) ?: w
                val t = fresh.targetPrice
                ok("${fresh.displayName}: ${"%,.0f".format(fresh.savedSoFar)}" + (if (t != null && t > 0) " / ${"%,.0f".format(t)} · ${((fresh.savedSoFar / t) * 100).toInt().coerceIn(0, 100)}% funded" + (if (fresh.savedSoFar >= t) " · `watch buy ${fresh.displayName}` when it lands" else "") else " saved"), Routes.WISHLIST)
            }
            "tick", "item" -> {
                // tick <habit> <item name | number>  – toggles one step of a checklist habit
                val words = rest.split(Regex("\\s+"))
                if (words.size < 2) return err("usage: tick <habit> <item>")
                var found: Pair<dev.personalterminal.data.db.Habit, Int>? = null
                for (split in words.size - 1 downTo 1) {
                    val h = habit(app, words.take(split).joinToString(" ")) ?: continue
                    if (h.type != HabitType.CHECKLIST) return err("${h.name} is not a checklist habit")
                    val itemRef = words.drop(split).joinToString(" ")
                    val idx = itemRef.toIntOrNull()?.minus(1) ?: h.checklistItems.indexOfFirst { it.equals(itemRef, true) }.takeIf { it >= 0 }
                        ?: h.checklistItems.indexOfFirst { it.startsWith(itemRef, true) }.takeIf { it >= 0 }
                    if (idx == null || idx !in h.checklistItems.indices) return err("${h.name}: items are " + h.checklistItems.mapIndexed { i, it -> "${i + 1}=$it" }.joinToString(" "))
                    found = h to idx; break
                }
                val (h, idx) = found ?: return noHabit(words.first())
                app.habits.toggleItem(h.id, idx, date)
                val log = app.db.habitLogDao().get(h.id, date.toEpochDay())
                val on = log?.hasItem(idx) == true
                ok((if (on) "[✓] " else "[ ] ") + "${h.checklistItems[idx]} · ${h.name} ${log?.value ?: 0}/${h.checklistItems.size}" + if (log?.completed == true) " ✓ done" + xpHint() else "")
            }
            "remind", "notify" -> {
                // remind <habit> 07:30 | remind <habit> off | remind <habit> checkin on|off | remind (list)
                if (rest.isBlank()) {
                    val hs = app.habits.allHabits().filter { !it.archived && (it.reminderMinutes >= 0 || it.checkIn) }
                    return ok(hs.joinToString("\n") { h -> "⏰ " + h.name + (if (h.reminderMinutes >= 0) " %02d:%02d".format(h.reminderMinutes / 60, h.reminderMinutes % 60) else "") + (if (h.checkIn) " · check-in" else "") }.ifBlank { "no reminders set · remind <habit> 07:30" })
                }
                val words = rest.split(Regex("\\s+"))
                val last = words.last().lowercase()
                val timeMatch = Regex("^(\\d{1,2})(?::(\\d{2}))?$").matchEntire(last)
                val h: dev.personalterminal.data.db.Habit?
                val result: String
                when {
                    words.size >= 3 && words[words.size - 2].lowercase() in setOf("checkin", "check-in", "ci") && last in setOf("on", "off") -> {
                        h = habit(app, words.dropLast(2).joinToString(" ")) ?: return noHabit(rest)
                        app.habits.saveHabit(h.copy(checkIn = last == "on")); result = "${h.name}: evening check-in ${last}"
                    }
                    last == "off" -> { h = habit(app, words.dropLast(1).joinToString(" ")) ?: return noHabit(rest); app.habits.saveHabit(h.copy(reminderMinutes = -1)); result = "${h.name}: reminder off" }
                    timeMatch != null -> {
                        val hh = timeMatch.groupValues[1].toInt(); val mm = timeMatch.groupValues[2].ifBlank { "0" }.toInt()
                        if (hh !in 0..23 || mm !in 0..59) return err("time must be HH:MM")
                        h = habit(app, words.dropLast(1).joinToString(" ")) ?: return noHabit(rest)
                        app.habits.saveHabit(h.copy(reminderMinutes = hh * 60 + mm)); result = "${h.name}: reminder at %02d:%02d".format(hh, mm)
                    }
                    else -> return err("usage: remind <habit> 07:30 | off | checkin on|off")
                }
                runCatching { dev.personalterminal.reminders.ReminderScheduler.reschedule(context) }
                ok("⏰ $result")
            }
            "shield" -> habit(app, rest)?.let { h ->
                val day = app.habits.streakFor(h.id, date)?.repairableDay ?: return err("${h.name}: nothing to repair")
                if (app.habits.useShield(h.id, day)) ok("⛨ shield used for ${h.name} on $day") else err("no shields available")
            } ?: noHabit(rest)
            "habit" -> when {
                rest.startsWith("add") -> {
                    val tpl = rest.removePrefix("add").trim()
                    if (tpl.isBlank()) ok("new habit", Routes.habitEdit())
                    else Templates.all.firstOrNull { it.id == tpl || it.name.equals(tpl, true) }?.let { t ->
                        app.habits.saveHabit(t.toHabit()); ok("added '${t.name}' from template")
                    } ?: ok("new habit '$tpl'", Routes.habitEdit(name = tpl))
                }
                rest.startsWith("rm") || rest.startsWith("archive") -> habit(app, rest.substringAfter(" ", ""))?.let { h -> app.habits.setArchived(h, true); ok("${h.name} archived") } ?: noHabit(rest)
                rest.isBlank() || rest == "ls" -> ok("habits", Routes.HABITS)
                else -> habit(app, rest)?.let { ok(it.name, Routes.habitDetail(it.id)) } ?: noHabit(rest)
            }
            "ls", "list" -> {
                val s = app.habits.daySummary(date)
                ok(s.due.joinToString("\n") { hs -> (if (hs.completed) "[✓] " else if (hs.skipped) "[»] " else if (hs.partial) "[~] " else "[ ] ") + hs.habit.name + if (hs.habit.type != HabitType.CHECKBOX) " ${hs.value}/${hs.target}" else "" }.ifBlank { "nothing due today" })
            }
            "status", "st" -> { val s = app.habits.daySummary(date); ok("${s.done}/${s.active.size} done · ⛨ ${s.shieldsAvailable} · ${s.totalXp} xp") }
            "review", "weekly" -> if (rest.trim().lowercase() in setOf("year", "--year", "annual")) ok("year in review", Routes.YEAR_REVIEW) else ok("weekly review", Routes.REVIEW)
            "year" -> ok("year in review", Routes.YEAR_REVIEW)
            "man", "achievements" -> ok("achievements", Routes.ACHIEVEMENTS)
            "insights", "correlations" -> ok("insights", Routes.INSIGHTS)
            "garden", "plant", "grow" -> ok("garden", Routes.GARDEN)
            "challenge", "chal" -> {
                // `challenge <habit> 30 [stake]` · `challenge <habit>` status/certificate · `challenge <habit> off`
                val today = date
                if (rest.isBlank()) {
                    val active = app.habits.allHabits().filter { dev.personalterminal.domain.Challenges.isActive(it, today) }
                    if (active.isEmpty()) err("no active challenges · `challenge <habit> 30 [stake]` starts one")
                    else ok(active.joinToString("\n") { it.name + "  " + (dev.personalterminal.domain.Challenges.label(it, today) ?: "") + "  " + dev.personalterminal.domain.Challenges.progressBar(it, today) })
                } else {
                    val words = rest.trim().split(Regex("\\s+"))
                    val off = words.lastOrNull()?.lowercase() in setOf("off", "clear", "rm", "end")
                    val nums = if (off) emptyList() else words.takeLastWhile { it.toIntOrNull() != null }
                    val ref = words.dropLast(if (off) 1 else nums.size).joinToString(" ")
                    habit(app, ref)?.let { h ->
                        when {
                            off -> {
                                app.habits.saveHabit(h.copy(chStart = 0L, chEnd = 0L, chStake = 0, chResolved = false))
                                ok("${h.name}: challenge cleared")
                            }
                            dev.personalterminal.domain.Challenges.isActive(h, today) -> ok(dev.personalterminal.domain.Challenges.status(h, today))
                            nums.isEmpty() && dev.personalterminal.domain.Challenges.hasChallenge(h) && h.chResolved -> ok("${h.name}: challenge closed · `status` shows the xp ledger")
                            nums.isEmpty() && dev.personalterminal.domain.Challenges.isSettling(h, today) -> {
                                val s = app.habits.settleChallenge(h)
                                if (s == null) err("${h.name}: window still open")
                                else {
                                    val fresh = app.habits.allHabits().first { it.id == h.id }
                                    ok(dev.personalterminal.domain.Challenges.certificate(fresh, s, java.time.LocalDate.ofEpochDay(h.chStart), java.time.LocalDate.ofEpochDay(h.chEnd)))
                                }
                            }
                            nums.isEmpty() -> ok("usage: challenge <habit> <days> [stake]  ·  challenge <habit> off")
                            nums.size > 2 -> err("usage: challenge <habit> <days> [stake]")
                            else -> {
                                val days = nums[0]
                                val stake = nums.getOrNull(1) ?: 0
                                if (days < 2 || days > 366) err("challenge length: 2–366 days")
                                else if (stake < 0 || stake > 50) err("stake: 0–50")
                                else {
                                    app.habits.saveHabit(
                                        h.copy(chStart = today.toEpochDay(), chEnd = today.toEpochDay() + days - 1, chStake = stake, chResolved = false)
                                    )
                                    ok("challenge started: ${h.name} · $days days" +
                                        (if (stake > 0) " · stake $stake (+${stake * 10} / -${stake * 5} xp)" else "") +
                                        "\nfrom $today to ${today.plusDays(days - 1L)}")
                                }
                            }
                        }
                    } ?: noHabit(ref)
                }
            }
            "saver" -> {
                val id = rest.trim().lowercase().substringBefore(' ')
                when {
                    id.isBlank() || id == "ls" -> ok("saver: ${app.prefs.current().saverPattern} · options: off matrix life stars")
                    id in listOf("off", "matrix", "life", "stars") -> {
                        app.prefs.setSaver(id)
                        ok("saver → $id" + if (id == "off") "" else " (after ${app.prefs.current().saverAfterMin} min idle)")
                    }
                    else -> err("saver: off matrix life stars")
                }
            }
            "ambient", "amb" -> {
                val id = rest.trim().lowercase().substringBefore(' ')
                when {
                    id.isBlank() || id == "ls" -> ok("amb: ${app.prefs.current().ambientSound} · options: off rain brown keys")
                    id in dev.personalterminal.timer.AmbientAudio.PATTERNS -> {
                        app.prefs.setAmbient(id)
                        dev.personalterminal.timer.AmbientAudio.setPattern(id)
                        ok("ambient → $id" + if (id == "off") "" else " (plays during focus phases)")
                    }
                    else -> err("ambient: off rain brown keys")
                }
            }
            "timeline", "log" -> ok("timeline", Routes.TIMELINE)
            "profile", "whoami" -> ok("profile", Routes.PROFILE)
            "settings", "config", "vim" -> ok("settings", Routes.SETTINGS)
            "theme" -> {
                val id = rest.trim().lowercase()
                val fam = dev.personalterminal.ui.theme.ThemeFamily.entries.firstOrNull { it.id == id }
                if (fam == null && id != "custom") err("themes: " + dev.personalterminal.ui.theme.ThemeFamily.entries.joinToString(" ") { it.id })
                else { app.prefs.setTheme(id); ok("theme → $id") }
            }
            "font" -> {
                val id = rest.trim().lowercase()
                val opts = dev.personalterminal.ui.theme.Fonts.options
                when {
                    id.isBlank() || id == "ls" -> ok(opts.joinToString("\n") { "${it.id.padEnd(10)} ${it.label}" })
                    id == "theme" || id == "auto" -> { app.prefs.unpinFont(app.prefs.current().themeName); ok("font follows the theme again") }
                    opts.none { it.id == id } -> err("fonts: " + opts.joinToString(" ") { it.id })
                    else -> { app.prefs.setFont(id); ok("font → $id (pinned)") }
                }
            }
            "icon" -> {
                val id = rest.trim().lowercase()
                val opts = dev.personalterminal.LauncherIcons.options
                if (opts.none { it.id == id }) err("icons: " + opts.joinToString(" ") { it.id })
                else { app.prefs.setLauncherIcon(id); runCatching { dev.personalterminal.LauncherIcons.apply(context, id) }; ok("icon → $id") }
            }
            "dark" -> { app.prefs.setThemeMode(dev.personalterminal.data.prefs.ThemeMode.DARK); ok("mode → dark") }
            "light" -> { app.prefs.setThemeMode(dev.personalterminal.data.prefs.ThemeMode.LIGHT); ok("mode → light") }
            "backup" -> { dev.personalterminal.sync.DriveSync.requestSoon(context); ok("backup requested") }
            "goto", "cd", "open" -> ok("→ $rest", rest.trim())
            "help", "?" -> ok(HELP)
            "clear" -> ok("")
            else -> {
                // bare habit name → toggle it, like typing `done`
                habit(app, trimmed)?.let { h -> run(context, app, "done $trimmed", date) } ?: err("command not found: $head · try `help`")
            }
        }
    }

    private suspend fun timer(context: Context, app: PersonalTerminalApp, rest: String): Result {
        val parts = rest.trim().split(" ", limit = 2)
        when (parts.firstOrNull()?.lowercase()) {
            "stop" -> { PomodoroService.send(context, PomodoroService.ACTION_STOP); return ok("timer stopped") }
            "pause" -> { PomodoroService.send(context, PomodoroService.ACTION_PAUSE); return ok("timer paused") }
            "resume" -> { PomodoroService.send(context, PomodoroService.ACTION_RESUME); return ok("timer resumed") }
            "skip" -> { PomodoroService.send(context, PomodoroService.ACTION_SKIP); return ok("phase skipped") }
        }
        val minutes = parts.firstOrNull()?.toIntOrNull()
        val habitRef = if (minutes != null) parts.getOrNull(1) ?: "" else rest.trim()
        val h = if (habitRef.isBlank()) null else habit(app, habitRef) ?: return noHabit(habitRef)
        val s = app.prefs.current()
        val focus = minutes ?: h?.focusMinutes?.takeIf { it > 0 } ?: s.pomodoroFocusMin
        val brk = h?.breakMinutes?.takeIf { it > 0 } ?: s.pomodoroBreakMin
        PomodoroService.start(context, focus, brk, s.pomodoroLongBreakMin, h?.id ?: 0L, h?.name ?: "")
        return ok("focus ${focus}m started" + (h?.let { " → ${it.name}" } ?: ""), Routes.timer(h?.id ?: 0L))
    }

    private fun xpHint() = " (+10 xp)"
    private fun noHabit(ref: String) = err(if (ref.isBlank()) "which habit?" else "no habit matches '$ref'")

    /** `uptime` – power-reserve status of the mechanical watches, stopped first, with wind-and-set steps. */
    private suspend fun uptime(app: PersonalTerminalApp): Result {
        val list = app.watches.uptime()
        if (list.isEmpty()) return err("no mechanical watches – set a power reserve in `watch edit`")
        val now = AppClock.now()
        val up = java.time.Duration.between(LocalDate.ofEpochDay(app.watches.allWear().minOfOrNull { it.day } ?: now.toLocalDate().toEpochDay()).atStartOfDay(), now).toDays()
        return ok("${"%02d:%02d".format(now.hour, now.minute)} up $up days, ${list.size} watch${if (list.size == 1) "" else "es"}\n" + dev.personalterminal.domain.Uptime.render(list), Routes.WATCHES)
    }

    private suspend fun status(app: PersonalTerminalApp, id: Long, date: LocalDate) =
        app.habits.daySummary(date).all.firstOrNull { it.habit.id == id }

    /** id, exact name, then unique prefix / substring – case-insensitive. */
    suspend fun habit(app: PersonalTerminalApp, ref: String): Habit? {
        val r = ref.trim().trim('"', '\'')
        if (r.isBlank()) return null
        val all = app.habits.allHabits().filter { !it.archived }
        r.toLongOrNull()?.let { id -> all.firstOrNull { it.id == id }?.let { return it } }
        all.firstOrNull { it.name.equals(r, true) }?.let { return it }
        val prefix = all.filter { it.name.startsWith(r, true) }
        if (prefix.size == 1) return prefix.first()
        val contains = all.filter { it.name.contains(r, true) }
        if (contains.size == 1) return contains.first()
        // initials: "dw" → "drink water"
        val initials = all.filter { h -> h.name.split(" ").filter { it.isNotBlank() }.map { it.first().lowercaseChar() }.joinToString("") == r.lowercase() }
        return if (initials.size == 1) initials.first() else prefix.firstOrNull() ?: contains.firstOrNull()
    }

    /** Resolves a watch reference; the collection (owned / in repair) wins, then sold and wishlist pieces. */
    suspend fun watch(app: PersonalTerminalApp, ref: String, anyStatus: Boolean = false): Watch? {
        val r = ref.trim().trim('"', '\'')
        if (r.isBlank()) return null
        val all = app.watches.allWatches().filter { !it.archived }
        val pools = if (anyStatus) listOf(all) else listOf(all.filter { it.owned }, all.filter { !it.owned })
        for (pool in pools) {
            r.toLongOrNull()?.let { id -> pool.firstOrNull { it.id == id }?.let { return it } }
            pool.firstOrNull { it.nickname.equals(r, true) || it.displayName.equals(r, true) || it.model.equals(r, true) }?.let { return it }
            val m = pool.filter { it.nickname.contains(r, true) || it.displayName.contains(r, true) || it.reference.contains(r, true) }
            if (m.isNotEmpty()) return m.first()
        }
        return null
    }

    private suspend fun numberThenHabit(app: PersonalTerminalApp, rest: String, default: Int?): Pair<Int, Habit>? {
        val parts = rest.trim().split(" ", limit = 2)
        val n = parts.firstOrNull()?.toIntOrNull()
        return if (n != null) habit(app, parts.getOrNull(1) ?: "")?.let { n to it }
        else if (default != null) habit(app, rest)?.let { default to it }
        else null
    }

    private fun split(line: String): Pair<String, String> {
        val i = line.indexOf(' ')
        return if (i < 0) line to "" else line.substring(0, i) to line.substring(i + 1).trim()
    }

    /** `foo -- some comment` → ("foo", "some comment"); also accepts a trailing `# comment`. */
    private fun String.splitComment(): Pair<String, String> {
        val sep = listOf(" -- ", " # ").map { indexOf(it) }.filter { it >= 0 }.minOrNull()
        return if (sep == null) {
            if (startsWith("-- ")) "" to removePrefix("-- ").trim() else trim() to ""
        } else substring(0, sep).trim() to substring(sep + 3).trim()
    }

    val HELP = """
        |done <habit>          undo <habit>
        |add <n> <habit>       set <n> <habit>
        |skip <habit> -- why   slip <habit>
        |note <habit> -- text  mood <1-5> [habit]
        |timer [min] [habit]   stopwatch [habit]
        |timer stop|pause      wear <watch>
        |watch next · vault    shield <habit>
        |watch sold|repair|back <w>   uptime
        |wish <brand model> 4500 · save 200 <w>
        |watch buy <w> · watch report
        |yesterday <habit>     (late log, till 12:00)
        |pause <habit> 2w      resume <habit>
        |sleep 23:30 · wake 06:45 · slept 23:30 06:45
        |strap <strap> <watch|drawer>   swap log
        |tick <habit> <item>   remind <habit> 07:30
        |away <why> [3d|dates] back
        |habit add [template]  ls · status
        |stats [habit]         30/90/365d block
        |strength [habit]      min <habit> [n]
        |ramp <habit> 30 8     after <habit> <anchor>
        |area <habit> body     sort strength|routine
        |review [year] · achievements · insights
        |garden · saver matrix|off · amb rain|keys
        |challenge <habit> 30 [3]  challenge off
        |theme <name> · font <name> · icon <name>
    """.trimMargin()
}
