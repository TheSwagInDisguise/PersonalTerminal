package dev.personalterminal.upgrade

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.personalterminal.PersonalTerminalApp
import dev.personalterminal.data.backup.BackupManager
import dev.personalterminal.data.db.AppDatabase
import dev.personalterminal.data.prefs.UserPrefs
import dev.personalterminal.data.repo.HabitRepository
import dev.personalterminal.data.repo.WatchRepository
import dev.personalterminal.domain.AppClock
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate

/**
 * **Upgrade gate** – proves that installing this build *over any earlier release* keeps the user's
 * data and that the app still works on it.
 *
 * For every schema version the app ever shipped (app/schemas/…/N.json, N < current):
 *  1. create a database with exactly that schema (Room's exported DDL, not a hand-written copy),
 *  2. fill **every table** with one generic row derived from the schema (every column gets a value,
 *     so a migration that drops or mistypes a column is caught even if no test names it),
 *  3. run the real migration chain to the current version and let Room validate the result
 *     against the current schema (dropped tables count as failures),
 *  4. assert that every row survived, then
 *  5. open the migrated file with the real repositories and exercise the code paths a user hits
 *     right after an update: today's summary, streaks, strength, collection stats, uptime,
 *     rotation, backup export + restore.
 *
 * Adding a table or column means: bump the Room version, write the migration, run `./gradlew
 * kspDebugKotlin` to export the new schema – this test then covers the new version automatically.
 * A change that this test cannot pass must not be released.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class UpgradeTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())

    private val app: PersonalTerminalApp get() = ApplicationProvider.getApplicationContext()
    private val schemaDir = File("schemas/dev.personalterminal.data.db.AppDatabase")
    private val current: Int get() = schemaDir.listFiles { f -> f.extension == "json" }!!.maxOf { it.nameWithoutExtension.toInt() }
    private val opened = mutableListOf<AppDatabase>()
    private val realClock = AppClock.clock

    /** Same "today" as the seeded rows (a thursday), so schedules/streaks/uptime see fresh data. */
    @Before fun pinClock() { AppClock.clock = java.time.Clock.fixed(java.time.Instant.parse("2026-09-10T00:00:00Z"), java.time.ZoneId.of("UTC")) }
    @After fun close() { AppClock.clock = realClock; opened.forEach { runCatching { it.close() } } }

    private val migrations get() = arrayOf(
        AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4,
        AppDatabase.MIGRATION_4_5, AppDatabase.MIGRATION_5_6, AppDatabase.MIGRATION_6_7,
        AppDatabase.MIGRATION_7_8, AppDatabase.MIGRATION_8_9,
    )

    @Test fun `every schema version the app ever shipped upgrades to the current one without losing a row`() {
        val schemas = (1 until current).toList()
        assertTrue("no historical schemas found next to ${schemaDir.absolutePath}", schemas.isNotEmpty())
        for (from in schemas) {
            val name = "upgrade-from-$from.db"
            val tables = tablesOf(from)
            val counts = mutableMapOf<String, Int>()
            helper.createDatabase(name, from).apply {
                tables.forEach { t -> counts[t.name] = seed(this, t) }
                close()
            }
            val db = helper.runMigrationsAndValidate(name, current, true, *migrations)
            tables.forEach { t ->
                db.query("SELECT COUNT(*) FROM `${t.name}`").use { c ->
                    c.moveToFirst()
                    assertEquals("upgrade $from → $current lost rows in ${t.name}", counts[t.name], c.getInt(0))
                }
            }
            // Columns added by later migrations must carry their declared defaults, never NULL-in-NOT-NULL garbage.
            tablesOf(current).forEach { t ->
                t.columns.filter { it.notNull }.forEach { col ->
                    db.query("SELECT COUNT(*) FROM `${t.name}` WHERE `${col.name}` IS NULL").use { c ->
                        c.moveToFirst(); assertEquals("upgrade $from → $current left NULL in ${t.name}.${col.name}", 0, c.getInt(0))
                    }
                }
            }
            db.close()
            exerciseApp(name, from)
        }
    }

    @Test fun `a fresh install and a migrated install produce the same schema`() {
        // Belt and braces for the validate() above: the migrated file must be usable by Room's
        // fresh-install path too (identity hash), i.e. no drift between entities and migrations.
        val name = "fresh-vs-migrated.db"
        helper.createDatabase(name, 1).close()
        helper.runMigrationsAndValidate(name, current, true, *migrations).close()
        val room = Room.databaseBuilder(app, AppDatabase::class.java, name).addMigrations(*migrations).allowMainThreadQueries().build().also { opened += it }
        runBlocking { assertEquals(0, room.habitDao().getAll().size) } // opening validates the identity hash
    }

    /** Opens the migrated database through the real repositories and runs the after-update code paths. */
    private fun exerciseApp(dbName: String, from: Int) = runBlocking {
        val db = Room.databaseBuilder(app, AppDatabase::class.java, dbName).addMigrations(*migrations).allowMainThreadQueries().build().also { opened += it }
        val habits = HabitRepository(db)
        val watches = WatchRepository(app, db)
        val today = AppClock.today()
        fun ctx(what: String) = "after upgrade from schema $from: $what"

        // habit loop
        val all = habits.allHabits()
        assertTrue(ctx("seeded habit missing"), all.isNotEmpty())
        val summary = habits.daySummary(today)
        assertNotNull(ctx("day summary"), summary)
        all.forEach { h -> habits.streakFor(h.id, today) }
        habits.strengths(today)
        habits.activeWithLogs().forEach { hwl -> dev.personalterminal.domain.HabitStats.report(hwl.habit, hwl.logs, hwl.shields, today, 10) }
        val first = all.first()
        val before = summary.all.firstOrNull { it.habit.id == first.id }?.completed ?: false
        habits.toggle(first.id, today)                     // write path (afterChange hook, XP ledger)
        val after = habits.daySummary(today).all.firstOrNull { it.habit.id == first.id }?.completed ?: false
        assertTrue(ctx("toggle did not flip ${first.name} (was $before)"), after != before)
        habits.toggle(first.id, today)                     // and back – leaves the seeded state intact for the backup check
        habits.applySkipRules()
        habits.settleNegativeHabits()
        habits.sleepRange(today.minusDays(30), today)

        // watch tracker
        val stats = watches.collectionStats(today)
        assertTrue(ctx("watch rows missing"), watches.allWatches().isNotEmpty())
        assertTrue(ctx("collection stats"), stats.perWatch.isNotEmpty())
        watches.suggestNext(today)
        watches.uptime()
        watches.challenges(today)
        watches.lifecycle()
        watches.memories(today)
        watches.strapFitSummaries(today)
        assertTrue(ctx("csv"), watches.wearLogCsv().lines().size >= 2)

        // backup round trip on the migrated data (what Drive sync does an hour after the update)
        val prefs = UserPrefs(app)
        val backups = BackupManager(app, db, prefs, watches, "test")
        val bytes = ByteArrayOutputStream().also { backups.writeArchive(it) }.toByteArray()
        val v = backups.verifyArchive(ByteArrayInputStream(bytes))
        assertEquals(ctx("backup habit count"), all.size, v.habits)
        val message = backups.restoreArchive(ByteArrayInputStream(bytes))
        assertTrue(ctx("restore message: $message"), message.startsWith("restored"))
        assertEquals(ctx("habits after restore"), all.size, habits.allHabits().size)
    }

    // ------------------------------------------------------------------ schema-driven seeding

    private data class Column(val name: String, val affinity: String, val notNull: Boolean, val default: String?)
    private data class Table(val name: String, val columns: List<Column>, val createSql: String)

    private fun tablesOf(version: Int): List<Table> {
        val json = JSONObject(File(schemaDir, "$version.json").readText()).getJSONObject("database")
        val ents = json.getJSONArray("entities")
        return (0 until ents.length()).map { i ->
            val e = ents.getJSONObject(i)
            val fields = e.getJSONArray("fields")
            Table(
                name = e.getString("tableName"),
                createSql = e.getString("createSql"),
                columns = (0 until fields.length()).map { j ->
                    val f = fields.getJSONObject(j)
                    Column(f.getString("columnName"), f.getString("affinity"), f.getBoolean("notNull"), f.optString("defaultValue", null))
                },
            )
        }
    }

    /**
     * Inserts one deterministic row per table. Foreign keys are satisfied because every parent id
     * is 1 and every child `*Id` column is set to 1; primary keys get 1; text columns get a
     * recognisable value; enum-like columns get a value the converters accept.
     */
    private fun seed(db: SupportSQLiteDatabase, t: Table): Int {
        val values = t.columns.associate { c -> c.name to valueFor(t.name, c) }
        val cols = values.keys.joinToString(", ") { "`$it`" }
        val vals = values.values.joinToString(", ")
        db.execSQL("INSERT INTO `${t.name}` ($cols) VALUES ($vals)")
        return 1
    }

    private fun valueFor(table: String, c: Column): String {
        val today = LocalDate.of(2026, 9, 10).toEpochDay()
        return when {
            c.name == "id" -> "1"
            c.name.endsWith("Id") -> if (c.name == "routineId" || c.name == "strapId" || c.name == "watchId" || c.name == "habitId") "1" else "0"
            c.name == "day" || c.name == "fromDay" -> "$today"
            c.name == "toDay" || c.name == "nextDueDay" -> "NULL"
            c.name == "type" -> "'COUNTER'"
            c.name == "schedule" -> "'DAILY'"
            c.name == "kind" -> if (table == "focus_sessions") "'focus'" else if (table == "skip_rules") "'weekly'" else "'service'"
            c.name == "status" -> "'owned'"
            c.name == "source" -> "'manual'"
            c.name == "color" -> "'cyan'"
            c.name == "name" -> "'${table.trimEnd('s')} one'"
            c.name == "brand" -> "'Seiko'"
            c.name == "model" -> "'SKX007'"
            c.name == "unit" -> "'cups'"
            c.name == "target" -> "8"
            c.name == "value" -> "3"
            c.name == "daysMask" -> "127"
            c.name == "weekdayMask" -> "64"  // sunday rest day (2026-09-10 is a thursday)
            c.name == "enabled" -> "1"
            c.name == "timesPerWeek" -> "3"
            c.name == "reminderMinutes" -> "-1"
            c.name == "minutes" -> "25"
            c.name == "amount" -> "10"
            c.name == "reason" -> "'complete'"
            c.name == "measuredAt" || c.name == "startedAt" || c.name == "endedAt" || c.name == "createdAt" || c.name == "updatedAt" -> "1757462400000"
            c.name == "offsetSeconds" -> "4.5"
            c.name == "completed" -> "1"
            c.affinity == "TEXT" -> "''"
            c.affinity == "REAL" -> if (c.notNull) "0" else "NULL"
            c.affinity == "INTEGER" -> if (c.notNull) "0" else "NULL"
            else -> "NULL"
        }
    }
}
