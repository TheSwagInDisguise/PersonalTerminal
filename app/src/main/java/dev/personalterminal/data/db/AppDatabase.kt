package dev.personalterminal.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase

class Converters {
    @TypeConverter fun habitTypeToString(t: HabitType): String = t.name
    @TypeConverter fun stringToHabitType(s: String): HabitType = runCatching { HabitType.valueOf(s) }.getOrDefault(HabitType.CHECKBOX)
    @TypeConverter fun scheduleToString(t: ScheduleType): String = t.name
    @TypeConverter fun stringToSchedule(s: String): ScheduleType = runCatching { ScheduleType.valueOf(s) }.getOrDefault(ScheduleType.DAILY)
}

@Database(
    entities = [
        Routine::class, Habit::class, HabitLog::class, ShieldUse::class, Watch::class, WearLog::class, XpEvent::class,
        FocusSession::class, WatchService::class, AccuracyReading::class, Strap::class, SkipRule::class, SleepLog::class, StrapSwap::class,
        WatchPhoto::class, WatchDocument::class, SyncEvent::class,
    ],
    version = 10,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun habitDao(): HabitDao
    abstract fun habitLogDao(): HabitLogDao
    abstract fun shieldDao(): ShieldDao
    abstract fun routineDao(): RoutineDao
    abstract fun watchDao(): WatchDao
    abstract fun wearLogDao(): WearLogDao
    abstract fun xpDao(): XpDao
    abstract fun focusSessionDao(): FocusSessionDao
    abstract fun watchServiceDao(): WatchServiceDao
    abstract fun accuracyDao(): AccuracyDao
    abstract fun strapDao(): StrapDao
    abstract fun skipRuleDao(): SkipRuleDao
    abstract fun sleepDao(): SleepDao
    abstract fun strapSwapDao(): StrapSwapDao
    abstract fun watchPhotoDao(): WatchPhotoDao
    abstract fun watchDocumentDao(): WatchDocumentDao
    abstract fun syncEventDao(): SyncEventDao

    /** Wipes every table inside one transaction (used when restoring a backup). */
    suspend fun clearAllData() = withTransaction {
        strapSwapDao().deleteAll()
        sleepDao().deleteAll()
        skipRuleDao().deleteAll()
        focusSessionDao().deleteAll()
        watchServiceDao().deleteAll()
        accuracyDao().deleteAll()
        strapDao().deleteAll()
        wearLogDao().deleteAll()
        watchDao().deleteAll()
        shieldDao().deleteAll()
        habitLogDao().deleteAll()
        habitDao().deleteAll()
        routineDao().deleteAll()
        xpDao().deleteAll()
        watchPhotoDao().deleteAll()
        watchDocumentDao().deleteAll()
    }

    companion object {
        const val NAME = "personal_terminal.db"

        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
                .also { INSTANCE = it }
        }

        /** 0.3.8 → 0.4.0: challenges (bounded window + stake), monthly / biweekly schedules, watch photos + documents. Additive only. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE habits ADD COLUMN chStart INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE habits ADD COLUMN chEnd INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE habits ADD COLUMN chStake INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE habits ADD COLUMN chResolved INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE habits ADD COLUMN monthDay INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS watch_photos (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "watchId INTEGER NOT NULL, path TEXT NOT NULL, kind TEXT NOT NULL, createdAt INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_watch_photos_watchId ON watch_photos (watchId)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS watch_documents (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "watchId INTEGER NOT NULL, path TEXT NOT NULL, kind TEXT NOT NULL, label TEXT NOT NULL, createdAt INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_watch_documents_watchId ON watch_documents (watchId)")
            }
        }

        /** 0.4.1 → 0.5.0: append-only sync journal (7.3). Additive only. */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS sync_log (device TEXT NOT NULL, seq INTEGER NOT NULL, " +
                        "ts INTEGER NOT NULL, entity TEXT NOT NULL, rowId TEXT NOT NULL, " +
                        "op TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(device, seq))",
                )
            }
        }

        /** 0.4.0 → 0.4.1: water resistance rating on watches. Additive only. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE watches ADD COLUMN waterResistanceM INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** 0.3.5 → 0.3.6: watch lifecycle (status / sale), wishlist fields, power reserve + complications. Additive only. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE watches ADD COLUMN status TEXT NOT NULL DEFAULT 'owned'")
                db.execSQL("ALTER TABLE watches ADD COLUMN statusDay INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE watches ADD COLUMN soldPrice REAL")
                db.execSQL("ALTER TABLE watches ADD COLUMN targetPrice REAL")
                db.execSQL("ALTER TABLE watches ADD COLUMN savedSoFar REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE watches ADD COLUMN link TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE watches ADD COLUMN powerReserveHours INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE watches ADD COLUMN complications TEXT NOT NULL DEFAULT ''")
            }
        }

        /** 0.3.4 → 0.3.5: minimum version + ramping targets, habit stacking anchors, life areas. Additive only. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE habits ADD COLUMN minTarget INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE habits ADD COLUMN rampTo INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE habits ADD COLUMN rampWeeks INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE habits ADD COLUMN rampStartDay INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE habits ADD COLUMN anchorId INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE habits ADD COLUMN anchorRemind INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE habits ADD COLUMN area TEXT NOT NULL DEFAULT ''")
            }
        }

        /** 0.3.3 → 0.3.4: habit pause with a return date, sleep anchors, strap swap log. Additive only. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE habits ADD COLUMN pausedUntil INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE habits ADD COLUMN pausedFrom INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS sleep_logs (day INTEGER NOT NULL, bedMinutes INTEGER, wakeMinutes INTEGER, " +
                        "source TEXT NOT NULL, note TEXT NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(day))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS strap_swaps (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, strapId INTEGER NOT NULL, " +
                        "watchId INTEGER, day INTEGER NOT NULL, note TEXT NOT NULL, createdAt INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_strap_swaps_strapId ON strap_swaps (strapId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_strap_swaps_watchId ON strap_swaps (watchId)")
                // seed the log from the current fits so "on <watch> since" has a starting point
                db.execSQL("INSERT INTO strap_swaps (strapId, watchId, day, note, createdAt) SELECT id, watchId, CAST(strftime('%s','now') / 86400 AS INTEGER), 'existing fit', CAST(strftime('%s','now') AS INTEGER) * 1000 FROM straps WHERE watchId IS NOT NULL")
            }
        }

        /**
         * 0.3.1 → 0.3.2: checklist habits (items on the habit, ticked bitmask on the log), time-of-day
         * sections and streak-insurance rules (`skip_rules` + `habit_logs.ruleId`).
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE habits ADD COLUMN checklist TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE habits ADD COLUMN timeOfDay TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE habit_logs ADD COLUMN items INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE habit_logs ADD COLUMN ruleId INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS skip_rules (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, " +
                        "kind TEXT NOT NULL, fromDay INTEGER, toDay INTEGER, weekdayMask INTEGER NOT NULL, habitIds TEXT NOT NULL, " +
                        "enabled INTEGER NOT NULL, createdAt INTEGER NOT NULL)",
                )
            }
        }

        /** 0.3 → 0.3.1: per-habit evening check-in flag. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE habits ADD COLUMN checkIn INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** 0.2 → 0.3: habit loop + timer sessions + watch tracker tables. Additive only – no data is touched. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE habits ADD COLUMN negative INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE habits ADD COLUMN reminderMinutes INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE habits ADD COLUMN focusMinutes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE habits ADD COLUMN breakMinutes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE habits ADD COLUMN healthMetric TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE habit_logs ADD COLUMN skipped INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE habit_logs ADD COLUMN skipReason TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE habit_logs ADD COLUMN note TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE habit_logs ADD COLUMN mood INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE watches ADD COLUMN purchasePrice REAL")
                db.execSQL("ALTER TABLE watches ADD COLUMN purchaseDay INTEGER")
                db.execSQL("ALTER TABLE watches ADD COLUMN currentValue REAL")
                db.execSQL("ALTER TABLE watches ADD COLUMN currency TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE watches ADD COLUMN lugWidthMm INTEGER")
                db.execSQL("ALTER TABLE watches ADD COLUMN serviceIntervalMonths INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE wear_logs ADD COLUMN strapId INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS focus_sessions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, habitId INTEGER NOT NULL, " +
                        "day INTEGER NOT NULL, startedAt INTEGER NOT NULL, endedAt INTEGER NOT NULL, minutes INTEGER NOT NULL, " +
                        "kind TEXT NOT NULL, completed INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_focus_sessions_habitId ON focus_sessions (habitId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_focus_sessions_day ON focus_sessions (day)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS watch_services (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, watchId INTEGER NOT NULL, " +
                        "day INTEGER NOT NULL, kind TEXT NOT NULL, cost REAL, notes TEXT NOT NULL, nextDueDay INTEGER, createdAt INTEGER NOT NULL, " +
                        "FOREIGN KEY(watchId) REFERENCES watches(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_watch_services_watchId ON watch_services (watchId)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS watch_accuracy (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, watchId INTEGER NOT NULL, " +
                        "measuredAt INTEGER NOT NULL, offsetSeconds REAL NOT NULL, note TEXT NOT NULL, " +
                        "FOREIGN KEY(watchId) REFERENCES watches(id) ON UPDATE NO ACTION ON DELETE CASCADE)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_watch_accuracy_watchId ON watch_accuracy (watchId)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS straps (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, material TEXT NOT NULL, " +
                        "color TEXT NOT NULL, widthMm INTEGER, watchId INTEGER, notes TEXT NOT NULL, createdAt INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_straps_watchId ON straps (watchId)")
            }
        }
    }
}
