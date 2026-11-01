package dev.personalterminal.data.db

import dev.personalterminal.domain.AppClock
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/** How a habit is tracked. */
enum class HabitType { CHECKBOX, COUNTER, TIMER, CHECKLIST }

/** Section of the day a habit belongs to on the Today screen. */
enum class TimeOfDay(val label: String, val glyph: String) {
    MORNING("morning", "☼"), AFTERNOON("afternoon", "☀"), EVENING("evening", "☾"), ANY("anytime", "∞");

    companion object {
        fun of(name: String): TimeOfDay = entries.firstOrNull { it.name.equals(name, true) } ?: ANY
        /** Section for a clock time: morning < 12:00 ≤ afternoon < 17:00 ≤ evening. */
        fun at(minuteOfDay: Int): TimeOfDay = when {
            minuteOfDay < 12 * 60 -> MORNING
            minuteOfDay < 17 * 60 -> AFTERNOON
            else -> EVENING
        }
    }
}

/** Which days a habit is scheduled for. */
enum class ScheduleType { DAILY, WEEKLY, SPECIFIC_DAYS, BIWEEKLY, MONTHLY }

/**
 * A group of habits, e.g. "Morning Routine". Habits with `routineId == null` live in the
 * implicit "unsorted" bucket.
 */
@Serializable
@Entity(tableName = "routines")
data class Routine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val icon: String = ">",
    val position: Int = 0,
    val createdAt: Long = AppClock.clock.millis(),
)

@Serializable
@Entity(
    tableName = "habits",
    foreignKeys = [
        ForeignKey(
            entity = Routine::class,
            parentColumns = ["id"],
            childColumns = ["routineId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index("routineId")],
)
data class Habit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: HabitType = HabitType.CHECKBOX,
    /** Counter: target count. Timer: target minutes. Checkbox: 1. */
    val target: Int = 1,
    val unit: String = "",
    val schedule: ScheduleType = ScheduleType.DAILY,
    /** Bitmask Mon=1, Tue=2, Wed=4 … Sun=64 (used for SPECIFIC_DAYS). */
    val daysMask: Int = 127,
    /** For WEEKLY schedule: how many completions per week are required. */
    val timesPerWeek: Int = 3,
    val routineId: Long? = null,
    val color: String = "green",
    val position: Int = 0,
    val archived: Boolean = false,
    val createdAt: Long = AppClock.clock.millis(),
    val notes: String = "",
    /** "Avoid" habit (e.g. no sugar): every scheduled day counts as kept unless a slip is logged. */
    @ColumnInfo(defaultValue = "0") val negative: Boolean = false,
    /** Reminder time as minutes after local midnight; -1 = no reminder. */
    @ColumnInfo(defaultValue = "-1") val reminderMinutes: Int = -1,
    /** Timer habits: per-habit focus / break length in minutes (0 = use the global pomodoro settings). */
    @ColumnInfo(defaultValue = "0") val focusMinutes: Int = 0,
    @ColumnInfo(defaultValue = "0") val breakMinutes: Int = 0,
    /** Health Connect metric that auto-fills this habit ("" = none). See [dev.personalterminal.health.HealthMetric]. */
    @ColumnInfo(defaultValue = "''") val healthMetric: String = "",
    /**
     * Evening check-in: at the global check-in time the app asks "did you do this today?" if the habit
     * is still unlogged (not done, not skipped). Independent of [reminderMinutes], which is the
     * "time to do it" nudge.
     */
    @ColumnInfo(defaultValue = "0") val checkIn: Boolean = false,
    /** Checklist habits: sub-items, newline separated (see [checklistItems]). */
    @ColumnInfo(defaultValue = "''") val checklist: String = "",
    /** Today-screen section: MORNING / AFTERNOON / EVENING / ANY (see [TimeOfDay]); "" = ANY. */
    @ColumnInfo(defaultValue = "''") val timeOfDay: String = "",
    /**
     * Pause with a return date (epoch day, exclusive): while `today < pausedUntil` the habit is not
     * due – it leaves Today, the widget and the reminders, and paused days neither count nor break
     * the streak. 0 = not paused. See [isPausedOn].
     */
    @ColumnInfo(defaultValue = "0") val pausedUntil: Long = 0L,
    /** First paused epoch day (the day the pause was set) – history before it is untouched. */
    @ColumnInfo(defaultValue = "0") val pausedFrom: Long = 0L,
    /**
     * Minimum version (counter / timer): reaching this value without reaching the target keeps the
     * streak as a partial day (`[~] min`). 0 = no minimum. See [dev.personalterminal.domain.Targets].
     */
    @ColumnInfo(defaultValue = "0") val minTarget: Int = 0,
    /** Ramp: the target grows linearly from [target] to [rampTo] over [rampWeeks] weeks starting on [rampStartDay]. 0 = no ramp. */
    @ColumnInfo(defaultValue = "0") val rampTo: Int = 0,
    @ColumnInfo(defaultValue = "0") val rampWeeks: Int = 0,
    @ColumnInfo(defaultValue = "0") val rampStartDay: Long = 0L,
    /**
     * Habit stacking: id of the anchor habit this one follows (`after meditate`). Today greys the
     * follower until the anchor is done and the reminder can fire *when the anchor completes*
     * ([anchorRemind]) instead of at a clock time. 0 = none.
     */
    @ColumnInfo(defaultValue = "0") val anchorId: Long = 0L,
    @ColumnInfo(defaultValue = "0") val anchorRemind: Boolean = false,
    /**
     * Life area for the balance radar: body / mind / work / people / home / money ("" = none).
     * See [dev.personalterminal.domain.Areas].
     */
    @ColumnInfo(defaultValue = "''") val area: String = "",
    /**
     * Challenge window (0.4.0): inclusive epoch days; both 0 = open-ended habit.
     * See [dev.personalterminal.domain.Challenges].
     */
    @ColumnInfo(defaultValue = "0") val chStart: Long = 0L,
    @ColumnInfo(defaultValue = "0") val chEnd: Long = 0L,
    /** Optional XP stake on the challenge (0 = none); settled once when the window closes. */
    @ColumnInfo(defaultValue = "0") val chStake: Int = 0,
    /** Whether the window's stake has already been settled (prevents double XP). */
    @ColumnInfo(defaultValue = "0") val chResolved: Boolean = false,
    /** MONTHLY schedule: day of month 1–31 (0 = none; in short months it falls on the last day). */
    @ColumnInfo(defaultValue = "0") val monthDay: Int = 0,
)

/** True when [epochDay] falls inside the habit's pause window `[pausedFrom, pausedUntil)`. */
fun Habit.isPausedOn(epochDay: Long): Boolean = pausedUntil > 0L && epochDay >= pausedFrom && epochDay < pausedUntil
fun Habit.isPausedOn(date: java.time.LocalDate): Boolean = isPausedOn(date.toEpochDay())

/** Sub-items of a checklist habit (empty for other types). */
val Habit.checklistItems: List<String>
    get() = if (type != HabitType.CHECKLIST) emptyList() else checklist.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

/**
 * Section the habit shows under when Today is grouped by time of day: the explicit choice, else
 * inferred from the reminder time, else [TimeOfDay.ANY].
 */
val Habit.section: TimeOfDay
    get() = when {
        timeOfDay.isNotBlank() -> TimeOfDay.of(timeOfDay)
        reminderMinutes >= 0 -> TimeOfDay.at(reminderMinutes)
        else -> TimeOfDay.ANY
    }

/** Reminder time as a LocalTime, or null when no reminder is set. */
val Habit.reminderTime: java.time.LocalTime?
    get() = if (reminderMinutes < 0) null else java.time.LocalTime.of(reminderMinutes / 60 % 24, reminderMinutes % 60)

/** One row per habit per day. `value` is count for counters, minutes for timers, 1/0 for checkbox. */
@Serializable
@Entity(
    tableName = "habit_logs",
    primaryKeys = ["habitId", "day"],
    foreignKeys = [
        ForeignKey(
            entity = Habit::class,
            parentColumns = ["id"],
            childColumns = ["habitId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("day")],
)
data class HabitLog(
    val habitId: Long,
    /** Epoch day (LocalDate.toEpochDay()). */
    val day: Long,
    val value: Int = 0,
    val completed: Boolean = false,
    val updatedAt: Long = AppClock.clock.millis(),
    /** Day deliberately skipped (sick, travelling …) – neither counts nor breaks the streak. */
    @ColumnInfo(defaultValue = "0") val skipped: Boolean = false,
    @ColumnInfo(defaultValue = "''") val skipReason: String = "",
    /** Free-text completion note ("felt great", "only 10 pages"). */
    @ColumnInfo(defaultValue = "''") val note: String = "",
    /** Mood 1 (awful) … 5 (great); 0 = not recorded. */
    @ColumnInfo(defaultValue = "0") val mood: Int = 0,
    /** Checklist habits: bitmask of ticked sub-items (bit i = item i). `value` is the popcount. */
    @ColumnInfo(defaultValue = "0") val items: Long = 0L,
    /**
     * Streak-insurance: id of the [SkipRule] that auto-skipped this day (0 = none). A log with a
     * ruleId but `skipped = false` means the user overrode the rule for that day – it is left alone.
     */
    @ColumnInfo(defaultValue = "0") val ruleId: Long = 0L,
)

/**
 * Streak insurance: a rule that automatically skips matching days so a chain survives travel, sick
 * leave or rest days without spending a shield. Either a date range (open-ended when [toDay] is
 * null – "away until I say I'm back") or a weekly pattern ([weekdayMask], bit 0 = Monday).
 */
@Serializable
@Entity(tableName = "skip_rules")
data class SkipRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Shown as the skip reason on Today ("travel", "sick", "rest day"). */
    val name: String,
    /** [KIND_RANGE] or [KIND_WEEKLY]. */
    val kind: String = KIND_RANGE,
    val fromDay: Long? = null,
    val toDay: Long? = null,
    val weekdayMask: Int = 0,
    /** Comma-separated habit ids; blank = every habit. */
    val habitIds: String = "",
    val enabled: Boolean = true,
    val createdAt: Long = AppClock.clock.millis(),
) {
    companion object {
        const val KIND_RANGE = "range"
        const val KIND_WEEKLY = "weekly"
    }
}

val SkipRule.habitIdSet: Set<Long> get() = habitIds.split(',').mapNotNull { it.trim().toLongOrNull() }.toSet()
val SkipRule.isOpenEnded: Boolean get() = kind == SkipRule.KIND_RANGE && toDay == null

/** Whether sub-item [index] of a checklist habit is ticked in this log. */
fun HabitLog.hasItem(index: Int): Boolean = index in 0..62 && (items shr index) and 1L == 1L

/** A streak shield that was consumed to bridge a missed day. */
@Serializable
@Entity(
    tableName = "shield_uses",
    primaryKeys = ["habitId", "day"],
    foreignKeys = [
        ForeignKey(
            entity = Habit::class,
            parentColumns = ["id"],
            childColumns = ["habitId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ShieldUse(
    val habitId: Long,
    val day: Long,
    val usedAt: Long = AppClock.clock.millis(),
)

/** A watch the user owns. */
@Serializable
@Entity(tableName = "watches")
data class Watch(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val brand: String,
    val model: String,
    val nickname: String = "",
    val reference: String = "",
    val movement: String = "",
    val caseSizeMm: Float? = null,
    val color: String = "cyan",
    /** Relative path (inside filesDir/watch_photos) of the profile photo. */
    val photoPath: String? = null,
    val notes: String = "",
    val archived: Boolean = false,
    val createdAt: Long = AppClock.clock.millis(),
    val purchasePrice: Double? = null,
    /** Epoch day of purchase. */
    val purchaseDay: Long? = null,
    /** Latest estimated value (same currency as [purchasePrice]). */
    val currentValue: Double? = null,
    @ColumnInfo(defaultValue = "''") val currency: String = "",
    val lugWidthMm: Int? = null,
    /** Recommended service interval in months (0 = unknown / not tracked). */
    @ColumnInfo(defaultValue = "0") val serviceIntervalMonths: Int = 0,
    // ---- lifecycle (0.3.6): owned → in repair → sold, or a wishlist entry that becomes owned
    /** One of [STATUS_OWNED], [STATUS_REPAIR], [STATUS_SOLD], [STATUS_WISHLIST]. */
    @ColumnInfo(defaultValue = "'owned'") val status: String = STATUS_OWNED,
    /** Epoch day the current status began (drop-off day, sale day, day added to the wishlist); 0 = unknown. */
    @ColumnInfo(defaultValue = "0") val statusDay: Long = 0,
    /** Sale price (same currency as [purchasePrice]); only meaningful when sold. */
    val soldPrice: Double? = null,
    // ---- wishlist
    val targetPrice: Double? = null,
    @ColumnInfo(defaultValue = "0") val savedSoFar: Double = 0.0,
    @ColumnInfo(defaultValue = "''") val link: String = "",
    // ---- uptime: mechanical power reserve + calendar complications
    /** Power reserve in hours (0 = unknown / not a mechanical watch). */
    @ColumnInfo(defaultValue = "0") val powerReserveHours: Int = 0,
    /** Comma-separated tokens from [dev.personalterminal.domain.Uptime.COMPLICATIONS] (`date,moonphase`). */
    @ColumnInfo(defaultValue = "''") val complications: String = "",
    /** Water resistance in metres (0 = not rated / unknown) — drives the wear-log swim badge. */
    @ColumnInfo(defaultValue = "0") val waterResistanceM: Int = 0,
) {
    companion object {
        const val STATUS_OWNED = "owned"
        const val STATUS_REPAIR = "repair"
        const val STATUS_SOLD = "sold"
        const val STATUS_WISHLIST = "wishlist"
    }
}

val Watch.displayName: String get() = nickname.ifBlank { "$brand $model".trim() }

/** In the collection right now (owned or away for service) – what `ls watches`, stats and reminders count. */
val Watch.owned: Boolean get() = !archived && (status == Watch.STATUS_OWNED || status == Watch.STATUS_REPAIR)
val Watch.inRepair: Boolean get() = !archived && status == Watch.STATUS_REPAIR
val Watch.sold: Boolean get() = !archived && status == Watch.STATUS_SOLD
val Watch.wished: Boolean get() = !archived && status == Watch.STATUS_WISHLIST
val Watch.complicationSet: Set<String> get() = complications.split(',').map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()

/** Which watch was worn on which day (plus optional wrist-shot). */
@Serializable
@Entity(
    tableName = "wear_logs",
    foreignKeys = [
        ForeignKey(
            entity = Watch::class,
            parentColumns = ["id"],
            childColumns = ["watchId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("day"), Index("watchId")],
)
data class WearLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val watchId: Long,
    val day: Long,
    val photoPath: String? = null,
    val note: String = "",
    val createdAt: Long = AppClock.clock.millis(),
    /** Strap the watch was on that day (see [Strap]); null = unknown / default. */
    val strapId: Long? = null,
)

/** Additional photo of a watch (dial / caseback / papers) beyond the profile [Watch.photoPath]. */
@Serializable
@Entity(tableName = "watch_photos", indices = [Index("watchId")])
data class WatchPhoto(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val watchId: Long,
    /** Relative path inside `filesDir/watch_photos`. */
    val path: String,
    /** Free-form tag shown in the gallery: "dial" | "caseback" | "papers" | "". */
    val kind: String = "",
    val createdAt: Long = AppClock.clock.millis(),
)

/** Receipt / warranty / other document attached to a watch (file copied into `filesDir/watch_docs`). */
@Serializable
@Entity(tableName = "watch_documents", indices = [Index("watchId")])
data class WatchDocument(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val watchId: Long,
    /** Relative path inside `filesDir/watch_docs`. */
    val path: String,
    /** "receipt" | "warranty" | "other". */
    val kind: String = "",
    val label: String = "",
    val createdAt: Long = AppClock.clock.millis(),
)

/** Persisted XP ledger — one row per reward so XP can be recomputed / audited. */
@Serializable
@Entity(tableName = "xp_events", indices = [Index("day")])
data class XpEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val day: Long,
    val amount: Int,
    val reason: String,
    @ColumnInfo(defaultValue = "0") val habitId: Long = 0,
    val createdAt: Long = AppClock.clock.millis(),
)

/** One focus (pomodoro) or stopwatch session – feeds the session heatmap and per-habit history. */
@Serializable
@Entity(tableName = "focus_sessions", indices = [Index("habitId"), Index("day")])
data class FocusSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 0 = free session (not bound to a habit). */
    val habitId: Long = 0,
    /** Epoch day the session started on. */
    val day: Long,
    val startedAt: Long,
    val endedAt: Long,
    /** Whole minutes of focus credited. */
    val minutes: Int,
    /** "focus" (countdown) or "stopwatch". */
    val kind: String = KIND_FOCUS,
    /** False when the phase was stopped/skipped before it finished. */
    val completed: Boolean = true,
) {
    companion object {
        const val KIND_FOCUS = "focus"
        const val KIND_STOPWATCH = "stopwatch"
    }
}

/** Service / maintenance entry for a watch (full service, battery, regulation, strap change …). */
@Serializable
@Entity(
    tableName = "watch_services",
    foreignKeys = [ForeignKey(entity = Watch::class, parentColumns = ["id"], childColumns = ["watchId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("watchId")],
)
data class WatchService(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val watchId: Long,
    val day: Long,
    /** service | battery | regulation | strap | polish | other */
    val kind: String = "service",
    val cost: Double? = null,
    val notes: String = "",
    /** Epoch day the next service is due (drives reminders); null = none. */
    val nextDueDay: Long? = null,
    val createdAt: Long = AppClock.clock.millis(),
)

/** One timekeeping measurement: how many seconds the watch is off a reference clock. */
@Serializable
@Entity(
    tableName = "watch_accuracy",
    foreignKeys = [ForeignKey(entity = Watch::class, parentColumns = ["id"], childColumns = ["watchId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("watchId")],
)
data class AccuracyReading(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val watchId: Long,
    /** Epoch millis of the measurement. */
    val measuredAt: Long,
    /** Watch time minus reference time in seconds (+ = running fast). */
    val offsetSeconds: Float,
    val note: String = "",
)

/** A strap / bracelet in the collection; optionally fitted to a watch right now. */
@Serializable
@Entity(tableName = "straps", indices = [Index("watchId")])
data class Strap(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** leather | nato | rubber | bracelet | sailcloth | other */
    val material: String = "",
    val color: String = "",
    val widthMm: Int? = null,
    /** Watch this strap is currently fitted to (null = in the drawer). */
    val watchId: Long? = null,
    val notes: String = "",
    val createdAt: Long = AppClock.clock.millis(),
)

/**
 * Sleep anchors for one night, keyed by the *wake day*. Minutes are relative to that day's midnight,
 * so a 23:30 bedtime the evening before is -30 and a 06:45 wake is 405. Either side may be missing
 * until logged (`sleep 23:30` in the evening, `wake 06:45` in the morning, or Health Connect).
 */
@Serializable
@Entity(tableName = "sleep_logs")
data class SleepLog(
    @PrimaryKey val day: Long,
    val bedMinutes: Int? = null,
    val wakeMinutes: Int? = null,
    /** [SOURCE_MANUAL] wins over [SOURCE_HEALTH] – a manual entry is never overwritten by the sync. */
    val source: String = SOURCE_MANUAL,
    val note: String = "",
    val updatedAt: Long = AppClock.clock.millis(),
) {
    companion object {
        const val SOURCE_MANUAL = "manual"
        const val SOURCE_HEALTH = "health"
    }
}

/**
 * Strap swap log – one row every time a strap is fitted to a watch (or taken off: `watchId = null`).
 * The current fit lives on [Strap.watchId]; this table is the history behind "on speedy for 23 days".
 */
@Serializable
@Entity(tableName = "strap_swaps", indices = [Index("strapId"), Index("watchId")])
data class StrapSwap(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val strapId: Long,
    /** Watch the strap went on; null = back in the drawer. */
    val watchId: Long? = null,
    /** Epoch day of the swap. */
    val day: Long,
    val note: String = "",
    val createdAt: Long = AppClock.clock.millis(),
)
