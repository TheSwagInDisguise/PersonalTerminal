package dev.personalterminal.screenshots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import dev.personalterminal.PersonalTerminalApp
import dev.personalterminal.data.db.Habit
import dev.personalterminal.data.db.HabitType
import dev.personalterminal.data.db.Routine
import dev.personalterminal.data.db.ScheduleType
import dev.personalterminal.data.db.Watch
import java.time.LocalDate
import kotlin.random.Random

/**
 * Seeds a believable few months of data so the README screenshots show the app "lived in":
 * routines with mixed habit types, a long streak, a heatmap with texture, XP/level progress and a
 * small watch collection with wear history. Deterministic (fixed random seed) so re-running
 * `./gradlew screenshots` produces the same images.
 */
object DemoData {
    const val USERNAME = "user"

    suspend fun seed(app: PersonalTerminalApp, today: LocalDate = LocalDate.now()) {
        // Robolectric keeps static singletons (the Room instance) alive across test methods, so the
        // previous test's rows would otherwise still be there – start from a clean slate every time.
        app.db.clearAllData()
        val prefs = app.prefs
        prefs.setUsername(USERNAME)
        prefs.setTheme("signal")
        prefs.setOnboarded(true)

        val habits = app.habits
        val db = app.db
        val morning = db.routineDao().insert(Routine(name = "morning", icon = "☼", position = 0))
        val work = db.routineDao().insert(Routine(name = "deep work", icon = "λ", position = 1))
        val evening = db.routineDao().insert(Routine(name = "evening", icon = "☾", position = 2))

        val meditate = habits.saveHabit(Habit(name = "meditate", type = HabitType.TIMER, target = 10, unit = "min", routineId = morning, color = "purple", position = 0, timeOfDay = "MORNING", area = "mind"))
        val water = habits.saveHabit(Habit(name = "drink water", type = HabitType.COUNTER, target = 8, unit = "cups", routineId = morning, color = "cyan", position = 1, area = "body"))
        // stretch is stacked after meditate (habit stacking) – Today shows the chain and nudges when meditate is ticked
        val stretch = habits.saveHabit(Habit(name = "stretch", type = HabitType.CHECKBOX, routineId = morning, color = "green", position = 2, timeOfDay = "MORNING", area = "body", anchorId = meditate, anchorRemind = true))
        val gymBag = habits.saveHabit(Habit(name = "pack gym bag", type = HabitType.CHECKLIST, routineId = morning, color = "cyan", position = 2, timeOfDay = "MORNING", checklist = "shoes\ntowel\nbottle\nheadphones", area = "body"))
        val focus = habits.saveHabit(Habit(name = "focus session", type = HabitType.TIMER, target = 50, unit = "min", routineId = work, color = "orange", position = 3, timeOfDay = "AFTERNOON", area = "work"))
        val commit = habits.saveHabit(Habit(name = "commit code", type = HabitType.CHECKBOX, routineId = work, color = "green", position = 4, schedule = ScheduleType.SPECIFIC_DAYS, daysMask = 31, timeOfDay = "AFTERNOON", area = "work"))
        // read has a minimum version (2 pages keep the streak) and ramps 20 → 30 pages over 8 weeks
        val read = habits.saveHabit(Habit(name = "read", type = HabitType.COUNTER, target = 20, unit = "pages", routineId = evening, color = "yellow", position = 5, timeOfDay = "EVENING", area = "mind",
            minTarget = 2, rampTo = 30, rampWeeks = 8, rampStartDay = today.minusWeeks(3).toEpochDay()))
        val journal = habits.saveHabit(Habit(name = "journal", type = HabitType.CHECKBOX, routineId = evening, color = "pink", position = 6, reminderMinutes = 21 * 60, checkIn = true, area = "mind", anchorId = read, anchorRemind = true))
        val workout = habits.saveHabit(Habit(name = "workout", type = HabitType.CHECKBOX, routineId = null, color = "red", position = 7, schedule = ScheduleType.WEEKLY, timesPerWeek = 3, area = "body"))
        val noSugar = habits.saveHabit(Habit(name = "no sugar", type = HabitType.CHECKBOX, routineId = null, color = "red", position = 8, negative = true, area = "body",
            reminderMinutes = 21 * 60, createdAt = today.minusDays(40).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()))

        // ~4 months of history. Each habit has its own "consistency", plus a slump in the middle so the
        // heatmap is not a solid block, and an unbroken run for the last few weeks for the streak counters.
        val rnd = Random(42)
        val start = today.minusDays(120)
        val missedJournal = mutableSetOf<LocalDate>()
        var d = start
        while (d.isBefore(today)) {
            val age = java.time.temporal.ChronoUnit.DAYS.between(d, today).toInt()
            val slump = age in 40..52
            val recent = age <= 23
            fun did(p: Double) = recent || (!slump && rnd.nextDouble() < p)
            if (did(0.85)) habits.setValue(meditate, 10 + rnd.nextInt(6), d)
            if (did(0.8)) habits.setValue(water, if (recent) 8 else 5 + rnd.nextInt(4), d)
            if (did(0.9)) habits.toggle(stretch, d)
            if (did(0.8)) habits.toggle(gymBag, d)
            if (did(0.7) && d.dayOfWeek.value <= 5) habits.setValue(focus, 50 + rnd.nextInt(30), d)
            if (did(0.75) && d.dayOfWeek.value <= 5) habits.toggle(commit, d)
            if (did(0.65)) habits.setValue(read, 20 + rnd.nextInt(25), d) else if (rnd.nextDouble() < 0.4) habits.setValue(read, 2 + rnd.nextInt(6), d) // thin days: minimum version
            if (did(0.6)) habits.toggle(journal, d) else missedJournal += d
            if (d.dayOfWeek.value in setOf(1, 3, 5) && did(0.8)) habits.toggle(workout, d)
            if (age in 1..39 && !recent && rnd.nextDouble() < 0.12) habits.logSlip(noSugar, true, d)
            if (age == 30) habits.skip(journal, "travelling", d)
            d = d.plusDays(1)
        }
        habits.settleNegativeHabits(today)
        // Notes & moods on a few recent days so the journal / review have content.
        habits.annotate(meditate, today.minusDays(1), note = "calm, slept well", mood = 4)
        habits.annotate(read, today.minusDays(2), note = "finished chapter 7", mood = 5)
        habits.annotate(focus, today.minusDays(3), note = "shipped the widget fix", mood = 4)
        habits.annotate(journal, today.minusDays(4), mood = 3)
        // Focus sessions for the session heatmap (~ last 8 weeks of weekday pomodoros).
        var sd = today.minusDays(56)
        while (!sd.isAfter(today)) {
            if (sd.dayOfWeek.value <= 5 && rnd.nextDouble() < 0.7) {
                val start = sd.atTime(9 + rnd.nextInt(6), 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                repeat(1 + rnd.nextInt(3)) { k ->
                    db.focusSessionDao().insert(dev.personalterminal.data.db.FocusSession(habitId = focus, day = sd.toEpochDay(), startedAt = start + k * 1_800_000L, endedAt = start + k * 1_800_000L + 1_500_000L, minutes = 25))
                }
            }
            sd = sd.plusDays(1)
        }
        // Sleep anchors for the last five weeks; short nights land on the days the journal was skipped
        // (plus a couple of random ones) so the insights panel has both groups and a believable lift.
        for (i in 0 until 35) {
            val night = today.minusDays(i.toLong())
            val short = night in missedJournal || i % 11 == 5
            val bed = if (short) 60 + rnd.nextInt(30) else -45 + rnd.nextInt(40) // 01:00–01:30 vs 23:15–23:55
            val wake = 6 * 60 + 30 + rnd.nextInt(30)
            habits.logSleep(night, bedMinutes = bed, wakeMinutes = wake)
        }
        // Today: a morning that is half done.
        habits.setValue(meditate, 10, today)
        habits.setValue(water, 5, today)
        habits.toggle(stretch, today)
        habits.toggleItem(gymBag, 0, today)
        habits.toggleItem(gymBag, 1, today)
        habits.setValue(focus, 25, today)
        habits.setValue(read, 3, today) // minimum reached → `[~] min`, full target still open
        // Streak insurance: a rest-day rule for the weekend workout and a past trip.
        habits.saveSkipRule(dev.personalterminal.data.db.SkipRule(name = "travel", fromDay = today.minusDays(30).toEpochDay(), toDay = today.minusDays(27).toEpochDay()), today)
        habits.saveSkipRule(dev.personalterminal.data.db.SkipRule(name = "rest day", kind = dev.personalterminal.data.db.SkipRule.KIND_WEEKLY, weekdayMask = 64, habitIds = "$focus,$commit"), today)

        // Watches + wear log.
        val watches = app.watches
        val speedy = watches.saveWatch(Watch(brand = "Omega", model = "Speedmaster Professional", nickname = "speedy", reference = "310.30.42.50.01.001", movement = "cal. 3861", caseSizeMm = 42f, color = "orange", photoPath = photo(app, "omega", 0xFF2B2B2B.toInt(), 0xFFE0E0E0.toInt()),
            purchasePrice = 9800.0, currentValue = 10400.0, currency = "AUD", purchaseDay = today.minusYears(2).toEpochDay(), lugWidthMm = 20, serviceIntervalMonths = 60, powerReserveHours = 50, complications = "chrono"))
        val seiko = watches.saveWatch(Watch(brand = "Seiko", model = "SPB143", nickname = "62MAS", movement = "6R35", caseSizeMm = 40.5f, color = "cyan", photoPath = photo(app, "seiko", 0xFF1F3A4A.toInt(), 0xFF8BE9FD.toInt()),
            purchasePrice = 1650.0, currentValue = 1500.0, currency = "AUD", purchaseDay = today.minusDays(500).toEpochDay(), lugWidthMm = 20, serviceIntervalMonths = 72, powerReserveHours = 70, complications = "date"))
        val cartier = watches.saveWatch(Watch(brand = "Cartier", model = "Tank Must", movement = "quartz", caseSizeMm = 33.7f, color = "yellow", photoPath = photo(app, "cartier", 0xFF3B3320.toInt(), 0xFFF1FA8C.toInt()),
            purchasePrice = 4200.0, currency = "AUD", purchaseDay = today.minusDays(300).toEpochDay(), lugWidthMm = 20))
        val gshock = watches.saveWatch(Watch(brand = "Casio", model = "GW-M5610", nickname = "square", movement = "tough solar", caseSizeMm = 43.2f, color = "green", photoPath = photo(app, "casio", 0xFF202020.toInt(), 0xFF50FA7B.toInt()),
            purchasePrice = 210.0, currency = "AUD", purchaseDay = today.minusDays(900).toEpochDay()))
        // Lifecycle: a funded wishlist entry, one on the list without a target, and a watch sold last spring.
        watches.saveWatch(Watch(brand = "Tudor", model = "Black Bay 58", nickname = "BB58", reference = "M79030N", caseSizeMm = 39f, color = "blue", status = Watch.STATUS_WISHLIST, statusDay = today.minusDays(120).toEpochDay(),
            targetPrice = 5200.0, savedSoFar = 3250.0, currency = "AUD", link = "https://www.tudorwatch.com/black-bay-58", powerReserveHours = 70, complications = ""))
        watches.saveWatch(Watch(brand = "Grand Seiko", model = "SBGA211 Snowflake", caseSizeMm = 41f, color = "purple", status = Watch.STATUS_WISHLIST, statusDay = today.minusDays(30).toEpochDay(), currency = "AUD", complications = "date"))
        watches.saveWatch(Watch(brand = "Hamilton", model = "Khaki Field Mechanical", nickname = "khaki", caseSizeMm = 38f, color = "green", movement = "H-50", status = Watch.STATUS_SOLD, statusDay = today.minusDays(160).toEpochDay(),
            purchasePrice = 780.0, soldPrice = 650.0, currency = "AUD", purchaseDay = today.minusDays(1100).toEpochDay(), powerReserveHours = 80))
        // Straps, service log and accuracy readings for the watch tracker screens.
        val suede = watches.saveStrap(dev.personalterminal.data.db.Strap(name = "brown suede", material = "leather", color = "brown", widthMm = 20))
        val nato = watches.saveStrap(dev.personalterminal.data.db.Strap(name = "bond nato", material = "nato", color = "grey/black", widthMm = 20))
        val bracelet = watches.saveStrap(dev.personalterminal.data.db.Strap(name = "flat link bracelet", material = "bracelet", color = "steel", widthMm = 20))
        // Swap log: the bracelet has been on the speedy for a month, the suede went on the 62MAS three weeks ago after a nato spell.
        watches.fitStrap(db.strapDao().getById(bracelet)!!, speedy, today.minusDays(31))
        watches.fitStrap(db.strapDao().getById(nato)!!, seiko, today.minusDays(40))
        watches.fitStrap(db.strapDao().getById(suede)!!, seiko, today.minusDays(23), "summer strap")
        watches.saveService(dev.personalterminal.data.db.WatchService(watchId = speedy, day = today.minusDays(400).toEpochDay(), kind = "service", cost = 950.0, notes = "full service, new mainspring", nextDueDay = today.plusDays(1425).toEpochDay()))
        watches.saveService(dev.personalterminal.data.db.WatchService(watchId = cartier, day = today.minusDays(20).toEpochDay(), kind = "battery", cost = 45.0))
        watches.saveService(dev.personalterminal.data.db.WatchService(watchId = seiko, day = today.minusDays(90).toEpochDay(), kind = "strap", notes = "fitted brown suede"))
        listOf(0f, 4.5f, 9f, 13f, 18f, 22.5f).forEachIndexed { i, off ->
            watches.addReading(dev.personalterminal.data.db.AccuracyReading(watchId = speedy, measuredAt = today.minusDays((5 - i).toLong()).atTime(8, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(), offsetSeconds = off))
        }
        check(suede > 0)
        val rotation = listOf(speedy, seiko, seiko, cartier, speedy, gshock, seiko)
        var w = today.minusDays(45)
        var i = 0
        while (!w.isAfter(today)) {
            if (rnd.nextDouble() < 0.9) {
                val id = rotation[i % rotation.size]
                val shot = if (rnd.nextDouble() < 0.4 || w == today) photo(app, "wrist_${w.toEpochDay()}", 0xFF3A2E2A.toInt(), 0xFFFFB86C.toInt(), wrist = true) else null
                watches.logWear(id, w, shot, if (w == today) "office day" else "")
            }
            i++
            w = w.plusDays(1)
        }
        // "On this day" memories: wrist shots 3, 6 and 12 months back (the rotation above covers 45 days).
        listOf(3L to seiko, 6L to speedy, 12L to cartier).forEach { (months, id) ->
            val day = today.minusMonths(months)
            watches.logWear(id, day, photo(app, "memory_$months", 0xFF2A2E3A.toInt(), 0xFFBD93F9.toInt(), wrist = true), "")
        }
        habits.mutations.value = System.currentTimeMillis()
    }

    /**
     * Generates a simple stylised "watch on a dark background" JPEG so the gallery and thumbnails have
     * content without shipping real photos in the repository.
     */
    private fun photo(app: PersonalTerminalApp, name: String, bg: Int, accent: Int, wrist: Boolean = false): String {
        val file = app.watches.photoFile("$name.jpg")
        if (file.exists()) return file.name
        val size = 512
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(size * 0.5f, size * 0.45f, size * 0.8f, lighten(bg, 0.25f), bg, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p)
        p.shader = null
        if (wrist) { // a hint of a strap running diagonally
            p.color = lighten(bg, 0.12f)
            p.strokeWidth = size * 0.28f
            c.drawLine(size * 0.15f, size * 1.05f, size * 0.85f, -size * 0.05f, p)
        }
        val cx = size * 0.5f; val cy = size * 0.5f; val r = size * 0.32f
        p.color = Color.argb(90, 0, 0, 0); c.drawCircle(cx + 6, cy + 10, r + 8, p)
        p.color = lighten(bg, 0.5f); c.drawCircle(cx, cy, r + 10, p)
        p.color = bg; c.drawCircle(cx, cy, r, p)
        p.color = accent
        for (h in 0 until 12) {
            val a = Math.toRadians(h * 30.0)
            val len = if (h % 3 == 0) r * 0.16f else r * 0.08f
            p.strokeWidth = if (h % 3 == 0) 8f else 4f
            c.drawLine(cx + (r - len) * Math.cos(a).toFloat(), cy + (r - len) * Math.sin(a).toFloat(), cx + r * 0.94f * Math.cos(a).toFloat(), cy + r * 0.94f * Math.sin(a).toFloat(), p)
        }
        p.strokeWidth = 9f; c.drawLine(cx, cy, cx + r * 0.5f * Math.cos(Math.toRadians(-60.0)).toFloat(), cy + r * 0.5f * Math.sin(Math.toRadians(-60.0)).toFloat(), p)
        p.strokeWidth = 6f; c.drawLine(cx, cy, cx + r * 0.78f * Math.cos(Math.toRadians(30.0)).toFloat(), cy + r * 0.78f * Math.sin(Math.toRadians(30.0)).toFloat(), p)
        p.color = Color.argb(255, 255, 85, 85); p.strokeWidth = 2.5f
        c.drawLine(cx, cy, cx + r * 0.85f * Math.cos(Math.toRadians(140.0)).toFloat(), cy + r * 0.85f * Math.sin(Math.toRadians(140.0)).toFloat(), p)
        p.color = accent; c.drawCircle(cx, cy, 7f, p)
        file.parentFile?.mkdirs()
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        return file.name
    }

    private fun lighten(color: Int, f: Float): Int = Color.rgb(
        (Color.red(color) + (255 - Color.red(color)) * f).toInt(),
        (Color.green(color) + (255 - Color.green(color)) * f).toInt(),
        (Color.blue(color) + (255 - Color.blue(color)) * f).toInt(),
    )
}
