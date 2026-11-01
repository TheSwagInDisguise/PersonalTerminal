package dev.personalterminal.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class HabitWithLogs(
    @Embedded val habit: Habit,
    @Relation(parentColumn = "id", entityColumn = "habitId") val logs: List<HabitLog>,
    @Relation(parentColumn = "id", entityColumn = "habitId") val shields: List<ShieldUse>,
)

data class WearLogWithWatch(
    @Embedded val log: WearLog,
    @Relation(parentColumn = "watchId", entityColumn = "id") val watch: Watch,
)

data class DayCount(val day: Long, val count: Int)

@Dao
interface HabitDao {
    @Query("SELECT * FROM habits WHERE archived = 0 ORDER BY position, id")
    fun observeActive(): Flow<List<Habit>>

    @Query("SELECT * FROM habits ORDER BY position, id")
    fun observeAll(): Flow<List<Habit>>

    @Query("SELECT * FROM habits ORDER BY position, id")
    suspend fun getAll(): List<Habit>

    @Query("SELECT * FROM habits WHERE archived = 0 ORDER BY position, id")
    suspend fun getActive(): List<Habit>

    @Query("SELECT * FROM habits WHERE id = :id")
    suspend fun getById(id: Long): Habit?

    @Query("SELECT * FROM habits WHERE id = :id")
    fun observeById(id: Long): Flow<Habit?>

    @Transaction
    @Query("SELECT * FROM habits WHERE archived = 0 ORDER BY position, id")
    fun observeActiveWithLogs(): Flow<List<HabitWithLogs>>

    @Transaction
    @Query("SELECT * FROM habits WHERE archived = 0 ORDER BY position, id")
    suspend fun getActiveWithLogs(): List<HabitWithLogs>

    @Transaction
    @Query("SELECT * FROM habits ORDER BY position, id")
    suspend fun getAllWithLogs(): List<HabitWithLogs>

    @Transaction
    @Query("SELECT * FROM habits WHERE id = :id")
    fun observeWithLogs(id: Long): Flow<HabitWithLogs?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(habit: Habit): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(habits: List<Habit>)

    @Update
    suspend fun update(habit: Habit)

    @Delete
    suspend fun delete(habit: Habit)

    @Query("DELETE FROM habits")
    suspend fun deleteAll()

    @Query("SELECT COALESCE(MAX(position), 0) + 1 FROM habits")
    suspend fun nextPosition(): Int
}

@Dao
interface HabitLogDao {
    @Query("SELECT * FROM habit_logs WHERE day = :day")
    fun observeForDay(day: Long): Flow<List<HabitLog>>

    /** Logs written (or overridden) by streak-insurance rules. */
    @Query("SELECT * FROM habit_logs WHERE ruleId != 0")
    suspend fun getRuleLogs(): List<HabitLog>

    @Query("SELECT * FROM habit_logs WHERE day = :day")
    suspend fun getForDay(day: Long): List<HabitLog>

    @Query("SELECT * FROM habit_logs WHERE habitId = :habitId AND day = :day")
    suspend fun get(habitId: Long, day: Long): HabitLog?

    @Query("SELECT * FROM habit_logs WHERE habitId = :habitId ORDER BY day DESC")
    suspend fun getForHabit(habitId: Long): List<HabitLog>

    @Query("SELECT * FROM habit_logs")
    suspend fun getAll(): List<HabitLog>

    @Query("SELECT * FROM habit_logs WHERE day BETWEEN :from AND :to")
    fun observeRange(from: Long, to: Long): Flow<List<HabitLog>>

    @Query("SELECT day, COUNT(*) AS count FROM habit_logs WHERE completed = 1 AND day BETWEEN :from AND :to GROUP BY day")
    fun observeCompletionCounts(from: Long, to: Long): Flow<List<DayCount>>

    @Query("SELECT * FROM habit_logs WHERE day BETWEEN :from AND :to")
    suspend fun getRange(from: Long, to: Long): List<HabitLog>

    @Query("SELECT * FROM habit_logs WHERE note != '' OR mood > 0 ORDER BY day DESC LIMIT :limit")
    fun observeJournal(limit: Int): Flow<List<HabitLog>>

    @Upsert
    suspend fun upsert(log: HabitLog)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(logs: List<HabitLog>)

    @Query("DELETE FROM habit_logs WHERE habitId = :habitId AND day = :day")
    suspend fun delete(habitId: Long, day: Long)

    @Query("DELETE FROM habit_logs")
    suspend fun deleteAll()
}

@Dao
interface ShieldDao {
    @Query("SELECT * FROM shield_uses")
    suspend fun getAll(): List<ShieldUse>

    @Query("SELECT * FROM shield_uses WHERE habitId = :habitId")
    suspend fun getForHabit(habitId: Long): List<ShieldUse>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(use: ShieldUse): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(uses: List<ShieldUse>)

    @Query("DELETE FROM shield_uses")
    suspend fun deleteAll()
}

@Dao
interface RoutineDao {
    @Query("SELECT * FROM routines ORDER BY position, id")
    fun observeAll(): Flow<List<Routine>>

    @Query("SELECT * FROM routines ORDER BY position, id")
    suspend fun getAll(): List<Routine>

    @Query("SELECT * FROM routines WHERE id = :id")
    suspend fun getById(id: Long): Routine?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(routine: Routine): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(routines: List<Routine>)

    @Update
    suspend fun update(routine: Routine)

    @Delete
    suspend fun delete(routine: Routine)

    @Query("DELETE FROM routines")
    suspend fun deleteAll()
}

@Dao
interface WatchDao {
    /** The collection proper: not archived, owned or away for service (sold and wishlist pieces live elsewhere). */
    @Query("SELECT * FROM watches WHERE archived = 0 AND status IN ('owned', 'repair') ORDER BY brand, model")
    fun observeActive(): Flow<List<Watch>>

    @Query("SELECT * FROM watches WHERE archived = 0 AND status = :status ORDER BY statusDay DESC, brand, model")
    fun observeByStatus(status: String): Flow<List<Watch>>

    @Query("SELECT * FROM watches ORDER BY brand, model")
    fun observeAll(): Flow<List<Watch>>

    @Query("SELECT * FROM watches")
    suspend fun getAll(): List<Watch>

    @Query("SELECT * FROM watches WHERE id = :id")
    suspend fun getById(id: Long): Watch?

    @Query("SELECT * FROM watches WHERE id = :id")
    fun observeById(id: Long): Flow<Watch?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(watch: Watch): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(watches: List<Watch>)

    @Update
    suspend fun update(watch: Watch)

    @Delete
    suspend fun delete(watch: Watch)

    @Query("DELETE FROM watches")
    suspend fun deleteAll()

    @Query("SELECT watchId, COUNT(*) AS count FROM wear_logs GROUP BY watchId")
    fun observeWearCounts(): Flow<List<WatchCount>>
}

data class WatchCount(val watchId: Long, val count: Int)

@Dao
interface WearLogDao {
    @Transaction
    @Query("SELECT * FROM wear_logs WHERE day = :day ORDER BY createdAt DESC")
    fun observeForDay(day: Long): Flow<List<WearLogWithWatch>>

    @Transaction
    @Query("SELECT * FROM wear_logs WHERE day BETWEEN :from AND :to ORDER BY day DESC, createdAt DESC")
    fun observeRange(from: Long, to: Long): Flow<List<WearLogWithWatch>>

    @Transaction
    @Query("SELECT * FROM wear_logs ORDER BY day DESC, createdAt DESC")
    fun observeAllWithWatch(): Flow<List<WearLogWithWatch>>

    @Transaction
    @Query("SELECT * FROM wear_logs WHERE watchId = :watchId ORDER BY day DESC")
    fun observeForWatch(watchId: Long): Flow<List<WearLogWithWatch>>

    @Query("SELECT * FROM wear_logs")
    suspend fun getAll(): List<WearLog>

    @Query("SELECT * FROM wear_logs WHERE id = :id")
    suspend fun getById(id: Long): WearLog?

    @Query("SELECT * FROM wear_logs WHERE watchId = :watchId AND day = :day ORDER BY createdAt DESC")
    suspend fun getForWatchAndDay(watchId: Long, day: Long): List<WearLog>

    @Query("SELECT COALESCE(MAX(day), -1) FROM wear_logs WHERE watchId = :watchId")
    suspend fun lastWornDay(watchId: Long): Long

    @Query("SELECT strapId AS watchId, COUNT(*) AS count FROM wear_logs WHERE strapId IS NOT NULL GROUP BY strapId")
    fun observeStrapCounts(): Flow<List<WatchCount>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: WearLog): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(logs: List<WearLog>)

    @Update
    suspend fun update(log: WearLog)

    @Delete
    suspend fun delete(log: WearLog)

    @Query("DELETE FROM wear_logs")
    suspend fun deleteAll()
}

@Dao
interface XpDao {
    @Query("SELECT COALESCE(SUM(amount), 0) FROM xp_events")
    fun observeTotal(): Flow<Int>

    @Query("SELECT COALESCE(SUM(amount), 0) FROM xp_events")
    suspend fun total(): Int

    @Query("SELECT * FROM xp_events ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<XpEvent>>

    @Query("SELECT * FROM xp_events")
    suspend fun getAll(): List<XpEvent>

    @Query("SELECT COUNT(*) FROM xp_events WHERE habitId = :habitId AND day = :day AND reason = :reason")
    suspend fun countFor(habitId: Long, day: Long, reason: String): Int

    @Query("SELECT * FROM xp_events WHERE habitId = :habitId AND day = :day AND reason = :reason")
    suspend fun find(habitId: Long, day: Long, reason: String): List<XpEvent>

    @Insert
    suspend fun insert(event: XpEvent): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<XpEvent>)

    @Query("DELETE FROM xp_events WHERE habitId = :habitId AND day = :day AND reason = :reason")
    suspend fun deleteFor(habitId: Long, day: Long, reason: String)

    @Query("DELETE FROM xp_events")
    suspend fun deleteAll()
}

@Dao
interface FocusSessionDao {
    @Query("SELECT * FROM focus_sessions ORDER BY startedAt DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<FocusSession>>

    @Query("SELECT * FROM focus_sessions WHERE habitId = :habitId ORDER BY startedAt DESC")
    fun observeForHabit(habitId: Long): Flow<List<FocusSession>>

    @Query("SELECT day, SUM(minutes) AS count FROM focus_sessions WHERE day BETWEEN :from AND :to GROUP BY day")
    fun observeMinutesPerDay(from: Long, to: Long): Flow<List<DayCount>>

    @Query("SELECT * FROM focus_sessions")
    suspend fun getAll(): List<FocusSession>

    @Query("SELECT COALESCE(SUM(minutes), 0) FROM focus_sessions")
    fun observeTotalMinutes(): Flow<Int>

    @Insert
    suspend fun insert(session: FocusSession): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(sessions: List<FocusSession>)

    @Delete
    suspend fun delete(session: FocusSession)

    @Query("DELETE FROM focus_sessions")
    suspend fun deleteAll()
}

@Dao
interface WatchServiceDao {
    @Query("SELECT * FROM watch_services WHERE watchId = :watchId ORDER BY day DESC")
    fun observeForWatch(watchId: Long): Flow<List<WatchService>>

    @Query("SELECT * FROM watch_services ORDER BY day DESC")
    fun observeAll(): Flow<List<WatchService>>

    @Query("SELECT * FROM watch_services")
    suspend fun getAll(): List<WatchService>

    @Query("SELECT * FROM watch_services WHERE nextDueDay IS NOT NULL AND nextDueDay <= :day ORDER BY nextDueDay")
    suspend fun dueBy(day: Long): List<WatchService>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(service: WatchService): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(services: List<WatchService>)

    @Update
    suspend fun update(service: WatchService)

    @Delete
    suspend fun delete(service: WatchService)

    @Query("DELETE FROM watch_services")
    suspend fun deleteAll()
}

@Dao
interface AccuracyDao {
    @Query("SELECT * FROM watch_accuracy WHERE watchId = :watchId ORDER BY measuredAt")
    fun observeForWatch(watchId: Long): Flow<List<AccuracyReading>>

    @Query("SELECT * FROM watch_accuracy")
    suspend fun getAll(): List<AccuracyReading>

    @Query("SELECT * FROM watch_accuracy WHERE watchId = :watchId ORDER BY measuredAt")
    suspend fun getForWatch(watchId: Long): List<AccuracyReading>

    @Insert
    suspend fun insert(reading: AccuracyReading): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(readings: List<AccuracyReading>)

    @Delete
    suspend fun delete(reading: AccuracyReading)

    @Query("DELETE FROM watch_accuracy WHERE watchId = :watchId")
    suspend fun deleteForWatch(watchId: Long)

    @Query("DELETE FROM watch_accuracy")
    suspend fun deleteAll()
}

@Dao
interface SkipRuleDao {
    @Query("SELECT * FROM skip_rules ORDER BY enabled DESC, createdAt DESC")
    fun observeAll(): Flow<List<SkipRule>>

    @Query("SELECT * FROM skip_rules ORDER BY enabled DESC, createdAt DESC")
    suspend fun getAll(): List<SkipRule>

    @Query("SELECT * FROM skip_rules WHERE id = :id")
    suspend fun getById(id: Long): SkipRule?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: SkipRule): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rules: List<SkipRule>)

    @Query("DELETE FROM skip_rules WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM skip_rules")
    suspend fun deleteAll()
}

@Dao
interface StrapDao {
    @Query("SELECT * FROM straps ORDER BY name")
    fun observeAll(): Flow<List<Strap>>

    @Query("SELECT * FROM straps ORDER BY name")
    suspend fun getAll(): List<Strap>

    @Query("SELECT * FROM straps WHERE id = :id")
    suspend fun getById(id: Long): Strap?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(strap: Strap): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(straps: List<Strap>)

    @Update
    suspend fun update(strap: Strap)

    @Delete
    suspend fun delete(strap: Strap)

    @Query("DELETE FROM straps")
    suspend fun deleteAll()
}

@Dao
interface SleepDao {
    @Query("SELECT * FROM sleep_logs WHERE day = :day")
    fun observe(day: Long): Flow<SleepLog?>

    @Query("SELECT * FROM sleep_logs WHERE day BETWEEN :from AND :to ORDER BY day")
    fun observeRange(from: Long, to: Long): Flow<List<SleepLog>>

    @Query("SELECT * FROM sleep_logs WHERE day = :day")
    suspend fun get(day: Long): SleepLog?

    @Query("SELECT * FROM sleep_logs WHERE day BETWEEN :from AND :to ORDER BY day")
    suspend fun getRange(from: Long, to: Long): List<SleepLog>

    @Query("SELECT * FROM sleep_logs ORDER BY day")
    suspend fun getAll(): List<SleepLog>

    @Upsert
    suspend fun upsert(log: SleepLog)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(logs: List<SleepLog>)

    @Query("DELETE FROM sleep_logs WHERE day = :day")
    suspend fun delete(day: Long)

    @Query("DELETE FROM sleep_logs")
    suspend fun deleteAll()
}

@Dao
interface StrapSwapDao {
    @Query("SELECT * FROM strap_swaps ORDER BY day DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<StrapSwap>>

    @Query("SELECT * FROM strap_swaps WHERE strapId = :strapId ORDER BY day DESC, id DESC")
    suspend fun getForStrap(strapId: Long): List<StrapSwap>

    @Query("SELECT * FROM strap_swaps WHERE strapId = :strapId ORDER BY day DESC, id DESC LIMIT 1")
    suspend fun latestForStrap(strapId: Long): StrapSwap?

    @Query("SELECT * FROM strap_swaps ORDER BY day, id")
    suspend fun getAll(): List<StrapSwap>

    @Insert
    suspend fun insert(swap: StrapSwap): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(swaps: List<StrapSwap>)

    @Query("DELETE FROM strap_swaps WHERE strapId = :strapId")
    suspend fun deleteForStrap(strapId: Long)

    @Query("DELETE FROM strap_swaps")
    suspend fun deleteAll()
}

@Dao
interface WatchPhotoDao {
    @Query("SELECT * FROM watch_photos WHERE watchId = :watchId ORDER BY createdAt DESC")
    fun observeForWatch(watchId: Long): Flow<List<WatchPhoto>>

    @Query("SELECT * FROM watch_photos ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<WatchPhoto>>

    @Query("SELECT * FROM watch_photos WHERE watchId = :watchId ORDER BY createdAt DESC")
    suspend fun getForWatch(watchId: Long): List<WatchPhoto>

    @Insert
    suspend fun insert(photo: WatchPhoto): Long

    @Delete
    suspend fun delete(photo: WatchPhoto)

    @Query("DELETE FROM watch_photos")
    suspend fun deleteAll()
}

@Dao
interface WatchDocumentDao {
    @Query("SELECT * FROM watch_documents WHERE watchId = :watchId ORDER BY createdAt DESC")
    fun observeForWatch(watchId: Long): Flow<List<WatchDocument>>

    @Query("SELECT * FROM watch_documents WHERE watchId = :watchId ORDER BY createdAt DESC")
    suspend fun getForWatch(watchId: Long): List<WatchDocument>

    @Insert
    suspend fun insert(doc: WatchDocument): Long

    @Delete
    suspend fun delete(doc: WatchDocument)

    @Query("DELETE FROM watch_documents")
    suspend fun deleteAll()
}

/** Append-only journal for multi-device sync (7.3). Reads are cursor- and LWW-driven. */
@Dao
interface SyncEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: SyncEvent)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<SyncEvent>)

    /** Highest sequence this device has emitted (local push cursor base). */
    @Query("SELECT COALESCE(MAX(seq), 0) FROM sync_log WHERE device = :device")
    suspend fun maxSeq(device: String): Long

    /** Local events not yet pushed to Drive. */
    @Query("SELECT * FROM sync_log WHERE device = :device AND seq > :since ORDER BY seq ASC")
    suspend fun since(device: String, since: Long): List<SyncEvent>

    /** Every event for one row — the LWW comparison set when applying a remote row. */
    @Query("SELECT * FROM sync_log WHERE entity = :entity AND rowId = :rowId")
    suspend fun forRow(entity: String, rowId: String): List<SyncEvent>

    @Query("SELECT COUNT(*) FROM sync_log")
    suspend fun count(): Int

    /** Housekeeping: drop entries older than the retention window once all cursors advanced. */
    @Query("DELETE FROM sync_log WHERE ts < :before")
    suspend fun pruneBefore(before: Long)

    @Query("SELECT * FROM sync_log ORDER BY ts ASC")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<SyncEvent>>
}
