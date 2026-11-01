package dev.personalterminal.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Schema guard: the hand-written migrations must produce exactly the schema Room generated for the
 * current version (app/schemas/…/N.json), and existing rows must survive with defaults.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MigrationTest {
    private val dbName = "migration-test.db"

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

    @Test
    fun `1 to 8 keeps data and matches the exported schema`() {
        helper.createDatabase(dbName, 1).apply {
            execSQL("INSERT INTO routines (id, name, icon, position, createdAt) VALUES (1, 'morning', '☼', 0, 1)")
            execSQL("INSERT INTO habits (id, name, type, target, unit, schedule, daysMask, timesPerWeek, routineId, color, position, archived, createdAt, notes) VALUES (1, 'stretch', 'CHECKBOX', 1, '', 'DAILY', 127, 3, 1, 'green', 0, 0, 1, '')")
            execSQL("INSERT INTO habit_logs (habitId, day, value, completed, updatedAt) VALUES (1, 20000, 1, 1, 1)")
            execSQL("INSERT INTO watches (id, brand, model, nickname, reference, movement, caseSizeMm, color, photoPath, notes, archived, createdAt) VALUES (1, 'Seiko', 'SPB143', '', '', '6R35', 40.5, 'cyan', NULL, '', 0, 1)")
            execSQL("INSERT INTO wear_logs (id, watchId, day, photoPath, note, createdAt) VALUES (1, 1, 20000, NULL, '', 1)")
            close()
        }
        // validateDroppedTables = true → any difference to 8.json fails the test
        val db = helper.runMigrationsAndValidate(dbName, 8, true, AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8)
        db.query("SELECT negative, reminderMinutes, healthMetric, checkIn FROM habits WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0)); assertEquals(-1, c.getInt(1)); assertEquals("", c.getString(2)); assertEquals(0, c.getInt(3))
        }
        db.query("SELECT skipped, note, mood, items, ruleId FROM habit_logs").use { c -> assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0)); assertEquals("", c.getString(1)); assertEquals(0, c.getInt(2)); assertEquals(0L, c.getLong(3)); assertEquals(0L, c.getLong(4)) }
        db.query("SELECT checklist, timeOfDay, pausedUntil FROM habits WHERE id = 1").use { c -> assertTrue(c.moveToFirst()); assertEquals("", c.getString(0)); assertEquals("", c.getString(1)); assertEquals(0L, c.getLong(2)) }
        db.query("SELECT minTarget, rampTo, rampWeeks, rampStartDay, anchorId, anchorRemind, area FROM habits WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0)); assertEquals(0, c.getInt(1)); assertEquals(0, c.getInt(2)); assertEquals(0L, c.getLong(3)); assertEquals(0L, c.getLong(4)); assertEquals(0, c.getInt(5)); assertEquals("", c.getString(6))
        }
        db.query("SELECT COUNT(*) FROM skip_rules").use { c -> assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0)) }
        db.query("SELECT chStart, chEnd, chStake, chResolved, monthDay FROM habits WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst()); assertEquals(0L, c.getLong(0)); assertEquals(0L, c.getLong(1)); assertEquals(0, c.getInt(2)); assertEquals(0, c.getInt(3)); assertEquals(0, c.getInt(4))
        }
        db.query("SELECT COUNT(*) FROM watch_photos").use { c -> assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0)) }
        db.query("SELECT COUNT(*) FROM watch_documents").use { c -> assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0)) }
        db.query("SELECT COUNT(*) FROM strap_swaps").use { c -> assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0)) } // no straps → nothing seeded
        db.query("SELECT COUNT(*) FROM sleep_logs").use { c -> assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0)) }
        db.query("SELECT serviceIntervalMonths, currency, purchasePrice FROM watches").use { c -> assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0)); assertEquals("", c.getString(1)); assertTrue(c.isNull(2)) }
        db.query("SELECT status, statusDay, soldPrice, targetPrice, savedSoFar, link, powerReserveHours, complications FROM watches").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("owned", c.getString(0)); assertEquals(0L, c.getLong(1)); assertTrue(c.isNull(2)); assertTrue(c.isNull(3)); assertEquals(0.0, c.getDouble(4), 0.0); assertEquals("", c.getString(5)); assertEquals(0, c.getInt(6)); assertEquals("", c.getString(7))
        }
        db.query("SELECT strapId FROM wear_logs").use { c -> assertTrue(c.moveToFirst()); assertTrue(c.isNull(0)) }
        db.close()

        // And Room itself opens the migrated file happily with the real DAOs.
        val room = Room.databaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7, AppDatabase.MIGRATION_7_8).allowMainThreadQueries().build()
        runBlocking {
            assertEquals(1, room.habitDao().getAll().size)
            room.habitDao().update(room.habitDao().getById(1)!!.copy(checkIn = true, type = HabitType.CHECKLIST, checklist = "shoes\ntowel", timeOfDay = "MORNING"))
            assertTrue(room.habitDao().getById(1)!!.checkIn)
            assertEquals(listOf("shoes", "towel"), room.habitDao().getById(1)!!.checklistItems)
            assertEquals(TimeOfDay.MORNING, room.habitDao().getById(1)!!.section)
            room.habitDao().update(room.habitDao().getById(1)!!.copy(pausedFrom = 20090, pausedUntil = 20100))
            assertTrue(room.habitDao().getById(1)!!.isPausedOn(20099)); assertTrue(!room.habitDao().getById(1)!!.isPausedOn(20100)); assertTrue(!room.habitDao().getById(1)!!.isPausedOn(20089))
            room.habitLogDao().upsert(HabitLog(1, 20002, 1, false, items = 0b10L))
            assertTrue(room.habitLogDao().get(1, 20002)!!.hasItem(1))
            room.focusSessionDao().insert(FocusSession(habitId = 1, day = 20001, startedAt = 0, endedAt = 0, minutes = 25))
            assertEquals(25, room.focusSessionDao().getAll().single().minutes)
            val id = room.skipRuleDao().insert(SkipRule(name = "travel", fromDay = 20010, toDay = null))
            assertEquals("travel", room.skipRuleDao().getById(id)!!.name)
            room.sleepDao().upsert(SleepLog(day = 20011, bedMinutes = -45, wakeMinutes = 400))
            assertEquals(445, dev.personalterminal.domain.Sleep.durationMinutes(room.sleepDao().get(20011)))
            room.habitDao().update(room.habitDao().getById(1)!!.copy(type = HabitType.COUNTER, target = 10, minTarget = 2, rampTo = 30, rampWeeks = 8, rampStartDay = 20000, anchorId = 1, area = "mind"))
            val h = room.habitDao().getById(1)!!
            assertEquals(2, h.minTarget); assertEquals(30, h.rampTo); assertEquals("mind", h.area)
            assertEquals(10, dev.personalterminal.domain.Targets.target(h, java.time.LocalDate.ofEpochDay(20000)))
            assertEquals(30, dev.personalterminal.domain.Targets.target(h, java.time.LocalDate.ofEpochDay(20000 + 8 * 7)))
        }
        room.close()
    }

    @Test
    fun `2 to 3 adds the check-in flag with default off`() {
        val name = "migration-2-3.db"
        helper.createDatabase(name, 2).apply {
            execSQL("INSERT INTO habits (id, name, type, target, unit, schedule, daysMask, timesPerWeek, routineId, color, position, archived, createdAt, notes, negative, reminderMinutes, focusMinutes, breakMinutes, healthMetric) VALUES (7, 'read', 'COUNTER', 20, 'pages', 'DAILY', 127, 3, NULL, 'yellow', 0, 0, 1, '', 0, 1260, 0, 0, '')")
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 5, true, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5)
        db.query("SELECT checkIn, reminderMinutes, checklist FROM habits WHERE id = 7").use { c -> assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0)); assertEquals(1260, c.getInt(1)); assertEquals("", c.getString(2)) }
        db.close()
    }

    @Test
    fun `3 to 4 adds checklist, time-of-day, items and skip rules`() {
        val name = "migration-3-4.db"
        helper.createDatabase(name, 3).apply {
            execSQL("INSERT INTO habits (id, name, type, target, unit, schedule, daysMask, timesPerWeek, routineId, color, position, archived, createdAt, notes, negative, reminderMinutes, focusMinutes, breakMinutes, healthMetric, checkIn) VALUES (9, 'gym', 'CHECKBOX', 1, '', 'DAILY', 127, 3, NULL, 'red', 0, 0, 1, '', 0, -1, 0, 0, '', 1)")
            execSQL("INSERT INTO habit_logs (habitId, day, value, completed, updatedAt, skipped, skipReason, note, mood) VALUES (9, 20000, 1, 1, 1, 0, '', 'felt great', 4)")
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 5, true, AppDatabase.MIGRATION_3_4, AppDatabase.MIGRATION_4_5)
        db.query("SELECT checkIn, checklist, timeOfDay FROM habits WHERE id = 9").use { c -> assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)); assertEquals("", c.getString(1)); assertEquals("", c.getString(2)) }
        db.query("SELECT note, mood, items, ruleId FROM habit_logs WHERE habitId = 9").use { c -> assertTrue(c.moveToFirst()); assertEquals("felt great", c.getString(0)); assertEquals(4, c.getInt(1)); assertEquals(0L, c.getLong(2)); assertEquals(0L, c.getLong(3)) }
        db.execSQL("INSERT INTO skip_rules (name, kind, fromDay, toDay, weekdayMask, habitIds, enabled, createdAt) VALUES ('rest day', 'weekly', NULL, NULL, 96, '', 1, 1)")
        db.query("SELECT name, weekdayMask FROM skip_rules").use { c -> assertTrue(c.moveToFirst()); assertEquals("rest day", c.getString(0)); assertEquals(96, c.getInt(1)) }
        db.close()
    }

    @Test
    fun `4 to 5 adds the pause window with default 0`() {
        val name = "migration-4-5.db"
        helper.createDatabase(name, 4).apply {
            execSQL("INSERT INTO habits (id, name, type, target, unit, schedule, daysMask, timesPerWeek, routineId, color, position, archived, createdAt, notes, negative, reminderMinutes, focusMinutes, breakMinutes, healthMetric, checkIn, checklist, timeOfDay) VALUES (11, 'run', 'CHECKBOX', 1, '', 'SPECIFIC_DAYS', 21, 3, NULL, 'green', 0, 0, 1, '', 0, -1, 0, 0, '', 0, '', 'MORNING')")
            execSQL("INSERT INTO watches (id, brand, model, nickname, reference, movement, caseSizeMm, color, photoPath, notes, archived, createdAt, purchasePrice, purchaseDay, currentValue, currency, lugWidthMm, serviceIntervalMonths) VALUES (3, 'Omega', 'Speedmaster', 'speedy', '', '1861', 42, 'cyan', NULL, '', 0, 1, NULL, NULL, NULL, '', 20, 0)")
            execSQL("INSERT INTO straps (id, name, material, color, widthMm, watchId, notes, createdAt) VALUES (5, 'bond nato', 'nato', 'grey', 20, 3, '', 1)")
            execSQL("INSERT INTO straps (id, name, material, color, widthMm, watchId, notes, createdAt) VALUES (6, 'brown suede', 'leather', 'brown', 20, NULL, '', 1)")
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 5, true, AppDatabase.MIGRATION_4_5)
        // the fitted strap got a seed row in the swap log, the drawer strap did not
        db.query("SELECT strapId, watchId, note FROM strap_swaps").use { c -> assertTrue(c.moveToFirst()); assertEquals(5L, c.getLong(0)); assertEquals(3L, c.getLong(1)); assertEquals("existing fit", c.getString(2)); assertTrue(!c.moveToNext()) }
        db.query("SELECT pausedUntil, pausedFrom, daysMask, timeOfDay FROM habits WHERE id = 11").use { c -> assertTrue(c.moveToFirst()); assertEquals(0L, c.getLong(0)); assertEquals(0L, c.getLong(1)); assertEquals(21, c.getInt(2)); assertEquals("MORNING", c.getString(3)) }
        db.query("SELECT COUNT(*) FROM strap_swaps").use { c -> assertTrue(c.moveToFirst()); assertEquals(1, c.getInt(0)) } // only the seeded fit
        db.execSQL("INSERT INTO sleep_logs (day, bedMinutes, wakeMinutes, source, note, updatedAt) VALUES (20700, -30, 405, 'manual', '', 1)")
        db.query("SELECT bedMinutes, wakeMinutes FROM sleep_logs WHERE day = 20700").use { c -> assertTrue(c.moveToFirst()); assertEquals(-30, c.getInt(0)); assertEquals(405, c.getInt(1)) }
        db.close()
    }

    @Test
    fun `5 to 6 adds minimum, ramp, anchor and area columns with defaults`() {
        val name = "migration-5-6.db"
        helper.createDatabase(name, 5).apply {
            execSQL("INSERT INTO habits (id, name, type, target, unit, schedule, daysMask, timesPerWeek, routineId, color, position, archived, createdAt, notes, negative, reminderMinutes, focusMinutes, breakMinutes, healthMetric, checkIn, checklist, timeOfDay, pausedUntil, pausedFrom) VALUES (21, 'read', 'COUNTER', 20, 'pages', 'DAILY', 127, 3, NULL, 'cyan', 0, 0, 1, '', 0, -1, 0, 0, '', 0, '', '', 0, 0)")
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 6, true, AppDatabase.MIGRATION_5_6)
        db.query("SELECT minTarget, rampTo, rampWeeks, rampStartDay, anchorId, anchorRemind, area, target FROM habits WHERE id = 21").use { c ->
            assertTrue(c.moveToFirst()); assertEquals(0, c.getInt(0)); assertEquals(0, c.getInt(1)); assertEquals(0, c.getInt(2)); assertEquals(0L, c.getLong(3)); assertEquals(0L, c.getLong(4)); assertEquals(0, c.getInt(5)); assertEquals("", c.getString(6)); assertEquals(20, c.getInt(7))
        }
        db.close()
    }

    @Test
    fun `6 to 7 adds lifecycle, wishlist and uptime columns with defaults`() {
        val name = "migration-6-7.db"
        helper.createDatabase(name, 6).apply {
            execSQL("INSERT INTO watches (id, brand, model, nickname, reference, movement, caseSizeMm, color, photoPath, notes, archived, createdAt, purchasePrice, purchaseDay, currentValue, currency, lugWidthMm, serviceIntervalMonths) VALUES (7, 'Omega', 'Speedmaster', 'speedy', '', 'manual', 42, 'orange', NULL, '', 0, 1, 9800, 19000, 10400, 'AUD', 20, 60)")
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 7, true, AppDatabase.MIGRATION_6_7)
        db.query("SELECT status, statusDay, soldPrice, targetPrice, savedSoFar, link, powerReserveHours, complications, purchasePrice FROM watches WHERE id = 7").use { c ->
            assertTrue(c.moveToFirst()); assertEquals("owned", c.getString(0)); assertEquals(0L, c.getLong(1)); assertTrue(c.isNull(2)); assertTrue(c.isNull(3))
            assertEquals(0.0, c.getDouble(4), 0.0); assertEquals("", c.getString(5)); assertEquals(0, c.getInt(6)); assertEquals("", c.getString(7)); assertEquals(9800.0, c.getDouble(8), 0.0)
        }
        db.close()
    }
}
