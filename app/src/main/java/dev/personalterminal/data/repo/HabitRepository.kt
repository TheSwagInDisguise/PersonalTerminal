package dev.personalterminal.data.repo

import dev.personalterminal.domain.AppClock
import dev.personalterminal.data.db.AppDatabase
import dev.personalterminal.data.db.DayCount
import dev.personalterminal.data.db.Habit
import dev.personalterminal.data.db.SleepLog
import dev.personalterminal.data.db.isPausedOn
import dev.personalterminal.data.db.HabitLog
import dev.personalterminal.data.db.HabitType
import dev.personalterminal.data.db.HabitWithLogs
import dev.personalterminal.data.db.checklistItems
import dev.personalterminal.data.db.FocusSession
import dev.personalterminal.data.db.Routine
import dev.personalterminal.data.db.ScheduleType
import dev.personalterminal.data.db.ShieldUse
import dev.personalterminal.data.db.XpEvent
import dev.personalterminal.domain.DaySummary
import dev.personalterminal.domain.HabitStatus
import dev.personalterminal.domain.Progression
import dev.personalterminal.domain.RoutineGroup
import dev.personalterminal.data.db.SkipRule
import dev.personalterminal.domain.Schedule
import dev.personalterminal.domain.SkipRules
import dev.personalterminal.domain.StreakInfo
import dev.personalterminal.domain.Streaks
import dev.personalterminal.domain.Strength
import dev.personalterminal.domain.Targets
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * Single source of truth for habits, logs, streaks, shields and XP.
 * All mutations go through here so the widget, the UI and the backup module see consistent data.
 */
class HabitRepository(private val db: AppDatabase) {

    private val habitDao = db.habitDao()
    private val logDao = db.habitLogDao()
    private val shieldDao = db.shieldDao()
    private val routineDao = db.routineDao()
    private val xpDao = db.xpDao()
    private val sessionDao = db.focusSessionDao()
    private val ruleDao = db.skipRuleDao()
    private val sleepDao = db.sleepDao()

    /** Emits whenever a mutation happened – lets the widget and other non-Flow consumers refresh. */
    val mutations = MutableStateFlow(0L)

    // ------------------------------------------------------------------ observation

    fun observeRoutines(): Flow<List<Routine>> = routineDao.observeAll()
    fun observeHabits(): Flow<List<Habit>> = habitDao.observeAll()
    fun observeActiveHabits(): Flow<List<Habit>> = habitDao.observeActive()
    fun observeHabit(id: Long): Flow<Habit?> = habitDao.observeById(id)
    fun observeHabitWithLogs(id: Long): Flow<HabitWithLogs?> = habitDao.observeWithLogs(id)
    fun observeTotalXp(): Flow<Int> = xpDao.observeTotal()
    fun observeRecentXp(limit: Int = 20): Flow<List<XpEvent>> = xpDao.observeRecent(limit)
    fun observeCompletionCounts(from: LocalDate, to: LocalDate): Flow<List<DayCount>> =
        logDao.observeCompletionCounts(from.toEpochDay(), to.toEpochDay())
    fun observeLogsRange(from: LocalDate, to: LocalDate): Flow<List<HabitLog>> =
        logDao.observeRange(from.toEpochDay(), to.toEpochDay())
    fun observeJournal(limit: Int = 30): Flow<List<HabitLog>> = logDao.observeJournal(limit)
    fun observeFocusSessions(limit: Int = 50): Flow<List<FocusSession>> = sessionDao.observeRecent(limit)
    fun observeFocusSessionsFor(habitId: Long): Flow<List<FocusSession>> = sessionDao.observeForHabit(habitId)
    fun observeFocusMinutesPerDay(from: LocalDate, to: LocalDate): Flow<List<DayCount>> =
        sessionDao.observeMinutesPerDay(from.toEpochDay(), to.toEpochDay())
    fun observeTotalFocusMinutes(): Flow<Int> = sessionDao.observeTotalMinutes()
    suspend fun logsInRange(from: LocalDate, to: LocalDate): List<HabitLog> = logDao.getRange(from.toEpochDay(), to.toEpochDay())
    suspend fun activeWithLogs(): List<HabitWithLogs> = habitDao.getActiveWithLogs()

    /** Full day summary (grouped by routine) for [date]. Recomputes whenever anything changes. */
    fun observeDay(date: LocalDate): Flow<DaySummary> =
        combine(habitDao.observeActiveWithLogs(), routineDao.observeAll(), xpDao.observeTotal()) { habits, routines, xp ->
            buildSummary(date, habits, routines, xp)
        }

    suspend fun daySummary(date: LocalDate): DaySummary =
        buildSummary(date, habitDao.getActiveWithLogs(), routineDao.getAll(), xpDao.total())

    private fun buildSummary(date: LocalDate, habits: List<HabitWithLogs>, routines: List<Routine>, xp: Int): DaySummary {
        val epoch = date.toEpochDay()
        val statuses = habits.map { hwl -> toStatus(hwl, date, epoch) }
        val byRoutine = statuses.groupBy { it.habit.routineId }
        val groups = buildList {
            routines.forEach { r -> byRoutine[r.id]?.let { add(RoutineGroup(r, it)) } }
            byRoutine[null]?.let { add(RoutineGroup(null, it)) }
            // habits pointing to a deleted routine (shouldn't happen thanks to SET_NULL, but be safe)
            byRoutine.keys.filterNotNull().filter { id -> routines.none { it.id == id } }
                .forEach { add(RoutineGroup(null, byRoutine[it]!!)) }
        }
        val totalCompletions = habits.sumOf { h -> h.logs.count { it.completed } }
        val shieldsUsed = habits.sumOf { it.shields.size }
        return DaySummary(
            date = date,
            groups = groups,
            shieldsAvailable = Progression.shieldsAvailable(totalCompletions, shieldsUsed),
            totalXp = xp,
        )
    }

    private fun toStatus(hwl: HabitWithLogs, date: LocalDate, epoch: Long): HabitStatus {
        val log = hwl.logs.firstOrNull { it.day == epoch }
        val streak = Streaks.compute(hwl.habit, hwl.logs, hwl.shields, date)
        val weekStart = Schedule.weekStart(date).toEpochDay()
        val weekCount = if (hwl.habit.schedule == ScheduleType.WEEKLY)
            hwl.logs.count { it.completed && it.day in weekStart..(weekStart + 6) } else 0
        val due = when {
            hwl.habit.isPausedOn(epoch) -> false
            hwl.habit.schedule == ScheduleType.WEEKLY -> weekCount < hwl.habit.timesPerWeek || log?.completed == true
            else -> Schedule.isDue(hwl.habit, date)
        }
        val strength = Strength.compute(hwl.habit, hwl.logs, date, hwl.shields.map { it.day }.toSet())
        return HabitStatus(hwl.habit, log, streak, due, weekCount, strength)
    }

    /** Strength of every active habit today – for `strength`, the profile and the review. */
    suspend fun strengths(date: LocalDate = AppClock.today()): List<Pair<Habit, Strength.Result>> =
        habitDao.getActiveWithLogs().map { it.habit to Strength.compute(it.habit, it.logs, date, it.shields.map { s -> s.day }.toSet()) }

    suspend fun streakFor(habitId: Long, date: LocalDate = AppClock.today()): StreakInfo? {
        val habit = habitDao.getById(habitId) ?: return null
        return Streaks.compute(habit, logDao.getForHabit(habitId), shieldDao.getForHabit(habitId), date)
    }

    // ------------------------------------------------------------------ routines

    suspend fun saveRoutine(routine: Routine): Long {
        val id = if (routine.id == 0L) routineDao.insert(routine.copy(position = routineDao.getAll().size))
        else { routineDao.update(routine); routine.id }
        bump(); return id
    }

    suspend fun deleteRoutine(routine: Routine) { routineDao.delete(routine); bump() }

    suspend fun moveRoutine(routine: Routine, delta: Int) {
        val all = routineDao.getAll().toMutableList()
        val idx = all.indexOfFirst { it.id == routine.id }
        val target = (idx + delta).coerceIn(0, all.lastIndex)
        if (idx < 0 || idx == target) return
        val item = all.removeAt(idx); all.add(target, item)
        all.forEachIndexed { i, r -> if (r.position != i) routineDao.update(r.copy(position = i)) }
        bump()
    }

    // ------------------------------------------------------------------ habits

    suspend fun saveHabit(habit: Habit): Long {
        @Suppress("NAME_SHADOWING")
        val habit = if (habit.type == HabitType.CHECKLIST) habit.copy(target = habit.checklistItems.size.coerceAtLeast(1)) else habit
        val id = if (habit.id == 0L) habitDao.insert(habit.copy(position = habitDao.nextPosition()))
        else { habitDao.update(habit); habit.id }
        bump(); return id
    }

    suspend fun deleteHabit(habit: Habit) { habitDao.delete(habit); bump() }

    suspend fun setArchived(habit: Habit, archived: Boolean) { habitDao.update(habit.copy(archived = archived)); bump() }

    /**
     * Pause [habit] until [until] (exclusive – the habit is back on that day); null resumes it now.
     * Unlike archiving the habit keeps its place and returns by itself; unlike insurance the paused
     * days are simply not scheduled, so nothing is written to the log.
     */
    suspend fun pauseHabit(habit: Habit, until: LocalDate?) {
        val today = AppClock.today().toEpochDay()
        val epoch = until?.toEpochDay() ?: 0L
        habitDao.update(if (epoch > today) habit.copy(pausedFrom = today, pausedUntil = epoch) else habit.copy(pausedFrom = 0L, pausedUntil = 0L))
        bump()
    }

    suspend fun moveHabit(habit: Habit, delta: Int) {
        val siblings = habitDao.getAll().filter { it.routineId == habit.routineId }.toMutableList()
        val idx = siblings.indexOfFirst { it.id == habit.id }
        val target = (idx + delta).coerceIn(0, siblings.lastIndex)
        if (idx < 0 || idx == target) return
        val item = siblings.removeAt(idx); siblings.add(target, item)
        siblings.forEachIndexed { i, h -> if (h.position != i) habitDao.update(h.copy(position = i)) }
        bump()
    }

    // ------------------------------------------------------------------ logging

    /**
     * Toggle a checkbox habit (or fully complete/un-complete any habit). For avoid-habits this toggles
     * the *slip* instead: clean → slipped → clean.
     */
    suspend fun toggle(habitId: Long, date: LocalDate = AppClock.today()) {
        val habit = habitDao.getById(habitId) ?: return
        if (habit.negative) { logSlip(habitId, slipped = !isSlipped(habit, date), date = date); return }
        val epoch = date.toEpochDay()
        val existing = logDao.get(habitId, epoch)
        val nowCompleted = !(existing?.completed ?: false)
        val value = if (nowCompleted) Targets.target(habit, date) else 0
        // checklist: completing the habit ticks every item, un-completing clears them
        val items = if (habit.type == HabitType.CHECKLIST) (if (nowCompleted) allItemsMask(habit) else 0L) else existing?.items ?: 0L
        logDao.upsert(existing.carry(habitId, epoch, value, nowCompleted, items))
        afterChange(habit, epoch, existing?.completed == true, nowCompleted, date)
    }

    /** Checklist habits: tick / untick sub-item [index]; the habit completes when every item is ticked. */
    suspend fun toggleItem(habitId: Long, index: Int, date: LocalDate = AppClock.today()) {
        val habit = habitDao.getById(habitId) ?: return
        val n = habit.checklistItems.size
        if (habit.type != HabitType.CHECKLIST || index !in 0 until n) return
        val epoch = date.toEpochDay()
        val existing = logDao.get(habitId, epoch)
        val mask = (existing?.items ?: 0L) xor (1L shl index)
        val ticked = java.lang.Long.bitCount(mask and allItemsMask(habit))
        val completed = ticked >= n
        if (ticked == 0 && existing.isBare()) logDao.delete(habitId, epoch)
        else logDao.upsert(existing.carry(habitId, epoch, ticked, completed, mask))
        afterChange(habit, epoch, existing?.completed == true, completed, date)
    }

    private fun allItemsMask(habit: Habit): Long = habit.checklistItems.size.let { n -> if (n >= 63) -1L else (1L shl n) - 1 }

    /** Increment/decrement a counter or add minutes to a timer habit. */
    suspend fun addValue(habitId: Long, delta: Int, date: LocalDate = AppClock.today()) {
        val habit = habitDao.getById(habitId) ?: return
        val epoch = date.toEpochDay()
        val existing = logDao.get(habitId, epoch)
        val newValue = ((existing?.value ?: 0) + delta).coerceAtLeast(0)
        val completed = newValue >= Targets.target(habit, date)
        if (newValue == 0 && !completed && existing.isBare()) logDao.delete(habitId, epoch)
        else logDao.upsert(existing.carry(habitId, epoch, newValue, completed))
        afterChange(habit, epoch, existing?.completed == true, completed, date, wasPartial = existing?.let { Targets.minimumReached(habit, it.value, date) } == true, isPartial = Targets.minimumReached(habit, newValue, date))
    }

    /** Minimum version: log exactly the minimum (`min read` on the command line / the `[~] min` button). */
    suspend fun logMinimum(habitId: Long, date: LocalDate = AppClock.today()) {
        val habit = habitDao.getById(habitId) ?: return
        if (habit.minTarget <= 0) return
        val existing = logDao.get(habitId, date.toEpochDay())
        if ((existing?.value ?: 0) >= habit.minTarget) return
        setValue(habitId, habit.minTarget, date)
    }

    suspend fun setValue(habitId: Long, value: Int, date: LocalDate = AppClock.today()) {
        val habit = habitDao.getById(habitId) ?: return
        val epoch = date.toEpochDay()
        val existing = logDao.get(habitId, epoch)
        val v = value.coerceAtLeast(0)
        val completed = if (habit.type == HabitType.CHECKBOX) v > 0 else v >= Targets.target(habit, date)
        val items = if (habit.type == HabitType.CHECKLIST) (if (completed) allItemsMask(habit) else if (v == 0) 0L else existing?.items ?: 0L) else existing?.items ?: 0L
        if (v == 0 && existing.isBare()) logDao.delete(habitId, epoch) else logDao.upsert(existing.carry(habitId, epoch, v, completed, items))
        afterChange(habit, epoch, existing?.completed == true, completed, date, wasPartial = existing?.let { Targets.minimumReached(habit, it.value, date) } == true, isPartial = Targets.minimumReached(habit, v, date))
    }

    /** Keeps note / mood when the value changes; a new value always clears a skip. */
    private fun HabitLog?.carry(habitId: Long, epoch: Long, value: Int, completed: Boolean, items: Long = this?.items ?: 0L) =
        HabitLog(habitId, epoch, value, completed, note = this?.note ?: "", mood = this?.mood ?: 0, items = items, ruleId = this?.ruleId ?: 0L)

    private fun HabitLog?.isBare() = this == null || (note.isBlank() && mood == 0 && !skipped && ruleId == 0L)

    // ------------------------------------------------------------------ skip / note / mood / avoid-habits

    /** Mark [date] as deliberately skipped (bridges the streak without spending a shield). */
    suspend fun skip(habitId: Long, reason: String, date: LocalDate = AppClock.today()) {
        val habit = habitDao.getById(habitId) ?: return
        val epoch = date.toEpochDay()
        val existing = logDao.get(habitId, epoch)
        logDao.upsert(HabitLog(habitId, epoch, 0, false, skipped = true, skipReason = reason.trim(), note = existing?.note ?: "", mood = existing?.mood ?: 0, ruleId = existing?.ruleId ?: 0L))
        afterChange(habit, epoch, existing?.completed == true, false, date)
    }

    suspend fun unskip(habitId: Long, date: LocalDate = AppClock.today()) {
        val habit = habitDao.getById(habitId) ?: return
        val epoch = date.toEpochDay()
        val existing = logDao.get(habitId, epoch) ?: return
        if (!existing.skipped) return
        if (existing.note.isBlank() && existing.mood == 0 && existing.ruleId == 0L) logDao.delete(habitId, epoch)
        else logDao.upsert(existing.copy(skipped = false, skipReason = ""))
        afterChange(habit, epoch, false, false, date)
    }

    /** Attach a completion note and/or mood (1–5, 0 = none) to the day. */
    suspend fun annotate(habitId: Long, date: LocalDate, note: String? = null, mood: Int? = null) {
        val epoch = date.toEpochDay()
        val existing = logDao.get(habitId, epoch) ?: HabitLog(habitId, epoch, 0, false)
        val updated = existing.copy(note = note?.trim() ?: existing.note, mood = mood?.coerceIn(0, 5) ?: existing.mood, updatedAt = System.currentTimeMillis())
        if (updated.value == 0 && !updated.completed && !updated.skipped && updated.note.isBlank() && updated.mood == 0) logDao.delete(habitId, epoch)
        else logDao.upsert(updated)
        bump()
    }

    private suspend fun isSlipped(habit: Habit, date: LocalDate): Boolean {
        val l = logDao.get(habit.id, date.toEpochDay()) ?: return false
        return !l.completed && l.value > 0 && !l.skipped
    }

    /** Avoid-habits: record (or clear) a slip for [date]. A slip forfeits the day's XP. */
    suspend fun logSlip(habitId: Long, slipped: Boolean, date: LocalDate = AppClock.today()) {
        val habit = habitDao.getById(habitId) ?: return
        val epoch = date.toEpochDay()
        val existing = logDao.get(habitId, epoch)
        val wasKept = existing?.completed == true
        if (slipped) {
            logDao.upsert(HabitLog(habitId, epoch, 1, false, note = existing?.note ?: "", mood = existing?.mood ?: 0))
            afterChange(habit, epoch, wasKept, false, date)
        } else {
            if (existing.isBare()) logDao.delete(habitId, epoch) else logDao.upsert(existing!!.copy(value = 0, completed = false))
            afterChange(habit, epoch, wasKept, false, date)
        }
    }

    /**
     * Closes past days for avoid-habits: every scheduled day up to yesterday without a slip/skip is
     * written as kept and earns its XP. Idempotent; called at app start and by the daily worker.
     */
    suspend fun settleNegativeHabits(today: LocalDate = AppClock.today(), lookbackDays: Long = 60) {
        val negatives = habitDao.getActive().filter { it.negative }
        if (negatives.isEmpty()) return
        var changed = false
        for (h in negatives) {
            val logs = logDao.getForHabit(h.id).associateBy { it.day }
            val created = java.time.Instant.ofEpochMilli(h.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            var d = maxOf(created, today.minusDays(lookbackDays))
            while (d.isBefore(today)) {
                if (Schedule.isDue(h, d) && logs[d.toEpochDay()] == null) {
                    val epoch = d.toEpochDay()
                    logDao.upsert(HabitLog(h.id, epoch, 0, true))
                    awardOnce(h.id, epoch, Progression.REASON_COMPLETE, Progression.XP_COMPLETE)
                    changed = true
                }
                d = d.plusDays(1)
            }
        }
        if (changed) bump()
    }

    // ------------------------------------------------------------------ streak insurance (auto-skip rules)

    fun observeSkipRules(): Flow<List<SkipRule>> = ruleDao.observeAll()
    suspend fun skipRules(): List<SkipRule> = ruleDao.getAll()

    suspend fun saveSkipRule(rule: SkipRule, today: LocalDate = AppClock.today()): Long {
        val id = ruleDao.insert(rule)
        applySkipRules(today)
        return id
    }

    suspend fun deleteSkipRule(id: Long, today: LocalDate = AppClock.today()) {
        ruleDao.delete(id)
        applySkipRules(today)
    }

    /** Close an open-ended range rule today ("I'm back"). */
    suspend fun endSkipRule(id: Long, today: LocalDate = AppClock.today()) {
        val r = ruleDao.getById(id) ?: return
        ruleDao.insert(r.copy(toDay = today.toEpochDay(), enabled = true))
        applySkipRules(today)
    }

    /**
     * Streak insurance: write auto-skips for every enabled rule over a window around today and
     * retract skips whose rule no longer covers the day. Idempotent – safe to call at app start, from
     * the daily worker and after every rule edit. Days the user completed (or manually skipped /
     * un-skipped) are never touched: a rule only creates logs where none exist, and only removes logs
     * it created itself.
     */
    suspend fun applySkipRules(today: LocalDate = AppClock.today(), pastDays: Long = 14, futureDays: Long = 60) {
        val rules = ruleDao.getAll()
        val habits = habitDao.getActive()
        val from = today.minusDays(pastDays).toEpochDay()
        val to = today.plusDays(futureDays).toEpochDay()
        var changed = false
        // 1) retract: logs created by a rule that no longer covers that day / habit / exists
        for (log in logDao.getRuleLogs()) {
            if (!log.skipped) continue // user overrode the rule for this day – leave their decision alone
            val habit = habits.firstOrNull { it.id == log.habitId } ?: continue
            val rule = rules.firstOrNull { it.id == log.ruleId }
            val still = rule != null && SkipRules.matchesDay(rule, LocalDate.ofEpochDay(log.day)) && SkipRules.appliesTo(rule, habit)
            if (!still) {
                if (log.note.isBlank() && log.mood == 0) logDao.delete(log.habitId, log.day)
                else logDao.upsert(log.copy(skipped = false, skipReason = "", ruleId = 0))
                changed = true
            }
        }
        // 2) apply: for every covered day without a log, write a skip tagged with the rule id
        if (rules.any { it.enabled }) {
            for (habit in habits) {
                if (habit.negative) continue // avoid-habits have nothing to skip
                val logs = logDao.getForHabit(habit.id).associateBy { it.day }
                var e = from
                while (e <= to) {
                    val date = LocalDate.ofEpochDay(e)
                    val rule = SkipRules.covering(rules, habit, date)
                    val existing = logs[e]
                    if (rule != null && existing == null && Schedule.isDue(habit, date)) {
                        logDao.upsert(HabitLog(habit.id, e, 0, false, skipped = true, skipReason = rule.name, ruleId = rule.id))
                        changed = true
                    }
                    e++
                }
            }
        }
        if (changed) bump()
    }

    // ------------------------------------------------------------------ focus sessions

    /** Records a finished focus / stopwatch session and credits the minutes to the habit (if any). */
    suspend fun recordSession(habitId: Long, startedAt: Long, endedAt: Long, minutes: Int, kind: String, completed: Boolean) {
        if (minutes <= 0) return
        val day = java.time.Instant.ofEpochMilli(startedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        sessionDao.insert(FocusSession(habitId = habitId, day = day.toEpochDay(), startedAt = startedAt, endedAt = endedAt, minutes = minutes, kind = kind, completed = completed))
        if (habitId != 0L) addValue(habitId, minutes, day) else bump()
    }

    suspend fun deleteSession(session: FocusSession) { sessionDao.delete(session); bump() }

    /**
     * Called by the app after an anchor habit is completed so a stacked follower can be nudged
     * ("meditate done → journal?"). Set once by the application; null in tests.
     */
    var onAnchorCompleted: (suspend (anchor: Habit, date: LocalDate) -> Unit)? = null

    private suspend fun afterChange(habit: Habit, epoch: Long, wasCompleted: Boolean, isCompleted: Boolean, date: LocalDate, wasPartial: Boolean = false, isPartial: Boolean = false) {
        if (!wasCompleted && isCompleted) {
            xpDao.deleteFor(habit.id, epoch, Progression.REASON_MINIMUM)
            awardOnce(habit.id, epoch, Progression.REASON_COMPLETE, Progression.XP_COMPLETE)
            val streak = Streaks.compute(habit, logDao.getForHabit(habit.id), shieldDao.getForHabit(habit.id), date)
            if (streak.current > 0 && streak.current % 7 == 0) {
                awardOnce(habit.id, epoch, Progression.REASON_STREAK, Progression.XP_STREAK_WEEK)
            }
        } else if (wasCompleted && !isCompleted) {
            xpDao.deleteFor(habit.id, epoch, Progression.REASON_COMPLETE)
            xpDao.deleteFor(habit.id, epoch, Progression.REASON_STREAK)
        }
        // minimum version: half the XP, kept only while the day is partial (a full completion replaces it)
        if (!isCompleted && isPartial && !wasPartial) awardOnce(habit.id, epoch, Progression.REASON_MINIMUM, Progression.XP_MINIMUM)
        else if (!isPartial && wasPartial && !isCompleted) xpDao.deleteFor(habit.id, epoch, Progression.REASON_MINIMUM)
        // Perfect-day bonus (habitId 0 = day-level event)
        val summary = daySummary(date)
        val hasPerfect = xpDao.countFor(0, epoch, Progression.REASON_PERFECT_DAY) > 0
        if (summary.isPerfect && !hasPerfect) {
            xpDao.insert(XpEvent(day = epoch, amount = Progression.XP_PERFECT_DAY, reason = Progression.REASON_PERFECT_DAY))
        } else if (!summary.isPerfect && hasPerfect) {
            xpDao.deleteFor(0, epoch, Progression.REASON_PERFECT_DAY)
        }
        bump()
        if (!wasCompleted && isCompleted && date == AppClock.today()) onAnchorCompleted?.let { hook -> runCatching { hook(habit, date) } }
    }

    // ------------------------------------------------------------------ minimum / ramp / stacking

    /** `min read 2` – 0 clears. Checkbox and checklist habits have no minimum. */
    suspend fun setMinimum(habitId: Long, minimum: Int) {
        val h = habitDao.getById(habitId) ?: return
        if (h.type == HabitType.CHECKBOX || h.type == HabitType.CHECKLIST) return
        habitDao.update(h.copy(minTarget = minimum.coerceIn(0, (h.target - 1).coerceAtLeast(0)))); bump()
    }

    /** `ramp read 30 8` – grow the target to [to] over [weeks] weeks starting today; weeks = 0 clears. */
    suspend fun setRamp(habitId: Long, to: Int, weeks: Int, from: LocalDate = AppClock.today()) {
        val h = habitDao.getById(habitId) ?: return
        if (h.type == HabitType.CHECKBOX || h.type == HabitType.CHECKLIST) return
        val cleared = weeks <= 0 || to <= 0 || to == h.target
        habitDao.update(if (cleared) h.copy(rampTo = 0, rampWeeks = 0, rampStartDay = 0L) else h.copy(rampTo = to, rampWeeks = weeks.coerceIn(1, 52), rampStartDay = from.toEpochDay()))
        bump()
    }

    /** `after journal meditate` – journal follows meditate; anchorId 0 clears. Refuses self / cycles. */
    suspend fun setAnchor(habitId: Long, anchorId: Long, remind: Boolean = true): Boolean {
        val h = habitDao.getById(habitId) ?: return false
        if (anchorId == habitId) return false
        if (anchorId > 0L) {
            val all = habitDao.getAll().associateBy { it.id }
            var cur = all[anchorId] ?: return false
            var hops = 0
            while (cur.anchorId > 0L && hops++ < 16) { if (cur.anchorId == habitId) return false; cur = all[cur.anchorId] ?: break }
        }
        habitDao.update(h.copy(anchorId = anchorId.coerceAtLeast(0L), anchorRemind = remind && anchorId > 0L)); bump()
        return true
    }

    suspend fun setArea(habitId: Long, area: String) {
        val h = habitDao.getById(habitId) ?: return
        habitDao.update(h.copy(area = area)); bump()
    }

    private suspend fun awardOnce(habitId: Long, day: Long, reason: String, amount: Int) {
        if (xpDao.countFor(habitId, day, reason) == 0) {
            xpDao.insert(XpEvent(day = day, amount = amount, reason = reason, habitId = habitId))
        }
    }

    // ------------------------------------------------------------------ challenges (0.4.0)

    /**
     * Settles an expired challenge exactly once: counts scheduled days inside the window that were
     * completed (half the scheduled days wins), writes the stake to the XP ledger and marks the
     * habit resolved. Returns null when the window is still open or already settled.
     */
    suspend fun settleChallenge(h: Habit): dev.personalterminal.domain.Challenges.Settlement? {
        val today = AppClock.today()
        if (h.chStart <= 0L || h.chEnd <= 0L || h.chResolved || h.chEnd >= today.toEpochDay()) return null
        var scheduled = 0
        var done = 0
        var d = java.time.LocalDate.ofEpochDay(h.chStart)
        val last = minOf(java.time.LocalDate.ofEpochDay(h.chEnd), today.minusDays(1))
        while (!d.isAfter(last)) {
            if (dev.personalterminal.domain.Schedule.isDue(h, d)) {
                scheduled++
                if (daySummary(d).due.firstOrNull { it.habit.id == h.id }?.completed == true) done++
            }
            d = d.plusDays(1)
        }
        val success = scheduled > 0 && done >= dev.personalterminal.domain.Challenges.threshold(scheduled)
        val xp = when {
            h.chStake <= 0 -> 0
            success -> h.chStake * 10
            else -> -(h.chStake * 5)
        }
        if (xp != 0) {
            xpDao.insert(
                XpEvent(
                    day = today.toEpochDay(), amount = xp,
                    reason = if (success) "challenge won" else "challenge missed", habitId = h.id,
                )
            )
        }
        habitDao.update(h.copy(chResolved = true)); bump()
        return dev.personalterminal.domain.Challenges.Settlement(success, xp, done, scheduled)
    }

    /** Launch-time sweep: settle every challenge whose window closed (idempotent). */
    suspend fun settleChallengesDue() {
        val today = AppClock.today()
        allHabits()
            .filter { it.chEnd > 0L && !it.chResolved && it.chEnd < today.toEpochDay() }
            .forEach { settleChallenge(it) }
    }

    /**
     * Repair window: marks [day] (the previous scheduled day) as done after the fact. Same effect as
     * ticking it on the day – value = target, XP awarded once, streak restored – with a note so the
     * journal shows it was logged late. No shield is spent.
     */
    suspend fun logLate(habitId: Long, day: LocalDate) {
        val habit = habitDao.getById(habitId) ?: return
        val epoch = day.toEpochDay()
        val existing = logDao.get(habitId, epoch)
        if (existing?.completed == true) return
        val items = if (habit.type == HabitType.CHECKLIST) allItemsMask(habit) else existing?.items ?: 0L
        val note = existing?.note?.takeIf { it.isNotBlank() } ?: "logged late"
        logDao.upsert(existing.carry(habitId, epoch, Targets.target(habit, day), true, items).copy(note = note))
        afterChange(habit, epoch, existing?.completed == true, true, day)
    }

    // ------------------------------------------------------------------ shields

    /** Spend a shield to bridge the gap on [day] for [habitId]. Returns false when no shield available. */
    suspend fun useShield(habitId: Long, day: LocalDate): Boolean {
        val summary = daySummary(AppClock.today())
        if (summary.shieldsAvailable <= 0) return false
        val inserted = shieldDao.insert(ShieldUse(habitId, day.toEpochDay()))
        bump()
        return inserted != -1L
    }

    // ------------------------------------------------------------------ sleep anchors

    fun observeSleep(day: LocalDate): Flow<SleepLog?> = sleepDao.observe(day.toEpochDay())
    fun observeSleepRange(from: LocalDate, to: LocalDate): Flow<List<SleepLog>> = sleepDao.observeRange(from.toEpochDay(), to.toEpochDay())
    suspend fun sleepRange(from: LocalDate, to: LocalDate): List<SleepLog> = sleepDao.getRange(from.toEpochDay(), to.toEpochDay())
    suspend fun sleep(day: LocalDate): SleepLog? = sleepDao.get(day.toEpochDay())

    /**
     * Log one side of the night ending on [wakeDay]. Manual entries always win; a Health Connect
     * value only fills in what the user has not typed themselves.
     */
    suspend fun logSleep(wakeDay: LocalDate, bedMinutes: Int? = null, wakeMinutes: Int? = null, source: String = SleepLog.SOURCE_MANUAL, note: String? = null) {
        val epoch = wakeDay.toEpochDay()
        val existing = sleepDao.get(epoch)
        val manual = source == SleepLog.SOURCE_MANUAL
        if (!manual && existing?.source == SleepLog.SOURCE_MANUAL && existing.bedMinutes != null && existing.wakeMinutes != null) return
        val merged = SleepLog(
            day = epoch,
            bedMinutes = if (manual) bedMinutes ?: existing?.bedMinutes else existing?.bedMinutes ?: bedMinutes,
            wakeMinutes = if (manual) wakeMinutes ?: existing?.wakeMinutes else existing?.wakeMinutes ?: wakeMinutes,
            source = if (manual || existing == null) source else existing.source,
            note = note ?: existing?.note ?: "",
            updatedAt = System.currentTimeMillis(),
        )
        if (merged.bedMinutes == null && merged.wakeMinutes == null) sleepDao.delete(epoch) else sleepDao.upsert(merged)
        bump()
    }

    suspend fun clearSleep(wakeDay: LocalDate) { sleepDao.delete(wakeDay.toEpochDay()); bump() }

    // ------------------------------------------------------------------ misc

    suspend fun allHabits(): List<Habit> = habitDao.getAll()
    suspend fun habit(id: Long): Habit? = habitDao.getById(id)
    suspend fun routine(id: Long): Routine? = routineDao.getById(id)

    suspend fun isEmpty(): Boolean = habitDao.getAll().isEmpty()

    /** Seeds a starter set of routines/habits for first launch. */
    suspend fun seedDefaults() {
        if (!isEmpty()) return
        val morning = routineDao.insert(Routine(name = "morning", icon = "☼", position = 0))
        val focus = routineDao.insert(Routine(name = "deep work", icon = "λ", position = 1))
        val evening = routineDao.insert(Routine(name = "evening", icon = "☾", position = 2))
        habitDao.insertAll(
            listOf(
                Habit(name = "meditate", type = HabitType.TIMER, target = 10, unit = "min", routineId = morning, color = "purple", position = 0, area = "mind", minTarget = 2),
                Habit(name = "drink water", type = HabitType.COUNTER, target = 8, unit = "cups", routineId = morning, color = "cyan", position = 1, area = "body"),
                Habit(name = "stretch", type = HabitType.CHECKBOX, routineId = morning, color = "green", position = 2, area = "body"),
                Habit(name = "focus session", type = HabitType.TIMER, target = 50, unit = "min", routineId = focus, color = "orange", position = 3, area = "work", minTarget = 25),
                Habit(name = "commit code", type = HabitType.CHECKBOX, routineId = focus, color = "green", position = 4,
                    schedule = ScheduleType.SPECIFIC_DAYS, daysMask = 31, area = "work"),
                Habit(name = "read", type = HabitType.COUNTER, target = 20, unit = "pages", routineId = evening, color = "yellow", position = 5, area = "mind", minTarget = 2),
                Habit(name = "workout", type = HabitType.CHECKBOX, routineId = null, color = "red", position = 6,
                    schedule = ScheduleType.WEEKLY, timesPerWeek = 3, area = "body"),
            ),
        )
        bump()
    }

    private fun bump() { mutations.value = System.currentTimeMillis() }

    suspend fun awaitFirstDay(date: LocalDate) = observeDay(date).first()
}
