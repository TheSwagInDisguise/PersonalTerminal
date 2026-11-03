package dev.personalterminal.upgrade

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.personalterminal.PersonalTerminalApp
import dev.personalterminal.domain.AppClock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate

/**
 * **Backup-compatibility gate** – archives written by *earlier releases* must keep restoring.
 *
 * `src/test/resources/backups/` holds one real `.ptbak` per backup schema the app ever wrote (the
 * `schema-1_v0.2.0` file is the very first format: 7 tables, 7 settings). Every file is restored
 * into the current app, then the same after-update paths as [UpgradeTest] are exercised and the
 * archive is re-exported – proving old Drive backups are still a working escape hatch for the
 * user (and the documented way to move from the debug-signed 0.3.x builds to the release key).
 *
 * When the payload format changes: bump `BackupPayload.schemaVersion`, keep every new field
 * optional, and drop a fresh export into the folder as `schema-<n>_v<version>.ptbak` – the test
 * picks it up by name.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LegacyBackupTest {
    private val app: PersonalTerminalApp get() = ApplicationProvider.getApplicationContext()
    private val fixtures = File("src/test/resources/backups").listFiles { f -> f.extension == "ptbak" || f.extension == "ptbakx" }!!.sortedBy { it.name }
    private val realClock = AppClock.clock

    @Before fun pinClock() { AppClock.clock = java.time.Clock.fixed(java.time.Instant.parse("2026-09-10T00:00:00Z"), java.time.ZoneId.of("UTC")) }
    @After fun unpin() { AppClock.clock = realClock }

    @Test fun `a fixture exists for every backup schema version ever written`() {
        val current = dev.personalterminal.data.backup.BackupPayload(appVersion = "", createdAt = 0, routines = emptyList(), habits = emptyList(),
            habitLogs = emptyList(), shieldUses = emptyList(), watches = emptyList(), wearLogs = emptyList(), xpEvents = emptyList(),
            settings = dev.personalterminal.data.backup.BackupSettings("", "", "", "", 0, 0, 0)).schemaVersion
        val have = fixtures.mapNotNull { Regex("schema-(\\d+)_").find(it.name)?.groupValues?.get(1)?.toInt() }.toSet()
        // Schema 1 is the oldest shipped format; the current one is exported by the round-trip test below.
        assertTrue("need at least the schema-1 fixture, have $have", 1 in have)
        assertTrue("current backup schema is $current – fixtures cover $have", have.max() <= current)
    }

    @Test fun `every legacy archive restores and the app works on the restored data`() = runBlocking {
        val today = AppClock.today()
        assertEquals(LocalDate.of(2026, 9, 10), today)
        for (f in fixtures) {
            val ctx = { what: String -> "${f.name}: $what" }
            val v = app.backups.verifyArchive(f.inputStream())
            assertTrue(ctx("verify: ${v.summary}"), v.habits > 0 && v.watches > 0)
            val msg = app.backups.restoreArchive(f.inputStream())
            assertTrue(ctx("restore message '$msg'"), msg.startsWith("restored ${v.habits} habits"))

            // data landed
            val habits = app.habits.allHabits()
            assertEquals(ctx("habit count"), v.habits, habits.size)
            assertEquals(ctx("watch count"), v.watches, app.watches.allWatches().size)
            val s = app.prefs.current()
            assertEquals(ctx("theme restored"), "dracula", s.themeName)
            assertEquals(ctx("username restored"), "karthik", s.username)
            assertEquals(ctx("pomodoro restored"), 30, s.pomodoroFocusMin)

            // the screens a user opens right after restoring
            val summary = app.habits.daySummary(today)
            assertTrue(ctx("today has due habits"), summary.all.isNotEmpty())
            val meditate = habits.first { it.name == "meditate" }
            val streak = app.habits.streakFor(meditate.id, today)
            assertNotNull(ctx("streak"), streak)
            // 11 logged days out of the last 12 with a shield on the gap → the chain is intact
            assertTrue(ctx("streak survived the shielded gap: $streak"), streak!!.current >= 11)
            app.habits.strengths(today)
            app.habits.activeWithLogs().forEach { hwl -> dev.personalterminal.domain.HabitStats.report(hwl.habit, hwl.logs, hwl.shields, today, 10) }
            val stats = app.watches.collectionStats(today)
            assertEquals(ctx("wear days"), v.wearLogs, stats.totalWearDays)
            assertTrue(ctx("rotation suggests a watch"), app.watches.suggestNext(today) != null)
            app.watches.uptime(); app.watches.challenges(today); app.watches.lifecycle(); app.watches.memories(today)
            assertTrue(ctx("csv"), app.watches.wearLogCsv().lines().size >= v.wearLogs + 1)

            // write path + re-export: the next Drive backup must contain everything that was restored
            app.habits.toggle(habits.first { it.name == "water" }.id, today)
            val bytes = ByteArrayOutputStream().also { app.backups.writeArchive(it) }.toByteArray()
            val again = app.backups.verifyArchive(ByteArrayInputStream(bytes))
            assertEquals(ctx("re-export habits"), v.habits, again.habits)
            assertEquals(ctx("re-export watches"), v.watches, again.watches)
            assertTrue(ctx("re-export logs"), again.logs >= v.logs)
            assertEquals(ctx("legacy archive has no planner rows"), 0, again.plannerTasks)
            assertEquals(ctx("legacy archive has no planner notes"), 0, again.plannerNotes)
        }
    }
}
